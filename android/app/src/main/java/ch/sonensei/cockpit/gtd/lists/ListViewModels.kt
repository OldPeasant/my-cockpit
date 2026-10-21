package ch.sonensei.cockpit.gtd.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.CompletionSnackbar
import ch.sonensei.cockpit.gtd.next.TaskCompleter
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** What a list action other than completing did: [outcome] for the task titled [title]. */
data class ActionSnackbar(
    val kind: Kind,
    val title: String,
    val outcome: ActionOutcome,
) {
    enum class Kind { ACTIVATED, FOLLOW_UP, COMPLETED, NEXT_STEP }
}

data class TaskListUiState(
    /** The open tasks in the order the screen shows them. */
    val tasks: List<GtdTask> = emptyList(),
    val loading: Boolean = true,
    /** Why the last load did not work; with no [tasks] loaded yet the screen offers a retry. */
    val error: LoadError? = null,
    /** Nothing was ever loaded (only then [error] replaces the list). */
    val loaded: Boolean = false,
    val contexts: List<GtdContext> = emptyList(),
    /** Tasks whose action is running: their buttons are disabled. */
    val busy: Set<UUID> = emptySet(),
    val completion: CompletionSnackbar? = null,
    val action: ActionSnackbar? = null,
    val today: LocalDate = LocalDate.MIN,
) {
    /** Changes need the server: while the last load failed, the list is shown read-only. */
    val readOnly: Boolean get() = error != null
}

/**
 * A live (uncached) task list with completion and undo, and the one action of its screen. After each change the list
 * reloads, so it is current also when the change stream is not connected.
 */
abstract class LiveTaskListViewModel(
    list: TaskList,
    repository: TaskListsRepository,
    contexts: ContextRepository,
    completion: TaskCompletionRepository,
    clock: Clock,
    order: (List<GtdTask>) -> List<GtdTask> = { it },
) : ViewModel() {
    private val completer = TaskCompleter(viewModelScope, completion)
    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val busy = MutableStateFlow(emptySet<UUID>())
    private val action = MutableStateFlow<ActionSnackbar?>(null)

    /** Reloads once a completion or undo got its answer (the pending set shrinks). */
    private val answered =
        completer.state
            .map { it.pending }
            .distinctUntilChanged()
            .drop(1)
            .map { }

    val uiState: StateFlow<TaskListUiState> =
        combine(
            repository.observe(list, merge(reloads, answered)),
            contexts.observe(),
            completer.state,
            busy,
            action,
        ) { live, contextList, done, running, snackbar ->
            TaskListUiState(
                tasks = order(live.data.orEmpty().filterNot(done::hides)),
                loading = live.loading,
                error = live.error,
                loaded = live.data != null,
                contexts = contextList.data,
                busy = running + done.pending,
                completion = done.snackbar,
                action = snackbar,
                today = LocalDate.now(clock),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TaskListUiState(today = LocalDate.now(clock)))

    fun retry() {
        reloads.tryEmit(Unit)
    }

    fun complete(task: GtdTask) {
        if (!uiState.value.readOnly) completer.complete(task)
    }

    fun undo(offer: CompletionSnackbar.Undo) = completer.undo(offer)

    fun dismissCompletion(shown: CompletionSnackbar) = completer.dismiss(shown)

    fun dismissAction(shown: ActionSnackbar) = action.update { if (it == shown) null else it }

    /** Runs the screen's action [run] on [task] once at a time, then reloads and reports how it went. */
    protected fun act(
        task: GtdTask,
        kind: ActionSnackbar.Kind,
        run: suspend () -> ActionOutcome,
    ) {
        if (uiState.value.readOnly || task.id in busy.value) return
        busy.update { it + task.id }
        viewModelScope.launch {
            val outcome = run()
            busy.update { it - task.id }
            action.value = ActionSnackbar(kind, task.title, outcome)
            reloads.tryEmit(Unit)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Waiting For (SPEC.md §5.3): longest waiting first, with "follow up". */
@HiltViewModel
class WaitingViewModel internal constructor(
    private val repository: TaskListsRepository,
    contexts: ContextRepository,
    completion: TaskCompletionRepository,
    clock: Clock,
) : LiveTaskListViewModel(TaskList.WAITING, repository, contexts, completion, clock, ::byWaitingSince) {
    @Inject
    constructor(
        repository: TaskListsRepository,
        contexts: ContextRepository,
        completion: TaskCompletionRepository,
    ) : this(repository, contexts, completion, Clock.systemDefaultZone())

    /** Creates a next action titled [title] to chase what [task] waits for. */
    fun followUp(
        task: GtdTask,
        title: String,
    ) = act(task, ActionSnackbar.Kind.FOLLOW_UP) { repository.followUp(task, title) }
}

/** Someday/Maybe: ideas parked for later; "Activate" makes one a next action. */
@HiltViewModel
class SomedayViewModel internal constructor(
    private val repository: TaskListsRepository,
    contexts: ContextRepository,
    completion: TaskCompletionRepository,
    clock: Clock,
) : LiveTaskListViewModel(TaskList.SOMEDAY, repository, contexts, completion, clock) {
    @Inject
    constructor(
        repository: TaskListsRepository,
        contexts: ContextRepository,
        completion: TaskCompletionRepository,
    ) : this(repository, contexts, completion, Clock.systemDefaultZone())

    fun activate(task: GtdTask) = act(task, ActionSnackbar.Kind.ACTIVATED) { repository.activate(task) }
}

/** Waiting longer than this many days is shown as due for a follow-up (like the web). */
const val LONG_WAIT_DAYS = 7

/** Waiting For order: longest waiting first, by `waitingSince`; tasks without a date last, ties in list order. */
internal fun byWaitingSince(tasks: List<GtdTask>): List<GtdTask> =
    tasks.sortedWith(compareBy<GtdTask, LocalDate?>(nullsLast()) { it.waitingSince }.thenBy { it.position })
