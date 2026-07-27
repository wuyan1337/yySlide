package sh.haven.core.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector
import sh.haven.core.ui.R

enum class Screen(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Connections("connections", R.string.nav_connections, Icons.Filled.Cable),
    Terminal("terminal", R.string.nav_terminal, Icons.Filled.Terminal),
    Desktop("desktop", R.string.nav_desktop, Icons.Filled.DesktopWindows),
    Keys("keys", R.string.nav_keys, Icons.Filled.VpnKey),
    Sftp("sftp", R.string.nav_sftp, Icons.Filled.Folder),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}
