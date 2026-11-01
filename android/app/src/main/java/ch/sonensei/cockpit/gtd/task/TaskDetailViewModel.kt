package ch.sonensei.cockpit.gtd.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.notes.NoteImages
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

/** The fields the edit form has; Problem Details errors are shown at these. */
enum class TaskField(
    /** The field's name in the API. */
    val apiName: String,
) {
    TITLE("title"),
    NOTES("notesMd"),
    PROJECT("projectId"),
    CONTEXTS("contextIds"),
    DUE_DATE("dueDate"),
    DUE_TIME("dueTime"),
    ESTIMATE("timeEstimateMin"),
    ENERGY("energy"),
    CREATIVITY("creativity"),
    ;

    companion object {
        fun ofApi(name: String): TaskField? = entries.firstOrNull { it.apiName == name }
    }
}

/** Why a field of the form is invalid: found here before sending, or the server's message. */
sealed interface FieldProblem {
    data object Required : FieldProblem

    data object TooLong : FieldProblem

    data object NotANumber : FieldProblem

    data class Server(
        val message: String,
    ) : FieldProblem
}

/** The edit form's values. [estimate] is the text typed (minutes). */
data class TaskDraft(
    val title: String,
    val notes: String,
    val projectId: UUID?,
    val contextIds: Set<UUID>,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val estimate: String,
    val energy: Energy?,
    val creativity: Creativity?,
) {
    /** The problems found before sending: the server would refuse these. */
    fun problems(): Map<TaskField, FieldProblem> =
        buildMap {
            if (title.isBlank()) put(TaskField.TITLE, FieldProblem.Required)
            if (title.trim().length > MAX_TITLE) put(TaskField.TITLE, FieldProblem.TooLong)
            val minutes = estimate.trim()
            if (minutes.isNotEmpty() && minutes.toIntOrNull()?.takeIf { it in 0..MAX_ESTIMATE } == null) {
                put(TaskField.ESTIMATE, FieldProblem.NotANumber)
            }
        }

    /** The PATCH body with the fields that differ from [task]: only those change, `null` clears one. */
    fun patch(task: GtdTask): JsonObject {
        val before = of(task)
        val fields = mutableMapOf<String, JsonElement>()
        val title = title.trim()
        if (title != before.title) fields[TaskField.TITLE.apiName] = jsonOf(title)
        if (notes != before.notes) fields[TaskField.NOTES.apiName] = jsonOf(notes.ifBlank { null })
        if (projectId != before.projectId) fields[TaskField.PROJECT.apiName] = jsonOf(projectId?.toString())
        if (contextIds != before.contextIds) fields[TaskField.CONTEXTS.apiName] = jsonOf(contextIds.map { it.toString() })
        if (dueDate != before.dueDate) fields[TaskField.DUE_DATE.apiName] = jsonOf(dueDate?.toString())
        // A time needs a date (the server refuses one without), so clearing the date clears the time too.
        val time = dueTime.takeIf { dueDate != null }
        if (time != before.dueTime || dueDate != before.dueDate && time != null) {
            fields[TaskField.DUE_TIME.apiName] = jsonOf(time?.let { "%02d:%02d:00".format(it.hour, it.minute) })
        }
        val minutes = estimate.trim().toIntOrNull()
        if (minutes != task.timeEstimateMin) fields[TaskField.ESTIMATE.apiName] = jsonOf(minutes)
        if (energy != before.energy) fields[TaskField.ENERGY.apiName] = jsonOf(energy?.name)
        if (creativity != before.creativity) fields[TaskField.CREATIVITY.apiName] = jsonOf(creativity?.name)
        return JsonObject(fields)
    }

    companion object {
        const val MAX_TITLE = 500
        const val MAX_ESTIMATE = 10_000

        fun of(task: GtdTask) =
            TaskDraft(
                title = task.title,
                notes = task.notesMd.orEmpty(),
                projectId = task.projectId,
                contextIds = task.contextIds.toSet(),
                dueDate = task.dueDate,
                dueTime = task.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                estimate = task.timeEstimateMin?.toString().orEmpty(),
                energy = task.energy,
                creativity = task.creativity,
            )
    }
}

/** What the detail screen's snackbar says. */
sealed interface DetailMessage {
    /** The server is unreachable: nothing was changed. */
    data object Offline : DetailMessage

