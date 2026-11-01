package ch.sonensei.cockpit.gtd.lists

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.ui.LocalFabClearance
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tags of the Logbook (the shared states use [LiveListTags]). */
object LogbookTags {
    const val LOADING_MORE = "logbook-loading-more"
    const val MORE_FAILED = "logbook-more-failed"
    const val MORE_RETRY = "logbook-more-retry"
    const val DETAIL = "logbook-detail"
    const val DETAIL_REOPEN = "logbook-detail-reopen"
    const val DETAIL_CLOSE = "logbook-detail-close"

    fun day(date: LocalDate) = "logbook-day-$date"
}

/** What the Logbook screen can ask for, so it can be tested without a view model. */
data class LogbookCallbacks(
    val retry: () -> Unit = {},
    val loadMore: () -> Unit = {},
    val open: (LoggedTask) -> Unit = {},
    val close: () -> Unit = {},
    val reopen: (LoggedTask) -> Unit = {},
    val dismissAction: (ActionSnackbar) -> Unit = {},
) {
    companion object {
        fun of(vm: LogbookViewModel) = LogbookCallbacks(vm::retry, vm::loadMore, vm::open, vm::close, vm::reopen, vm::dismissAction)
    }
}

/** The Logbook entry of More; outside a Hilt activity (navigation tests, previews) it shows the placeholder. */
@Composable
fun LogbookScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        LogbookRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_logbook, Icons.Outlined.TaskAlt, modifier)
    }
}

@Composable
fun LogbookRoute(
    modifier: Modifier = Modifier,
    viewModel: LogbookViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LogbookContent(state, LogbookCallbacks.of(viewModel), modifier)
}

/**
 * The Logbook: done tasks newest first under a header per completion day, each with its project and contexts and a
 * "Reopen" button; tapping one shows its details. Older pages load when the list is scrolled to its end.
 */
@Composable
fun LogbookContent(
    state: LogbookUiState,
    callbacks: LogbookCallbacks,
    modifier: Modifier = Modifier,
) {
    val snackbarHost = remember { SnackbarHostState() }
    ActionSnackbarEffect(state.action, snackbarHost, callbacks.dismissAction)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.loaded && state.error != null) StaleBanner(state.error, callbacks.retry)
            when {
                state.tasks.isNotEmpty() -> LogbookList(state, callbacks)
                state.loading -> Centered(stringResource(R.string.tasks_loading), LiveListTags.LOADING)
                !state.loaded && state.error != null -> LoadFailed(state.error, callbacks.retry)
                else -> Centered(stringResource(R.string.logbook_empty), LiveListTags.EMPTY)
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalFabClearance.current))
    }
    state.opened?.let { LogbookDetail(it, state, callbacks) }
}

@Composable
private fun LogbookList(
    state: LogbookUiState,
    callbacks: LogbookCallbacks,
) {
    val listState = rememberLazyListState()
    LoadMoreAtEnd(listState, state.canLoadMore && state.moreError == null, callbacks.loadMore)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LiveListTags.LIST),
        state = listState,
        contentPadding = PaddingValues(bottom = LocalFabClearance.current),
    ) {
        state.days.forEach { day ->
            item(key = "day-${day.date}") { DayHeader(day.date, state.today) }
            items(day.tasks, key = { it.task.id }) { done ->
                LogbookRow(done, state, callbacks)
                HorizontalDivider()
            }
        }
        if (state.canLoadMore) item(key = "more") { MoreFooter(state, callbacks.loadMore) }
    }
}

/** Asks for the next page once the last rows are visible, so the list grows while it is scrolled. */
@Composable
private fun LoadMoreAtEnd(
    listState: LazyListState,
    enabled: Boolean,
    loadMore: () -> Unit,
) {
    val canLoad by rememberUpdatedState(enabled)
    val load by rememberUpdatedState(loadMore)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD
        }.distinctUntilChanged()
            .filter { it }
            .collect { if (canLoad) load() }
    }
    // A short first page fits on the screen without scrolling: the effect above saw its end before more was possible.
    LaunchedEffect(enabled) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (enabled && info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD) loadMore()
    }
}

/** Rows from the end at which the next page is requested. */
private const val LOAD_AHEAD = 5

@Composable
private fun DayHeader(
    date: LocalDate,
    today: LocalDate,
) {
    val label =
        when (date) {
            today -> stringResource(R.string.logbook_today)
            today.minusDays(1) -> stringResource(R.string.logbook_yesterday)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(date)
        }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(LogbookTags.day(date))
                    .semantics { heading() },
        )
    }
}

@Composable
private fun LogbookRow(
    done: LoggedTask,
    state: LogbookUiState,
    callbacks: LogbookCallbacks,
) {
    val task = done.task
    val where = whereText(done, state)
    ListItem(
        modifier =
            Modifier
                .testTag(TaskListTags.row(task.id))
                .clickable(onClickLabel = stringResource(R.string.logbook_open)) { callbacks.open(done) },
        leadingContent = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = where?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            RowAction(
                task = task,
                label = stringResource(R.string.logbook_reopen),
                description = stringResource(R.string.logbook_reopen_label, task.title),
                enabled = !state.readOnly && task.id !in state.busy,
                onClick = { callbacks.reopen(done) },
            )
        },
    )
}

/** "Project · @home @phone": the task's project and contexts, `null` when it has neither (or they are not loaded). */
private fun whereText(
    done: LoggedTask,
    state: LogbookUiState,
): String? {
    val project = done.task.projectId?.let { state.projects[it] }
    val contexts = done.task.contextIds.mapNotNull { state.contexts[it]?.name }
    return listOfNotNull(project, contexts.takeIf { it.isNotEmpty() }?.joinToString(" ")).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun MoreFooter(
    state: LogbookUiState,
    loadMore: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (state.moreError != null) {
            Column(Modifier.testTag(LogbookTags.MORE_FAILED), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(
                        if (state.moreError == LoadError.OFFLINE) R.string.logbook_more_offline else R.string.logbook_more_failed,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = loadMore, modifier = Modifier.testTag(LogbookTags.MORE_RETRY)) {
                    Text(stringResource(R.string.lists_retry))
                }
            }
        } else {
            Text(
                stringResource(R.string.logbook_loading_more),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(LogbookTags.LOADING_MORE),
            )
        }
    }
}

/** The opened done task: when it was completed, its project, contexts and notes, with "Reopen". */
@Composable
private fun LogbookDetail(
    done: LoggedTask,
    state: LogbookUiState,
    callbacks: LogbookCallbacks,
) {
    val task = done.task
    AlertDialog(
        onDismissRequest = callbacks.close,
        modifier = Modifier.testTag(LogbookTags.DETAIL),
        title = { Text(task.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val completed =
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(done.completedAt.atZone(state.zone))
                Text(stringResource(R.string.logbook_completed_at, completed))
                task.projectId?.let { state.projects[it] }?.let { Text(stringResource(R.string.logbook_project, it)) }
                done.task.contextIds
                    .mapNotNull { state.contexts[it]?.name }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Text(stringResource(R.string.logbook_contexts, it.joinToString(", "))) }
                task.notesMd?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { callbacks.reopen(done) },
                enabled = !state.readOnly && task.id !in state.busy,
                modifier = Modifier.testTag(LogbookTags.DETAIL_REOPEN),
            ) { Text(stringResource(R.string.logbook_reopen)) }
        },
        dismissButton = {
            TextButton(onClick = callbacks.close, modifier = Modifier.testTag(LogbookTags.DETAIL_CLOSE)) {
                Text(stringResource(R.string.logbook_close))
            }
        },
    )
}
