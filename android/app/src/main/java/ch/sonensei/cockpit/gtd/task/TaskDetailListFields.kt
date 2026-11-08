package ch.sonensei.cockpit.gtd.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.TaskList
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tags of the list, date, waiting and repeat fields of the edit form. */
object TaskDetailListTags {
    const val WAITING_FOR = "task-detail-waiting-for-input"
    const val WAITING_SINCE = "task-detail-waiting-since"
    const val REPEAT_INTERVAL = "task-detail-repeat-interval"
    const val REPEAT_RULE = "task-detail-repeat-rule"

    fun list(list: TaskList) = "task-detail-list-${list.name.lowercase()}"

    /** The date field [name] (`scheduled`, `tickler`): its button opens the picker. */
    fun date(name: String) = "task-detail-date-$name"

    fun dateClear(name: String) = "task-detail-date-$name-clear"

    /** A quick chip of the date field [name]: `tomorrow`, `week`, `month`. */
    fun dateChip(
        name: String,
        chip: String,
    ) = "task-detail-date-$name-$chip"

    fun repeat(preset: RepeatPreset) = "task-detail-repeat-${preset.name.lowercase()}"

    fun repeatMode(mode: RecurrenceMode) = "task-detail-repeat-mode-${mode.name.lowercase()}"
}

/** The lists a task can be put on in the form, in the order of the GTD workflow. */
private val FORM_LISTS = listOf(TaskList.INBOX, TaskList.NEXT, TaskList.WAITING, TaskList.SCHEDULED, TaskList.SOMEDAY)

/** The list, the scheduled date, the waiting-for fields and the tickler date. */
@Composable
internal fun ListFields(
    state: TaskDetailUiState,
    draft: TaskDraft,
    callbacks: TaskDetailCallbacks,
    enabled: Boolean,
) {
    FormGroup(stringResource(R.string.task_detail_list_label), state.problems[TaskField.LIST]?.let { TaskField.LIST to it }) {
        FORM_LISTS.forEach { list ->
            FilterChip(
                selected = draft.list == list,
                onClick = { callbacks.edit { it.onList(list) } },
                label = { Text(stringResource(listLabel(list))) },
                enabled = enabled,
                modifier = Modifier.testTag(TaskDetailListTags.list(list)),
            )
        }
    }
    if (draft.list == TaskList.WAITING) WaitingFields(state, draft, callbacks, enabled)
    DateField(
        name = "scheduled",
        title = stringResource(R.string.task_detail_scheduled),
        date = draft.scheduledDate,
        problem = state.problems[TaskField.SCHEDULED]?.let { TaskField.SCHEDULED to it },
        enabled = enabled,
        hint = stringResource(R.string.task_detail_scheduled_hint),
        set = { date -> callbacks.edit { it.scheduledOn(date) } },
    )
    DateField(
        name = "tickler",
        title = stringResource(R.string.task_detail_tickler),
        date = draft.ticklerDate,
        problem = state.problems[TaskField.TICKLER]?.let { TaskField.TICKLER to it },
        enabled = enabled,
        hint = stringResource(R.string.task_detail_tickler_hint),
        set = { date -> callbacks.edit { it.copy(ticklerDate = date) } },
    )
}

@Composable
private fun WaitingFields(
    state: TaskDetailUiState,
    draft: TaskDraft,
    callbacks: TaskDetailCallbacks,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = draft.waitingFor,
        onValueChange = { value -> callbacks.edit { it.copy(waitingFor = value) } },
        label = { Text(stringResource(R.string.task_detail_waiting_for)) },
        isError = TaskField.WAITING_FOR in state.problems,
        supportingText = state.problems[TaskField.WAITING_FOR]?.let { { ProblemText(TaskField.WAITING_FOR, it) } },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(TaskDetailListTags.WAITING_FOR),
    )
    // A task moved to Waiting gets today from the server; only a task already waiting can change the day.
    if (state.detail?.task?.list == TaskList.WAITING) {
        var picking by rememberSaveable { mutableStateOf(false) }
        FormGroup(
            stringResource(R.string.task_detail_waiting_since_label),
            state.problems[TaskField.WAITING_SINCE]?.let { TaskField.WAITING_SINCE to it },
        ) {
            OutlinedButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.testTag(TaskDetailListTags.WAITING_SINCE)) {
                Text(draft.waitingSince?.format(mediumDate) ?: stringResource(R.string.task_detail_date_pick))
            }
        }
        if (picking) {
            DateDialog(draft.waitingSince ?: LocalDate.now(), close = { picking = false }) { date ->
                callbacks.edit { it.copy(waitingSince = date) }
            }
        }
    }
}

