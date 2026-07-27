package sh.haven.core.stepca

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.ssh.OpenSshCertificate
import java.util.concurrent.TimeUnit

/**
 * Daily background sweep over [sh.haven.core.data.db.entities.SshKey]s
 * that were minted via step-ca; for any whose cert expires within
 * [EXPIRY_NOTIFICATION_HORIZON_HOURS] hours, posts a system notification
 * inviting the user to renew. Tapping the notification deep-links into
 * Haven and runs the OIDC + sign flow against the existing CA.
 *
 * Deliberately *not* attempting silent renewal: step-ca's OIDC flow
 * needs a Custom Tab and user interaction (typically password +
 * possibly MFA), which we can't drive from a worker. The worker stops
 * at the notification.
 *
 * Phase 2b of #133.
 */
@HiltWorker
class CertRenewalWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sshKeyRepository: SshKeyRepository,
    private val notifier: RenewalNotifier,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val keys = sshKeyRepository.getAll()
            val now = System.currentTimeMillis() / 1000
            val horizon = now + TimeUnit.HOURS.toSeconds(EXPIRY_NOTIFICATION_HORIZON_HOURS)
            val expiring = keys.mapNotNull { key ->
                val caId = key.caConfigId ?: return@mapNotNull null
                val certBytes = key.certificateBytes ?: return@mapNotNull null
                val cert = OpenSshCertificate.parseOrNull(certBytes) ?: return@mapNotNull null
                if (cert.validBefore in (now + 1)..horizon) {
                    Triple(key.id, key.label, cert.validBefore)
                } else null
            }
            Log.d(TAG, "renewal sweep: ${expiring.size} cert(s) within horizon")
            expiring.forEach { (keyId, keyLabel, validBefore) ->
                notifier.notifyExpiring(keyId, keyLabel, validBefore)
            }
            Result.success()
        } catch (e: Throwable) {
            Log.w(TAG, "renewal sweep failed", e)
            // Retry — a transient DB error on this run shouldn't kill
            // the schedule. WorkManager backs off exponentially.
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CertRenewalWorker"
        private const val UNIQUE_NAME = "stepca-cert-renewal"
        const val EXPIRY_NOTIFICATION_HORIZON_HOURS: Long = 4

        /**
         * Idempotent. Safe to call from `Application.onCreate` on every
         * cold start; KEEP policy means the existing schedule survives.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CertRenewalWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
