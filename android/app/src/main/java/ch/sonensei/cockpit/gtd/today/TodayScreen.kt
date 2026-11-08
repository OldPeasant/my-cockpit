package ch.sonensei.cockpit.gtd.today

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.next.CompletionSnackbar
import ch.sonensei.cockpit.gtd.next.CompletionSnackbarEffect
import ch.sonensei.cockpit.gtd.next.LoadFailed
import ch.sonensei.cockpit.gtd.next.LoadFailedBanner
import ch.sonensei.cockpit.gtd.next.OfflineBanner
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.gtd.next.TaskRow
import ch.sonensei.cockpit.ui.LocalFabClearance
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder

/**
 * The Today tab. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests, previews)
 * it shows the placeholder instead of failing.
 */
@Composable
fun TodayScreen(
    modifier: Modifier = Modifier,
    openNext: () -> Unit = {},
) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        TodayWithEditor(openNext, modifier)
    } else {
        PlaceholderScreen(R.string.nav_today, Icons.Outlined.Today, modifier)
    }
}

@Composable
fun TodayRoute(
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
    extras: TodayExtrasUiState = TodayExtrasUiState(),
    extrasCallbacks: TodayExtrasCallbacks = TodayExtrasCallbacks(),
    openNext: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TodayContent(
        state,
        TodayCallbacks(viewModel::complete, viewModel::undo, viewModel::dismiss, viewModel::retry, openNext),
        modifier,
        extras,
        extrasCallbacks,
    )
}

/** What the Today screen can ask for, so the screen can be tested without a view model. */
data class TodayCallbacks(
    val complete: (GtdTask) -> Unit = {},
    val undo: (CompletionSnackbar.Undo) -> Unit = {},
    val dismiss: (CompletionSnackbar) -> Unit = {},
    val retry: () -> Unit = {},
    val openNext: () -> Unit = {},
)

object TodayTags {
    const val LIST = "today-list"
    const val EMPTY = "today-empty"
    const val LOADING = "today-loading"
    const val PICK_NEXT = "today-pick-next"

    fun section(kind: TodaySectionKind) = "today-section-${kind.name.lowercase()}"
}

@Composable
fun TodayContent(
    state: TodayUiState,
    callbacks: TodayCallbacks,
    modifier: Modifier = Modifier,
    extras: TodayExtrasUiState = TodayExtrasUiState(),
    extrasCallbacks: TodayExtrasCallbacks = TodayExtrasCallbacks(),
) {
    val snackbarHost = remember { SnackbarHostState() }
    CompletionSnackbarEffect(state.snackbar, snackbarHost, callbacks.undo, callbacks.dismiss)
    val contexts = state.contexts.associateBy { it.id }
    val hasExtras = extras.events.isNotEmpty() || extras.habits.isNotEmpty()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.offline) OfflineBanner(state.refreshedAt)
            if (state.firstLoadFailed && (state.sections.isNotEmpty() || hasExtras)) {
                LoadFailedBanner(callbacks.retry, Modifier.testTag(TaskListTags.ERROR))
            }
            when {
                state.sections.isEmpty() && !hasExtras && state.loading ->
                    Centered(
                        stringResource(R.string.tasks_loading),
                        TodayTags.LOADING,
                    )
                state.sections.isEmpty() && !hasExtras && state.firstLoadFailed ->
                    LoadFailed(callbacks.retry, Modifier.testTag(TaskListTags.ERROR))
                else ->
                    LazyColumn(
                        Modifier.fillMaxSize().testTag(TodayTags.LIST),
                        contentPadding = PaddingValues(bottom = LocalFabClearance.current),
                    ) {
                        todayEvents(extras, state.today, extrasCallbacks.openEvent)
                        state.sections.forEach { section ->
                            item(key = section.kind) { SectionHeader(section) }
                            items(section.tasks, key = { it.id }) { task ->
                                TaskRow(task, contexts, state.today, state.offline, callbacks.complete)
                            }
                        }
                        if (state.sections.isEmpty() && !state.loading && !state.firstLoadFailed) {
                            item(key = TodayTags.EMPTY) { EmptyDay(callbacks.openNext) }
                        }
                        todayHabits(extras, extrasCallbacks.checkHabit)
                    }
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalFabClearance.current))
    }
}

/** No task for today: says so and offers to pick one from Next (as on the web). */
@Composable
private fun EmptyDay(openNext: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp).testTag(TodayTags.EMPTY),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.today_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = openNext, modifier = Modifier.testTag(TodayTags.PICK_NEXT)) {
            Text(stringResource(R.string.today_pick_next))
        }
    }
}

@Composable
private fun SectionHeader(section: TodaySection) {
    val overdue = section.kind == TodaySectionKind.OVERDUE
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).testTag(TodayTags.section(section.kind)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(
                when (section.kind) {
                    TodaySectionKind.OVERDUE -> R.string.today_overdue
                    TodaySectionKind.DUE -> R.string.today_due
                    TodaySectionKind.SCHEDULED -> R.string.today_scheduled
                },
            ),
            style = MaterialTheme.typography.titleSmall,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(
            section.tasks.size.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Centered(
    text: String,
    tag: String,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
