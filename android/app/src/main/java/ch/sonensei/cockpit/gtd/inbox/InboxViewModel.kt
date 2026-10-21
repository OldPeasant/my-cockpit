package ch.sonensei.cockpit.gtd.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** The buttons of the processing mode, in the order of the GTD decision: act on it, defer it, or drop it. */
enum class InboxActionKind { DONE, NEXT, PROJECT, WAITING, SCHEDULE, TICKLER, SOMEDAY, TRASH }

/** The value an action asks for before it runs: a date, who it waits for, or the project. */
data class InboxPrompt(
    val kind: InboxActionKind,
    val date: LocalDate,
    val text: String = "",
    /** The chosen project; `null` creates a new one named [text]. */
    val projectId: UUID? = null,
) {
    fun isValid(today: LocalDate): Boolean =
        when (kind) {
            InboxActionKind.TICKLER -> date.isAfter(today)
            InboxActionKind.PROJECT -> projectId != null || text.isNotBlank()
            else -> true
        }

    fun action(): InboxAction =
        when (kind) {
            InboxActionKind.WAITING -> InboxAction.Waiting(text)
            InboxActionKind.SCHEDULE -> InboxAction.Schedule(date)
            InboxActionKind.TICKLER -> InboxAction.Tickler(date)
            InboxActionKind.PROJECT -> InboxAction.ToProject(projectId?.let(ProjectChoice::Existing) ?: ProjectChoice.New(text))
            else -> error("$kind needs no prompt")
        }
}

/** Why the last action did not go through; the item is back in the queue. */
enum class InboxMessage { OFFLINE, CONFLICT, FAILED }

data class InboxUiState(
    /** The items still to process, in Inbox order (without those whose action is running). */
    val tasks: List<GtdTask> = emptyList(),
    /** When the shown Inbox was loaded; `null` while nothing was ever loaded. */
    val refreshedAt: Instant? = null,
    /** The server is unreachable: the cached Inbox is shown read-only, every action is disabled. */
    val offline: Boolean = false,
    val contexts: List<GtdContext> = emptyList(),
    /** The active projects, which "To project" offers. */
    val projects: List<GtdProject> = emptyList(),
    val processing: Boolean = false,
    /** Items processed since the processing mode started. */
    val processed: Int = 0,
    /** The attributes shown for [current]: its own, with the inline changes over them. */
    val clarification: Clarification = Clarification(),
    val prompt: InboxPrompt? = null,
    val message: InboxMessage? = null,
    /** A reload asked for by the user (the offline banner's retry) is running. */
    val retrying: Boolean = false,
    val today: LocalDate = LocalDate.MIN,
) {
    /** The item being processed. */
    val current: GtdTask? get() = tasks.firstOrNull()

    val actionsEnabled: Boolean get() = !offline && current != null
}

