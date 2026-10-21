package ch.sonensei.cockpit.calendar.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/** What the editor can ask for, so it can be tested without a view model. */
data class EventEditorCallbacks(
    val update: ((EventForm) -> EventForm) -> Unit = {},
    val save: () -> Unit = {},
    val close: () -> Unit = {},
    val chooseScope: (EditScope) -> Unit = {},
    val dismissScope: () -> Unit = {},
    val reload: () -> Unit = {},
)

internal object EventEditorTags {
    const val EDITOR = "event-editor"
    const val CLOSE = "event-editor-close"
    const val SAVE = "event-editor-save"
    const val TITLE = "event-editor-title"
    const val CALENDAR = "event-editor-calendar"
    const val ALL_DAY = "event-editor-all-day"
    const val START_DATE = "event-editor-start-date"
    const val START_TIME = "event-editor-start-time"
    const val END_DATE = "event-editor-end-date"
    const val END_TIME = "event-editor-end-time"
    const val REPEAT = "event-editor-repeat"
    const val LOCATION = "event-editor-location"
    const val DESCRIPTION = "event-editor-description"
    const val MESSAGE = "event-editor-message"
    const val RELOAD = "event-editor-reload"
    const val END_ERROR = "event-editor-end-error"
    const val SCOPE_DIALOG = "event-editor-scope"
    const val ADD = "calendar-add-event"

    fun repeat(repeat: Repeat) = "event-editor-repeat:$repeat"

    fun calendar(id: UUID) = "event-editor-calendar:$id"

    fun scope(scope: EditScope) = "event-editor-scope:$scope"
}

/** The event form (SPEC.md §6.3), full screen over the calendar views; back closes it without saving. */
@Composable
fun EventEditorContent(
    state: EventEditorUiState,
    callbacks: EventEditorCallbacks,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = callbacks.close)
    Surface(modifier.fillMaxSize().testTag(EventEditorTags.EDITOR)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = callbacks.close, modifier = Modifier.testTag(EventEditorTags.CLOSE)) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.calendar_edit_close))
                }
                Text(
                    stringResource(if (state.creating) R.string.calendar_edit_new else R.string.calendar_edit_edit),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = callbacks.save,
                    enabled = state.canSave,
                    modifier = Modifier.padding(end = 8.dp).testTag(EventEditorTags.SAVE),
                ) { Text(stringResource(R.string.calendar_edit_save)) }
            }
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Message(it, callbacks.reload) }
            state.form?.let { Form(state, it, callbacks.update) }
        }
    }
    state.scopes?.let { ScopeDialog(it, callbacks.chooseScope, callbacks.dismissScope) }
}

@Composable
private fun Message(
    message: EditorMessage,
    reload: () -> Unit,
) {
    val text =
        when (message) {
            EditorMessage.LOAD_OFFLINE -> R.string.calendar_edit_load_offline
            EditorMessage.LOAD_FAILED -> R.string.calendar_edit_load_failed
            EditorMessage.OFFLINE -> R.string.calendar_edit_offline
            EditorMessage.FAILED -> R.string.calendar_edit_failed
            EditorMessage.CONFLICT -> R.string.calendar_edit_conflict
        }
    val action =
        when (message) {
            EditorMessage.CONFLICT -> R.string.calendar_edit_reload
            EditorMessage.LOAD_OFFLINE, EditorMessage.LOAD_FAILED -> R.string.calendar_edit_retry
            else -> null
        }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(EventEditorTags.MESSAGE)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(text), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            action?.let {
                TextButton(onClick = reload, modifier = Modifier.testTag(EventEditorTags.RELOAD)) { Text(stringResource(it)) }
            }
        }
    }
}

