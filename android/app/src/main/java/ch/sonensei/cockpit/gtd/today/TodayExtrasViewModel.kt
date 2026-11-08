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

    /** Loads both again; a failed load keeps what was shown (the events and habits are an extra on Today). */
    fun load() {
        viewModelScope.launch {
            val zone = clock.zone
            val today = LocalDate.now(clock)
            val calendars = (calendar.calendars() as? CalendarResult.Ok)?.value
            val occurrences = calendar.occurrences(today, today.plusDays(1), zone)
            if (calendars != null && occurrences is CalendarResult.Ok) {
                val visible = calendars.filter { it.visible }.map { it.id }.toSet()
                val events =
                    occurrences.value
                        .filter { it.calendarId in visible }
                        .sortedWith(compareBy<Occurrence>({ !it.allDay }, { it.start }, { it.title }))
                state.update { it.copy(events = events, calendars = calendars, zone = zone) }
            }
        }
        viewModelScope.launch {
            val loaded = habits.list()
            if (loaded is HabitsResult.Ok) {
                state.update { it.copy(habits = loaded.value.filter { habit -> habit.dueToday && !habit.archived }) }
            }
        }
    }

    /** One tap on [habit]'s check button: one more check up to its target, then back to none. */
    fun check(habit: Habit) {
        if (habit.id in state.value.checking) return
        state.update { it.copy(checking = it.checking + habit.id, checkFailed = false) }
        viewModelScope.launch {
            val result = habits.check(habit)
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
