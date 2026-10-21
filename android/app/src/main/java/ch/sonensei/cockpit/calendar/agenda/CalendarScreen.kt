package ch.sonensei.cockpit.calendar.agenda

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * The Calendar screen. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests,
 * previews) it shows the placeholder instead of failing.
 */
@Composable
fun CalendarScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        CalendarRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_calendar, Icons.Outlined.CalendarMonth, modifier)
    }
}

@Composable
fun CalendarRoute(
    modifier: Modifier = Modifier,
    onOpen: (Occurrence) -> Unit = {},
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Reloaded each time the screen is shown or the app comes back to it (also on a later day, so "today" moves on),
    // so events changed on the web or another device appear. The first resume is the first load.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    CalendarContent(
        state = state,
        callbacks =
            CalendarCallbacks(
                select = viewModel::select,
                move = viewModel::move,
                today = viewModel::goToToday,
                openDay = viewModel::openDay,
                loadMore = viewModel::loadMore,
                setVisible = viewModel::setVisible,
                retry = viewModel::refresh,
                open = onOpen,
            ),
        modifier = modifier,
    )
}

/** What the views can ask for, so they can be tested without a view model. */
data class CalendarCallbacks(
    val select: (CalendarView) -> Unit = {},
    /** `-1` previous, `+1` next day or week. */
    val move: (Int) -> Unit = {},
    val today: () -> Unit = {},
    val openDay: (LocalDate) -> Unit = {},
    val loadMore: () -> Unit = {},
    val setVisible: (UUID, Boolean) -> Unit = { _, _ -> },
    val retry: () -> Unit = {},
    /** An occurrence was tapped (its detail and editing come with the event editor). */
    val open: (Occurrence) -> Unit = {},
)

internal object CalendarTags {
    const val AGENDA = "calendar-agenda"
    const val TIME_GRID = "calendar-time-grid"
    const val EMPTY = "calendar-empty"
    const val MORE = "calendar-more"
    const val MESSAGE = "calendar-message"
    const val RETRY = "calendar-retry"
    const val TODAY = "calendar-today"
    const val PREVIOUS = "calendar-previous"
    const val NEXT = "calendar-next"
    const val RANGE = "calendar-range"
    const val CALENDARS = "calendar-calendars"
    const val NOW = "calendar-now"

    fun view(view: CalendarView) = "calendar-view:$view"

    fun day(date: LocalDate) = "calendar-day:$date"

    fun allDay(date: LocalDate) = "calendar-all-day:$date"

    fun calendar(id: UUID) = "calendar-calendar:$id"

    fun entry(
        key: String,
        date: LocalDate,
    ) = "calendar-entry:$key@$date"
}

@Composable
fun CalendarContent(
    state: CalendarUiState,
    callbacks: CalendarCallbacks,
    modifier: Modifier = Modifier,
) {
    var showCalendars by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Toolbar(state, callbacks, showCalendars) { showCalendars = it }
        if (showCalendars) CalendarFilter(state.calendars, state.pendingVisibility.keys, callbacks.setVisible)
        state.message?.let { Message(it, state.loading, callbacks.retry) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        when (state.view) {
            CalendarView.AGENDA -> Agenda(state, callbacks)
            CalendarView.DAY, CalendarView.WEEK -> {
                RangeHeader(state, callbacks)
                TimeGrid(state, callbacks)
            }
        }
    }
}

@Composable
private fun Toolbar(
    state: CalendarUiState,
    callbacks: CalendarCallbacks,
    showCalendars: Boolean,
    onShowCalendars: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val views = CalendarView.entries
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            views.forEachIndexed { index, view ->
                SegmentedButton(
                    selected = state.view == view,
                    onClick = { callbacks.select(view) },
                    shape = SegmentedButtonDefaults.itemShape(index, views.size),
                    icon = {},
                    label = { Text(stringResource(view.label), maxLines = 1) },
                    modifier = Modifier.testTag(CalendarTags.view(view)),
                )
            }
        }
        TextButton(onClick = callbacks.today, modifier = Modifier.testTag(CalendarTags.TODAY)) {
            Text(stringResource(R.string.calendar_today))
        }
        val label = stringResource(R.string.calendar_calendars)
        IconToggleButton(
            checked = showCalendars,
            onCheckedChange = onShowCalendars,
            modifier = Modifier.testTag(CalendarTags.CALENDARS).semantics { contentDescription = label },
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null)
        }
    }
}

private val CalendarView.label: Int
    get() =
        when (this) {
            CalendarView.AGENDA -> R.string.calendar_view_agenda
            CalendarView.DAY -> R.string.calendar_view_day
            CalendarView.WEEK -> R.string.calendar_view_week
        }

