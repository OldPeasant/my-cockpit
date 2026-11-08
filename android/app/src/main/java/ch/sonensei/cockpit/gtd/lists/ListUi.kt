package ch.sonensei.cockpit.gtd.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.next.CompletionSnackbar
import ch.sonensei.cockpit.gtd.next.CompletionSnackbarEffect
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.gtd.next.opensTask
import ch.sonensei.cockpit.ui.LocalFabClearance
import java.util.UUID

/** Test tags of the Waiting For, Someday and project detail lists. */
object LiveListTags {
    const val LIST = "live-list"
    const val LOADING = "live-loading"
    const val EMPTY = "live-empty"
    const val ERROR = "live-error"
    const val RETRY = "live-retry"
    const val STALE = "live-stale"

    fun action(id: UUID) = "live-action-$id"
}

/** What a live list screen can ask for, so the screens can be tested without a view model. */
data class LiveListCallbacks(
    val retry: () -> Unit = {},
    val complete: (GtdTask) -> Unit = {},
    val undo: (CompletionSnackbar.Undo) -> Unit = {},
    val dismissCompletion: (CompletionSnackbar) -> Unit = {},
    val dismissAction: (ActionSnackbar) -> Unit = {},
) {
    companion object {
        fun of(vm: LiveTaskListViewModel) = LiveListCallbacks(vm::retry, vm::complete, vm::undo, vm::dismissCompletion, vm::dismissAction)
    }
}

/**
 * The frame of a live list: a banner while the shown list may be outdated, the rows, or loading, an error with retry,
 * or [empty]; and the snackbars of completion and of the screen's action.
 */
@Composable
fun LiveTaskListContent(
    state: TaskListUiState,
    callbacks: LiveListCallbacks,
    empty: String,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    section: ListSection<*>? = null,
    row: @Composable (GtdTask) -> Unit,
) {
    val snackbarHost = remember { SnackbarHostState() }
    CompletionSnackbarEffect(state.completion, snackbarHost, callbacks.undo, callbacks.dismissCompletion)
    ActionSnackbarEffect(state.action, snackbarHost, callbacks.dismissAction)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.loaded && state.error != null) StaleBanner(state.error, callbacks.retry)
            header()
            when {
                state.tasks.isNotEmpty() ->
                    LazyColumn(
                        Modifier.fillMaxSize().testTag(LiveListTags.LIST),
                        contentPadding = PaddingValues(bottom = LocalFabClearance.current),
                    ) {
                        if (section == null) {
                            items(state.tasks, key = { it.id }) { task ->
                                row(task)
                                HorizontalDivider()
                            }
                        } else {
                            section.items(this, state.tasks, row)
                        }
                    }
                state.loading -> Centered(stringResource(R.string.tasks_loading), LiveListTags.LOADING)
                !state.loaded && state.error != null -> LoadFailed(state.error, callbacks.retry)
                else -> Centered(empty, LiveListTags.EMPTY)
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalFabClearance.current))
    }
}

/**
 * Splits a live list into sections: a new section starts with each task whose [key] differs from the task before it
 * (the screen's order already keeps a section's tasks together), and each section shows its [header] above its rows.
 */
class ListSection<K>(
    val key: (GtdTask) -> K,
    val header: @Composable (K) -> Unit,
) {
    /** Adds [tasks] to [scope] as sections: each section's [header], then its rows. */
    internal fun items(
        scope: LazyListScope,
        tasks: List<GtdTask>,
        row: @Composable (GtdTask) -> Unit,
    ) {
        tasks.groupConsecutiveBy(key).forEach { (sectionKey, sectionTasks) ->
            scope.item(key = "section-$sectionKey") { header(sectionKey) }
            scope.items(sectionTasks, key = { it.id }) { task ->
                row(task)
                HorizontalDivider()
            }
        }
    }
}

