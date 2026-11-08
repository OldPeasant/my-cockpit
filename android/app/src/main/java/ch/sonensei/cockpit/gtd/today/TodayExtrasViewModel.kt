package ch.sonensei.cockpit.gtd.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarRepository
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.habits.Habit
import ch.sonensei.cockpit.habits.HabitsRepository
import ch.sonensei.cockpit.habits.HabitsResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** What the Today view shows besides tasks: today's events and the habits due today (as on the web). */
data class TodayExtrasUiState(
    /** Today's occurrences of the visible calendars: all-day ones first, then by start. */
    val events: List<Occurrence> = emptyList(),
    val calendars: List<CalendarInfo> = emptyList(),
    /** The active habits due today, by name; done ones stay so a tap can undo them. */
    val habits: List<Habit> = emptyList(),
    /** Habits whose check is being sent: their button is disabled meanwhile. */
    val checking: Set<UUID> = emptySet(),
    /** The last check was not saved (offline or refused). */
    val checkFailed: Boolean = false,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** Loads today's events and due habits and checks habits off (same semantics as the Habits screen: [Habit.nextCount]). */
@HiltViewModel
class TodayExtrasViewModel internal constructor(
    private val calendar: CalendarRepository,
    private val habits: HabitsRepository,
    private val clock: Clock,
) : ViewModel() {
    @Inject
    constructor(calendar: CalendarRepository, habits: HabitsRepository) : this(calendar, habits, Clock.systemDefaultZone())

    private val state = MutableStateFlow(TodayExtrasUiState(zone = clock.zone))
    val uiState: StateFlow<TodayExtrasUiState> = state.asStateFlow()

    /** Numbers the event loads, so the answer of a load that a newer one replaced is dropped. */
    private var eventsSeq = 0

    /** Numbers the habit loads, so the answer of a load that a newer one replaced is dropped. */
    private var habitsSeq = 0

    /** Counts the starts and ends of habit checks, so a habit list requested before one of them is known to be stale. */
    private var changeSeq = 0

    /** For each habit checked here: [changeSeq] at the last start or end of its check. */
    private val lastChange = mutableMapOf<UUID, Int>()

    /**
     * Loads both again; a failed load keeps what was shown (the events and habits are an extra on Today). Only the
     * newest load's answers are shown, and a habit whose check started or ended after the list was requested keeps
     * what is on screen (the list may have been read before the check and would undo it).
     */
    fun load() {
        val eventsLoad = ++eventsSeq
        viewModelScope.launch {
            val zone = clock.zone
            val today = LocalDate.now(clock)
            val calendars = (calendar.calendars() as? CalendarResult.Ok)?.value
            val occurrences = calendar.occurrences(today, today.plusDays(1), zone)
            if (eventsLoad == eventsSeq && calendars != null && occurrences is CalendarResult.Ok) {
                val visible = calendars.filter { it.visible }.map { it.id }.toSet()
                val events =
                    occurrences.value
                        .filter { it.calendarId in visible && it.isOn(today) }
                        .sortedWith(compareBy<Occurrence>({ !it.allDay }, { it.start }, { it.title }))
                state.update { it.copy(events = events, calendars = calendars, zone = zone) }
            }
        }
        val habitsLoad = ++habitsSeq
        val changes = changeSeq
        viewModelScope.launch {
            val loaded = habits.list()
            if (habitsLoad == habitsSeq && loaded is HabitsResult.Ok) {
                state.update { current ->
                    val due =
                        loaded.value
                            .filter { habit -> habit.dueToday && !habit.archived }
                            .map { habit ->
                                val checkedSince = (lastChange[habit.id] ?: 0) > changes
                                current.habits.firstOrNull { it.id == habit.id }?.takeIf { checkedSince } ?: habit
                            }
                    current.copy(habits = due, checkFailed = current.checkFailed && changes != changeSeq)
                }
            }
        }
    }

    /** One tap on [habit]'s check button: one more check up to its target, then back to none. */
    fun check(habit: Habit) {
        if (habit.id in state.value.checking) return
        state.update { it.copy(checking = it.checking + habit.id, checkFailed = false) }
        lastChange[habit.id] = ++changeSeq
        viewModelScope.launch {
            val result = habits.check(habit)
            lastChange[habit.id] = ++changeSeq
            state.update { current ->
                val checking = current.checking - habit.id
                if (result is HabitsResult.Ok) {
                    current.copy(
                        habits = current.habits.map { if (it.id == habit.id) result.value else it },
                        checking = checking,
                    )
                } else {
                    current.copy(checking = checking, checkFailed = true)
                }
            }
        }
    }
}

/**
 * Whether this occurrence is on [day]: a timed one always (the query asked for that day), an all-day one when its days
 * ([Occurrence.startDate] up to the exclusive [Occurrence.endDate]) contain [day]. The server picks all-day occurrences
 * by the synced time zone, so when the device zone differs it can also return a neighbouring day's (as web `isOnDate`).
 */
internal fun Occurrence.isOn(day: LocalDate): Boolean {
    val first = startDate
    if (!allDay || first == null) return true
    val end = endDate?.takeIf { it > first } ?: first.plusDays(1)
    return first <= day && day < end
}