    /** The task was changed elsewhere: it was reloaded instead of overwritten. */
    data object Conflict : DetailMessage

    /** The task is gone (trashed or deleted elsewhere). */
    data object NotFound : DetailMessage

    data class Failed(
        val detail: String? = null,
    ) : DetailMessage

    data object Saved : DetailMessage

    data object Completed : DetailMessage

    data object Reopened : DetailMessage

    data class Moved(
        val target: MoveTarget,
    ) : DetailMessage

    /** The task went to the Trash; the snackbar offers to undo that. */
    data object Deleted : DetailMessage

    data object Restored : DetailMessage
}

data class TaskDetailUiState(
    val loading: Boolean = true,
    val detail: TaskDetail? = null,
    /** The server is unreachable: [detail] is the copy the app had, shown read-only. */
    val offline: Boolean = false,
    /** When the offline copy was loaded, if known. */
    val cachedAt: Instant? = null,
    /** The task could not be loaded and the app has no copy of it. */
    val loadFailed: Boolean = false,
    /** The task is gone (trashed or deleted elsewhere). */
    val notFound: Boolean = false,
    /** Active projects (and the task's own one), for the picker and to show the task's project. */
    val projects: List<GtdProject> = emptyList(),
    val contexts: List<GtdContext> = emptyList(),
    /** The edit form, while editing. */
    val draft: TaskDraft? = null,
    val problems: Map<TaskField, FieldProblem> = emptyMap(),
    /** A change is being sent: the controls wait for it. */
    val busy: Boolean = false,
    /** The task was moved to the Trash here: the screen offers to undo, then closes. */
    val deleted: Boolean = false,
    val message: DetailMessage? = null,
    val server: HttpUrl? = null,
) {
    /** Changes need the server and a loaded task. */
    val readOnly: Boolean get() = offline || detail == null || deleted || notFound

    val editing: Boolean get() = draft != null
}

/**
 * One task with everything about it, editable (SPEC.md §5.1): fields through the form (PATCH with `If-Match`; a 412
 * reloads the task instead of overwriting it), the checklist, completing, moving and deleting. Offline it shows the
 * copy the lists had, read-only.
 */
