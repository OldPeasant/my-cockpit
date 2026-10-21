package ch.sonensei.cockpit.calendar.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** The calendar views of SPEC.md §9.2 item 4. */
enum class CalendarView { AGENDA, DAY, WEEK }

/** Why the shown occurrences may be outdated. */
enum class CalendarMessage { OFFLINE, FAILED, VISIBILITY_FAILED }

data class CalendarUiState(
    val view: CalendarView = CalendarView.AGENDA,
    val today: LocalDate = NO_DATE,
    /** The day shown by the day view, and a day of the week shown by the week view. */
    val anchor: LocalDate = NO_DATE,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val zone: ZoneId = ZoneId.systemDefault(),
    val calendars: List<CalendarInfo> = emptyList(),
    /** The loaded days [from, to) and their occurrences. */
    val from: LocalDate = NO_DATE,
    val to: LocalDate = NO_DATE,
    val occurrences: List<Occurrence> = emptyList(),
    /** A load of the whole range is running. */
    val loading: Boolean = false,
    /** The agenda is loading the days after [to]. */
    val loadingMore: Boolean = false,
    /** The range was loaded at least once. */
    val loaded: Boolean = false,
    val message: CalendarMessage? = null,
) {
    /** The agenda: the days of the range that have occurrences. */
    val agendaDays: List<AgendaDay> get() = groupByDay(occurrences, from, to, zone)

    /** The days shown by the day or the week view. */
    val days: List<LocalDate>
        get() =
            when (view) {
                CalendarView.WEEK -> weekOf(anchor, firstDayOfWeek)
                else -> listOf(anchor)
            }

    fun calendar(id: UUID): CalendarInfo? = calendars.firstOrNull { it.id == id }
}

/**
 * Agenda (from today on, loaded further by range as it is scrolled), day and week views, and which calendars are
 * shown. Nothing is cached: every view loads its range from the server.
 */
