package ch.sonensei.cockpit.auth

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.CockpitShell

/** The logout action of the running app; null where there is no session (previews, shell-only tests). */
val LocalLogout = staticCompositionLocalOf<(() -> Unit)?> { null }

/** App start: the login screen while no tokens are stored, the shell once they are. */
@Composable
fun CockpitRoot(viewModel: SessionViewModel = hiltViewModel()) {
    val loggedIn by viewModel.loggedIn.collectAsStateWithLifecycle()
    if (loggedIn) {
        CompositionLocalProvider(LocalLogout provides viewModel::logout) { CockpitShell() }
    } else {
        LoginRoute()
    }
}

/** Logs out; shows nothing outside a session. */
@Composable
fun LogoutButton(modifier: Modifier = Modifier) {
    val logout = LocalLogout.current ?: return
    OutlinedButton(onClick = logout, modifier = modifier) { Text(stringResource(R.string.logout)) }
}
