package ch.sonensei.cockpit.gtd.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.lists.ActionOutcome
import ch.sonensei.cockpit.gtd.lists.ActionSnackbar
import ch.sonensei.cockpit.gtd.lists.Live
import ch.sonensei.cockpit.gtd.lists.LoadError
import ch.sonensei.cockpit.gtd.lists.TaskListsRepository
import ch.sonensei.cockpit.notes.NoteImages
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * The projects of one area; [area] is `null` for the projects without a (known) area, and for those whose area is not
 * known because the areas could not be loaded ([areaUnloaded]).
 */
data class ProjectGroup(
    val area: Area?,
    val projects: List<GtdProject>,
    /** How many of them are stalled. */
    val stalled: Int,
    /** The projects have an area, but the areas could not be loaded: they are not shown as "No area". */
    val areaUnloaded: Boolean = false,
)

/**
 * The "Choose next step" prompt of the project [projectId]; [done] is the title of the next step just completed, if that
 * opened it.
 */
data class NextStepPrompt(
    val projectId: UUID,
    val done: String? = null,
    /** The task an "Add" created before setting it as next step failed: retrying the same title only sets it. */
    val created: CreatedStep? = null,
)

/** A task created as new next step, titled [title]. */
data class CreatedStep(
    val title: String,
    val taskId: UUID,
)

/** A change on a project that is asked about first. */
sealed interface ProjectConfirm {
    /** The project asked about: the confirmation only applies while it is the open project. */
    val projectId: UUID

    /**
     * Completing or dropping ([status]) the open project while [openTasks] tasks are still open (`null`: its tasks have
     * not loaded yet, so it is not known whether any remain); [viaForm]: the form's save.
     */
    data class Finish(
        override val projectId: UUID,
        val status: ProjectStatus,
        val openTasks: Int?,
        val viaForm: Boolean,
    ) : ProjectConfirm

    /** Moving the open project to the Trash; its [openTasks] open tasks keep the project. */
    data class Delete(
        override val projectId: UUID,
        val openTasks: Int,
    ) : ProjectConfirm
}

/**
 * How a change on the project titled [title] went; for [Kind.TASK_ADDED] and an announced [Kind.MOVED] [title] is the
 * task's, and a move says where it went: [position] (from 1) of [count].
 */
data class ProjectMessage(
    val kind: Kind,
    val title: String,
    val outcome: ActionOutcome,
    val position: Int = 0,
    val count: Int = 0,
) {
    enum class Kind { CREATED, SAVED, COMPLETED, DROPPED, REACTIVATED, DELETED, TASK_ADDED, MOVED }
}

/**
 * A task the detail's "Add task" created in [projectId] from the text [title]: the field clears when it still holds that
 * text. [seq] tells apart two tasks added with the same title.
 */
data class AddedTask(
    val projectId: UUID,
    val title: String,
    val seq: Long,
)

data class ProjectDetailState(
    val project: GtdProject,
    val area: Area? = null,
    /** The project's open tasks in its order. */
    val tasks: List<GtdTask> = emptyList(),
    val loading: Boolean = true,
    val error: LoadError? = null,
    val loaded: Boolean = false,
    val prompt: NextStepPrompt? = null,
    /** The task "Add task" created last in this project, so the field can clear. */
    val added: AddedTask? = null,
) {
    /** The open task that is the project's next step, if any. */
    val nextStep: GtdTask? get() = tasks.firstOrNull { it.id == project.nextTaskId }
}

data class ProjectsUiState(
    val groups: List<ProjectGroup> = emptyList(),
    val showFinished: Boolean = false,
    /** How many completed or dropped projects [showFinished] would add. */
    val finished: Int = 0,
    val refreshedAt: Instant? = null,
    /** The server is unreachable: the cached projects are shown read-only. */
    val offline: Boolean = false,
    /** The open project, `null` on the list. */
    val detail: ProjectDetailState? = null,
    val contexts: List<GtdContext> = emptyList(),
    /** Tasks (or the prompt, keyed by the project id) whose change is running. */
    val busy: Set<UUID> = emptySet(),
    val message: ActionSnackbar? = null,
    val today: LocalDate = LocalDate.MIN,
    /** No areas are known yet and they are still loading: the projects are shown without their areas meanwhile. */
    val areasLoading: Boolean = false,
    /** Why the areas could not be loaded, while none are known: the projects are not grouped by area. */
    val areasError: LoadError? = null,
    /** The known areas in their order, to pick from in the form. */
    val areas: List<Area> = emptyList(),
    /** The open project form (new project or edit), `null` when closed. */
    val form: ProjectFormState? = null,
    val confirm: ProjectConfirm? = null,
    val projectMessage: ProjectMessage? = null,
) {
    val loading: Boolean get() = groups.isEmpty() && ((refreshedAt == null && !offline) || areasLoading)

    /** Changes need the server, and current data: not while offline or the detail's last load failed. */
    val readOnly: Boolean get() = offline || detail?.error != null
}

