package ch.sonensei.cockpit.gtd.lists

import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tags of the Scheduled list (the shared states use [LiveListTags]). */
object ScheduledTags {
    const val NO_DATE = "scheduled-day-none"

    fun day(date: LocalDate?) = if (date == null) NO_DATE else "scheduled-day-$date"
}

/** The Scheduled entry of More; outside a Hilt activity (navigation tests, previews) it shows the placeholder. */
@Composable
fun ScheduledScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        ScheduledRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_scheduled, Icons.Outlined.Schedule, modifier)
    }
}

@Composable
fun ScheduledRoute(
    modifier: Modifier = Modifier,
    viewModel: ScheduledViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScheduledContent(state, LiveListCallbacks.of(viewModel), modifier)
}

/**
 * Scheduled: tasks that become next actions on their date, under a header per date (earliest first); a task is
 * completed with its checkbox and opens its detail when tapped.
 */
@Composable
fun ScheduledContent(
    state: TaskListUiState,
    callbacks: LiveListCallbacks,
    modifier: Modifier = Modifier,
) {
    val section =
        ListSection<LocalDate?>(key = { it.scheduledDate }) { date ->
            ScheduledDayHeader(date, state.today)
        }
    LiveTaskListContent(state, callbacks, stringResource(R.string.scheduled_empty), modifier, section = section) { task ->
        LiveTaskRow(
            task = task,
            readOnly = state.readOnly,
            busy = task.id in state.busy,
            onComplete = callbacks.complete,
        )
    }
}

@Composable
private fun ScheduledDayHeader(
    date: LocalDate?,
    today: LocalDate,
) {
    val label =
        when (date) {
            null -> stringResource(R.string.scheduled_no_date)
            today -> stringResource(R.string.scheduled_today)
            today.plusDays(1) -> stringResource(R.string.scheduled_tomorrow)
            today.minusDays(1) -> stringResource(R.string.scheduled_yesterday)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(date)
        }
    DayHeader(label, ScheduledTags.day(date))
}