@Composable
private fun Form(
    state: EventEditorUiState,
    form: EventForm,
    update: ((EventForm) -> EventForm) -> Unit,
) {
    val enabled = state.canSave
    val errors = state.errors
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.readOnly) Text(stringResource(R.string.calendar_edit_read_only), color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = form.title,
            onValueChange = { text -> update { it.copy(title = text) } },
            label = { Text(stringResource(R.string.calendar_edit_title)) },
            enabled = enabled,
            singleLine = true,
            isError = FormError.TITLE_MISSING in errors,
            supportingText = errorText(FormError.TITLE_MISSING in errors, R.string.calendar_edit_title_missing),
            modifier = Modifier.fillMaxWidth().testTag(EventEditorTags.TITLE),
        )
        CalendarChoice(state, form, enabled, update)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.calendar_edit_all_day), modifier = Modifier.weight(1f))
            Switch(
                checked = form.allDay,
                onCheckedChange = { allDay -> update { it.copy(allDay = allDay) } },
                enabled = enabled,
                modifier = Modifier.testTag(EventEditorTags.ALL_DAY),
            )
        }
        Text(stringResource(R.string.calendar_edit_starts), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateButton(form.startDate, locale, enabled, EventEditorTags.START_DATE) { date -> update { it.withStartDate(date) } }
            if (!form.allDay) {
                TimeButton(form.startTime, locale, enabled, EventEditorTags.START_TIME) { time -> update { it.withStartTime(time) } }
            }
        }
        Text(stringResource(R.string.calendar_edit_ends), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateButton(form.endDate, locale, enabled, EventEditorTags.END_DATE) { date -> update { it.copy(endDate = date) } }
            if (!form.allDay) {
                TimeButton(form.endTime, locale, enabled, EventEditorTags.END_TIME) { time -> update { it.copy(endTime = time) } }
            }
        }
        if (FormError.END_BEFORE_START in errors) {
            Text(
                stringResource(R.string.calendar_edit_end_before_start),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(EventEditorTags.END_ERROR),
            )
        }
        if (!form.allDay) {
            Text(
                stringResource(R.string.calendar_edit_time_zone, form.zone.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RepeatChoice(state, form, enabled, update)
        OutlinedTextField(
            value = form.location,
            onValueChange = { text -> update { it.copy(location = text) } },
            label = { Text(stringResource(R.string.calendar_edit_location)) },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(EventEditorTags.LOCATION),
        )
        OutlinedTextField(
            value = form.description,
            onValueChange = { text -> update { it.copy(description = text) } },
            label = { Text(stringResource(R.string.calendar_edit_description)) },
            enabled = enabled,
            minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag(EventEditorTags.DESCRIPTION),
        )
    }
}

private fun errorText(
    shown: Boolean,
    text: Int,
): (@Composable () -> Unit)? = if (shown) ({ Text(stringResource(text)) }) else null

@Composable
private fun CalendarChoice(
    state: EventEditorUiState,
    form: EventForm,
    enabled: Boolean,
    update: ((EventForm) -> EventForm) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val targets = state.targetCalendars
    val missing = FormError.CALENDAR_MISSING in state.errors
    val name = state.calendars.firstOrNull { it.id == form.calendarId }?.name
    val missingText = stringResource(R.string.calendar_edit_calendar_missing)
    Column {
        Text(stringResource(R.string.calendar_edit_calendar), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && targets.size > 1,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(EventEditorTags.CALENDAR)
                        .semantics { if (missing) error(missingText) },
            ) { Text(name ?: "—") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                targets.forEach { calendar ->
                    DropdownMenuItem(
                        text = { Text(calendar.name) },
                        onClick = {
                            update { it.copy(calendarId = calendar.id) }
                            expanded = false
                        },
                        modifier = Modifier.testTag(EventEditorTags.calendar(calendar.id)),
                    )
                }
            }
        }
        if (missing) {
            Text(
                missingText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RepeatChoice(
    state: EventEditorUiState,
    form: EventForm,
    enabled: Boolean,
    update: ((EventForm) -> EventForm) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.calendar_edit_repeat), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(EventEditorTags.REPEAT),
            ) { Text(repeatLabel(form.repeat, form.rule)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.repeatChoices.forEach { repeat ->
                    DropdownMenuItem(
                        text = { Text(repeatLabel(repeat, form.rule)) },
                        onClick = {
                            update { it.copy(repeat = repeat) }
                            expanded = false
                        },
                        modifier = Modifier.testTag(EventEditorTags.repeat(repeat)),
                    )
                }
            }
        }
    }
}

@Composable
private fun repeatLabel(
    repeat: Repeat,
    rule: String,
): String =
    when (repeat) {
        Repeat.NONE -> stringResource(R.string.calendar_edit_repeat_none)
        Repeat.DAILY -> stringResource(R.string.calendar_edit_repeat_daily)
        Repeat.WEEKLY -> stringResource(R.string.calendar_edit_repeat_weekly)
        Repeat.MONTHLY -> stringResource(R.string.calendar_edit_repeat_monthly)
        Repeat.YEARLY -> stringResource(R.string.calendar_edit_repeat_yearly)
        Repeat.CUSTOM -> stringResource(R.string.calendar_edit_repeat_custom, rule)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateButton(
    date: LocalDate,
    locale: Locale,
    enabled: Boolean,
    tag: String,
    onPick: (LocalDate) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
    }
    if (picking) {
        // The picker works in UTC midnights.
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text(stringResource(R.string.calendar_edit_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.calendar_edit_cancel)) } },
        ) { DatePicker(pickerState) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeButton(
    time: LocalTime,
    locale: Locale,
    enabled: Boolean,
    tag: String,
    onPick: (LocalTime) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Text(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)))
    }
    if (picking) {
        val pickerState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(LocalTime.of(pickerState.hour, pickerState.minute))
                    picking = false
                }) { Text(stringResource(R.string.calendar_edit_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.calendar_edit_cancel)) } },
            text = { TimePicker(pickerState) },
        )
    }
}

/** Asks which occurrences of a repeating event a change applies to (SPEC.md §6.2). */
@Composable
private fun ScopeDialog(
    scopes: List<EditScope>,
    choose: (EditScope) -> Unit,
    dismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag(EventEditorTags.SCOPE_DIALOG),
        title = { Text(stringResource(R.string.calendar_edit_scope_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.calendar_edit_scope_question))
                scopes.forEach { scope ->
                    OutlinedButton(onClick = { choose(scope) }, modifier = Modifier.fillMaxWidth().testTag(EventEditorTags.scope(scope))) {
                        Text(
                            stringResource(
                                when (scope) {
                                    EditScope.THIS -> R.string.calendar_edit_scope_this
                                    EditScope.FOLLOWING -> R.string.calendar_edit_scope_following
                                    EditScope.ALL -> R.string.calendar_edit_scope_all
                                },
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.calendar_edit_cancel)) } },
    )
}
