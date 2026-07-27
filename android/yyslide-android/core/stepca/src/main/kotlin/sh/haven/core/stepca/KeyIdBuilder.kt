package sh.haven.core.stepca

import android.os.Build

/**
 * Build the cert `keyID` field. step-ca records this in its sign log so
 * an admin can later see which device minted a cert. We use
 * `<deviceModel>:<random>` so two devices with the same login don't
 * produce identical IDs, and so a user revoking "the cert from my old
 * phone" can pick it out by model.
 */
internal object KeyIdBuilder {

    fun build(label: String): String {
        val safeLabel = label.replace(Regex("[^A-Za-z0-9._@-]+"), "-").trim('-').ifEmpty { "haven" }
        val safeModel = (Build.MODEL ?: "android").replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
        return "$safeLabel@$safeModel"
    }
}
