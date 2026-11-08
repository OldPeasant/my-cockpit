package ch.sonensei.cockpit.habits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

internal object HabitEditorTags {
    const val DIALOG = "habit-editor"
    const val NAME = "habit-editor-name"
    const val SAVE = "habit-editor-save"
    const val CANCEL = "habit-editor-cancel"
    const val ERROR = "habit-editor-error"
    const val PROBLEM = "habit-editor-problem"
    const val TIMES_PER_WEEK = "habit-editor-times-per-week"
    const val TARGET = "habit-editor-target"
    const val REMINDER = "habit-editor-reminder"
    const val REMINDER_CLEAR = "habit-editor-reminder-clear"

    fun schedule(schedule: HabitSchedule) = "habit-editor-schedule:$schedule"

    fun weekday(day: DayOfWeek) = "habit-editor-day:$day"

    fun less(stepper: String) = "$stepper-less"

    fun more(stepper: String) = "$stepper-more"
}

/** Creates or edits a habit: name, schedule, times a day and an optional daily reminder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HabitEditorDialog(
    editor: HabitEditor,
    callbacks: HabitsCallbacks,
) {
    val draft = editor.draft
    val update = callbacks.updateDraft
    AlertDialog(
        onDismissRequest = { if (!editor.saving) callbacks.cancelEdit() },
        modifier = Modifier.testTag(HabitEditorTags.DIALOG),
        title = { Text(stringResource(if (editor.habitId == null) R.string.habits_new_title else R.string.habits_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val problem = draft.problem?.takeIf { editor.tried }
                val nameProblem = problem == HabitDraftProblem.NAME || problem == HabitDraftProblem.NAME_TOO_LONG
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { update(draft.copy(name = it)) },
                    label = { Text(stringResource(R.string.habits_field_name)) },
                    singleLine = true,
                    isError = nameProblem,
                    enabled = !editor.saving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag(HabitEditorTags.NAME),
                )
                ScheduleChoice(draft, editor.saving, update)
                when (draft.schedule) {
                    HabitSchedule.TIMES_PER_WEEK ->
                        Stepper(
                            label = stringResource(R.string.habits_field_times_per_week),
                            value = draft.timesPerWeek,
                            range = 1..HabitDraft.DAYS_PER_WEEK,
                            enabled = !editor.saving,
                            tag = HabitEditorTags.TIMES_PER_WEEK,
                        ) { update(draft.copy(timesPerWeek = it)) }
                    HabitSchedule.WEEKDAYS -> WeekdayChoice(draft, editor.saving, update)
                    HabitSchedule.DAILY -> Unit
                }
                Stepper(
                    label = stringResource(R.string.habits_field_target),
                    value = draft.targetCount,
                    range = 1..HabitDraft.MAX_TARGET,
                    enabled = !editor.saving,
                    tag = HabitEditorTags.TARGET,
                ) { update(draft.copy(targetCount = it)) }
                ReminderField(draft.reminderTime, editor.saving) { update(draft.copy(reminderTime = it)) }
                problem?.let {
                    Text(
                        stringResource(problemText(it)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(HabitEditorTags.PROBLEM),
                    )
                }
                editor.error?.let {
                    Text(
                        stringResource(
                            when (it) {
                                HabitEditorError.OFFLINE -> R.string.habits_save_offline
                                HabitEditorError.FAILED -> R.string.habits_save_failed
                                HabitEditorError.CONFLICT -> R.string.habits_save_conflict
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(HabitEditorTags.ERROR),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = callbacks.save, enabled = !editor.saving, modifier = Modifier.testTag(HabitEditorTags.SAVE)) {
                Text(stringResource(if (editor.habitId == null) R.string.habits_add else R.string.habits_save))
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.cancelEdit, enabled = !editor.saving, modifier = Modifier.testTag(HabitEditorTags.CANCEL)) {
                Text(stringResource(R.string.habits_cancel))
            }
        },
    )
}

private fun problemText(problem: HabitDraftProblem) =
    when (problem) {
        HabitDraftProblem.NAME -> R.string.habits_problem_name
        HabitDraftProblem.NAME_TOO_LONG -> R.string.habits_problem_name_too_long
        HabitDraftProblem.WEEKDAYS -> R.string.habits_problem_weekdays
        HabitDraftProblem.TIMES_PER_WEEK -> R.string.habits_problem_times_per_week
        HabitDraftProblem.TARGET -> R.string.habits_problem_target
    }

@Composable
private fun ScheduleChoice(
    draft: HabitDraft,
    saving: Boolean,
    update: (HabitDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.habits_field_schedule), style = MaterialTheme.typography.labelLarge)
        val schedules = HabitSchedule.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            schedules.forEachIndexed { index, schedule ->
                SegmentedButton(
                    selected = draft.schedule == schedule,
                    onClick = { update(draft.copy(schedule = schedule)) },
                    shape = SegmentedButtonDefaults.itemShape(index, schedules.size),
                    enabled = !saving,
                    icon = {},
                    label = {
                        Text(
                            stringResource(
                                when (schedule) {
                                    HabitSchedule.DAILY -> R.string.habits_schedule_daily
                                    HabitSchedule.TIMES_PER_WEEK -> R.string.habits_schedule_per_week
                                    HabitSchedule.WEEKDAYS -> R.string.habits_schedule_weekdays
                                },
                            ),
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.testTag(HabitEditorTags.schedule(schedule)),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChoice(
    draft: HabitDraft,
    saving: Boolean,
    update: (HabitDraft) -> Unit,
) {
    val locale = Locale.getDefault()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { day ->
            val selected = day in draft.weekdays
            FilterChip(
                selected = selected,
                onClick = { update(draft.copy(weekdays = if (selected) draft.weekdays - day else draft.weekdays + day)) },
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                enabled = !saving,
                modifier =
                    Modifier
                        .testTag(HabitEditorTags.weekday(day))
                        .semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale) },
            )
        }
    }
}

/** A number with minus and plus buttons, kept within [range]. */
@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    tag: String,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { onChange((value - 1).coerceIn(range)) },
            enabled = enabled && value > range.first,
            modifier = Modifier.testTag(HabitEditorTags.less(tag)),
        ) { Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.habits_less, label)) }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 32.dp).testTag(tag),
        )
        IconButton(
            onClick = { onChange((value + 1).coerceIn(range)) },
            enabled = enabled && value < range.last,
            modifier = Modifier.testTag(HabitEditorTags.more(tag)),
        ) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.habits_more, label)) }
    }
}

/** The reminder time, picked in a dialog; can be removed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderField(
    time: LocalTime?,
    saving: Boolean,
    onPick: (LocalTime?) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.habits_field_reminder), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { picking = true }, enabled = !saving, modifier = Modifier.testTag(HabitEditorTags.REMINDER)) {
            Text(
                time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
                    ?: stringResource(R.string.habits_reminder_none),
            )
        }
        if (time != null) {
            IconButton(onClick = { onPick(null) }, enabled = !saving, modifier = Modifier.testTag(HabitEditorTags.REMINDER_CLEAR)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.habits_reminder_remove))
            }
        }
    }
    if (picking) {
        val start = time ?: DEFAULT_REMINDER
        val pickerState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(LocalTime.of(pickerState.hour, pickerState.minute))
                    picking = false
                }) { Text(stringResource(R.string.habits_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.habits_cancel)) } },
            text = { TimePicker(pickerState) },
        )
    }
}

private val DEFAULT_REMINDER: LocalTime = LocalTime.of(8, 0)
