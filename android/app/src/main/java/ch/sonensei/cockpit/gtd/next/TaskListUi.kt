package ch.sonensei.cockpit.gtd.next

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Test tags shared by the Next and Today screens. */
object TaskListTags {
    const val OFFLINE = "tasks-offline"

    fun row(id: UUID) = "task-row-$id"

    fun complete(id: UUID) = "task-complete-$id"
}

/** The cached list is shown read-only while the server is unreachable (SPEC.md §9.3). */
@Composable
fun OfflineBanner(
    refreshedAt: Instant?,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth().testTag(TaskListTags.OFFLINE)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Spacer(Modifier.padding(start = 12.dp))
            val text =
                refreshedAt?.let {
                    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(it.atZone(ZoneId.systemDefault()))
                    stringResource(R.string.tasks_offline, time)
                } ?: stringResource(R.string.tasks_offline_never)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One open task: a checkbox to complete it (disabled while [readOnly]), its title and what it needs. */
@Composable
fun TaskRow(
    task: GtdTask,
    contexts: Map<UUID, GtdContext>,
    today: LocalDate,
    readOnly: Boolean,
    onComplete: (GtdTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val details =
        listOfNotNull(
            task.contextIds
                .mapNotNull { contexts[it]?.name }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" "),
            task.timeEstimateMin?.let { stringResource(R.string.tasks_estimate, it) },
        ).joinToString("   ")
    val due = task.dueDate?.let { dueText(it, task.dueTime, today) }
    ListItem(
        modifier = modifier.testTag(TaskListTags.row(task.id)),
        leadingContent = {
            val label = stringResource(R.string.tasks_complete, task.title)
            Checkbox(
                checked = false,
                onCheckedChange = { onComplete(task) },
                enabled = !readOnly,
                modifier = Modifier.testTag(TaskListTags.complete(task.id)).semantics { contentDescription = label },
            )
        },
        headlineContent = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            if (details.isEmpty() && due == null) {
                null
            } else {
                {
                    Row {
                        due?.let { (text, late) ->
                            Text(
                                text,
                                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                        if (details.isNotEmpty()) Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
    )
}

/** "3 days overdue", "Due today 14:30", "Due Oct 24"; the flag says whether it is late. */
@Composable
private fun dueText(
    date: LocalDate,
    time: String?,
    today: LocalDate,
): Pair<String, Boolean> {
    val late = ChronoUnit.DAYS.between(date, today).toInt()
    val clock = time?.take(TIME_LENGTH)
    return when {
        late > 0 -> pluralStringResource(R.plurals.tasks_overdue, late, late) to true
        late == 0 && clock != null -> stringResource(R.string.tasks_due_today_at, clock) to false
        late == 0 -> stringResource(R.string.tasks_due_today) to false
        else -> stringResource(R.string.tasks_due_on, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)) to false
    }
}

private const val TIME_LENGTH = 5

/** Shows [snackbar] on [host]: the undo offer after a completion, or why an action failed. */
@Composable
fun CompletionSnackbarEffect(
    snackbar: CompletionSnackbar?,
    host: SnackbarHostState,
    onUndo: (CompletionSnackbar.Undo) -> Unit,
    onDismiss: (CompletionSnackbar) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(snackbar) {
        if (snackbar == null) return@LaunchedEffect
        val result =
            when (snackbar) {
                is CompletionSnackbar.Undo ->
                    host.showSnackbar(
                        message = resources.getString(R.string.tasks_completed, snackbar.completion.title),
                        actionLabel = resources.getString(R.string.tasks_undo),
                        duration = SnackbarDuration.Long,
                    )
                is CompletionSnackbar.Failed ->
                    host.showSnackbar(
                        message =
                            resources.getString(
                                when (snackbar.message) {
                                    CompletionMessage.OFFLINE -> R.string.tasks_message_offline
                                    CompletionMessage.CONFLICT -> R.string.tasks_message_conflict
                                    CompletionMessage.FAILED -> R.string.tasks_message_failed
                                },
                            ),
                        duration = SnackbarDuration.Short,
                    )
            }
        if (result == SnackbarResult.ActionPerformed && snackbar is CompletionSnackbar.Undo) onUndo(snackbar) else onDismiss(snackbar)
    }
}