/** Runs of consecutive tasks with the same [key], in order. */
internal fun <K> List<GtdTask>.groupConsecutiveBy(key: (GtdTask) -> K): List<Pair<K, List<GtdTask>>> {
    val groups = mutableListOf<Pair<K, MutableList<GtdTask>>>()
    forEach { task ->
        val k = key(task)
        val last = groups.lastOrNull()
        if (last != null && last.first == k) last.second += task else groups += k to mutableListOf(task)
    }
    return groups
}

/** The header of a day section: [label] as a heading, tagged [tag]. */
@Composable
internal fun DayHeader(
    label: String,
    tag: String,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(tag)
                    .semantics { heading() },
        )
    }
}

/** One open task of a live list: a checkbox to complete it, its title, [supporting] text and an [action] button. */
@Composable
fun LiveTaskRow(
    task: GtdTask,
    readOnly: Boolean,
    busy: Boolean,
    onComplete: (GtdTask) -> Unit,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
    overline: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = modifier.testTag(TaskListTags.row(task.id)).opensTask(task),
        leadingContent = {
            val label = stringResource(R.string.tasks_complete, task.title)
            Checkbox(
                checked = false,
                onCheckedChange = { onComplete(task) },
                enabled = !readOnly && !busy,
                modifier = Modifier.testTag(TaskListTags.complete(task.id)).semantics { contentDescription = label },
            )
        },
        overlineContent = overline,
        headlineContent = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting,
        trailingContent = action,
    )
}

/** The row button of the screen's action ("Follow up", "Activate"). */
@Composable
fun RowAction(
    task: GtdTask,
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(LiveListTags.action(task.id)).semantics { contentDescription = description },
    ) { Text(label) }
}

/** Says that the shown data may be outdated because its last load failed with [error], with a retry; [message] overrides the text. */
@Composable
internal fun StaleBanner(
    error: LoadError,
    retry: () -> Unit,
    message: String? = null,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag(LiveListTags.STALE)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp)) {
            Text(
                message ?: stringResource(if (error == LoadError.OFFLINE) R.string.lists_stale_offline else R.string.lists_stale_failed),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = retry, modifier = Modifier.align(Alignment.End).testTag(LiveListTags.RETRY)) {
                Text(stringResource(R.string.lists_retry))
            }
        }
    }
}

@Composable
internal fun LoadFailed(
    error: LoadError,
    retry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag(LiveListTags.ERROR),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (error == LoadError.OFFLINE) R.string.lists_error_offline else R.string.lists_error_failed),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = retry, modifier = Modifier.testTag(LiveListTags.RETRY)) { Text(stringResource(R.string.lists_retry)) }
    }
}

@Composable
internal fun Centered(
    text: String,
    tag: String,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Shows [snackbar] on [host]: what the screen's action did, or why it did not go through. */
@Composable
fun ActionSnackbarEffect(
    snackbar: ActionSnackbar?,
    host: SnackbarHostState,
    onDismiss: (ActionSnackbar) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(snackbar) {
        if (snackbar == null) return@LaunchedEffect
        val message =
            when (snackbar.outcome) {
                ActionOutcome.DONE ->
                    resources.getString(
                        when (snackbar.kind) {
                            ActionSnackbar.Kind.ACTIVATED -> R.string.someday_activated
                            ActionSnackbar.Kind.FOLLOW_UP -> R.string.waiting_followed_up
                            ActionSnackbar.Kind.COMPLETED -> R.string.tasks_completed
                            ActionSnackbar.Kind.NEXT_STEP -> R.string.projects_next_step_set
                            ActionSnackbar.Kind.REOPENED -> R.string.logbook_reopened
                        },
                        snackbar.title,
                    )
                ActionOutcome.OFFLINE -> resources.getString(R.string.tasks_message_offline)
                ActionOutcome.CONFLICT -> resources.getString(R.string.tasks_message_conflict)
                ActionOutcome.FAILED -> resources.getString(R.string.tasks_message_failed)
            }
        host.showSnackbar(message, duration = SnackbarDuration.Short)
        onDismiss(snackbar)
    }
}
