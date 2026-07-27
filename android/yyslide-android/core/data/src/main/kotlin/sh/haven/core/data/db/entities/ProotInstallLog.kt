package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One event in the proot install/uninstall pipeline.
 *
 * Two events per phase by convention: a start event with `exit=null,
 * ok=true` and a completion event with `exit=<code>, ok=(code==0)`.
 * Failures additionally include the last ~1500 chars of command
 * output in [logTail]. Successes leave [logTail] null to keep the
 * table small.
 *
 * [phase] is a free-form string rather than an enum so future phases
 * (Phase 4 nested-Wayland install, Phase 5 KDE/GNOME paths) don't
 * need schema migrations. Recognised values today:
 *
 *   - "RootfsDownload"
 *   - "RootfsExtract"
 *   - "BootstrapHook:<hookId>"   e.g. "BootstrapHook:void-xbps-bootstrap"
 *   - "Baseline"
 *   - "DePackage:<deId>"          e.g. "DePackage:xfce4"
 *   - "DeConfig:<deId>"           e.g. "DeConfig:xfce4"
 *   - "DeUninstall:<deId>"
 *   - "Addons"
 *
 * No foreign key on [distroId] — distros aren't a Room table (they're
 * a Kotlin object catalog). Index on [distroId] keeps the
 * per-distro filter fast.
 */
@Entity(
    tableName = "proot_install_log",
    indices = [Index("distroId")],
)
data class ProotInstallLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val distroId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val phase: String,
    val deId: String? = null,
    val exit: Int? = null,
    val ok: Boolean = true,
    val message: String? = null,
    val logTail: String? = null,
)