/** One chip per calendar, in its color: selected ones are shown in the views. */
@Composable
private fun CalendarFilter(
    calendars: List<CalendarInfo>,
    /** Calendars whose visibility change is still being written: their chip waits for the answer. */
    pending: Set<UUID>,
    setVisible: (UUID, Boolean) -> Unit,
) {
    if (calendars.isEmpty()) {
        Text(
            stringResource(R.string.calendar_no_calendars),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(calendars, key = { it.id }) { calendar ->
            FilterChip(
                selected = calendar.visible,
                onClick = { setVisible(calendar.id, !calendar.visible) },
                enabled = calendar.id !in pending,
                label = { Text(calendar.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { ColorDot(calendarColor(calendar)) },
                modifier = Modifier.testTag(CalendarTags.calendar(calendar.id)),
            )
        }
    }
}

@Composable
private fun ColorDot(
    color: Color,
    size: Dp = 10.dp,
) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
private fun calendarColor(calendar: CalendarInfo?): Color = calendar?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

@Composable
private fun Message(
    message: CalendarMessage,
    loading: Boolean,
    onRetry: () -> Unit,
) {
    val (text, color) =
        when (message) {
            CalendarMessage.OFFLINE -> R.string.calendar_offline to MaterialTheme.colorScheme.secondaryContainer
            CalendarMessage.FAILED -> R.string.calendar_failed to MaterialTheme.colorScheme.errorContainer
            CalendarMessage.VISIBILITY_FAILED -> R.string.calendar_visibility_failed to MaterialTheme.colorScheme.errorContainer
        }
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag(CalendarTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            )
            TextButton(onClick = onRetry, enabled = !loading, modifier = Modifier.testTag(CalendarTags.RETRY)) {
                Text(stringResource(R.string.calendar_retry))
            }
        }
    }
}

// ---- Agenda ----

/** Item keys of the agenda list: a day header, an entry, the footer. */
private sealed interface AgendaItem {
    val key: String

    data class Header(
        val date: LocalDate,
    ) : AgendaItem {
        override val key get() = "day:$date"
    }

    data class Entry(
        val entry: DayEntry,
    ) : AgendaItem {
        override val key get() = "entry:${entry.occurrence.key}@${entry.date}"
    }
}

/** How many items before the end of the agenda the next days start loading. */
private const val LOAD_AHEAD_ITEMS = 5

@Composable
private fun Agenda(
    state: CalendarUiState,
    callbacks: CalendarCallbacks,
) {
    val days = remember(state.occurrences, state.from, state.to, state.zone) { state.agendaDays }
    val items = remember(days) { days.flatMap { day -> listOf(AgendaItem.Header(day.date)) + day.entries.map { AgendaItem.Entry(it) } } }
    val listState = rememberLazyListState()
    val loadMore by rememberUpdatedState(callbacks.loadMore)
    val busy by rememberUpdatedState(state.loading || state.loadingMore || !state.loaded)
    val rangeStart by rememberUpdatedState(state.from)
    // Infinite scroll: near the end of a list that can scroll, the next range loads by itself, once for each length of
    // the list. So it loads again when the days loaded last are reached, even if they added only a few entries, but a
    // range without events (same length) does not load range after range by itself: the footer still offers more. A
    // list that does not fill the screen (few events) loads more only on request, so an empty calendar does not load
    // day after day. While a load runs nothing is asked for, so a request is not lost to a view model that ignores it.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val scrollable = listState.canScrollForward || listState.canScrollBackward
            val nearEnd = scrollable && info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD_ITEMS
            if (nearEnd && !busy) rangeStart to info.totalItemsCount else null
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { loadMore() }
    }
    // Before the first days arrive only the footer is shown, and the list would keep it in place when the days are
    // inserted above it, opening the agenda scrolled past today. So the agenda starts at its first day.
    val hasItems = items.isNotEmpty()
    LaunchedEffect(state.from, hasItems) {
        if (hasItems) listState.scrollToItem(0)
    }
    val locale = currentLocale()
    LazyColumn(Modifier.fillMaxSize().testTag(CalendarTags.AGENDA), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is AgendaItem.Header -> DayHeader(item.date, state.today, locale, onClick = { callbacks.openDay(item.date) })
                is AgendaItem.Entry -> AgendaRow(item.entry, state.calendar(item.entry.occurrence.calendarId), state.zone, callbacks.open)
            }
        }
        item(key = "footer") {
            AgendaFooter(state, empty = days.isEmpty(), locale = locale, onMore = callbacks.loadMore)
        }
    }
}