@HiltViewModel
class TaskDetailViewModel internal constructor(
    private val source: TaskDetailSource,
    projects: Flow<List<GtdProject>>,
    contexts: Flow<List<GtdContext>>,
    server: Flow<HttpUrl?>,
    /** Loads the drive images of the notes; `null` (tests) shows placeholders. */
    val imageLoader: ImageLoader? = null,
) : ViewModel() {
    @Inject
    constructor(
        source: TaskDetailRepository,
        projects: ProjectRepository,
        contexts: ContextRepository,
        serverUrls: ServerUrlRepository,
        images: NoteImages,
    ) : this(source, projects.observe().map { it.data }, contexts.observe().map { it.data }, serverUrls.serverUrl, images.loader)

    private val state = MutableStateFlow(TaskDetailUiState())
    private var taskId: UUID? = null

    val uiState: StateFlow<TaskDetailUiState> =
        combine(
            state,
            projects.onStart { emit(emptyList()) },
            contexts.onStart { emit(emptyList()) },
            server.onStart { emit(null) },
        ) { s, projectList, contextList, url ->
            val own = s.detail?.task?.projectId
            s.copy(
                projects = projectList.filter { it.status == ProjectStatus.ACTIVE || it.id == own },
                contexts = contextList,
                server = url,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TaskDetailUiState())

    /** Shows the task [id]; again for the same task it does nothing (a configuration change). */
    fun open(id: UUID) {
        if (taskId == id) return
        taskId = id
        state.value = TaskDetailUiState()
        reload()
    }

    /** Loads the task again: after a failed load, or to leave the offline copy. */
    fun reload() {
        val id = taskId ?: return
        viewModelScope.launch { load(id) }
    }

    private suspend fun load(
        id: UUID,
        message: DetailMessage? = null,
    ) {
        when (val result = source.load(id)) {
            is TaskResult.Ok ->
                state.update {
                    it.copy(
                        loading = false,
                        detail = result.value,
                        offline = false,
                        loadFailed = false,
                        notFound = false,
                        message = message,
                    )
                }
            TaskResult.Offline -> {
                val cached = source.cached(id)
                state.update {
                    it.copy(
                        loading = false,
                        detail = cached?.let { copy -> TaskDetail(copy.task) } ?: it.detail,
                        cachedAt = cached?.loadedAt ?: it.cachedAt,
                        offline = true,
                        loadFailed = cached == null && it.detail == null,
                        draft = null,
                        message = message ?: it.message,
                    )
                }
            }
            TaskResult.NotFound ->
                state.update {
                    it.copy(
                        loading = false,
                        notFound = true,
                        draft = null,
                        message = DetailMessage.NotFound,
                    )
                }
            else -> state.update { it.copy(loading = false, loadFailed = it.detail == null, message = message ?: DetailMessage.Failed()) }
        }
    }

    fun startEditing() {
        val s = state.value
        val task = s.detail?.task ?: return
        if (s.readOnly || s.detail.completed) return
        state.update { it.copy(draft = TaskDraft.of(task), problems = emptyMap()) }
    }

    fun edit(change: (TaskDraft) -> TaskDraft) =
        state.update { s ->
            val draft = s.draft ?: return@update s
            val next = change(draft)
            // A field changed by the user no longer shows its old problem.
            val stale = TaskField.entries.filter { s.problems.containsKey(it) && changed(it, draft, next) }
            s.copy(draft = next, problems = s.problems - stale.toSet())
        }

    fun cancelEditing() = state.update { it.copy(draft = null, problems = emptyMap()) }

    /** Sends the form's changes with the loaded version as `If-Match`. */
    fun save() {
        val s = state.value
        val draft = s.draft ?: return
        val task = s.detail?.task ?: return
        if (s.busy || s.readOnly) return
        val problems = draft.problems()
        if (problems.isNotEmpty()) {
            state.update { it.copy(problems = problems) }
            return
        }
        val patch = draft.patch(task)
        if (patch.isEmpty()) {
            state.update { it.copy(draft = null, problems = emptyMap()) }
            return
        }
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = source.update(task, patch)) {
                is TaskResult.Ok -> {
                    state.update {
                        it.copy(
                            busy = false,
                            draft = null,
                            problems = emptyMap(),
                            detail = it.detail?.copy(task = result.value),
                            message = DetailMessage.Saved,
                        )
                    }
                    source.changed()
                }
                is TaskResult.Invalid -> {
                    val fields = result.fields.mapNotNull { (name, text) -> TaskField.ofApi(name)?.let { it to FieldProblem.Server(text) } }
                    val unplaced = result.fields.keys.any { TaskField.ofApi(it) == null }
                    state.update {
                        it.copy(
                            busy = false,
                            problems = fields.toMap(),
                            message = if (fields.isEmpty() || unplaced) DetailMessage.Failed(result.message) else null,
                        )
                    }
                }
                else -> afterRefusal(result)
            }
        }
    }

    fun complete() = change(DetailMessage.Completed) { source.complete(it) }

    fun reopen() = change(DetailMessage.Reopened) { source.reopen(it) }

    /** Moves the task to [target]; Waiting needs [waitingFor], the person it waits for. */
    fun move(
        target: MoveTarget,
        waitingFor: String? = null,
    ) {
        if (target == MoveTarget.WAITING && waitingFor.isNullOrBlank()) return
        change(DetailMessage.Moved(target)) { source.move(it, target, waitingFor) }
    }

    /** Moves the task to the Trash; the snackbar then offers [undoDelete]. */
    fun delete() {
        val s = state.value
        val task = s.detail?.task ?: return
        if (s.busy || s.readOnly) return
        state.update { it.copy(busy = true, draft = null) }
        viewModelScope.launch {
            when (val result = source.delete(task)) {
                is TaskResult.Ok -> {
                    state.update { it.copy(busy = false, deleted = true, message = DetailMessage.Deleted) }
                    source.changed()
                }
                else -> afterRefusal(result)
            }
        }
    }

    /** Takes the task just deleted out of the Trash again. */
    fun undoDelete() {
        val s = state.value
        val id = s.detail?.task?.id ?: return
        if (!s.deleted || s.busy) return
        state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            when (val result = source.restore(id)) {
                is TaskResult.Ok -> {
                    state.update { it.copy(busy = false, deleted = false) }
                    load(id, DetailMessage.Restored)
                    source.changed()
                }
                TaskResult.Offline -> state.update { it.copy(busy = false, message = DetailMessage.Offline) }
                else -> state.update { it.copy(busy = false, message = DetailMessage.Failed((result as? TaskResult.Invalid)?.message)) }
            }
        }
    }

    fun toggleChecklistItem(item: ChecklistItem) =
        checklistChange { id ->
            source.updateChecklistItem(
                id,
                item,
                JsonObject(
                    mapOf(
                        "done" to jsonOf(!item.done),
                    ),
                ),
            )
        }

    fun renameChecklistItem(
        item: ChecklistItem,
        text: String,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == item.text) return
        checklistChange { id -> source.updateChecklistItem(id, item, JsonObject(mapOf("text" to jsonOf(trimmed)))) }
    }

    fun addChecklistItem(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        checklistChange { id -> source.addChecklistItem(id, trimmed) }
    }

    fun deleteChecklistItem(item: ChecklistItem) = checklistChange { id -> source.deleteChecklistItem(id, item) }

    /** Hides [shown] once it was dismissed or timed out; a newer message stays. */
    fun dismiss(shown: DetailMessage) = state.update { if (it.message == shown) it.copy(message = null) else it }

    /** Runs a checklist [request], then shows the checklist the server has (also after a refusal). */
    private fun checklistChange(request: suspend (UUID) -> TaskResult<*>) {
        val s = state.value
        val task = s.detail?.task ?: return
        if (s.busy || s.readOnly) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = request(task.id)
            val items = (source.checklist(task.id) as? TaskResult.Ok)?.value
            state.update {
                it.copy(
                    busy = false,
                    detail = items?.let { list -> it.detail?.copy(checklist = list) } ?: it.detail,
                    message = messageOf(result),
                )
            }
        }
    }

    /** Runs a change of the whole task, then reloads it so the screen shows what the server made of it. */
    private fun change(
        done: DetailMessage,
        request: suspend (GtdTask) -> TaskResult<*>,
    ) {
        val s = state.value
        val task = s.detail?.task ?: return
        if (s.busy || s.readOnly) return
        state.update { it.copy(busy = true, draft = null, problems = emptyMap()) }
        viewModelScope.launch {
            when (val result = request(task)) {
                is TaskResult.Ok -> {
                    load(task.id, done)
                    state.update { it.copy(busy = false) }
                    source.changed()
                }
                else -> afterRefusal(result)
            }
        }
    }

    /** After a refused change: a conflict reloads the task (never overwriting it), the rest is reported. */
    private suspend fun afterRefusal(result: TaskResult<*>) {
        val id = taskId ?: return
        when (result) {
            TaskResult.Conflict -> {
                state.update { it.copy(draft = null, problems = emptyMap()) }
                load(id, DetailMessage.Conflict)
                source.changed()
            }
            TaskResult.NotFound -> {
                state.update { it.copy(notFound = true, draft = null, message = DetailMessage.NotFound) }
                source.changed()
            }
            else -> state.update { it.copy(message = messageOf(result)) }
        }
        state.update { it.copy(busy = false) }
    }

    private fun messageOf(result: TaskResult<*>): DetailMessage? =
        when (result) {
            is TaskResult.Ok -> null
            TaskResult.Offline -> DetailMessage.Offline
            TaskResult.Conflict -> DetailMessage.Conflict
            TaskResult.NotFound -> DetailMessage.NotFound
            is TaskResult.Invalid -> DetailMessage.Failed(result.fields.values.firstOrNull() ?: result.message)
            is TaskResult.Failed -> DetailMessage.Failed()
        }

    private fun changed(
        field: TaskField,
        a: TaskDraft,
        b: TaskDraft,
    ): Boolean =
        when (field) {
            TaskField.TITLE -> a.title != b.title
            TaskField.NOTES -> a.notes != b.notes
            TaskField.PROJECT -> a.projectId != b.projectId
            TaskField.CONTEXTS -> a.contextIds != b.contextIds
            TaskField.DUE_DATE -> a.dueDate != b.dueDate
            TaskField.DUE_TIME -> a.dueTime != b.dueTime
            TaskField.ESTIMATE -> a.estimate != b.estimate
            TaskField.ENERGY -> a.energy != b.energy
            TaskField.CREATIVITY -> a.creativity != b.creativity
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
