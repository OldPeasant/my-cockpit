package ch.sonensei.cockpit.gtd.today

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import ch.sonensei.cockpit.gtd.next.OfflineBanner
import ch.sonensei.cockpit.gtd.next.TaskRow
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder

/**
 * The Today tab. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests, previews)
 * it shows the placeholder instead of failing.
 */
@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        TodayRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_today, Icons.Outlined.Today, modifier)
    }
}

@Composable
fun TodayRoute(
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TodayContent(state, TodayCallbacks(viewModel::complete, viewModel::undo, viewModel::dismiss), modifier)
}

/** What the Today screen can ask for, so the screen can be tested without a view model. */
data class TodayCallbacks(
    val complete: (GtdTask) -> Unit = {},
    val undo: (CompletionSnackbar.Undo) -> Unit = {},
    val dismiss: (CompletionSnackbar) -> Unit = {},
)

object TodayTags {
    const val LIST = "today-list"
    const val EMPTY = "today-empty"
    const val LOADING = "today-loading"

    fun section(kind: TodaySectionKind) = "today-section-${kind.name.lowercase()}"
}

@Composable
fun TodayContent(
    state: TodayUiState,
    callbacks: TodayCallbacks,
    modifier: Modifier = Modifier,
) {
    val snackbarHost = remember { SnackbarHostState() }
    CompletionSnackbarEffect(state.snackbar, snackbarHost, callbacks.undo, callbacks.dismiss)
    val contexts = state.contexts.associateBy { it.id }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.offline) OfflineBanner(state.refreshedAt)
            when {
                state.sections.isNotEmpty() ->
                    LazyColumn(Modifier.fillMaxSize().testTag(TodayTags.LIST)) {
                        state.sections.forEach { section ->
                            item(key = section.kind) { SectionHeader(section) }
                            items(section.tasks, key = { it.id }) { task ->
                                TaskRow(task, contexts, state.today, state.offline, callbacks.complete)
                            }
                        }
                    }
                state.loading -> Centered(stringResource(R.string.tasks_loading), TodayTags.LOADING)
                else -> Centered(stringResource(R.string.today_empty), TodayTags.EMPTY)
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
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
