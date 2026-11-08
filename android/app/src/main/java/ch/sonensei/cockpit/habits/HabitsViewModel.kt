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

/** A problem shown to the user above the list. */
enum class HabitsMessage {
    LOAD_OFFLINE,
    LOAD_FAILED,
    CHECK_OFFLINE,
    CHECK_FAILED,
    ARCHIVE_OFFLINE,
    ARCHIVE_FAILED,

    /** A change was refused because the habit changed elsewhere: the list was loaded again. */
    CONFLICT,
}

/** Why the create/edit form could not save. */
enum class HabitEditorError { OFFLINE, FAILED, CONFLICT }

/** The open create/edit form. */
data class HabitEditor(
    /** The habit being edited; `null` for a new one. */
    val habitId: UUID?,
    val draft: HabitDraft,
    val saving: Boolean = false,
    /** A save was tried: from then on the form shows what keeps it from saving. */
    val tried: Boolean = false,
    val error: HabitEditorError? = null,
)

data class HabitsUiState(
    /** Whether the list shows the archived habits instead of the active ones. */
    val archived: Boolean = false,
    val habits: List<Habit> = emptyList(),
    val loading: Boolean = true,
    /** The list was loaded at least once (an empty list then means: no habits). */
    val loaded: Boolean = false,
    /** Habits with a change on its way to the server: they cannot be tapped again meanwhile. */
    val checking: Set<UUID> = emptySet(),
    val message: HabitsMessage? = null,
    val editor: HabitEditor? = null,
) {
    /** Habits due today, then the others (each in the server's order, by name). */
    val dueToday: List<Habit> get() = habits.filter { it.dueToday }
    val notDueToday: List<Habit> get() = habits.filterNot { it.dueToday }
}

/**
 * The habits (SPEC.md §8.5, §9.2 item 8): the active ones with a tap that counts one more check for today, or the
 * archived ones; creating, editing, archiving and unarchiving them.
 */