/** A date with quick chips (as in inbox processing), a picker for any other day and a button to clear it. */
@Composable
private fun DateField(
    name: String,
    title: String,
    date: LocalDate?,
    problem: Pair<TaskField, FieldProblem>?,
    enabled: Boolean,
    hint: String,
    set: (LocalDate?) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    Column {
        FormGroup(title, problem) {
            OutlinedButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.testTag(TaskDetailListTags.date(name))) {
                Text(date?.format(mediumDate) ?: stringResource(R.string.task_detail_date_pick))
            }
            if (date != null) {
                TextButton(onClick = { set(null) }, enabled = enabled, modifier = Modifier.testTag(TaskDetailListTags.dateClear(name))) {
                    Text(stringResource(R.string.task_detail_date_clear))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple("tomorrow", R.string.task_detail_date_tomorrow, today.plusDays(1)),
                Triple("week", R.string.task_detail_date_week, today.plusWeeks(1)),
                Triple("month", R.string.task_detail_date_month, today.plusMonths(1)),
            ).forEach { (chip, label, day) ->
                FilterChip(
                    selected = date == day,
                    onClick = { set(day) },
                    label = { Text(stringResource(label)) },
                    enabled = enabled,
                    modifier = Modifier.testTag(TaskDetailListTags.dateChip(name, chip)),
                )
            }
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (picking) DateDialog(date ?: today.plusDays(1), close = { picking = false }, pick = set)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
    initial: LocalDate,
    close: () -> Unit,
    pick: (LocalDate) -> Unit,
) {
    // The picker works in UTC midnights.
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = close,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { pick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                close()
            }) { Text(stringResource(R.string.task_detail_ok)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.task_detail_cancel)) } },
    ) { DatePicker(pickerState) }
}