/**
 * Projects (SPEC.md §5.3): the cached projects grouped by area with the stalled ones highlighted, and the detail of
 * one project with its open tasks, its next step, completing tasks and the "Choose next step" prompt. Projects are
 * created, edited (with `If-Match`: a 412 reloads the project and keeps the user's edits to save again), completed,
 * dropped and deleted here; finishing a project with open tasks, and deleting one, are confirmed first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectsViewModel internal constructor(
    private val projects: ProjectRepository,
    contexts: ContextRepository,
    private val lists: TaskListsRepository,
    private val work: ProjectWorkRepository,
    private val clock: Clock,
    /** Loads the drive images of the descriptions; `null` (tests) shows placeholders. */
    val imageLoader: ImageLoader? = null,
    /** Projects other screens asked to open. */
    private val openRequests: ProjectOpenRequests = ProjectOpenRequests(),
) : ViewModel() {
    @Inject
    constructor(
        projects: ProjectRepository,
        contexts: ContextRepository,
        lists: TaskListsRepository,
        work: ProjectWorkRepository,
        images: NoteImages,
        openRequests: ProjectOpenRequests,
    ) : this(projects, contexts, lists, work, Clock.systemDefaultZone(), images.loader, openRequests)

    private val selected = MutableStateFlow<UUID?>(null)
    private val showFinished = MutableStateFlow(false)
    private val prompt = MutableStateFlow<NextStepPrompt?>(null)
    private val busy = MutableStateFlow(emptySet<UUID>())

    /** Tasks completed here: hidden until the reloaded project tasks no longer hold them. */
    private val completed = MutableStateFlow(emptySet<UUID>())
    private val message = MutableStateFlow<ActionSnackbar?>(null)
    private val form = MutableStateFlow<ProjectFormState?>(null)
    private val confirm = MutableStateFlow<ProjectConfirm?>(null)
    private val projectMessage = MutableStateFlow<ProjectMessage?>(null)
    private val added = MutableStateFlow<AddedTask?>(null)
    private var addedSeq = 0L

    /** The order the user gave the project's tasks, shown until the server has it (or refused it). */
    private val pendingOrder = MutableStateFlow<PendingOrder?>(null)

    /** Sends the order saves one after another, in the order they were made, so the server applies the last one last. */
    private val orderSaves = Mutex()

    /** Counts the forms opened and closed: a save only applies its result to the form it was started from. */
    private var formSession = 0L
    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The areas loaded last: kept while a later load fails, and while they load again after the screen came back. */
    @Volatile private var knownAreas: List<Area>? = null

    private val projectTasks: Flow<Live<List<GtdTask>>?> =
        selected
            .flatMapLatest { id -> if (id == null) flowOf(null) else lists.observeProject(id, reloads) }
            .onEach { live ->
                val loaded = live?.data?.takeIf { live.error == null } ?: return@onEach
                forgetCompleted(loaded)
                // A load after the order was saved holds it: the server's order is shown from now on.
                pendingOrder.update { if (it?.saved == true) null else it }
            }

    private val view =
        combine(projects.observe(), work.observeAreas(reloads), contexts.observe(), showFinished, selected) { p, a, c, finished, id ->
            val areas = a.data?.also { knownAreas = it } ?: knownAreas
            ProjectsView(
                projects = p.data,
                offline = p.offline,
                refreshedAt = p.refreshedAt,
                areas = areas,
                areasLoading = areas == null && a.loading,
                areasError = if (areas == null) a.error else null,
                contexts = c.data,
                showFinished = finished,
                selected = id,
            )
        }

    private val local =
        combine(
            combine(prompt, busy, completed, message) { p, b, c, m -> LocalState(p, b, c, m) },
            form,
            confirm,
            projectMessage,
            combine(added, pendingOrder, ::Pair),
        ) { l, f, c, m, (a, o) -> l.copy(form = f, confirm = c, projectMessage = m, added = a, order = o) }

    val uiState: StateFlow<ProjectsUiState> =
        combine(view, projectTasks, local) { v, tasks, l ->
            val visible = if (v.showFinished) v.projects else v.projects.filterNot(::finished)
            val project = v.selected?.let { id -> v.projects.firstOrNull { it.id == id } }
            ProjectsUiState(
                // While no areas are known the cached projects show at once, without their areas (no banner while loading).
                groups = groupByArea(visible, v.areas),
                showFinished = v.showFinished,
                finished = v.projects.count(::finished),
                refreshedAt = v.refreshedAt,
                offline = v.offline,
                detail =
                    project?.let {
                        ProjectDetailState(
                            project = it,
                            area = v.areas?.firstOrNull { area -> area.id == it.areaId },
                            tasks =
                                tasks
                                    ?.data
                                    .orEmpty()
                                    .filterNot { task -> task.id in l.completed }
                                    .inOrder(l.order?.takeIf { o -> o.projectId == it.id }?.taskIds),
                            loading = tasks?.loading ?: true,
                            error = tasks?.error,
                            loaded = tasks?.data != null,
                            prompt = l.prompt?.takeIf { p -> p.projectId == it.id },
                            added = l.added?.takeIf { a -> a.projectId == it.id },
                        )
                    },
                contexts = v.contexts,
                busy = l.busy,
                message = l.message,
                today = LocalDate.now(clock),
                areasLoading = v.areasLoading,
                areasError = v.areasError,
                areas = v.areas.orEmpty().sortedBy { it.position },
                form = l.form,
                confirm = l.confirm,
                projectMessage = l.projectMessage,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectsUiState(today = LocalDate.now(clock)))

    init {
        // A project another screen asked to open (the weekly review), also one asked for while this screen was kept.
        viewModelScope.launch {
            openRequests.requests.collect { id ->
                if (id != null && openRequests.take(id)) {
                    prompt.value = null
                    confirm.value = null
                    selected.value = id
                }
            }
        }
    }

    fun open(project: GtdProject) {
        prompt.value = null
        confirm.value = null
        selected.value = project.id
    }

    fun close() {
        prompt.value = null
        confirm.value = null
        if (form.value?.isNew == false) replaceForm(null)
        selected.value = null
    }

    /** Opens the form for a new project. */
    fun newProject() {
        if (uiState.value.offline) return
        replaceForm(ProjectFormState())
    }

    /** Opens the form to edit the open project. */
    fun editProject() {
        val project = uiState.value.detail?.project ?: return
        if (uiState.value.readOnly) return
        replaceForm(ProjectFormState.edit(project))
    }

    fun editForm(change: (ProjectDraft) -> ProjectDraft) =
        form.update { f ->
            f?.copy(draft = change(f.draft))?.let { if (it.draft.title.isNotBlank()) it.copy(titleMissing = false) else it }
        }

    fun togglePreview() = form.update { it?.copy(preview = !it.preview) }

    fun cancelForm() {
        replaceForm(null)
        confirm.update { if (it is ProjectConfirm.Finish && it.viaForm) null else it }
    }

    /**
     * Saves the form: creates the project and opens it, or sends the changed fields. Finishing a project that still has
     * open tasks is confirmed first, unless [confirmed].
     */
    fun saveForm(confirmed: Boolean = false) {
        val f = form.value ?: return
        if (f.saving || uiState.value.offline) return
        if (f.draft.title.isBlank()) {
            form.value = f.copy(titleMissing = true)
            return
        }
        val id = f.projectId ?: return create(f)
        // Only asked while this project is open (the confirmation shows on its detail); a project that left the cache is
        // saved at once, and the server's conflict closes the form and tells the user.
        val detail = uiState.value.detail?.takeIf { it.project.id == id }
        // `null` while the project's tasks are not loaded: whether any remain open is not known, so it is asked too.
        val open = detail?.tasks?.size?.takeIf { detail.loaded }
        val finishing = f.draft.status != f.initial.status && finishes(f.draft.status)
        if (!confirmed && detail != null && finishing && (open == null || open > 0)) {
            confirm.value = ProjectConfirm.Finish(id, f.draft.status, open, viaForm = true)
            return
        }
        val patch = projectPatch(f.initial, f.draft)
        if (patch.isEmpty()) {
            form.value = null
            return
        }
        form.value = f.copy(saving = true)
        val session = formSession
        viewModelScope.launch {
            val outcome = work.update(id, f.version, patch)
            // Changed (or trashed) elsewhere: the projects were reloaded. Read before the session check, so no suspension
            // lies between the check and applying the result.
            val current = if (outcome == ActionOutcome.CONFLICT) currentProject(id) else null
            // The form was cancelled (and maybe another one opened) meanwhile: only the message is shown.
            if (session == formSession) {
                when (outcome) {
                    ActionOutcome.DONE -> replaceForm(null)
                    // Show the current project with the user's edits.
                    ActionOutcome.CONFLICT -> replaceForm(current?.let { f.rebase(it).copy(conflict = true) })
                    else -> form.update { it?.copy(saving = false) }
                }
            }
            projectMessage.value = ProjectMessage(ProjectMessage.Kind.SAVED, f.draft.title.trim(), outcome)
        }
    }

    /** Completes, drops or reactivates the open project; finishing one with open tasks is confirmed first, unless [confirmed]. */
    fun setStatus(
        status: ProjectStatus,
        confirmed: Boolean = false,
    ) {
        val detail = uiState.value.detail ?: return
        val project = detail.project
        if (uiState.value.readOnly || project.status == status) return
        // While the tasks are not loaded yet it is not known whether any remain open: ask too.
        if (!confirmed && finishes(status) && (!detail.loaded || detail.tasks.isNotEmpty())) {
            confirm.value = ProjectConfirm.Finish(project.id, status, detail.tasks.size.takeIf { detail.loaded }, viaForm = false)
            return
        }
        val kind =
            when (status) {
                ProjectStatus.COMPLETED -> ProjectMessage.Kind.COMPLETED
                ProjectStatus.DROPPED -> ProjectMessage.Kind.DROPPED
                else -> ProjectMessage.Kind.REACTIVATED
            }
        run(project.id) {
            val outcome = work.update(project.id, project.version, JsonObject(mapOf("status" to JsonPrimitive(status.name))))
            projectMessage.value = ProjectMessage(kind, project.title, outcome)
        }
    }

    /** Asks before moving the open project to the Trash. */
    fun requestDelete() {
        val detail = uiState.value.detail ?: return
        if (uiState.value.readOnly) return
        confirm.value = ProjectConfirm.Delete(detail.project.id, detail.tasks.size)
    }

    /** The user confirmed the change [ProjectsUiState.confirm] asked about. */
    fun confirm() {
        val asked = confirm.value ?: return
        confirm.value = null
        // Asked about a project that is no longer the open one (it disappeared, or another one was opened): drop it.
        if (asked.projectId != selected.value || uiState.value.detail == null) return
        when (asked) {
            is ProjectConfirm.Finish -> if (asked.viaForm) saveForm(confirmed = true) else setStatus(asked.status, confirmed = true)
            is ProjectConfirm.Delete -> delete()
        }
    }

    fun cancelConfirm() {
        confirm.value = null
    }

    fun dismissProjectMessage(shown: ProjectMessage) = projectMessage.update { if (it == shown) null else it }

    private fun create(f: ProjectFormState) {
        form.value = f.copy(saving = true)
        val session = formSession
        viewModelScope.launch {
            val result = work.create(f.draft)
            // The form was cancelled (and maybe another one opened) meanwhile: only the message is shown.
            if (session == formSession) {
                if (result.outcome == ActionOutcome.DONE && result.id != null) {
                    replaceForm(null)
                    prompt.value = null
                    selected.value = result.id
                } else {
                    form.update { it?.copy(saving = false) }
                }
            }
            projectMessage.value = ProjectMessage(ProjectMessage.Kind.CREATED, f.draft.title.trim(), result.outcome)
        }
    }

    private fun delete() {
        val project = uiState.value.detail?.project ?: return
        run(project.id) {
            val outcome = work.delete(project)
            if (outcome == ActionOutcome.DONE && selected.value == project.id) close()
            projectMessage.value = ProjectMessage(ProjectMessage.Kind.DELETED, project.title, outcome)
        }
    }

    /** Opens, replaces or closes ([state] `null`) the form: a save still running for the previous one no longer applies. */
    private fun replaceForm(state: ProjectFormState?) {
        formSession++
        form.value = state
    }

    /** The cached project [id] as it is now (after a reload), `null` when it is gone. */
    private suspend fun currentProject(id: UUID): GtdProject? =
        projects
            .observe()
            .first()
            .data
            .firstOrNull { it.id == id }

    fun toggleFinished() = showFinished.update { !it }

    fun retry() {
        reloads.tryEmit(Unit)
    }

    /** Completes [task]; when it was the project's next step, asks for the new one (if that project is still open). */
    fun complete(task: GtdTask) {
        val projectId =
            uiState.value.detail
                ?.project
                ?.id
        run(task.id) {
            when (val result = work.complete(task)) {
                is ProjectCompletion.Completed -> {
                    completed.update { it + task.id }
                    message.value = ActionSnackbar(ActionSnackbar.Kind.COMPLETED, task.title, ActionOutcome.DONE)
                    if (result.needsNextStep && projectId != null && selected.value == projectId) {
                        prompt.value = NextStepPrompt(projectId, done = task.title)
                    }
                }
                is ProjectCompletion.NotDone -> message.value = ActionSnackbar(ActionSnackbar.Kind.COMPLETED, task.title, result.outcome)
            }
        }
    }

    /** Makes [task] the open project's next step (from a row or the prompt). */
    fun makeNextStep(task: GtdTask) {
        val project = uiState.value.detail?.project ?: return
        run(task.id) {
            val outcome = work.setNextStep(project, task.id)
            if (outcome == ActionOutcome.DONE) prompt.update { if (it?.projectId == project.id) null else it }
            message.value = ActionSnackbar(ActionSnackbar.Kind.NEXT_STEP, task.title, outcome)
        }
    }

    /**
     * The prompt's "Add": creates a task titled [title] and makes it the next step. When an earlier "Add" of the same
     * title created the task but could not set it, only the next step is set again.
     */
    fun addNextStep(title: String) {
        val project = uiState.value.detail?.project ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        run(project.id) {
            val created =
                prompt.value
                    ?.takeIf { it.projectId == project.id }
                    ?.created
                    ?.takeIf { it.title == trimmed }
                    ?.taskId
            val result = work.addNextStep(project, trimmed, created)
            prompt.update { p ->
                when {
                    p == null || p.projectId != project.id -> p
                    result.outcome == ActionOutcome.DONE -> null
                    result.taskId != null -> p.copy(created = CreatedStep(trimmed, result.taskId))
                    else -> p
                }
            }
            message.value = ActionSnackbar(ActionSnackbar.Kind.NEXT_STEP, trimmed, result.outcome)
        }
    }

    /**
     * "Add task": adds a next action titled [title] at the end of the open project; it becomes the next step when
     * [makeNext], or when the project has none (like on web). Once the task exists the field clears
     * ([ProjectDetailState.added]), also when making it the next step failed, so submitting again does not add it twice.
     */
    fun addTask(
        title: String,
        makeNext: Boolean,
    ) {
        val project = uiState.value.detail?.project ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        run(project.id) {
            val result = work.addTask(project, trimmed, makeNext)
            if (result.taskId != null) added.value = AddedTask(project.id, trimmed, ++addedSeq)
            projectMessage.value = ProjectMessage(ProjectMessage.Kind.TASK_ADDED, trimmed, result.outcome)
        }
    }

    /** Saves [taskIds] (the open project's open tasks, dragged into a new order) as its order. */
    fun reorder(taskIds: List<UUID>) = saveOrder(taskIds, moved = null)

    /** Moves [task] [by] places (-1: up, 1: down) in the open project's order (the accessibility actions), and says where to. */
    fun move(
        task: GtdTask,
        by: Int,
    ) {
        val ids =
            uiState.value.detail
                ?.tasks
                ?.map { it.id } ?: return
        val from = ids.indexOf(task.id)
        val to = from + by
        if (from < 0 || to !in ids.indices) return
        saveOrder(ids.toMutableList().apply { add(to, removeAt(from)) }, moved = task)
    }

    /**
     * Shows [taskIds] as the order at once and saves it; a refused order goes back to the server's. With [moved] the
     * message says where that task went; after a drag, which shows where it went, only a failure is told.
     */
    private fun saveOrder(
        taskIds: List<UUID>,
        moved: GtdTask?,
    ) {
        val detail = uiState.value.detail ?: return
        if (uiState.value.readOnly || taskIds == detail.tasks.map { it.id }) return
        val pending = PendingOrder(detail.project.id, taskIds)
        pendingOrder.value = pending
        viewModelScope.launch {
            // The mutex is fair: the saves run in launch order.
            val outcome = orderSaves.withLock { work.reorder(pending.projectId, taskIds) }
            pendingOrder.update { current ->
                when {
                    current == null || current !== pending -> current
                    outcome == ActionOutcome.DONE -> current.copy(saved = true)
                    else -> null
                }
            }
            if (moved != null || outcome != ActionOutcome.DONE) {
                val title = moved?.title ?: detail.project.title
                val position = moved?.let { taskIds.indexOf(it.id) + 1 } ?: 0
                projectMessage.value = ProjectMessage(ProjectMessage.Kind.MOVED, title, outcome, position, taskIds.size)
            }
            reloads.tryEmit(Unit)
        }
    }

    fun choose() {
        val id = selected.value ?: return
        prompt.value = NextStepPrompt(id)
    }

    /** Closes the prompt without a next step: the project stays stalled. */
    fun skip() {
        prompt.value = null
    }

    fun dismiss(shown: ActionSnackbar) = message.update { if (it == shown) null else it }

    /** A successful reload no longer holds these completed tasks: stop hiding them, so a task reopened elsewhere shows again. */
    private fun forgetCompleted(loaded: List<GtdTask>) {
        val ids = loaded.mapTo(HashSet()) { it.id }
        completed.update { done -> if (done.all { it in ids }) done else done.filterTo(HashSet()) { it in ids } }
    }

    /** Runs [block] for [key] once at a time and not while read-only, then reloads the project's tasks. */
    private fun run(
        key: UUID,
        block: suspend () -> Unit,
    ) {
        if (uiState.value.readOnly || key in busy.value) return
        busy.update { it + key }
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.update { it - key }
                reloads.tryEmit(Unit)
            }
        }
    }

    private data class ProjectsView(
        val projects: List<GtdProject>,
        val offline: Boolean,
        val refreshedAt: Instant?,
        /** `null` while no areas have loaded. */
        val areas: List<Area>?,
        val areasLoading: Boolean,
        val areasError: LoadError?,
        val contexts: List<GtdContext>,
        val showFinished: Boolean,
        val selected: UUID?,
    )

    private data class LocalState(
        val prompt: NextStepPrompt?,
        val busy: Set<UUID>,
        val completed: Set<UUID>,
        val message: ActionSnackbar?,
        val form: ProjectFormState? = null,
        val confirm: ProjectConfirm? = null,
        val projectMessage: ProjectMessage? = null,
        val added: AddedTask? = null,
        val order: PendingOrder? = null,
    )

    /** An order of [projectId]'s open tasks being saved; [saved]: the server has it, shown until the next load. */
    private data class PendingOrder(
        val projectId: UUID,
        val taskIds: List<UUID>,
        val saved: Boolean = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The tasks in [order] (task ids), tasks not in it last; unchanged without one. */
private fun List<GtdTask>.inOrder(order: List<UUID>?): List<GtdTask> {
    if (order == null) return this
    val rank = order.withIndex().associate { (index, id) -> id to index }
    return sortedBy { rank[it.id] ?: order.size }
}

/** Whether a project is finished: the list hides these unless asked to show them. */
internal fun finished(project: GtdProject): Boolean = finishes(project.status)

/**
 * The projects grouped by area (SPEC.md §5.3), areas in their order, then the projects without a known area. Empty
 * areas are left out. Within a group the stalled projects come first, then by status (active, on hold, someday,
 * finished) and title. Without [areas] (none could be loaded) the projects that have an area form a group of their own,
 * so they are not shown as having none.
 */
internal fun groupByArea(
    projects: List<GtdProject>,
    areas: List<Area>?,
): List<ProjectGroup> {
    if (areas == null) {
        val (inArea, without) = projects.partition { it.areaId != null }
        return listOfNotNull(
            inArea.takeIf { it.isNotEmpty() }?.let { groupOf(null, it).copy(areaUnloaded = true) },
            without.takeIf { it.isNotEmpty() }?.let { groupOf(null, it) },
        )
    }
    val known = areas.map { it.id }.toSet()
    val byArea = projects.groupBy { project -> project.areaId?.takeIf { it in known } }
    val grouped = areas.sortedBy { it.position }.mapNotNull { area -> byArea[area.id]?.let { groupOf(area, it) } }
    return grouped + listOfNotNull(byArea[null]?.let { groupOf(null, it) })
}

private val projectOrder =
    compareByDescending<GtdProject> { it.stalled }.thenBy { it.status.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

private fun groupOf(
    area: Area?,
    members: List<GtdProject>,
) = ProjectGroup(area, members.sortedWith(projectOrder), members.count { it.stalled })
