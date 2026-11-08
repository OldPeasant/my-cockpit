package ch.sonensei.cockpit.gtd.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

object TaskReminderTags {
    const val SECTION = "task-reminders"
    const val ITEM = "task-reminder"
    const val REMOVE = "task-reminder-remove"
    const val PRESET = "task-reminder-preset-"
    const val CUSTOM = "task-reminder-custom"
    const val ERROR = "task-reminders-error"
    const val EMPTY = "task-reminders-empty"
}

/** The reminders of task [taskId] on its detail screen, loaded through [TaskRemindersViewModel]. */
@Composable
fun TaskRemindersSection(
    taskId: UUID,
    anchors: ReminderAnchors,
    enabled: Boolean,
    viewModel: TaskRemindersViewModel = hiltViewModel(),
) {
    LaunchedEffect(taskId) { viewModel.open(taskId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TaskRemindersContent(
        state = state,
        anchors = anchors,
        enabled = enabled,
        add = viewModel::add,
        remove = viewModel::remove,
        retry = viewModel::retry,
    )
}

/** The reminders list with its add choices, without a view model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRemindersContent(
    state: TaskRemindersUiState,
    anchors: ReminderAnchors,
    enabled: Boolean,
    add: (Instant) -> Unit,
    remove: (TaskReminder) -> Unit,
    retry: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    val canEdit = enabled && !state.busy && !state.loading
    val formatter = remember(zone) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone) }
    Column(Modifier.testTag(TaskReminderTags.SECTION)) {
        Text(
            stringResource(R.string.task_detail_reminders),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!state.loading && state.reminders.isEmpty() && state.error == null) {
                Text(
                    stringResource(R.string.task_detail_reminders_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TaskReminderTags.EMPTY),
                )
            }
            state.reminders.forEach { reminder ->
                val time = formatter.format(reminder.remindAt)
                Row(Modifier.testTag(TaskReminderTags.ITEM), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (reminder.firedAt != null) stringResource(R.string.task_detail_reminder_fired, time) else time,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { remove(reminder) },
                        enabled = canEdit,
                        modifier = Modifier.testTag(TaskReminderTags.REMOVE),
                    ) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.task_detail_reminder_remove, time))
                    }
                }
            }
            val message =
                when {
                    state.pastTime -> stringResource(R.string.task_detail_reminder_past)
                    state.error == ReminderError.OFFLINE -> stringResource(R.string.task_detail_reminders_offline)
                    state.error == ReminderError.INVALID -> stringResource(R.string.task_detail_reminder_invalid)
                    state.error != null -> stringResource(R.string.task_detail_reminders_failed)
                    else -> null
                }
            if (message != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).testTag(TaskReminderTags.ERROR),
                    )
                    if (state.error != null && !state.pastTime) {
                        TextButton(onClick = retry) { Text(stringResource(R.string.task_detail_retry)) }
                    }
                }
            }
            if (enabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availablePresets(anchors, zone, now).forEach { (preset, at) ->
                        AssistChip(
                            onClick = { add(at) },
                            enabled = canEdit,
                            label = { Text(stringResource(preset.label())) },
                            modifier = Modifier.testTag(TaskReminderTags.PRESET + preset.name),
                        )
                    }
                    CustomReminderChip(canEdit, zone, now, add)
                }
            }
        }
    }
}

private fun ReminderPreset.label(): Int =
    when (this) {
        ReminderPreset.AT_DUE -> R.string.task_detail_reminder_at_due
        ReminderPreset.HOUR_BEFORE_DUE -> R.string.task_detail_reminder_hour_before_due
        ReminderPreset.DAY_BEFORE_DUE -> R.string.task_detail_reminder_day_before_due
        ReminderPreset.AT_SCHEDULED -> R.string.task_detail_reminder_at_scheduled
    }

/** "Date and time…": a date picker, then a time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomReminderChip(
    enabled: Boolean,
    zone: ZoneId,
    now: Instant,
    add: (Instant) -> Unit,
) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var date by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    AssistChip(
        onClick = { pickingDate = true },
        enabled = enabled,
        label = { Text(stringResource(R.string.task_detail_reminder_custom)) },
        modifier = Modifier.testTag(TaskReminderTags.CUSTOM),
    )
    if (pickingDate) {
        // The picker works in UTC midnights.
        val today = now.atZone(zone).toLocalDate()
        val state = rememberDatePickerState(initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    date = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.task_detail_cancel)) }
            },
        ) { DatePicker(state) }
    }
    val picked = date
    if (picked != null) {
        val state = rememberTimePickerState(initialHour = DEFAULT_REMINDER_TIME.hour, initialMinute = DEFAULT_REMINDER_TIME.minute)
        AlertDialog(
            onDismissRequest = { date = null },
            confirmButton = {
                TextButton(onClick = {
                    add(picked.atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant())
                    date = null
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { date = null }) { Text(stringResource(R.string.task_detail_cancel)) } },
            text = { TimePicker(state) },
        )
    }
}