@HiltViewModel
class HabitsViewModel
    @Inject
    constructor(
        private val habits: HabitsRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(HabitsUiState())
        val uiState: StateFlow<HabitsUiState> = state.asStateFlow()

        /** Counts the loads, so an answer for a view that is no longer shown is dropped. */
        private var loadSeq = 0

        init {
            startLoad()
        }

        /** Loads the list again (after a failed load, or to pick up changes from elsewhere). */
        fun reload() {
            if (state.value.loading) return
            state.update { it.copy(loading = true, message = null) }
            startLoad()
        }

        /** Shows the archived habits ([archived]) or the active ones. */
        fun showArchived(archived: Boolean) {
            if (state.value.archived == archived) return
            state.update {
                it.copy(archived = archived, habits = emptyList(), loaded = false, loading = true, message = null, editor = null)
            }
            startLoad()
        }

        private fun startLoad() {
            val seq = ++loadSeq
            val archived = state.value.archived
            viewModelScope.launch {
                val result = habits.list(archived)
                if (seq != loadSeq) return@launch
                state.update {
                    when (result) {
                        is HabitsResult.Ok -> it.copy(habits = result.value, loading = false, loaded = true)
                        HabitsResult.Offline -> it.copy(loading = false, message = it.message ?: HabitsMessage.LOAD_OFFLINE)
                        is HabitsResult.Failed -> it.copy(loading = false, message = it.message ?: HabitsMessage.LOAD_FAILED)
                    }
                }
            }
        }

        /** Loads the list again after a conflict, keeping what is on screen meanwhile. */
        private fun reloadAfterConflict() {
            state.update { it.copy(loading = true) }
            startLoad()
        }

        /** One more check for today on habit [id] until its target is reached, then back to none. */
        fun toggle(id: UUID) {
            val habit = state.value.habits.firstOrNull { it.id == id } ?: return
            if (habit.archived || id in state.value.checking) return
            change(id, HabitsMessage.CHECK_OFFLINE, HabitsMessage.CHECK_FAILED, removes = false) { habits.check(habit) }
        }

        /** Archives habit [id], or (in the archived view) moves it back to the active habits. */
        fun toggleArchived(id: UUID) {
            val habit = state.value.habits.firstOrNull { it.id == id } ?: return
            if (id in state.value.checking) return
            change(id, HabitsMessage.ARCHIVE_OFFLINE, HabitsMessage.ARCHIVE_FAILED, removes = true) {
                habits.setArchived(habit, !habit.archived)
            }
        }

        /** Sends a change of habit [id]; the answer replaces it, or (when it [removes] it from this view) drops it. */
        private fun change(
            id: UUID,
            offline: HabitsMessage,
            failed: HabitsMessage,
            removes: Boolean,
            send: suspend () -> HabitsResult<Habit>,
        ) {
            state.update { it.copy(checking = it.checking + id, message = null) }
            viewModelScope.launch {
                val result = send()
                val conflict = result is HabitsResult.Failed && result.status in HabitsRepository.CONFLICTS
                state.update { s ->
                    val checking = s.checking - id
                    when {
                        result is HabitsResult.Ok ->
                            s.copy(
                                habits =
                                    if (removes) {
                                        s.habits.filterNot { it.id == id }
                                    } else {
                                        s.habits.map {
                                            if (it.id ==
                                                id
                                            ) {
                                                result.value
                                            } else {
                                                it
                                            }
                                        }
                                    },
                                checking = checking,
                                editor = if (removes && s.editor?.habitId == id) null else s.editor,
                            )
                        conflict -> s.copy(checking = checking, message = HabitsMessage.CONFLICT)
                        result == HabitsResult.Offline -> s.copy(checking = checking, message = offline)
                        else -> s.copy(checking = checking, message = failed)
                    }
                }
                if (conflict) reloadAfterConflict()
            }
        }

        /** Opens the form for a new habit. */
        fun startCreate() {
            if (state.value.archived) return
            state.update { it.copy(editor = HabitEditor(habitId = null, draft = HabitDraft())) }
        }

        /** Opens the form for habit [id]. */
        fun startEdit(id: UUID) {
            val habit = state.value.habits.firstOrNull { it.id == id } ?: return
            state.update { it.copy(editor = HabitEditor(habitId = id, draft = HabitDraft.of(habit))) }
        }

        /** The form's values changed. */
        fun updateDraft(draft: HabitDraft) {
            state.update { s -> s.copy(editor = s.editor?.takeUnless { it.saving }?.copy(draft = draft, error = null) ?: s.editor) }
        }

        fun cancelEdit() {
            if (state.value.editor?.saving == true) return
            state.update { it.copy(editor = null) }
        }

        /** Saves the form: creates the habit, or changes the edited one (with its latest loaded version). */
        fun save() {
            val editor = state.value.editor ?: return
            if (editor.saving) return
            if (editor.draft.problem != null) {
                state.update { it.copy(editor = editor.copy(tried = true)) }
                return
            }
            val habit = editor.habitId?.let { id -> state.value.habits.firstOrNull { it.id == id } }
            if (editor.habitId != null && habit == null) {
                state.update { it.copy(editor = editor.copy(tried = true, error = HabitEditorError.CONFLICT)) }
                return
            }
            state.update { it.copy(editor = editor.copy(saving = true, tried = true, error = null)) }
            viewModelScope.launch {
                val result = if (habit == null) habits.create(editor.draft) else habits.update(habit, editor.draft)
                val conflict = habit != null && result is HabitsResult.Failed && result.status in HabitsRepository.CONFLICTS
                state.update { s ->
                    val current = s.editor ?: return@update s
                    when (result) {
                        is HabitsResult.Ok -> s.copy(habits = saved(s, result.value), editor = null)
                        HabitsResult.Offline -> s.copy(editor = current.copy(saving = false, error = HabitEditorError.OFFLINE))
                        is HabitsResult.Failed ->
                            s.copy(
                                editor =
                                    current.copy(
                                        saving = false,
                                        error = if (conflict) HabitEditorError.CONFLICT else HabitEditorError.FAILED,
                                    ),
                            )
                    }
                }
                if (conflict) reloadAfterConflict()
            }
        }

        /** The list with a saved habit: replaced in place, or (new and shown in this view) added in name order. */
        private fun saved(
            s: HabitsUiState,
            habit: Habit,
        ): List<Habit> =
            when {
                s.habits.any { it.id == habit.id } -> s.habits.map { if (it.id == habit.id) habit else it }
                habit.archived != s.archived -> s.habits
                else -> (s.habits + habit).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }

        fun dismissMessage() = state.update { it.copy(message = null) }
    }
