package ch.sonensei.cockpit.calendar.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** The calendar views of SPEC.md §9.2 item 4, and the month view of the web (SPEC.md §6.3). */
enum class CalendarView { AGENDA, DAY, WEEK, MONTH }

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
    /** Calendars whose visibility change is still being written, and the visibility the user chose. */
    val pendingVisibility: Map<UUID, Boolean> = emptyMap(),
    /**
     * The server could not be reached and the shown occurrences come from the offline cache, loaded at this time; they
     * are read-only until a load works again. `null` when nothing cached is shown.
     */
    val cachedAt: Instant? = null,
    /** The task overlay is on: due and scheduled tasks and ticklers of the shown days are shown (SPEC.md §6.3). */
    val overlay: Boolean = false,
    /** The overlay's tasks of [from, to); empty while it is off. */
    val tasks: List<OverlayTask> = emptyList(),
    /** The overlay's tasks could not be loaded for the shown range. */
    val tasksFailed: Boolean = false,
) {
    /** Something is shown for the range: loaded from the server, or from the cache. */
    val shown: Boolean get() = loaded || cachedAt != null

    /** The agenda: the days of the range that have occurrences. */
    val agendaDays: List<AgendaDay> get() = groupByDay(occurrences, from, to, zone)

    /** The days shown by the day or the week view. */
    val days: List<LocalDate>
        get() =
            when (view) {
                CalendarView.WEEK -> weekOf(anchor, firstDayOfWeek)
                CalendarView.MONTH -> monthGrid(anchor, firstDayOfWeek)
                else -> listOf(anchor)
            }

    /** The overlay's marks by day; empty while it is off. */
    val taskMarks: Map<LocalDate, List<TaskMark>> get() = if (overlay) taskMarks(tasks, from, to, today) else emptyMap()

    fun calendar(id: UUID): CalendarInfo? = calendars.firstOrNull { it.id == id }
}

/**
 * Agenda (from today on, loaded further by range as it is scrolled), day and week views, and which calendars are
 * shown. Every view loads its range from the server; the agenda's loads are also kept in the [AgendaCache], which is
 * shown read-only when the server cannot be reached (SPEC.md §9.3).
 */