/** The repeat editor: presets with an interval, the mode, or a custom rule kept as text. */
@Composable
internal fun RepeatFields(
    state: TaskDetailUiState,
    draft: TaskDraft,
    callbacks: TaskDetailCallbacks,
    enabled: Boolean,
) {
    val repeat = draft.repeat
    val serverProblem = (state.problems[TaskField.RECURRENCE] ?: state.problems[TaskField.RECURRENCE_MODE]) as? FieldProblem.Server
    FormGroup(stringResource(R.string.task_detail_repeat), serverProblem?.let { TaskField.RECURRENCE to it }) {
        RepeatPreset.entries.forEach { preset ->
            FilterChip(
                selected = repeat.preset == preset,
                onClick = {
                    callbacks.edit { d ->
                        // Choosing Custom starts from the rule shown so far.
                        val custom =
                            if (preset == RepeatPreset.CUSTOM &&
                                d.repeat.custom.isBlank()
                            ) {
                                d.repeat.rule().orEmpty()
                            } else {
                                d.repeat.custom
                            }
                        d.copy(repeat = d.repeat.copy(preset = preset, custom = custom))
                    }
                },
                label = { Text(stringResource(presetLabel(preset))) },
                enabled = enabled,
                modifier = Modifier.testTag(TaskDetailListTags.repeat(preset)),
            )
        }
    }
    val localProblem = state.problems[TaskField.RECURRENCE]?.takeIf { it !is FieldProblem.Server }
    if (repeat.preset.hasInterval) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            Text(
                stringResource(R.string.task_detail_repeat_every),
                modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = repeat.interval,
                onValueChange = { value ->
                    callbacks.edit { it.copy(repeat = it.repeat.copy(interval = value.filter(Char::isDigit).take(3))) }
                },
                isError = localProblem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.width(88.dp).testTag(TaskDetailListTags.REPEAT_INTERVAL),
            )
            Text(
                pluralStringResource(unitLabel(repeat.preset), repeat.interval.toIntOrNull() ?: 1),
                modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    if (repeat.preset == RepeatPreset.CUSTOM) {
        OutlinedTextField(
            value = repeat.custom,
            onValueChange = { value -> callbacks.edit { it.copy(repeat = it.repeat.copy(custom = value)) } },
            label = { Text(stringResource(R.string.task_detail_repeat_rule)) },
            isError = localProblem != null,
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(TaskDetailListTags.REPEAT_RULE),
        )
    }
    localProblem?.let {
        Text(
            when {
                it == FieldProblem.Required -> stringResource(R.string.task_detail_error_required)
                repeat.preset == RepeatPreset.CUSTOM -> stringResource(R.string.task_detail_error_repeat_rule)
                else -> stringResource(R.string.task_detail_error_repeat_interval, RepeatDraft.MAX_INTERVAL)
            },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(TaskDetailTags.error(TaskField.RECURRENCE)),
        )
    }
    if (repeat.preset != RepeatPreset.NONE) {
        FormGroup(stringResource(R.string.task_detail_repeat_mode), null) {
            RecurrenceMode.entries.forEach { mode ->
                FilterChip(
                    selected = repeat.mode == mode,
                    onClick = { callbacks.edit { it.copy(repeat = it.repeat.copy(mode = mode)) } },
                    label = { Text(stringResource(modeLabel(mode))) },
                    enabled = enabled,
                    modifier = Modifier.testTag(TaskDetailListTags.repeatMode(mode)),
                )
            }
        }
    }
}

/** How the detail view shows a repeat: the preset and interval in words, a custom rule as it is, and the mode. */
@Composable
internal fun repeatText(
    rule: String,
    mode: RecurrenceMode?,
): String {
    val repeat = RepeatDraft.of(rule, mode)
    val every = repeat.interval.toIntOrNull() ?: 1
    val what =
        when {
            repeat.preset == RepeatPreset.CUSTOM -> rule
            every == 1 -> stringResource(presetLabel(repeat.preset))
            repeat.preset == RepeatPreset.WEEKDAYS ->
                stringResource(
                    R.string.task_detail_repeat_weekdays_every,
                    pluralStringResource(
                        R.plurals.task_detail_repeat_every_count,
                        every,
                        every,
                        pluralStringResource(unitLabel(repeat.preset), every),
                    ),
                )
            else ->
                pluralStringResource(
                    R.plurals.task_detail_repeat_every_count,
                    every,
                    every,
                    pluralStringResource(unitLabel(repeat.preset), every),
                )
        }
    return listOfNotNull(what, mode?.let { stringResource(modeLabel(it)) }).joinToString(" · ")
}

internal fun listLabel(list: TaskList) =
    when (list) {
        TaskList.INBOX -> R.string.task_detail_list_inbox
        TaskList.NEXT -> R.string.task_detail_list_next
        TaskList.WAITING -> R.string.task_detail_list_waiting
        TaskList.SOMEDAY -> R.string.task_detail_list_someday
        TaskList.SCHEDULED -> R.string.task_detail_list_scheduled
    }

private fun presetLabel(preset: RepeatPreset) =
    when (preset) {
        RepeatPreset.NONE -> R.string.task_detail_repeat_none
        RepeatPreset.DAILY -> R.string.task_detail_repeat_daily
        RepeatPreset.WEEKDAYS -> R.string.task_detail_repeat_weekdays
        RepeatPreset.WEEKLY -> R.string.task_detail_repeat_weekly
        RepeatPreset.MONTHLY -> R.string.task_detail_repeat_monthly
        RepeatPreset.YEARLY -> R.string.task_detail_repeat_yearly
        RepeatPreset.CUSTOM -> R.string.task_detail_repeat_custom
    }

private fun unitLabel(preset: RepeatPreset) =
    when (preset) {
        RepeatPreset.DAILY -> R.plurals.task_detail_repeat_days
        RepeatPreset.MONTHLY -> R.plurals.task_detail_repeat_months
        RepeatPreset.YEARLY -> R.plurals.task_detail_repeat_years
        else -> R.plurals.task_detail_repeat_weeks
    }

private fun modeLabel(mode: RecurrenceMode) =
    when (mode) {
        RecurrenceMode.FIXED -> R.string.task_detail_repeat_fixed
        RecurrenceMode.AFTER_COMPLETION -> R.string.task_detail_repeat_after_completion
    }

private val mediumDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
