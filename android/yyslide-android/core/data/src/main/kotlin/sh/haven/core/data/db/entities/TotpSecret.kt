package sh.haven.core.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * An OATH-TOTP shared secret (#178), referenced from a connection
 * profile's auth chain by `TOTP:<id>` to auto-fill the SSH
 * keyboard-interactive "Verification code:" prompt.
 *
 * [secret] is stored encrypted at rest (`ENC:`+Base64 via
 * `CredentialEncryption`) — the encrypt/decrypt boundary is
 * `TotpSecretRepository`, mirroring how `sshPassword` is handled. The
 * remaining columns are otpauth parameters (public, not secret).
 */
@Entity(tableName = "totp_secrets")
data class TotpSecret(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    /** Base32 shared secret. Encrypted at rest; plaintext only in memory. */
    val secret: String,
    val issuer: String? = null,
    val accountName: String? = null,
    /** "SHA1" / "SHA256" / "SHA512" — matches `Totp.Algorithm.name`. */
    @ColumnInfo(defaultValue = "SHA1")
    val algorithm: String = "SHA1",
    @ColumnInfo(defaultValue = "6")
    val digits: Int = 6,
    @ColumnInfo(defaultValue = "30")
    val periodSeconds: Int = 30,
    val createdAt: Long = System.currentTimeMillis(),
)