@HiltViewModel
class CalendarViewModel internal constructor(
    private val repository: CalendarRepository,
    private val clock: Clock,
    locale: Locale,
) : ViewModel() {
    @Inject
    constructor(repository: CalendarRepository) : this(repository, Clock.systemDefaultZone(), Locale.getDefault())

    private val state =
        MutableStateFlow(
            LocalDate.now(clock).let { today ->
                CalendarUiState(
                    today = today,
                    anchor = today,
                    firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek,
                    zone = clock.zone,
                )
            },
        )
    val uiState: StateFlow<CalendarUiState> = state.asStateFlow()

    private var loadJob: Job? = null

    /** Loads the calendars and the current view's range again (the agenda: everything scrolled to so far). */
    fun refresh() {
        val today = LocalDate.now(clock)
        state.update { current ->
            val next = current.copy(today = today)
            // Nothing loaded yet, or the day changed since the agenda was opened: it starts at the new today.
            if (current.to <= current.from || (current.view == CalendarView.AGENDA && current.from != today)) {
                val (from, to) = rangeOf(current.view, current.anchor, next)
                next.copy(from = from, to = to, occurrences = emptyList(), loaded = false)
            } else {
                next
            }
        }
        load(state.value.from, state.value.to)
    }

    /** Switches to [view]; the day and week views keep the day they show. */
    fun select(view: CalendarView) {
        if (view == state.value.view) return
        val anchor = if (view == CalendarView.AGENDA) state.value.today else state.value.anchor
        show(view, anchor)
    }

    /** The previous (`-1`) or next (`+1`) day or week. */
    fun move(step: Int) {
        val current = state.value
        val days =
            when (current.view) {
                CalendarView.AGENDA -> return
                CalendarView.DAY -> 1L
                CalendarView.WEEK -> 7L
            }
        show(current.view, current.anchor.plusDays(step * days))
    }

    /** Back to today in the current view. */
    fun goToToday() = show(state.value.view, state.value.today, force = true)

    /** Opens the day view on [date] (e.g. from a day header of the agenda or the week). */
    fun openDay(date: LocalDate) = show(CalendarView.DAY, date)

    /** The agenda was scrolled near its end: loads the next days and appends them. */
    fun loadMore() {
        val current = state.value
        if (current.view != CalendarView.AGENDA || current.loading || current.loadingMore || !current.loaded) return
        val from = current.to
        val to = from.plusDays(AGENDA_CHUNK_DAYS)
        state.update { it.copy(loadingMore = true) }
        loadJob =
            viewModelScope.launch {
                val result = repository.occurrences(from, to, current.zone)
                state.update {
                    when (result) {
                        is CalendarResult.Ok ->
                            it.copy(
                                to = to,
                                occurrences = (it.occurrences + result.value).distinctBy { o -> o.key },
                                loadingMore = false,
                                message = null,
                            )
                        CalendarResult.Offline -> it.copy(loadingMore = false, message = CalendarMessage.OFFLINE)
                        is CalendarResult.Failed -> it.copy(loadingMore = false, message = CalendarMessage.FAILED)
                    }
                }
            }
    }

    /** Shows or hides a calendar's events (stored on the server, so every device and the web agree), then reloads. */
    fun setVisible(
        calendarId: UUID,
        visible: Boolean,
    ) {
        val calendar = state.value.calendar(calendarId) ?: return
        if (calendar.visible == visible) return
        state.update { s -> s.copy(calendars = s.calendars.map { if (it.id == calendarId) it.copy(visible = visible) else it }) }
        viewModelScope.launch {
            when (val result = repository.setVisible(calendar, visible)) {
                is CalendarResult.Ok -> {
                    state.update { s -> s.copy(calendars = s.calendars.map { if (it.id == calendarId) result.value else it }) }
                    load(state.value.from, state.value.to)
                }
                else -> {
                    // Refused (or offline): the toggle goes back, and the calendars are reloaded in case it was stale.
                    state.update { s ->
                        s.copy(
                            calendars = s.calendars.map { if (it.id == calendarId) calendar else it },
                            message = if (result == CalendarResult.Offline) CalendarMessage.OFFLINE else CalendarMessage.VISIBILITY_FAILED,
                        )
                    }
                    if (result != CalendarResult.Offline) refreshCalendars()
                }
            }
        }
    }

    private fun refreshCalendars() {
        viewModelScope.launch {
            (repository.calendars() as? CalendarResult.Ok)?.let { ok -> state.update { it.copy(calendars = ok.value) } }
        }
    }

    private fun show(
        view: CalendarView,
        anchor: LocalDate,
        force: Boolean = false,
    ) {
        val current = state.value
        val (from, to) = rangeOf(view, anchor, current)
        if (!force && view == current.view && from == current.from && to == current.to && current.loaded) {
            state.update { it.copy(anchor = anchor) }
            return
        }
        val sameRange = from == current.from && to == current.to
        state.update {
            it.copy(
                view = view,
                anchor = anchor,
                from = from,
                to = to,
                // The occurrences of another range would be wrong here: cleared until the new ones arrive.
                occurrences = if (sameRange) it.occurrences else emptyList(),
                loaded = it.loaded && sameRange,
            )
        }
        load(from, to)
    }

    private fun rangeOf(
        view: CalendarView,
        anchor: LocalDate,
        current: CalendarUiState,
    ): Pair<LocalDate, LocalDate> =
        when (view) {
            CalendarView.AGENDA -> current.today to current.today.plusDays(AGENDA_CHUNK_DAYS)
            CalendarView.DAY -> anchor to anchor.plusDays(1)
            CalendarView.WEEK -> weekOf(anchor, current.firstDayOfWeek).let { it.first() to it.last().plusDays(1) }
        }

    /** Loads [from, to) and the calendars; a load still running for an earlier range is dropped. */
    private fun load(
        from: LocalDate,
        to: LocalDate,
    ) {
        loadJob?.cancel()
        val zone = state.value.zone
        state.update { it.copy(loading = true, loadingMore = false) }
        loadJob =
            viewModelScope.launch {
                val calendars = async { repository.calendars() }
                val result = repository.occurrences(from, to, zone)
                val calendarList = calendars.await()
                state.update {
                    it.copy(
                        calendars = (calendarList as? CalendarResult.Ok)?.value ?: it.calendars,
                        occurrences = (result as? CalendarResult.Ok)?.value ?: it.occurrences,
                        loading = false,
                        loaded = it.loaded || result is CalendarResult.Ok,
                        message =
                            when (result) {
                                is CalendarResult.Ok -> null
                                CalendarResult.Offline -> CalendarMessage.OFFLINE
                                is CalendarResult.Failed -> CalendarMessage.FAILED
                            },
                    )
                }
            }
    }

    internal companion object {
        /** Days loaded at once by the agenda. */
        const val AGENDA_CHUNK_DAYS = 30L
    }
}
