package ch.sonensei.cockpit.gtd.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.notes.NoteImages
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
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
    LIST("list"),
    SCHEDULED("scheduledDate"),
    TICKLER("ticklerDate"),
    WAITING_FOR("waitingForText"),
    WAITING_SINCE("waitingSince"),
    RECURRENCE("recurrenceRule"),
    RECURRENCE_MODE("recurrenceMode"),
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

    /** A tickler date must be after today. */
    data object NotFuture : FieldProblem

    /** A repeat interval that is no number in range, or a custom rule without `FREQ`. */
    data object Invalid : FieldProblem

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
    val list: TaskList = TaskList.INBOX,
    /** Only kept on the SCHEDULED list. */
    val scheduledDate: LocalDate? = null,
    val ticklerDate: LocalDate? = null,
    /** The person waited for, only kept on the WAITING list. */
    val waitingFor: String = "",
    val waitingSince: LocalDate? = null,
    val repeat: RepeatDraft = RepeatDraft(),
) {
    /** The draft on [target]: a list other than SCHEDULED has no scheduled date. */
    fun onList(target: TaskList): TaskDraft = copy(list = target, scheduledDate = scheduledDate.takeIf { target == TaskList.SCHEDULED })

    /**
     * The draft with the scheduled date [date]: a date puts the task on the SCHEDULED list, clearing it takes a
     * scheduled task to Next (where the server puts it on its date, too).
     */
    fun scheduledOn(date: LocalDate?): TaskDraft =
        when {
            date != null -> copy(list = TaskList.SCHEDULED, scheduledDate = date)
            list == TaskList.SCHEDULED -> copy(list = TaskList.NEXT, scheduledDate = null)
            else -> copy(scheduledDate = null)
        }

    /** The problems found before sending: the server would refuse these. */
    fun problems(
        before: TaskDetail? = null,
        today: LocalDate = LocalDate.now(),
    ): Map<TaskField, FieldProblem> =
        buildMap {
            if (title.isBlank()) put(TaskField.TITLE, FieldProblem.Required)
            if (title.trim().length > MAX_TITLE) put(TaskField.TITLE, FieldProblem.TooLong)
            val minutes = estimate.trim()
            if (minutes.isNotEmpty() && minutes.toIntOrNull()?.takeIf { it in 0..MAX_ESTIMATE } == null) {
                put(TaskField.ESTIMATE, FieldProblem.NotANumber)
            }
            if (list == TaskList.SCHEDULED && scheduledDate == null) put(TaskField.SCHEDULED, FieldProblem.Required)
            // Every move sends the tickler: one that is no longer in the future would be refused.
            val moving = before != null && move(before) != null
            if (moving && ticklerDate != null && !ticklerDate.isAfter(today)) put(TaskField.TICKLER, FieldProblem.NotFuture)
            val toWaiting = before != null && list == TaskList.WAITING && before.task.list != TaskList.WAITING
            if (toWaiting && waitingFor.isBlank()) put(TaskField.WAITING_FOR, FieldProblem.Required)
            repeat.problem()?.let { put(TaskField.RECURRENCE, it) }
        }

    /** The move that puts the task on the draft's list with its tickler, or `null` when neither changed. */
    fun move(before: TaskDetail): TaskMove? {
        if (list == before.task.list && ticklerDate == before.ticklerDate) return null
        return TaskMove(
            list = list,
            scheduledDate = scheduledDate.takeIf { list == TaskList.SCHEDULED },
            ticklerDate = ticklerDate,
            waitingForText = waitingFor.trim().takeIf { list == TaskList.WAITING && it.isNotEmpty() },
        )
    }

    /**
     * The PATCH turning [task] (after the [move], if any) and the repeat it has ([recurrence]) into the draft. The
     * list-bound fields are only sent while the task is on their list; the list and tickler change with [move].
     */
    fun patch(
        task: GtdTask,
        recurrence: RepeatDraft,
    ): JsonObject {
        val fields = patch(task).toMutableMap()
        if (task.list == TaskList.SCHEDULED && list == TaskList.SCHEDULED && scheduledDate != task.scheduledDate) {
            fields[TaskField.SCHEDULED.apiName] = jsonOf(scheduledDate?.toString())
        }
        if (task.list == TaskList.WAITING && list == TaskList.WAITING) {
            val person = waitingFor.trim().ifEmpty { null }
            if (person != task.waitingForText?.ifBlank { null }) fields[TaskField.WAITING_FOR.apiName] = jsonOf(person)
            if (waitingSince != null && waitingSince != task.waitingSince) {
                fields[TaskField.WAITING_SINCE.apiName] = jsonOf(waitingSince.toString())
            }
        }
        if (!repeat.sameAs(recurrence)) {
            val rule = repeat.rule()
            if (rule != recurrence.rule()) fields[TaskField.RECURRENCE.apiName] = jsonOf(rule)
            if (repeat.modeOrNull() !=
                recurrence.modeOrNull()
            ) {
                fields[TaskField.RECURRENCE_MODE.apiName] = jsonOf(repeat.modeOrNull()?.name)
            }
        }
        return JsonObject(fields)
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
            fields[TaskField.DUE_TIME.apiName] = jsonOf(time?.let { "%02d:%02d:00".format(Locale.ROOT, it.hour, it.minute) })
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
                list = task.list,
                scheduledDate = task.scheduledDate,
                waitingFor = task.waitingForText.orEmpty(),
                waitingSince = task.waitingSince,
            )

        /** The form for [detail]: the task with its tickler and repeat. */
        fun of(detail: TaskDetail) =
            of(detail.task).copy(
                ticklerDate = detail.ticklerDate,
                repeat = RepeatDraft.of(detail.recurrenceRule, detail.recurrenceMode),
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

    /** "New project…" could not create the project (the task's project is unchanged); [detail] is the server's reason. */
    data class ProjectNotCreated(
        val detail: String? = null,
    ) : DetailMessage

    data object Completed : DetailMessage

    data object Reopened : DetailMessage

    data class Moved(
        val target: MoveTarget,
    ) : DetailMessage

    /** The task went to the Trash; the snackbar offers to undo that. */
    data object Deleted : DetailMessage

    data object Restored : DetailMessage

    /** The task became [item] (a note or an event) and is in the Trash; the snackbar offers to open the item. */
    data class Converted(
        val item: ConvertedItem,
    ) : DetailMessage
}

/** The open "To calendar" dialog: the plan being edited and the calendars it offers. */
data class CalendarDialogState(
    val plan: EventPlan,
    val choice: CalendarChoice = CalendarChoice(),
)

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
    /** The open "To calendar" dialog. */
    val calendarDialog: CalendarDialogState? = null,
    /** What the task was converted into here: it is in the Trash now, the screen offers to open the item, then closes. */
    val converted: ConvertedItem? = null,
    /** Projects made here with "New project…": the picker shows them before the projects have reloaded. */
    val createdProjects: List<GtdProject> = emptyList(),
) {
    /** Changes need the server and a loaded task. */
    val readOnly: Boolean get() = offline || detail == null || deleted || notFound || converted != null

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
    /** The clock and zone "To calendar" starts from and puts timed events in. */
    private val clock: Clock = Clock.systemDefaultZone(),
    /** The synced settings: "To calendar" puts timed events in their time zone (else the [clock]'s). */
    private val settings: StateFlow<UserSettings?> = MutableStateFlow(null),
) : ViewModel() {
    @Inject
    constructor(
        source: TaskDetailRepository,
        projects: ProjectRepository,
        contexts: ContextRepository,
        serverUrls: ServerUrlRepository,
        images: NoteImages,
        settings: UserSettingsRepository,
    ) : this(
        source,
        projects.observe().map { it.data },
        contexts.observe().map { it.data },
        serverUrls.serverUrl,
        images.loader,
        Clock.systemDefaultZone(),
        settings.settings,
    )

    private val state = MutableStateFlow(TaskDetailUiState())
    private var taskId: UUID? = null

    /** The running load of the "To calendar" calendars: a newer load cancels it, so an older answer never wins. */
    private var calendarsJob: Job? = null

    /** The zone of timed events: the user's synced time zone, else the device's. */
    private val zone: ZoneId get() = settings.value?.timeZone ?: clock.zone

    val uiState: StateFlow<TaskDetailUiState> =
        combine(
            state,
            projects.onStart { emit(emptyList()) },
            contexts.onStart { emit(emptyList()) },
            server.onStart { emit(null) },
        ) { s, projectList, contextList, url ->
            val own = s.detail?.task?.projectId
            val known = projectList.map { it.id }.toSet()
            s.copy(
                projects =
                    projectList.filter { it.status == ProjectStatus.ACTIVE || it.id == own } +
                        s.createdProjects.filter { it.id !in known },
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
                // A task already loaded (e.g. reloading after a change) is newer than the list's copy: keep it.
                val cached = if (state.value.detail == null) source.cached(id) else null
                state.update {
                    it.copy(
                        loading = false,
                        detail = it.detail ?: cached?.let { copy -> TaskDetail(copy.task, completedAt = copy.completedAt) },
                        cachedAt = if (it.detail == null) cached?.loadedAt else it.cachedAt,
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
        if (s.detail?.task == null || s.readOnly || s.detail.completed) return
        state.update { it.copy(draft = TaskDraft.of(s.detail), problems = emptyMap()) }
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

    /**
     * "New project…" of the picker: creates an Active project without area titled [title] and puts the task in it
     * (saved with the form). A blank title does nothing. A project already made here with the same title is reused,
     * so a retry never creates a second one; a failure shows an error and leaves the task's project as it was.
     */
    fun newProject(title: String) {
        val trimmed = title.trim()
        val s = state.value
        if (trimmed.isEmpty() || s.draft == null || s.busy || s.readOnly) return
        s.createdProjects.firstOrNull { it.title == trimmed }?.let { project ->
            return edit { it.copy(projectId = project.id) }
        }
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = source.createProject(trimmed)) {
                is TaskResult.Ok ->
                    state.update { st ->
                        st.copy(
                            busy = false,
                            createdProjects = st.createdProjects + result.value,
                            draft = st.draft?.copy(projectId = result.value.id),
                            problems = st.problems - TaskField.PROJECT,
                        )
                    }
                else ->
                    state.update {
                        it.copy(
                            busy = false,
                            message =
                                when (result) {
                                    TaskResult.Offline -> DetailMessage.Offline
                                    is TaskResult.Invalid ->
                                        DetailMessage.ProjectNotCreated(
                                            result.fields.values.firstOrNull() ?: result.message,
                                        )
                                    else -> DetailMessage.ProjectNotCreated()
                                },
                        )
                    }
            }
        }
    }

    /**
     * Sends the form's changes with the loaded version as `If-Match`: first a move when the list or tickler changed,
     * then a PATCH of the other fields on the moved task.
     */
    fun save() {
        val s = state.value
        val draft = s.draft ?: return
        val detail = s.detail ?: return
        if (s.busy || s.readOnly) return
        val problems = draft.problems(detail)
        if (problems.isNotEmpty()) {
            state.update { it.copy(problems = problems) }
            return
        }
        val move = draft.move(detail)
        val recurrence = RepeatDraft.of(detail.recurrenceRule, detail.recurrenceMode)
        if (move == null && draft.patch(detail.task, recurrence).isEmpty()) {
            state.update { it.copy(draft = null, problems = emptyMap()) }
            return
        }
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            var task = detail.task
            if (move != null) {
                when (val moved = source.relist(task, move)) {
                    is TaskResult.Ok -> {
                        task = moved.value
                        // The move went through: a refused PATCH below starts again from the moved task.
                        state.update { it.copy(detail = it.detail?.copy(task = task, ticklerDate = move.ticklerDate)) }
                    }
                    else -> return@launch refused(moved)
                }
            }
            val patch = draft.patch(task, recurrence)
            val result = if (patch.isEmpty()) TaskResult.Ok(task) else source.update(task, patch)
            when (result) {
                is TaskResult.Ok -> {
                    state.update {
                        it.copy(
                            busy = false,
                            draft = null,
                            problems = emptyMap(),
                            detail =
                                it.detail?.copy(
                                    task = result.value,
                                    recurrenceRule = draft.repeat.rule(),
                                    recurrenceMode = draft.repeat.modeOrNull(),
                                ),
                            message = DetailMessage.Saved,
                        )
                    }
                    source.changed()
                }
                else -> {
                    refused(result)
                    if (move != null) source.changed()
                }
            }
        }
    }

    /** After a refused save: the invalid fields show their problems, the rest is handled as any refusal. */
    private suspend fun refused(result: TaskResult<*>) {
        if (result !is TaskResult.Invalid) return afterRefusal(result)
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

    /** Moves [item] to index [to] of the checklist and saves the positions that changed (0, 1, 2, ...). */
    fun moveChecklistItem(
        item: ChecklistItem,
        to: Int,
    ) {
        val s = state.value
        val items = s.detail?.checklist ?: return
        if (s.busy || s.readOnly) return
        val order = reordered(items, item, to)
        if (order.map { it.id } == items.map { it.id }) return
        // Shown at once; the server's checklist replaces it when the positions are saved.
        state.update { it.copy(detail = it.detail?.copy(checklist = order)) }
        checklistChange { id ->
            var result: TaskResult<*> = TaskResult.Ok(Unit)
            for ((index, entry) in order.withIndex()) {
                if (entry.position == index) continue
                result = source.updateChecklistItem(id, entry, JsonObject(mapOf("position" to jsonOf(index))))
                if (result !is TaskResult.Ok) break
            }
            result
        }
    }

    /** Converts the task into a note ("To reference", SPEC.md §5.3). */
    fun toReference() = convert { source.convertToNote(it) }

    /** Opens the "To calendar" dialog, starting at the next full hour, and loads the calendars it offers. */
    fun openToCalendar() {
        val s = state.value
        if (s.detail?.task == null || s.readOnly || s.busy || s.detail.completed) return
        state.update { it.copy(calendarDialog = CalendarDialogState(EventPlan.startingAfter(ZonedDateTime.now(clock.withZone(zone))))) }
        loadCalendars()
    }

    fun editPlan(change: (EventPlan) -> EventPlan) =
        state.update { s -> s.calendarDialog?.let { s.copy(calendarDialog = it.copy(plan = change(it.plan))) } ?: s }

    fun retryCalendars() = loadCalendars()

    fun cancelToCalendar() = state.update { it.copy(calendarDialog = null) }

    /** Converts the task into the event of the dialog's plan. The dialog stays open until the server answered. */
    fun confirmToCalendar() {
        val dialog = state.value.calendarDialog ?: return
        if (!dialog.plan.isValid || dialog.choice.calendars.isNullOrEmpty()) return
        val request = dialog.plan.request(zone)
        convert(closesDialog = true) { source.convertToEvent(it, request) }
    }

    private fun loadCalendars() {
        state.update { s -> s.copy(calendarDialog = s.calendarDialog?.copy(choice = CalendarChoice())) }
        calendarsJob?.cancel()
        calendarsJob =
            viewModelScope.launch {
                val result = source.calendars()
                state.update { s ->
                    val dialog = s.calendarDialog ?: return@update s
                    val choice =
                        when (result) {
                            is TaskResult.Ok -> CalendarChoice(result.value)
                            else -> CalendarChoice(failed = true)
                        }
                    s.copy(
                        calendarDialog =
                            dialog.copy(
                                plan = choice.calendars?.let { dialog.plan.withCalendarOf(it) } ?: dialog.plan,
                                choice = choice,
                            ),
                    )
                }
            }
    }

    /** Runs a conversion; on success the task is gone (in the Trash) and the screen shows where it went. */
    private fun convert(
        closesDialog: Boolean = false,
        request: suspend (GtdTask) -> TaskResult<ConvertedItem>,
    ) {
        val s = state.value
        val task = s.detail?.task ?: return
        if (s.busy || s.readOnly || s.detail.completed) return
        state.update { it.copy(busy = true, draft = null, problems = emptyMap()) }
        viewModelScope.launch {
            when (val result = request(task)) {
                is TaskResult.Ok -> {
                    state.update {
                        it.copy(
                            busy = false,
                            calendarDialog = null,
                            converted = result.value,
                            message = DetailMessage.Converted(result.value),
                        )
                    }
                    source.changed()
                }
                else -> {
                    // A refusal the dialog cannot fix (gone, changed elsewhere) closes it; an offline one keeps it to retry.
                    if (closesDialog && result != TaskResult.Offline) state.update { it.copy(calendarDialog = null) }
                    afterRefusal(result)
                }
            }
        }
    }

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
            TaskField.LIST -> a.list != b.list
            TaskField.SCHEDULED -> a.scheduledDate != b.scheduledDate || a.list != b.list
            TaskField.TICKLER -> a.ticklerDate != b.ticklerDate
            TaskField.WAITING_FOR -> a.waitingFor != b.waitingFor || a.list != b.list
            TaskField.WAITING_SINCE -> a.waitingSince != b.waitingSince
            TaskField.RECURRENCE, TaskField.RECURRENCE_MODE -> a.repeat != b.repeat
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
