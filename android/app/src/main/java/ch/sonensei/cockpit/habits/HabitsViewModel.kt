package ch.sonensei.cockpit.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** A problem shown to the user. */
enum class HabitsMessage { LOAD_OFFLINE, LOAD_FAILED, CHECK_OFFLINE, CHECK_FAILED }

data class HabitsUiState(
    val habits: List<Habit> = emptyList(),
    val loading: Boolean = true,
    /** The list was loaded at least once (an empty list then means: no habits). */
    val loaded: Boolean = false,
    /** Habits whose check-off is on its way to the server: they cannot be tapped again meanwhile. */
    val checking: Set<UUID> = emptySet(),
    val message: HabitsMessage? = null,
) {
    /** Habits due today, then the others (each in the server's order, by name). */
    val dueToday: List<Habit> get() = habits.filter { it.dueToday }
    val notDueToday: List<Habit> get() = habits.filterNot { it.dueToday }
}

/** The active habits with a one-tap check-off for today (SPEC.md §8.5, §9.2 item 8). */
@HiltViewModel
class HabitsViewModel
    @Inject
    constructor(
        private val habits: HabitsRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(HabitsUiState())
        val uiState: StateFlow<HabitsUiState> = state.asStateFlow()

        init {
            viewModelScope.launch { load() }
        }

        /** Loads the list again (after a failed load, or to pick up changes from elsewhere). */
        fun reload() {
            if (state.value.loading) return
            state.update { it.copy(loading = true, message = null) }
            viewModelScope.launch { load() }
        }

        private suspend fun load() {
            val result = habits.list()
            state.update {
                when (result) {
                    is HabitsResult.Ok -> it.copy(habits = result.value, loading = false, loaded = true)
                    HabitsResult.Offline -> it.copy(loading = false, message = HabitsMessage.LOAD_OFFLINE)
                    is HabitsResult.Failed -> it.copy(loading = false, message = HabitsMessage.LOAD_FAILED)
                }
            }
        }

        /** Checks habit [id] off for today, or removes today's check when it is done already. */
        fun toggle(id: UUID) {
            val habit = state.value.habits.firstOrNull { it.id == id } ?: return
            if (id in state.value.checking) return
            state.update { it.copy(checking = it.checking + id, message = null) }
            viewModelScope.launch {
                val result = habits.check(habit, done = !habit.doneToday)
                state.update { s ->
                    val checking = s.checking - id
                    when (result) {
                        is HabitsResult.Ok ->
                            s.copy(habits = s.habits.map { if (it.id == id) result.value else it }, checking = checking)
                        HabitsResult.Offline -> s.copy(checking = checking, message = HabitsMessage.CHECK_OFFLINE)
                        is HabitsResult.Failed -> s.copy(checking = checking, message = HabitsMessage.CHECK_FAILED)
                    }
                }
            }
        }

        fun dismissMessage() = state.update { it.copy(message = null) }
    }
