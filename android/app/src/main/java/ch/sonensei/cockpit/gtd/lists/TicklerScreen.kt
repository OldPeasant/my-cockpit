package ch.sonensei.cockpit.gtd.lists

import android.text.format.DateFormat
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Test tags of the Tickler list (the shared states use [LiveListTags]). */
object TicklerTags {
    fun backOn(id: UUID) = "tickler-back-on-$id"
}

/** The Tickler entry of More; outside a Hilt activity (navigation tests, previews) it shows the placeholder. */
@Composable
fun TicklerScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        TicklerRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_tickler, Icons.Outlined.Alarm, modifier)
    }
}

@Composable
fun TicklerRoute(
    modifier: Modifier = Modifier,
    viewModel: TicklerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TicklerContent(state, LiveListCallbacks.of(viewModel), modifier)
}

/**
 * Tickler: tasks hidden until their tickler date, earliest first, each with the day it comes back to the Inbox; a task
 * is completed with its checkbox and opens its detail (to change the date or choose another list) when tapped.
 */
@Composable
fun TicklerContent(
    state: TaskListUiState,
    callbacks: LiveListCallbacks,
    modifier: Modifier = Modifier,
) {
    val locale = Locale.current.platformLocale
    LiveTaskListContent(state, callbacks, stringResource(R.string.tickler_empty), modifier) { task ->
        LiveTaskRow(
            task = task,
            readOnly = state.readOnly,
            busy = task.id in state.busy,
            onComplete = callbacks.complete,
            supporting =
                task.ticklerDate?.let { date ->
                    {
                        Text(
                            stringResource(R.string.tickler_back_on, backOnDate(date, state.today, locale)),
                            modifier = Modifier.testTag(TicklerTags.backOn(task.id)),
                        )
                    }
                },
        )
    }
}

/** "Mon, 14 Dec" in [locale]'s order, with the year when it is not [today]'s. */
internal fun backOnDate(
    date: LocalDate,
    today: LocalDate,
    locale: java.util.Locale,
): String {
    val skeleton = if (date.year == today.year) "EEEdMMM" else "EEEdMMMyyyy"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
}
