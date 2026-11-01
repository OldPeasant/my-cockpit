package ch.sonensei.cockpit.capture.entry

import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object CaptureButtonTags {
    const val FAB = "capture-button:fab"
    const val ACTION = "capture-button:action"
    const val BADGE = "capture-button:badge"
}

/** The in-app capture button's state: how many captures still wait in the offline queue (SPEC.md §9.3). */
@HiltViewModel
class CaptureButtonViewModel internal constructor(
    status: Flow<CaptureQueueStatus>,
) : ViewModel() {
    @Inject
    constructor(queue: CaptureQueue) : this(queue.status)

    /** Captures not yet on the server; 0 while the queue cannot be read (the button must stay usable then). */
    val pendingCount: StateFlow<Int> =
        status
            .map { it.pendingCount }
            .catch { if (it is CancellationException) throw it else emit(0) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** How the capture button sits on a screen: as the screen's FAB, or in the app bar when the screen has its own FAB. */
enum class CaptureButtonPlacement { Fab, AppBarAction }

/**
 * The in-app quick capture (SPEC.md §2): opens the same sheet as the widget, tile and app shortcut ([CaptureActivity]).
 * The sheet is translucent and finishes after saving or cancelling, so the user is back on the screen they were on.
 * The badge shows the captures still waiting for the network.
 */
@Composable
fun CaptureButton(
    placement: CaptureButtonPlacement,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    CaptureButtonContent(
        placement = placement,
        pendingCount = capturePendingCount(),
        onClick = { context.startActivity(captureSheetIntent(context)) },
        modifier = modifier,
    )
}

/**
 * The intent behind the in-app button: in the app's own task, so closing the sheet returns to the app. Single top, so
 * a quick double tap opens one sheet, not two stacked ones.
 */
fun captureSheetIntent(context: Context): Intent = Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** The queue's pending count from Hilt; 0 outside a Hilt activity (the shell's navigation tests, previews). */
@Composable
private fun capturePendingCount(): Int {
    if (LocalActivity.current !is GeneratedComponentManagerHolder) return 0
    val viewModel: CaptureButtonViewModel = hiltViewModel()
    val count by viewModel.pendingCount.collectAsStateWithLifecycle()
    return count
}

/** The button without its wiring, so it can be tested with any count and click handler. */
@Composable
fun CaptureButtonContent(
    placement: CaptureButtonPlacement,
    pendingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description =
        if (pendingCount > 0) {
            pluralStringResource(R.plurals.capture_button_pending, pendingCount, pendingCount)
        } else {
            stringResource(R.string.capture_button)
        }
    when (placement) {
        CaptureButtonPlacement.Fab ->
            FloatingActionButton(
                onClick = onClick,
                modifier = modifier.semantics { contentDescription = description }.testTag(CaptureButtonTags.FAB),
            ) { CaptureIcon(pendingCount) }
        CaptureButtonPlacement.AppBarAction ->
            IconButton(
                onClick = onClick,
                modifier = modifier.semantics { contentDescription = description }.testTag(CaptureButtonTags.ACTION),
            ) { CaptureIcon(pendingCount) }
    }
}

@Composable
private fun CaptureIcon(pendingCount: Int) {
    BadgedBox(
        badge = {
            if (pendingCount > 0) {
                Badge(Modifier.testTag(CaptureButtonTags.BADGE)) {
                    // The button's description already says it: the number must not be read out a second time.
                    Text(badgeText(pendingCount), Modifier.clearAndSetSemantics {})
                }
            }
        },
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null)
    }
}

private const val MAX_BADGE_COUNT = 99

/** A badge stays small: more than 99 pending captures show as "99+". */
internal fun badgeText(count: Int): String = if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else count.toString()
