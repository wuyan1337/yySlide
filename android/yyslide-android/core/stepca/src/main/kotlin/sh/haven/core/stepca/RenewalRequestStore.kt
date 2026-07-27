package sh.haven.core.stepca

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issues and consumes one-time capabilities for certificate-renewal
 * notification intents. The capabilities live in app-private preferences,
 * so another application cannot forge a renewal request by launching our
 * exported activity with a guessed key id.
 */
@Singleton
class RenewalRequestStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Synchronized
    fun issue(keyId: String, nowMillis: Long = System.currentTimeMillis()): String {
        require(keyId.isNotBlank()) { "keyId must not be blank" }

        val editor = preferences.edit()
        preferences.all.forEach { (name, value) ->
            if (!name.startsWith(TOKEN_PREFIX)) return@forEach
            val record = parseRecord(value as? String)
            if (record == null || record.keyId == keyId || nowMillis - record.issuedAtMillis > MAX_AGE_MILLIS) {
                editor.remove(name)
            }
        }

        val nonceBytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val nonce = Base64.encodeToString(
            nonceBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        editor.putString(TOKEN_PREFIX + nonce, "$nowMillis\n$keyId")
        check(editor.commit()) { "Could not persist certificate renewal request" }
        return nonce
    }

    /**
     * Atomically consumes [nonce]. Any presented nonce is invalidated even if
     * its key id is wrong or it has expired, preventing replay and probing.
     */
    @Synchronized
    fun consume(
        keyId: String,
        nonce: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (keyId.isBlank() || nonce.isBlank()) return false
        val prefKey = TOKEN_PREFIX + nonce
        val record = parseRecord(preferences.getString(prefKey, null))
        if (!preferences.edit().remove(prefKey).commit()) return false
        return record != null &&
            record.keyId == keyId &&
            nowMillis >= record.issuedAtMillis &&
            nowMillis - record.issuedAtMillis <= MAX_AGE_MILLIS
    }

    private fun parseRecord(raw: String?): Record? {
        if (raw == null) return null
        val separator = raw.indexOf('\n')
        if (separator <= 0 || separator == raw.lastIndex) return null
        val issuedAt = raw.substring(0, separator).toLongOrNull() ?: return null
        val keyId = raw.substring(separator + 1)
        if (keyId.isBlank()) return null
        return Record(issuedAt, keyId)
    }

    private data class Record(val issuedAtMillis: Long, val keyId: String)

    companion object {
        private const val PREFS_NAME = "stepca_renewal_requests"
        private const val TOKEN_PREFIX = "nonce."
        private const val NONCE_BYTES = 32
        internal const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
