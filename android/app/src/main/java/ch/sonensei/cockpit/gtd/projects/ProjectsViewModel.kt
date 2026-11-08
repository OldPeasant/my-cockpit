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
    /** Completing or dropping ([status]) the open project while [openTasks] tasks are still open; [viaForm]: the form's save. */
    data class Finish(
        val status: ProjectStatus,
        val openTasks: Int,
        val viaForm: Boolean,
    ) : ProjectConfirm

    /** Moving the open project to the Trash; its [openTasks] open tasks keep the project. */
    data class Delete(
        val openTasks: Int,
    ) : ProjectConfirm
}

/** How a change on the project titled [title] went. */
data class ProjectMessage(
    val kind: Kind,
    val title: String,
    val outcome: ActionOutcome,
) {
    enum class Kind { CREATED, SAVED, COMPLETED, DROPPED, REACTIVATED, DELETED }
}

data class ProjectDetailState(
    val project: GtdProject,
    val area: Area? = null,
    /** The project's open tasks in its order. */
    val tasks: List<GtdTask> = emptyList(),
    val loading: Boolean = true,
    val error: LoadError? = null,
    val loaded: Boolean = false,
    val prompt: NextStepPrompt? = null,
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
) : ViewModel() {
    @Inject
    constructor(
        projects: ProjectRepository,
        contexts: ContextRepository,
        lists: TaskListsRepository,
        work: ProjectWorkRepository,
        images: NoteImages,
    ) : this(projects, contexts, lists, work, Clock.systemDefaultZone(), images.loader)

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
    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The areas loaded last: kept while a later load fails, and while they load again after the screen came back. */
    @Volatile private var knownAreas: List<Area>? = null

    private val projectTasks: Flow<Live<List<GtdTask>>?> =
        selected
            .flatMapLatest { id -> if (id == null) flowOf(null) else lists.observeProject(id, reloads) }
            .onEach { live -> if (live?.error == null) live?.data?.let(::forgetCompleted) }

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
        ) { l, f, c, m -> l.copy(form = f, confirm = c, projectMessage = m) }

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
                            tasks = tasks?.data.orEmpty().filterNot { task -> task.id in l.completed },
                            loading = tasks?.loading ?: true,
                            error = tasks?.error,
                            loaded = tasks?.data != null,
                            prompt = l.prompt?.takeIf { p -> p.projectId == it.id },
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

    fun open(project: GtdProject) {
        prompt.value = null
        selected.value = project.id
    }

    fun close() {
        prompt.value = null
        confirm.value = null
        form.update { if (it?.isNew == false) null else it }
        selected.value = null
    }

    /** Opens the form for a new project. */
    fun newProject() {
        if (uiState.value.offline) return
        form.value = ProjectFormState()
    }

    /** Opens the form to edit the open project. */
    fun editProject() {
        val project = uiState.value.detail?.project ?: return
        if (uiState.value.readOnly) return
        form.value = ProjectFormState.edit(project)
    }

    fun editForm(change: (ProjectDraft) -> ProjectDraft) =
        form.update { f ->
            f?.copy(draft = change(f.draft))?.let { if (it.draft.title.isNotBlank()) it.copy(titleMissing = false) else it }
        }

    fun togglePreview() = form.update { it?.copy(preview = !it.preview) }

    fun cancelForm() {
        form.value = null
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
        val open =
            uiState.value.detail
                ?.takeIf { it.project.id == id }
                ?.tasks
                ?.size ?: 0
        if (!confirmed && f.draft.status != f.initial.status && finishes(f.draft.status) && open > 0) {
            confirm.value = ProjectConfirm.Finish(f.draft.status, open, viaForm = true)
            return
        }
        val patch = projectPatch(f.initial, f.draft)
        if (patch.isEmpty()) {
            form.value = null
            return
        }
        form.value = f.copy(saving = true)
        viewModelScope.launch {
            val outcome = work.update(id, f.version, patch)
            when (outcome) {
                ActionOutcome.DONE -> form.value = null
                // Changed (or trashed) elsewhere: the projects were reloaded; show the current one with the user's edits.
                ActionOutcome.CONFLICT -> form.value = currentProject(id)?.let { f.rebase(it).copy(conflict = true) }
                else -> form.update { it?.copy(saving = false) }
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
        if (!confirmed && finishes(status) && detail.tasks.isNotEmpty()) {
            confirm.value = ProjectConfirm.Finish(status, detail.tasks.size, viaForm = false)
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
        confirm.value = ProjectConfirm.Delete(detail.tasks.size)
    }

    /** The user confirmed the change [ProjectsUiState.confirm] asked about. */
    fun confirm() {
        val asked = confirm.value ?: return
        confirm.value = null
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
        viewModelScope.launch {
            val result = work.create(f.draft)
            if (result.outcome == ActionOutcome.DONE && result.id != null) {
                form.value = null
                prompt.value = null
                selected.value = result.id
            } else {
                form.update { it?.copy(saving = false) }
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
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
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