@HiltViewModel
class CalendarViewModel internal constructor(
    private val repository: CalendarRepository,
    private val clock: Clock,
    locale: Locale,
    private val cache: AgendaCache = AgendaCache.None,
    settings: StateFlow<UserSettings?> = MutableStateFlow(null),
    private val taskSource: TaskOverlaySource = TaskOverlaySource.None,
    private val overlaySetting: TaskOverlaySetting = TaskOverlaySetting.InMemory(),
) : ViewModel() {
    @Inject
    constructor(
        repository: CalendarRepository,
        cache: AgendaCache,
        settings: UserSettingsRepository,
        tasks: TaskOverlayRepository,
        overlaySetting: SharedPreferencesTaskOverlaySetting,
    ) : this(repository, Clock.systemDefaultZone(), Locale.getDefault(), cache, settings.settings, tasks, overlaySetting)

    /** The week start of the device locale, for as long as the synced settings are not known. */
    private val localeWeekStart = WeekFields.of(locale).firstDayOfWeek

    private val state =
        MutableStateFlow(
            settings.value.let { initial ->
                val zone = initial?.timeZone ?: clock.zone
                val today = LocalDate.now(clock.withZone(zone))
                CalendarUiState(
                    today = today,
                    anchor = today,
                    firstDayOfWeek = initial?.weekStart ?: localeWeekStart,
                    zone = zone,
                    overlay = overlaySetting.enabled,
                )
            },
        )
    val uiState: StateFlow<CalendarUiState> = state.asStateFlow()

    private var loadJob: Job? = null
    private var tasksJob: Job? = null

    /** For each calendar with a pending visibility change: the newest state known from the server. */
    private val serverCalendars = mutableMapOf<UUID, CalendarInfo>()

    init {
        viewModelScope.launch { settings.collect(::applySettings) }
    }

    /** The synced week start or time zone changed (e.g. loaded at start): the shown range is computed and loaded again. */
    private fun applySettings(settings: UserSettings?) {
        val current = state.value
        val weekStart = settings?.weekStart ?: localeWeekStart
        val zone = settings?.timeZone ?: clock.zone
        if (weekStart == current.firstDayOfWeek && zone == current.zone) return
        val today = LocalDate.now(clock.withZone(zone))
        state.update { it.copy(firstDayOfWeek = weekStart, zone = zone, today = today) }
        // Nothing loaded or requested yet: the first refresh() uses the new values.
        if (current.to <= current.from) return
        val anchor = if (current.view == CalendarView.AGENDA) today else current.anchor
        show(current.view, anchor, force = true)
    }

    /** Loads the calendars and the current view's range again (the agenda: everything scrolled to so far). */
    fun refresh() {
        val today = LocalDate.now(clock.withZone(state.value.zone))
        state.update { current ->
            val next = current.copy(today = today)
            // Nothing loaded yet, or the day changed since the agenda was opened: it starts at the new today.
            if (current.to <= current.from || (current.view == CalendarView.AGENDA && current.from != today)) {
                val (from, to) = rangeOf(current.view, current.anchor, next)
                next.copy(from = from, to = to, occurrences = emptyList(), loaded = false, cachedAt = null)
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
                CalendarView.MONTH -> return show(current.view, current.anchor.plusMonths(step.toLong()))
            }
        show(current.view, current.anchor.plusDays(step * days))
    }

    /** Back to today in the current view. */
    fun goToToday() = show(state.value.view, state.value.today, force = true)

    /** Opens the day view on [date] (e.g. from a day header of the agenda or the week). */
    fun openDay(date: LocalDate) = show(CalendarView.DAY, date)

    /** Turns the task overlay on (loads the tasks of the shown days) or off; the choice is remembered. */
    fun setOverlay(enabled: Boolean) {
        if (enabled == state.value.overlay) return
        overlaySetting.enabled = enabled
        if (enabled) {
            state.update { it.copy(overlay = true) }
            loadTasks(state.value.from, state.value.to, append = false)
        } else {
            tasksJob?.cancel()
            state.update { it.copy(overlay = false, tasks = emptyList(), tasksFailed = false) }
        }
    }

    /** Loads the overlay's tasks of the shown range again, e.g. after they could not be loaded. */
    fun retryTasks() = loadTasks(state.value.from, state.value.to, append = false)

    /**
     * Loads the overlay's tasks of [from, to) (when it is on): replaces the shown ones, or adds those of the days the
     * agenda appended. A load still running for an earlier range is dropped when the range is replaced.
     */
    private fun loadTasks(
        from: LocalDate,
        to: LocalDate,
        append: Boolean,
    ) {
        if (!state.value.overlay || to <= from) return
        if (!append) tasksJob?.cancel()
        val job =
            viewModelScope.launch {
                val result = taskSource.tasks(from, to, state.value.today)
                state.update {
                    // Turned off, or another range shown meanwhile: the answer no longer fits.
                    if (!it.overlay || from < it.from || to > it.to) return@update it
                    when (result) {
                        is CalendarResult.Ok ->
                            it.copy(
                                tasks = if (append) (it.tasks + result.value).distinctBy { t -> t.id } else result.value,
                                tasksFailed = false,
                            )
                        else -> it.copy(tasksFailed = true)
                    }
                }
            }
        if (!append) tasksJob = job
    }

    /** The agenda was scrolled near its end: loads the next days and appends them. */
    fun loadMore() {
        val current = state.value
        if (current.view != CalendarView.AGENDA || current.loading || current.loadingMore || !current.loaded) return
        val from = current.to
        val to = from.plusDays(AGENDA_CHUNK_DAYS)
        state.update { it.copy(loadingMore = true) }
        loadJob =
            viewModelScope.launch {
                val session = cache.session()
                val result = repository.occurrences(from, to, current.zone)
                if (result is CalendarResult.Ok) cache.append(session, from, to, result.value)
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
                // After the range was extended: an answer for the new days is only kept while they are in the range.
                if (result is CalendarResult.Ok) loadTasks(from, to, append = true)
            }
    }

    /**
     * Shows or hides a calendar's events (stored on the server, so every device and the web agree), then reloads.
     * While a change of a calendar is being written, further changes of that calendar are ignored: they would be sent
     * with the version the running one is about to replace, and refused.
     */
    fun setVisible(
        calendarId: UUID,
        visible: Boolean,
    ) {
        val current = state.value
        // Cached data is read-only: the toggle would be written against calendars that may have changed since.
        if (calendarId in current.pendingVisibility || current.cachedAt != null) return
        val calendar = current.calendar(calendarId) ?: return
        if (calendar.visible == visible) return
        // What the server has for this calendar; a calendar list loaded while the change runs replaces it.
        serverCalendars[calendarId] = calendar
        state.update { s ->
            s.copy(
                calendars = s.calendars.map { if (it.id == calendarId) it.copy(visible = visible) else it },
                pendingVisibility = s.pendingVisibility + (calendarId to visible),
            )
        }
        viewModelScope.launch {
            val result = repository.setVisible(calendar, visible)
            val before = serverCalendars.remove(calendarId) ?: calendar
            when (result) {
                is CalendarResult.Ok -> {
                    state.update { s ->
                        s.copy(
                            calendars = s.calendars.map { if (it.id == calendarId) result.value else it },
                            pendingVisibility = s.pendingVisibility - calendarId,
                        )
                    }
                    load(state.value.from, state.value.to)
                }
                else -> {
                    // Refused (or offline): the toggle goes back to what the server has (the newest calendar list loaded
                    // meanwhile, or the calendar as it was before the tap), and the calendars are reloaded in case it was stale.
                    state.update { s ->
                        s.copy(
                            calendars = s.calendars.map { if (it.id == calendarId) before else it },
                            pendingVisibility = s.pendingVisibility - calendarId,
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
            (repository.calendars() as? CalendarResult.Ok)?.let { ok -> state.update { it.withCalendars(ok.value) } }
        }
    }

    /**
     * Applies a calendar list loaded from the server. It may have been asked for before a visibility change was
     * written: a calendar with a pending change keeps the visibility the user chose, and one already holding a newer
     * version (the answer of a change) is kept.
     */
    private fun CalendarUiState.withCalendars(loaded: List<CalendarInfo>): CalendarUiState {
        val known = calendars.associateBy { it.id }
        loaded.filter { it.id in pendingVisibility }.forEach { fetched ->
            serverCalendars.computeIfPresent(fetched.id) { _, seen -> if (fetched.version >= seen.version) fetched else seen }
        }
        return copy(
            calendars =
                loaded.map { fetched ->
                    val local = known[fetched.id]
                    val pending = pendingVisibility[fetched.id]
                    when {
                        local != null && local.version > fetched.version -> local
                        pending != null -> fetched.copy(visible = pending)
                        else -> fetched
                    }
                },
        )
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
                tasks = if (sameRange) it.tasks else emptyList(),
                // A failure of another range's tasks says nothing about this range's.
                tasksFailed = it.tasksFailed && sameRange,
                loaded = it.loaded && sameRange,
                cachedAt = it.cachedAt.takeIf { sameRange },
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
            CalendarView.MONTH -> monthGrid(anchor, current.firstDayOfWeek).let { it.first() to it.last().plusDays(1) }
        }

    /**
     * Loads [from, to) and the calendars; a load still running for an earlier range is dropped. The agenda's answer is
     * cached; when the server cannot be reached and nothing is shown for the range yet, the cache is shown instead.
     */
    private fun load(
        from: LocalDate,
        to: LocalDate,
    ) {
        loadJob?.cancel()
        val zone = state.value.zone
        val agenda = state.value.view == CalendarView.AGENDA
        state.update { it.copy(loading = true, loadingMore = false) }
        loadTasks(from, to, append = false)
        loadJob =
            viewModelScope.launch {
                val session = cache.session()
                val calendars = async { repository.calendars() }
                val result = repository.occurrences(from, to, zone)
                val calendarList = calendars.await()
                if (result is CalendarResult.Ok && agenda) {
                    // Only a calendar list from the server is stored; without one, the cached calendars are kept.
                    val loadedCalendars = (calendarList as? CalendarResult.Ok)?.value ?: cache.read()?.calendars.orEmpty()
                    cache.store(session, CachedAgenda(from, to, loadedCalendars, result.value, Instant.now(clock)))
                }
                if (result == CalendarResult.Offline && !state.value.loaded) {
                    val cached = cache.read()?.let { cachedRange(it, from, to, agenda) }
                    if (cached != null) {
                        state.update { current ->
                            // Calendars loaded now or earlier in this session are newer than the cached ones.
                            val withCalendars =
                                (calendarList as? CalendarResult.Ok)?.let { current.withCalendars(it.value) }
                                    ?: current.copy(calendars = current.calendars.ifEmpty { cached.calendars })
                            withCalendars.withCached(cached)
                        }
                        return@launch
                    }
                }
                state.update { current ->
                    val withCalendars = (calendarList as? CalendarResult.Ok)?.let { current.withCalendars(it.value) } ?: current
                    withCalendars.copy(
                        occurrences = (result as? CalendarResult.Ok)?.value ?: current.occurrences,
                        loading = false,
                        loaded = current.loaded || result is CalendarResult.Ok,
                        cachedAt = current.cachedAt.takeUnless { result is CalendarResult.Ok },
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

    /**
     * The part of [cached] for [from, to): the agenda shows the cached days from [from] on (the cache may have been
     * loaded on an earlier day), a day or week only when all its days are cached. `null` when nothing fits.
     */
    private fun cachedRange(
        cached: CachedAgenda,
        from: LocalDate,
        to: LocalDate,
        agenda: Boolean,
    ): CachedAgenda? {
        if (cached.from > from) return null
        val end = if (agenda) cached.to else to
        if (end <= from || cached.to < end) return null
        val zone = state.value.zone
        val touching = cached.occurrences.filter { o -> o.days(zone).let { it.start < end && it.endInclusive >= from } }
        return cached.copy(from = from, to = end, occurrences = touching)
    }

    private fun CalendarUiState.withCached(cached: CachedAgenda): CalendarUiState =
        copy(
            to = cached.to,
            occurrences = cached.occurrences,
            loading = false,
            cachedAt = cached.loadedAt,
            message = CalendarMessage.OFFLINE,
        )

    internal companion object {
        /** Days loaded at once by the agenda. */
        const val AGENDA_CHUNK_DAYS = 30L
    }
}
