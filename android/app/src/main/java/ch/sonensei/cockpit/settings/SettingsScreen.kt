package ch.sonensei.cockpit.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.auth.LogoutButton
import ch.sonensei.cockpit.ui.PlaceholderScreen

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PlaceholderScreen(R.string.nav_settings, Icons.Outlined.Settings, Modifier.weight(1f))
        LogoutButton(Modifier.padding(bottom = 32.dp))
    }
}
