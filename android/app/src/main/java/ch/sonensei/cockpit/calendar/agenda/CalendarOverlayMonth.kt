package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.sonensei.cockpit.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// ---- Task overlay ----

private val TaskMarkKind.icon: ImageVector
    get() =
        when (this) {
            TaskMarkKind.DUE -> Icons.Outlined.Flag
            TaskMarkKind.SCHEDULED -> Icons.Outlined.CheckBoxOutlineBlank
            TaskMarkKind.TICKLER -> Icons.Outlined.EventRepeat
        }

private val TaskMarkKind.label: Int
    get() =
        when (this) {
            TaskMarkKind.DUE -> R.string.calendar_task_due
            TaskMarkKind.SCHEDULED -> R.string.calendar_task_scheduled
            TaskMarkKind.TICKLER -> R.string.calendar_task_tickler
        }

/** Tasks are drawn outlined in the tertiary color, so they are not taken for events. */
@Composable
private fun taskColor(): Color = MaterialTheme.colorScheme.tertiary

/** "Due 14:30", "Scheduled", "Back from tickler". */
@Composable
private fun markLabel(
    mark: TaskMark,
    locale: Locale,
): String {
    val kind = stringResource(mark.kind.label)
    val time = mark.time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return if (time == null) kind else "$kind $time"
}