@Composable
private fun DayHeader(
    date: LocalDate,
    today: LocalDate,
    locale: Locale,
    onClick: () -> Unit,
) {
    val isToday = date == today
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .testTag(CalendarTags.day(date)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val numberColor = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = numberColor)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                if (isToday) stringResource(R.string.calendar_today) else date.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
                style = MaterialTheme.typography.titleSmall,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                date.format(DateTimeFormatter.ofPattern(monthYearPattern(date, today), locale)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun monthYearPattern(
    date: LocalDate,
    today: LocalDate,
) = if (date.year == today.year) "MMMM" else "MMMM yyyy"

@Composable
private fun AgendaRow(
    entry: DayEntry,
    calendar: CalendarInfo?,
    zone: ZoneId,
    onOpen: (Occurrence) -> Unit,
) {
    val occurrence = entry.occurrence
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(occurrence) }
            .padding(start = 64.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .testTag(CalendarTags.entry(occurrence.key, entry.date)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(calendarColor(calendar)),
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(occurrence.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(timeLabel(entry, zone), occurrence.location).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "All day", "09:00 – 10:30", or "09:00 – continues" for the parts of an event spanning days. */
@Composable
private fun timeLabel(
    entry: DayEntry,
    zone: ZoneId,
): String {
    val occurrence = entry.occurrence
    if (entry.allDay || occurrence.start == null) return stringResource(R.string.calendar_all_day)
    val continues = stringResource(R.string.calendar_continues)
    val locale = currentLocale()
    val start = if (entry.continuesBefore) "…" else formatTime(occurrence.start, zone, locale)
    val end = if (entry.continuesAfter) continues else occurrence.end?.let { formatTime(it, zone, locale) }
    return if (end == null) start else "$start – $end"
}

private fun formatTime(
    instant: Instant,
    zone: ZoneId,
    locale: Locale,
): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(instant.atZone(zone))

/** The locale of the configuration, so the dates follow a change of the system language. */
@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
private fun AgendaFooter(
    state: CalendarUiState,
    empty: Boolean,
    locale: Locale,
    onMore: () -> Unit,
) {
    val until = state.to.minusDays(1).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.loaded && !state.loading) {
            Text(
                stringResource(if (empty) R.string.calendar_agenda_empty else R.string.calendar_loaded_until, until),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (empty) Modifier.testTag(CalendarTags.EMPTY) else Modifier,
            )
        }
        if (state.loadingMore) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        } else if (state.loaded) {
            TextButton(onClick = onMore, enabled = !state.loading, modifier = Modifier.testTag(CalendarTags.MORE)) {
                Text(stringResource(R.string.calendar_load_more))
            }
        }
    }
}

// ---- Day and week ----

@Composable
private fun RangeHeader(
    state: CalendarUiState,
    callbacks: CalendarCallbacks,
) {
    val locale = currentLocale()
    val week = state.view == CalendarView.WEEK
    val title =
        if (week) {
            val days = state.days
            val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            stringResource(R.string.calendar_week_range, days.first().format(format), days.last().format(format))
        } else {
            state.anchor.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { callbacks.move(-1) }, modifier = Modifier.testTag(CalendarTags.PREVIOUS)) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = stringResource(if (week) R.string.calendar_previous_week else R.string.calendar_previous_day),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(CalendarTags.RANGE),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { callbacks.move(1) }, modifier = Modifier.testTag(CalendarTags.NEXT)) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(if (week) R.string.calendar_next_week else R.string.calendar_next_day),
            )
        }
    }
}

private val HOUR_HEIGHT = 48.dp
private val GUTTER = 48.dp

/** The hour the grid is scrolled to when it opens, so a working day is in view. */
private const val FIRST_VISIBLE_HOUR = 7
private const val MINUTE_MILLIS = 60_000L

/** The clock the "now" line of the day and week grid follows (tests provide their own). */
internal val LocalNowClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }

/** Day columns over a 24-hour grid, all-day events in a strip above it; one column for the day view, seven for the week. */
@Composable
private fun TimeGrid(
    state: CalendarUiState,
    callbacks: CalendarCallbacks,
) {
    val days = state.days
    val entriesByDay =
        remember(state.occurrences, days, state.zone) { days.associateWith { entriesOn(state.occurrences, it, state.zone) } }
    val week = state.view == CalendarView.WEEK
    val locale = currentLocale()
    Column(Modifier.fillMaxSize().testTag(CalendarTags.TIME_GRID)) {
        if (week) {
            Row(Modifier.fillMaxWidth().padding(start = GUTTER)) {
                days.forEach { day ->
                    WeekDayHeader(day, day == state.today, locale, Modifier.weight(1f)) { callbacks.openDay(day) }
                }
            }
        }
        AllDayStrip(days, entriesByDay, state, callbacks.open)
        HorizontalDivider()
        if (!week && state.loaded && entriesByDay.values.all { it.isEmpty() }) {
            Text(
                stringResource(R.string.calendar_day_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = GUTTER + 8.dp, top = 8.dp).testTag(CalendarTags.EMPTY),
            )
        }
        val initialScroll = with(LocalDensity.current) { (HOUR_HEIGHT * FIRST_VISIBLE_HOUR).roundToPx() }
        val scroll = rememberScrollState(initialScroll)
        Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            HourLabels(locale)
            days.forEach { day ->
                DayColumn(day, entriesByDay[day].orEmpty(), state, week, callbacks.open, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WeekDayHeader(
    day: LocalDate,
    isToday: Boolean,
    locale: Locale,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 4.dp).testTag(CalendarTags.day(day)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${day.dayOfMonth}",
                style = MaterialTheme.typography.labelLarge,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AllDayStrip(
    days: List<LocalDate>,
    entriesByDay: Map<LocalDate, List<DayEntry>>,
    state: CalendarUiState,
    onOpen: (Occurrence) -> Unit,
) {
    if (days.none { day -> entriesByDay[day].orEmpty().any { it.allDay } }) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            stringResource(R.string.calendar_all_day),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.width(GUTTER).padding(horizontal = 4.dp),
        )
        days.forEach { day ->
            Column(Modifier.weight(1f).padding(horizontal = 1.dp).testTag(CalendarTags.allDay(day))) {
                entriesByDay[day].orEmpty().filter { it.allDay }.forEach { entry ->
                    EventBlock(
                        entry,
                        state.calendar(entry.occurrence.calendarId),
                        null,
                        onOpen,
                        Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HourLabels(locale: Locale) {
    val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    Column(Modifier.width(GUTTER)) {
        for (hour in 0 until 24) {
            Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth().padding(end = 6.dp), contentAlignment = Alignment.TopEnd) {
                if (hour > 0) {
                    Text(
                        LocalTime.of(hour, 0).format(format),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.offset(y = (-7).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    entries: List<DayEntry>,
    state: CalendarUiState,
    week: Boolean,
    onOpen: (Occurrence) -> Unit,
    modifier: Modifier,
) {
    val placed = remember(entries) { layoutDay(entries) }
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier.height(HOUR_HEIGHT * 24)) {
        // Hour lines, and a hairline between the days of the week.
        for (hour in 0 until 24) {
            HorizontalDivider(Modifier.offset(y = HOUR_HEIGHT * hour), color = lineColor.copy(alpha = 0.6f))
        }
        if (week) Box(Modifier.fillMaxHeight().width(0.5.dp).background(lineColor))
        val laneWidth = maxWidth
        placed.forEach { item ->
            val top = HOUR_HEIGHT * item.entry.startMinute / 60f
            val height = HOUR_HEIGHT * (visibleEnd(item.entry) - item.entry.startMinute) / 60f
            val width = laneWidth / item.lanes
            EventBlock(
                item.entry,
                state.calendar(item.entry.occurrence.calendarId),
                state.zone.takeUnless { week },
                onOpen,
                Modifier
                    .offset(x = width * item.lane, y = top)
                    .width(width)
                    .height(height)
                    .padding(start = 1.dp, end = 1.dp, bottom = 1.dp),
            )
        }
        if (day == state.today) NowLine(state.zone)
    }
}

@Composable
private fun NowLine(zone: ZoneId) {
    val clock = LocalNowClock.current
    var now by remember(clock, zone) { mutableStateOf(LocalTime.now(clock.withZone(zone))) }
    // The line moves with the time while the grid is shown: checked again at the start of every minute.
    LaunchedEffect(clock, zone) {
        while (true) {
            delay(MINUTE_MILLIS - clock.millis() % MINUTE_MILLIS)
            now = LocalTime.now(clock.withZone(zone))
        }
    }
    val color = MaterialTheme.colorScheme.error
    Box(
        Modifier
            .offset { IntOffset(0, (HOUR_HEIGHT * (now.hour * 60 + now.minute) / 60f).roundToPx()) }
            .fillMaxWidth()
            .height(2.dp)
            .background(color)
            .testTag(CalendarTags.NOW),
    )
}

/** An event in the grid or the all-day strip: tinted with its calendar's color, a solid stripe on its leading edge. */
@Composable
private fun EventBlock(
    entry: DayEntry,
    calendar: CalendarInfo?,
    /** Given: the time is shown under the title (there is room for it in the day view). */
    timeZone: ZoneId?,
    onOpen: (Occurrence) -> Unit,
    modifier: Modifier,
) {
    val color = calendarColor(calendar)
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(color.copy(alpha = 0.18f))
            .clickable { onOpen(entry.occurrence) }
            .testTag(CalendarTags.entry(entry.occurrence.key, entry.date)),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                entry.occurrence.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (timeZone == null) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (timeZone != null && !entry.allDay) {
                Text(
                    listOfNotNull(timeLabel(entry, timeZone), entry.occurrence.location).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
