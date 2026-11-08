package ch.sonensei.cockpit.gtd.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The calendars "To calendar" offers: still loading, failed to load, or loaded. */
data class CalendarChoice(
    /** `null` while loading. */
    val calendars: List<TargetCalendar>? = null,
    val failed: Boolean = false,
)

object ToCalendarTags {
    const val DIALOG = "to-calendar-dialog"
    const val LOADING = "to-calendar-loading"
    const val FAILED = "to-calendar-failed"
    const val NONE = "to-calendar-none"
    const val RETRY = "to-calendar-retry"
    const val ALL_DAY = "to-calendar-all-day"
    const val DATE = "to-calendar-date"
    const val TIME = "to-calendar-time"
    const val CONFIRM = "to-calendar-confirm"
    const val CANCEL = "to-calendar-cancel"

    fun calendar(calendar: TargetCalendar) = "to-calendar-calendar-${calendar.id}"

    fun duration(minutes: Int) = "to-calendar-duration-$minutes"
}

/**
 * Asks where and when a task goes on the calendar (SPEC.md §5.3 "To calendar"): a writable calendar, the start day,
 * and either a start time with a duration or all-day. Used by the task detail screen and the Inbox processing.
 */
@Composable
fun ToCalendarDialog(
    plan: EventPlan,
    choice: CalendarChoice,
    update: ((EventPlan) -> EventPlan) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    enabled: Boolean = true,
) {
    val calendars = choice.calendars
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ToCalendarTags.DIALOG),
        title = { Text(stringResource(R.string.task_detail_convert_calendar_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label(R.string.task_detail_convert_calendar_calendar)
                when {
                    choice.failed ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.task_detail_convert_calendar_failed),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f).testTag(ToCalendarTags.FAILED),
                            )
                            TextButton(onClick = onRetry, modifier = Modifier.testTag(ToCalendarTags.RETRY)) {
                                Text(stringResource(R.string.task_detail_retry))
                            }
                        }
                    calendars == null ->
                        Text(
                            stringResource(R.string.task_detail_convert_calendar_loading),
                            modifier = Modifier.testTag(ToCalendarTags.LOADING),
                        )
                    calendars.isEmpty() ->
                        Text(
                            stringResource(R.string.task_detail_convert_calendar_none),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(ToCalendarTags.NONE),
                        )
                    else ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            calendars.forEach { calendar ->
                                FilterChip(
                                    selected = plan.calendarId == calendar.id,
                                    onClick = { update { it.copy(calendarId = calendar.id) } },
                                    label = { Text(calendar.name) },
                                    modifier = Modifier.testTag(ToCalendarTags.calendar(calendar)),
                                )
                            }
                        }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.task_detail_convert_calendar_all_day), modifier = Modifier.weight(1f))
                    Switch(
                        checked = plan.allDay,
                        onCheckedChange = { allDay -> update { it.copy(allDay = allDay) } },
                        modifier = Modifier.testTag(ToCalendarTags.ALL_DAY),
                    )
                }
                Label(R.string.task_detail_convert_calendar_start)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateButton(plan, update)
                    if (!plan.allDay) TimeButton(plan.time) { time -> update { it.copy(time = time) } }
                }
                if (!plan.allDay) {
                    Label(R.string.task_detail_convert_calendar_duration)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EventPlan.DURATIONS_MIN.forEach { minutes ->
                            FilterChip(
                                selected = plan.durationMin == minutes,
                                onClick = { update { it.copy(durationMin = minutes) } },
                                label = { Text(durationLabel(minutes)) },
                                modifier = Modifier.testTag(ToCalendarTags.duration(minutes)),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = enabled && plan.isValid && !calendars.isNullOrEmpty(),
                modifier = Modifier.testTag(ToCalendarTags.CONFIRM),
            ) { Text(stringResource(R.string.task_detail_convert_calendar_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ToCalendarTags.CANCEL)) {
                Text(stringResource(R.string.task_detail_cancel))
            }
        },
    )
}

@Composable
private fun Label(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun durationLabel(minutes: Int): String =
    if (minutes % MINUTES_PER_HOUR == 0) {
        stringResource(R.string.task_detail_convert_calendar_hours, minutes / MINUTES_PER_HOUR)
    } else if (minutes > MINUTES_PER_HOUR) {
        stringResource(R.string.task_detail_convert_calendar_hours_minutes, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    } else {
        stringResource(R.string.task_detail_convert_calendar_minutes, minutes)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateButton(
    plan: EventPlan,
    update: ((EventPlan) -> EventPlan) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag(ToCalendarTags.DATE)) {
        Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(plan.date))
    }
    if (picking) {
        // The picker works in UTC midnights.
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    plan.date
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        update { it.copy(date = date) }
                    }
                    picking = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.task_detail_cancel)) } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeButton(
    time: LocalTime,
    onPick: (LocalTime) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag(ToCalendarTags.TIME)) {
        Text(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(time))
    }
    if (picking) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(LocalTime.of(state.hour, state.minute))
                    picking = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.task_detail_cancel)) } },
            text = { TimePicker(state) },
        )
    }
}

private const val MINUTES_PER_HOUR = 60