/** The Inbox and its processing mode (SPEC.md §5.3): one item at a time, clarified inline, then moved out. */
@HiltViewModel
class InboxViewModel internal constructor(
    private val inbox: InboxRepository,
    private val tasks: TaskRepository,
    projects: ProjectRepository,
    contexts: ContextRepository,
    private val clock: Clock,
    /** Monotonic milliseconds, for the double-tap guard. */
    private val uptimeMs: () -> Long,
) : ViewModel() {
    @Inject
    constructor(
        inbox: InboxRepository,
        tasks: TaskRepository,
        projects: ProjectRepository,
        contexts: ContextRepository,
    ) : this(inbox, tasks, projects, contexts, Clock.systemDefaultZone(), { System.nanoTime() / 1_000_000 })

    /** When the last action started: taps right after it belong to the item that just left (see [choose]). */
    private var lastRunAt: Long? = null

    private data class Local(
        val processing: Boolean = false,
        val processed: Int = 0,
        /** Items whose action is running: they are no longer offered. */
        val busy: Set<UUID> = emptySet(),
        /** The inline changes, for the item they were made on. */
        val edit: Pair<UUID, Clarification>? = null,
        /** The open prompt, for the item it was opened on. */
        val prompt: Pair<UUID, InboxPrompt>? = null,
        val message: InboxMessage? = null,
        val retrying: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<InboxUiState> =
        combine(tasks.observe(TaskView.INBOX), projects.observe(), contexts.observe(), local) { inbox, projectList, contextList, l ->
            val queue = inbox.data.filter { it.id !in l.busy }
            val current = queue.firstOrNull()
            InboxUiState(
                tasks = queue,
                refreshedAt = inbox.refreshedAt,
                offline = inbox.offline,
                contexts = contextList.data,
                projects = projectList.data.filter { it.status == ProjectStatus.ACTIVE },
                processing = l.processing,
                processed = l.processed,
                clarification = current?.let { clarificationOf(it, l) } ?: Clarification(),
                prompt = l.prompt?.takeIf { it.first == current?.id }?.second,
                message = l.message,
                retrying = l.retrying,
                today = today(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InboxUiState(today = today()))

    fun startProcessing() = local.update { it.copy(processing = true, processed = 0, prompt = null, message = null) }

    fun stopProcessing() = local.update { it.copy(processing = false, prompt = null) }

    fun setEnergy(energy: Energy?) = edit { it.copy(energy = energy) }

    fun setCreativity(creativity: Creativity?) = edit { it.copy(creativity = creativity) }

    fun setEstimate(minutes: Int?) = edit { it.copy(timeEstimateMin = minutes) }

    fun toggleContext(id: UUID) = edit { it.copy(contextIds = if (id in it.contextIds) it.contextIds - id else it.contextIds + id) }

    /**
     * Reloads the Inbox from the server. A reload that works clears the offline state, so the actions are enabled
     * again without waiting for the next automatic sync.
     */
    fun retry() {
        if (local.value.retrying) return
        local.update { it.copy(retrying = true) }
        viewModelScope.launch {
            try {
                tasks.refresh(TaskView.INBOX)
            } finally {
                local.update { it.copy(retrying = false) }
            }
        }
    }

    /**
     * Runs [kind] on the current item, or opens its prompt first. [itemId] is the item the button was shown for: a
     * tap that arrives after that item left the queue (a double tap) is dropped instead of acting on the next item.
     * Taps within [TAP_GUARD_MS] of the last action are dropped too: by then the buttons may already show the next
     * item, which the user has not seen yet.
     */
    fun choose(
        itemId: UUID,
        kind: InboxActionKind,
    ) {
        val state = uiState.value
        val task = state.current?.takeIf { it.id == itemId } ?: return
        if (state.offline || justRan()) return
        val action =
            when (kind) {
                InboxActionKind.DONE -> InboxAction.Done
                InboxActionKind.NEXT -> InboxAction.Next
                InboxActionKind.SOMEDAY -> InboxAction.Someday
                InboxActionKind.TRASH -> InboxAction.Trash
                else -> null
            }
        if (action != null) {
            run(task, action)
            return
        }
        // The project named in the capture (`#project`) is offered first while it is active.
        val project = state.projects.firstOrNull { it.id == task.projectId } ?: state.projects.firstOrNull()
        val prompt = InboxPrompt(kind, date = state.today.plusDays(1), projectId = project?.id)
        local.update { it.copy(prompt = task.id to prompt, message = null) }
    }

    fun updatePrompt(change: (InboxPrompt) -> InboxPrompt) =
        local.update { l -> l.copy(prompt = l.prompt?.let { (id, p) -> id to change(p) }) }

    fun cancelPrompt() = local.update { it.copy(prompt = null) }

    /** Runs the open prompt's action on [itemId], if that still is the current item (see [choose]). */
    fun confirmPrompt(itemId: UUID) {
        val state = uiState.value
        val task = state.current?.takeIf { it.id == itemId } ?: return
        val prompt = state.prompt ?: return
        if (state.offline || justRan() || !prompt.isValid(state.today)) return
        run(task, prompt.action())
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    /** The item leaves the queue at once; if the server refuses, it comes back with a message. */
    private fun run(
        task: GtdTask,
        action: InboxAction,
    ) {
        lastRunAt = uptimeMs()
        val clarification = clarificationOf(task, local.value)
        local.update { it.copy(busy = it.busy + task.id, prompt = null, message = null) }
        viewModelScope.launch {
            val result = inbox.process(task, clarification, action)
            local.update {
                val message =
                    when (result) {
                        ProcessResult.Processed -> null
                        ProcessResult.Offline -> InboxMessage.OFFLINE
                        ProcessResult.Conflict -> InboxMessage.CONFLICT
                        is ProcessResult.Rejected -> InboxMessage.FAILED
                    }
                it.copy(
                    busy = it.busy - task.id,
                    processed = if (message == null) it.processed + 1 else it.processed,
                    edit = it.edit?.takeIf { (id, _) -> id != task.id || message != null },
                    message = message ?: it.message,
                )
            }
        }
    }

    private fun edit(change: (Clarification) -> Clarification) {
        val task = uiState.value.current ?: return
        local.update { it.copy(edit = task.id to change(clarificationOf(task, it))) }
    }

    private fun clarificationOf(
        task: GtdTask,
        l: Local,
    ): Clarification = l.edit?.takeIf { it.first == task.id }?.second ?: Clarification.of(task)

    private fun justRan(): Boolean = lastRunAt?.let { uptimeMs() - it < TAP_GUARD_MS } ?: false

    private fun today(): LocalDate = LocalDate.now(clock)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TAP_GUARD_MS = 500L
    }
}
