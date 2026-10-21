package ch.sonensei.cockpit.gtd.lists

import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The Waiting tab; outside a Hilt activity (navigation tests, previews) it shows the placeholder. */
@Composable
fun WaitingScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        WaitingRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_waiting, Icons.Outlined.HourglassEmpty, modifier)
    }
}

@Composable
fun WaitingRoute(
    modifier: Modifier = Modifier,
    viewModel: WaitingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WaitingContent(state, LiveListCallbacks.of(viewModel), viewModel::followUp, modifier)
}

/** Waiting For: longest waiting first, how long, and "Follow up" (creates a next action to chase it). */
@Composable
fun WaitingContent(
    state: TaskListUiState,
    callbacks: LiveListCallbacks,
    followUp: (GtdTask, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTaskListContent(state, callbacks, stringResource(R.string.waiting_empty), modifier) { task ->
        val followUpTitle =
            task.waitingForText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                stringResource(
                    R.string.waiting_follow_up_title_who,
                    it,
                    task.title,
                )
            }
                ?: stringResource(R.string.waiting_follow_up_title, task.title)
        val busy = task.id in state.busy
        LiveTaskRow(
            task = task,
            readOnly = state.readOnly,
            busy = busy,
            onComplete = callbacks.complete,
            supporting = { WaitingSince(task, state.today) },
            action = {
                RowAction(
                    task = task,
                    label = stringResource(R.string.waiting_follow_up),
                    description = stringResource(R.string.waiting_follow_up_label, task.title),
                    enabled = !state.readOnly && !busy,
                    onClick = { followUp(task, followUpTitle) },
                )
            },
        )
    }
}

/** "Anna, for 9 days"; in the error color once it is time to follow up. */
@Composable
private fun WaitingSince(
    task: GtdTask,
    today: LocalDate,
) {
    val who = task.waitingForText?.trim()?.takeIf { it.isNotEmpty() }
    val days = task.waitingSince?.let { maxOf(0, ChronoUnit.DAYS.between(it, today).toInt()) }
    val since =
        when {
            days == null -> null
            days == 0 -> stringResource(R.string.waiting_since_today)
            else -> pluralStringResource(R.plurals.waiting_days, days, days)
        }
    val text =
        when {
            who != null && since != null -> stringResource(R.string.waiting_for_since, who, since)
            who != null -> stringResource(R.string.waiting_for, who)
            since != null -> since
            else -> return
        }
    val long = days != null && days > LONG_WAIT_DAYS
    Text(text, color = if (long) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