/** A task of the overlay in the agenda: like an event row, but with the task's icon instead of the color stripe. */
@Composable
internal fun AgendaTaskRow(
    mark: TaskMark,
    locale: Locale,
    onOpen: (OverlayTask) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(mark.task) }
            .padding(start = 60.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .testTag(CalendarTags.task(mark.key, mark.date)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(mark.kind.icon, contentDescription = null, tint = taskColor(), modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(mark.task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                markLabel(mark, locale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A task of the overlay in the all-day strip of the day and week views: outlined, so it is not taken for an event. */
@Composable
internal fun TaskChip(
    mark: TaskMark,
    /** The day view has room for the kind and time under the title. */
    detailed: Boolean,
    locale: Locale,
    onOpen: (OverlayTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = taskColor()
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .clickable { onOpen(mark.task) }
            .padding(horizontal = 3.dp, vertical = 2.dp)
            .testTag(CalendarTags.task(mark.key, mark.date)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(mark.kind.icon, contentDescription = stringResource(mark.kind.label), tint = color, modifier = Modifier.size(14.dp))
        Column(Modifier.padding(start = 3.dp)) {
            Text(
                mark.task.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = if (detailed) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detailed) {
                Text(
                    markLabel(mark, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Shown under the toolbar when the overlay is on but its tasks could not be loaded; [onRetry] loads them again. */
@Composable
internal fun TasksFailedLine(onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.calendar_tasks_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).testTag(CalendarTags.TASKS_FAILED),
        )
        val description = stringResource(R.string.calendar_tasks_retry)
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = description }.testTag(CalendarTags.TASKS_RETRY),
        ) {
            Text(stringResource(R.string.calendar_retry))
        }
    }
}

// ---- Month ----

/** How many entries a day of the month view lists by title before it says "+n" (fewer when the cell is too low). */
internal const val MONTH_TITLES = 3

/**
 * Room kept free under the month grid for the calendar's floating "new event" button (56dp plus its 16dp margin), so
 * it never covers the last week's cells: the month grid does not scroll like the other views.
 */
internal val MONTH_FAB_SPACE = 72.dp

/**
 * How many of a day's [count] entries are listed by title when [lines] title lines fit into the cell: at most
 * [MONTH_TITLES], and when not all are listed, one line is left for "+n".
 */
internal fun monthTitlesShown(
    count: Int,
    lines: Int,
): Int {
    val fit = lines.coerceAtLeast(0)
    return if (count <= minOf(fit, MONTH_TITLES)) count else minOf(MONTH_TITLES, fit - 1).coerceAtLeast(0)
}

/** One item listed in a month day: an event or an overlay task. */
private sealed interface MonthItem {
    data class Event(
        val entry: DayEntry,
    ) : MonthItem

    data class Task(
        val mark: TaskMark,
    ) : MonthItem
}

/**
 * The month view: whole weeks of the anchor's month, each day with up to [MONTH_TITLES] compact titles (events, then
 * overlay tasks) and "+n" for the rest. Tapping a day opens it in the day view.
 */
@Composable
internal fun MonthGrid(
    state: CalendarUiState,
    callbacks: CalendarCallbacks,
    locale: Locale,
) {
    val days = state.days
    val marks = remember(state.overlay, state.tasks, state.from, state.to, state.today) { state.taskMarks }
    val itemsByDay =
        remember(state.occurrences, days, state.zone, marks) {
            days.associateWith { day ->
                entriesOn(state.occurrences, day, state.zone).map { MonthItem.Event(it) } +
                    marks[day].orEmpty().map { MonthItem.Task(it) }
            }
        }
    val weeks = days.chunked(DAYS_PER_WEEK)
    Column(Modifier.fillMaxSize().padding(bottom = MONTH_FAB_SPACE).testTag(CalendarTags.MONTH)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            weeks.first().forEach { day ->
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        HorizontalDivider()
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                week.forEach { day ->
                    MonthDay(day, itemsByDay[day].orEmpty(), state, locale, Modifier.weight(1f).fillMaxHeight()) {
                        callbacks.openDay(day)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private const val DAYS_PER_WEEK = 7

@Composable
private fun MonthDay(
    day: LocalDate,
    items: List<MonthItem>,
    state: CalendarUiState,
    locale: Locale,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val isToday = day == state.today
    val inMonth = day.month == state.anchor.month
    val description =
        listOf(
            day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),
            pluralStringResource(R.plurals.calendar_month_items, items.size, items.size),
        ).joinToString(", ")
    // The cells do not scroll: only as many title lines are listed as fit under the day number, so "+n" is never cut off.
    val labelLineHeight = MaterialTheme.typography.labelSmall.lineHeight
    // A theme without a line height (TextUnit.Unspecified) cannot be converted to dp: Material's 16sp is assumed then.
    val lineHeight = with(LocalDensity.current) { (if (labelLineHeight.isSp) labelLineHeight else MONTH_LINE_HEIGHT).toDp() }
    BoxWithConstraints(
        modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 1.dp, vertical = 2.dp)
            .testTag(CalendarTags.monthDay(day)),
    ) {
        val lines =
            if (constraints.hasBoundedHeight) {
                ((maxHeight - MONTH_DAY_SIZE) / (maxOf(lineHeight, MONTH_TITLE_MIN_HEIGHT) + MONTH_SPACING)).toInt()
            } else {
                MONTH_TITLES + 1
            }
        MonthDayContent(day, items, state, isToday, inMonth, monthTitlesShown(items.size, lines))
    }
}

private val MONTH_DAY_SIZE = 22.dp
private val MONTH_SPACING = 1.dp
private val MONTH_LINE_HEIGHT = 16.sp

/** An event title has a 14dp color bar, so a title line is at least that high. */
private val MONTH_TITLE_MIN_HEIGHT = 14.dp

@Composable
private fun MonthDayContent(
    day: LocalDate,
    items: List<MonthItem>,
    state: CalendarUiState,
    isToday: Boolean,
    inMonth: Boolean,
    shown: Int,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MONTH_SPACING)) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(MONTH_DAY_SIZE)
                .clip(CircleShape)
                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${day.dayOfMonth}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                color =
                    when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        inMonth -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
            )
        }
        items.take(shown).forEach { item -> MonthTitle(item, state) }
        val more = items.size - shown
        if (more > 0) {
            Text(
                stringResource(R.string.calendar_month_more, more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp).testTag(CalendarTags.monthMore(day)),
            )
        }
    }
}

@Composable
private fun MonthTitle(
    item: MonthItem,
    state: CalendarUiState,
) {
    val shape = RoundedCornerShape(2.dp)
    when (item) {
        is MonthItem.Event -> {
            val color = calendarColor(state.calendar(item.entry.occurrence.calendarId))
            Row(
                Modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.18f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(2.dp).height(14.dp).background(color))
                MonthTitleText(item.entry.occurrence.title)
            }
        }
        is MonthItem.Task -> {
            val color = taskColor()
            Row(
                Modifier.fillMaxWidth().clip(shape).border(0.5.dp, color.copy(alpha = 0.6f), shape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.mark.kind.icon, contentDescription = null, tint = color, modifier = Modifier.size(10.dp))
                MonthTitleText(item.mark.task.title)
            }
        }
    }
}

@Composable
private fun MonthTitleText(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}
