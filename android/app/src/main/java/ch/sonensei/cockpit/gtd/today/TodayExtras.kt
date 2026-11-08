package ch.sonensei.cockpit.gtd.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.habits.Habit
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the event and habit sections of Today can ask for. */
data class TodayExtrasCallbacks(
    val openEvent: (Occurrence) -> Unit = {},
    val checkHabit: (Habit) -> Unit = {},
)

object TodayExtrasTags {
    const val EVENTS = "today-events"
    const val HABITS = "today-habits"
    const val CHECK_FAILED = "today-habit-check-failed"

    fun event(occurrence: Occurrence) = "today-event-${occurrence.key}"

    fun habit(habit: Habit) = "today-habit-${habit.id}"

    fun check(habit: Habit) = "today-habit-check-${habit.id}"
}

/** Today's events, under a heading; nothing when there are none. */
internal fun LazyListScope.todayEvents(
    state: TodayExtrasUiState,
    today: LocalDate,
    open: (Occurrence) -> Unit,
) {
    if (state.events.isEmpty()) return
    item(key = TodayExtrasTags.EVENTS) { ExtrasHeader(R.string.today_events, state.events.size, TodayExtrasTags.EVENTS) }
    items(state.events, key = { "event-${it.key}" }) { event ->
        val color = state.calendars.firstOrNull { it.id == event.calendarId }?.color
        EventRow(event, color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary, today, state.zone, open)
    }
}

/** The habits due today with their check buttons; nothing when none is due. */
internal fun LazyListScope.todayHabits(
    state: TodayExtrasUiState,
    check: (Habit) -> Unit,
) {
    if (state.habits.isEmpty()) return
    item(key = TodayExtrasTags.HABITS) { ExtrasHeader(R.string.today_habits, state.habits.size, TodayExtrasTags.HABITS) }
    if (state.checkFailed) {
        item(key = TodayExtrasTags.CHECK_FAILED) {
            Text(
                stringResource(R.string.today_habit_check_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(TodayExtrasTags.CHECK_FAILED),
            )
        }
    }
    items(state.habits, key = { "habit-${it.id}" }) { habit ->
        HabitRow(habit, enabled = habit.id !in state.checking, check)
    }
}

@Composable
private fun ExtrasHeader(
    title: Int,
    count: Int,
    tag: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EventRow(
    event: Occurrence,
    color: Color,
    today: LocalDate,
    zone: ZoneId,
    open: (Occurrence) -> Unit,
) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val time = (if (event.allDay) null else eventTime(event, today, zone, locale)) ?: stringResource(R.string.today_event_all_day)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open(event) }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(TodayExtrasTags.event(event)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(
            time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            event.location?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The time range of a timed [event] on [today] in [zone]: "09:00–10:00". A side outside today is left out ("–10:00"
 * for an event that began yesterday, "22:00–" for one that ends tomorrow); `null` for one that spans all of today.
 */
internal fun eventTime(
    event: Occurrence,
    today: LocalDate,
    zone: ZoneId,
    locale: Locale,
): String? {
    val start = event.start ?: return null
    val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val startText = format.format(start.atZone(zone)).takeIf { start.atZone(zone).toLocalDate() == today }
    val end = event.end?.takeIf { it > start } ?: start
    val endsToday = end.minusNanos(1).atZone(zone).toLocalDate() == today || end == start
    val endText = format.format(end.atZone(zone)).takeIf { endsToday && end != start }
    return when {
        startText != null && endText != null -> "$startText–$endText"
        startText != null && end == start -> startText
        startText != null -> "$startText–"
        endText != null -> "–$endText"
        else -> null
    }
}

@Composable
private fun HabitRow(
    habit: Habit,
    enabled: Boolean,
    check: (Habit) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp).testTag(TodayExtrasTags.habit(habit)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label =
            stringResource(
                if (habit.doneToday) R.string.today_habit_uncheck else R.string.today_habit_check,
                habit.name,
            )
        IconButton(onClick = { check(habit) }, enabled = enabled, modifier = Modifier.testTag(TodayExtrasTags.check(habit))) {
            Icon(
                if (habit.doneToday) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = label,
                tint = if (habit.doneToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            habit.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (habit.targetCount > 1) {
            Text(
                stringResource(R.string.today_habit_count, habit.todayCount, habit.targetCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
