package sh.haven.core.stepca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "step-ca cert about to expire" system notification used by
 * [CertRenewalWorker]. Notifications use an explicit, immutable intent plus
 * a one-time app-private capability so external applications cannot forge a
 * request that rotates an SSH key.
 *
 * String resources live in the app module (because they need to be
 * localised alongside the rest of the user-facing copy); to avoid a
 * core/stepca → app dependency cycle, the notifier reads them via
 * generic Android `getString(int)` calls and the resource ids are
 * passed in by the worker / caller. For now we hardcode English text
 * — i18n strings are wired up separately in the app module via a
 * thin shim. (#133 phase 2b)
 */
@Singleton
class RenewalNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val requestStore: RenewalRequestStore,
) {
    fun notifyExpiring(keyId: String, keyLabel: String, validBeforeUnixSeconds: Long) {
        ensureChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val nonce = requestStore.issue(keyId)
        val renewalIntent = Intent(ACTION_RENEW_CERT).apply {
            setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
            putExtra(EXTRA_KEY_ID, keyId)
            putExtra(EXTRA_NONCE, nonce)
            // Reuse the existing MainActivity task if Haven is already
            // foregrounded; otherwise launch into a fresh one.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(
            context,
            keyId.hashCode(),
            renewalIntent,
            pendingFlags,
        )

        val nowSeconds = System.currentTimeMillis() / 1000
        val hoursLeft = ((validBeforeUnixSeconds - nowSeconds) / 3600).coerceAtLeast(0)
        val title = "step-ca cert expiring soon"
        val body = "$keyLabel expires in ${hoursLeft}h. Tap to renew."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        nm.notify(NOTIFICATION_ID_BASE + keyId.hashCode(), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "step-ca certificate renewal",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminders to renew SSH certificates issued by your step-ca CA."
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_RENEW_CERT = "sh.haven.app.action.RENEW_STEP_CA_CERT"
        const val EXTRA_KEY_ID = "sh.haven.app.extra.RENEW_STEP_CA_KEY_ID"
        const val EXTRA_NONCE = "sh.haven.app.extra.RENEW_STEP_CA_NONCE"
        private const val MAIN_ACTIVITY_CLASS = "sh.haven.app.MainActivity"
        const val CHANNEL_ID = "stepca_cert_renewal"
        /**
         * Notification ids are derived from the ssh key id's hash so
         * one-per-key (subsequent sweeps replace, don't stack).
         */
        const val NOTIFICATION_ID_BASE = 0x57E9CA00
    }
}
