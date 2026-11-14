package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.core.network.generated.models.ConvertToEventRequest
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Answers like the server with [detail]; records what the view model asked for. */
class FakeTaskDetailSource(
    detail: TaskDetail,
) : TaskDetailSource {
    var loadResult: TaskResult<TaskDetail> = TaskResult.Ok(detail)
    var updateResult: TaskResult<GtdTask>? = null
    var itemResult: TaskResult<ChecklistItem>? = null
    var checklistResult: TaskResult<List<ChecklistItem>> = TaskResult.Ok(detail.checklist)
    var cachedTask: CachedTask? = null

    /** What complete, reopen, delete and restore answer; `null` is success. */
    var actionResult: TaskResult<Unit>? = null
    var restoreResult: TaskResult<Unit>? = null
    var moveResult: TaskResult<GtdTask>? = null
    var loads = 0
    var checklistLoads = 0
    var changes = 0
    val updates = mutableListOf<Pair<GtdTask, JsonObject>>()
    val itemPatches = mutableListOf<Pair<ChecklistItem, JsonObject>>()
    val added = mutableListOf<String>()
    val deletedItems = mutableListOf<ChecklistItem>()
    val moves = mutableListOf<Triple<GtdTask, MoveTarget, String?>>()
    val calls = mutableListOf<String>()

    override suspend fun load(id: UUID): TaskResult<TaskDetail> {
        loads++
        return loadResult
    }

    override suspend fun cached(id: UUID): CachedTask? = cachedTask

    override suspend fun update(
        task: GtdTask,
        patch: JsonObject,
    ): TaskResult<GtdTask> {
        updates += task to patch
        val title = (patch["title"] as? JsonPrimitive)?.content ?: task.title
        return updateResult ?: TaskResult.Ok(task.copy(title = title, version = task.version + 1))
    }

    override suspend fun complete(task: GtdTask): TaskResult<Unit> {
        calls += "complete"
        return actionResult ?: TaskResult.Ok(Unit)
    }

    override suspend fun reopen(task: GtdTask): TaskResult<Unit> {
        calls += "reopen"
        return actionResult ?: TaskResult.Ok(Unit)
    }

    override suspend fun move(
        task: GtdTask,
        target: MoveTarget,
        waitingFor: String?,
    ): TaskResult<GtdTask> {
        moves += Triple(task, target, waitingFor)
        return moveResult ?: TaskResult.Ok(task)
    }

    /** What relist answers; `null` is the task on the new list with its scheduled date. */
    var relistResult: TaskResult<GtdTask>? = null
    val relists = mutableListOf<Pair<GtdTask, TaskMove>>()

    override suspend fun relist(
        task: GtdTask,
        move: TaskMove,
    ): TaskResult<GtdTask> {
        relists += task to move
        return relistResult
            ?: TaskResult.Ok(
                task.copy(
                    list = move.list,
                    scheduledDate = move.scheduledDate,
                    waitingForText = if (move.list == ch.sonensei.cockpit.gtd.data.TaskList.WAITING) move.waitingForText else null,
                    version = task.version + 1,
                ),
            )
    }

    override suspend fun delete(task: GtdTask): TaskResult<Unit> {
        calls += "delete"
        return actionResult ?: TaskResult.Ok(Unit)
    }

    override suspend fun restore(id: UUID): TaskResult<Unit> {
        calls += "restore"
        return restoreResult ?: TaskResult.Ok(Unit)
    }

    override suspend fun addChecklistItem(
        taskId: UUID,
        text: String,
    ): TaskResult<ChecklistItem> {
        added += text
        return itemResult ?: TaskResult.Ok(ChecklistItem(UUID.randomUUID(), text, false, 1, 1))
    }

    override suspend fun updateChecklistItem(
        taskId: UUID,
        item: ChecklistItem,
        patch: JsonObject,
    ): TaskResult<ChecklistItem> {
        itemPatches += item to patch
        return itemResult ?: TaskResult.Ok(item)
    }

    override suspend fun deleteChecklistItem(
        taskId: UUID,
        item: ChecklistItem,
    ): TaskResult<Unit> {
        deletedItems += item
        return TaskResult.Ok(Unit)
    }

    override suspend fun checklist(taskId: UUID): TaskResult<List<ChecklistItem>> {
        checklistLoads++
        return checklistResult
    }

    /** What the conversions answer; `null` is a new note or event. */
    var convertResult: TaskResult<ConvertedItem>? = null
    var calendarsResult: TaskResult<List<TargetCalendar>> = TaskResult.Ok(emptyList())
    val eventRequests = mutableListOf<ConvertToEventRequest>()

    override suspend fun convertToNote(task: GtdTask): TaskResult<ConvertedItem> {
        calls += "toNote"
        return convertResult ?: TaskResult.Ok(ConvertedItem(ConvertedType.NOTE, UUID.randomUUID(), task.title))
    }

    override suspend fun convertToEvent(
        task: GtdTask,
        request: ConvertToEventRequest,
    ): TaskResult<ConvertedItem> {
        calls += "toEvent"
        eventRequests += request
        return convertResult ?: TaskResult.Ok(ConvertedItem(ConvertedType.EVENT, UUID.randomUUID(), task.title, request.startDate))
    }

    override suspend fun calendars(): TaskResult<List<TargetCalendar>> {
        calls += "calendars"
        return calendarsResult
    }

    override suspend fun changed() {
        changes++
    }

    /** What createProject answers; `null` is an Active project with the title asked for. */
    var createProjectResult: TaskResult<GtdProject>? = null
    val createdProjects = mutableListOf<String>()

    override suspend fun createProject(title: String): TaskResult<GtdProject> {
        createdProjects += title
        return createProjectResult
            ?: TaskResult.Ok(
                GtdProject(UUID.nameUUIDFromBytes(title.toByteArray()), title, ProjectStatus.ACTIVE, stalled = true, version = 0),
            )
    }
}
