package ch.sonensei.cockpit.mail.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Runs [follow] (a view model collecting the mail change events) only while the screen is started, i.e. shown with
 * the app in the foreground: it stops when the screen leaves or the app goes to the background, so nothing listens or
 * polls there.
 */
@Composable
fun FollowMailChanges(
    key: Any,
    follow: suspend () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current = rememberUpdatedState(follow)
    LaunchedEffect(key, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { current.value() }
    }
}
