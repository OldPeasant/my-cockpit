package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.auth.SessionCache
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.models.ChecklistItemResponse
import ch.sonensei.cockpit.core.network.generated.models.CreateChecklistItemRequest
import ch.sonensei.cockpit.core.network.generated.models.LinkListResponse
import ch.sonensei.cockpit.core.network.generated.models.LinkResponse
import ch.sonensei.cockpit.core.network.generated.models.MoveTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import ch.sonensei.cockpit.gtd.data.GtdCacheDao
import ch.sonensei.cockpit.gtd.data.GtdConnectivity
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.HttpFailure
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.data.toDomain
import ch.sonensei.cockpit.gtd.lists.toTask
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task writes with hand-built bodies: the generated request classes send every field, and a PATCH treats `null` as
 * "clear", so changing the title would wipe the due date. Also the Trash's restore (undo of a delete) and the task's
 * links, so the generated `LinksApi` stays free for the links feature to bind.
 */
interface TaskWriteApi {
    @PATCH("api/v1/tasks/{id}")
    suspend fun patchTask(
        @Path("id") id: UUID,
        @Body patch: JsonObject,
        @Header("If-Match") ifMatch: String,
    ): Response<TaskResponse>

    @PATCH("api/v1/tasks/{taskId}/checklist/{itemId}")
    suspend fun patchChecklistItem(
        @Path("taskId") taskId: UUID,
        @Path("itemId") itemId: UUID,
        @Body patch: JsonObject,
        @Header("If-Match") ifMatch: String,
    ): Response<ChecklistItemResponse>

    @POST("api/v1/trash/TASK/{id}/restore")
    suspend fun restoreTask(
        @Path("id") id: UUID,
    ): Response<Unit>

    @GET("api/v1/links?type=TASK")
    suspend fun listTaskLinks(
        @Query("id") id: UUID,
    ): Response<LinkListResponse>
}

@Module
@InstallIn(SingletonComponent::class)
object TaskDetailModule {
    @Provides
    fun taskWriteApi(retrofit: Retrofit): TaskWriteApi = retrofit.create(TaskWriteApi::class.java)

    /** The list copies kept for the detail screen belong to the user: a logout drops them. */
    @Provides
    @IntoSet
    fun taskSnapshots(): SessionCache = SessionCache { TaskSnapshots.clear() }
}

/** One checklist item of a task. */
data class ChecklistItem(
    val id: UUID,
    val text: String,
    val done: Boolean,
    val position: Int,
    val version: Long,
)

/** An item linked to the task (the other end of the link). */
data class TaskLink(
    val linkId: UUID,
    val type: String,
    val itemId: UUID,
    val title: String?,
    val subtitle: String?,
    val deepLink: String?,
    /** `false` when the item is in the Trash or gone. */
    val available: Boolean,
)

/** Everything the detail screen shows of a task. */
data class TaskDetail(
    val task: GtdTask,
    val checklist: List<ChecklistItem> = emptyList(),
    val links: List<TaskLink> = emptyList(),
    /** When it was completed; `null` while open. */
    val completedAt: Instant? = null,
    /** The tickler date of a Someday task (it comes back to the Inbox then). */
    val ticklerDate: LocalDate? = null,
    /** The RFC 5545 rule of a repeating task (without the `RRULE:` prefix). */
    val recurrenceRule: String? = null,
    /** How the next date of a repeating task is found; `null` when it does not repeat. */
    val recurrenceMode: RecurrenceMode? = null,
) {
    val completed: Boolean get() = completedAt != null
}

/** How a request about a task went. */
sealed interface TaskResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : TaskResult<T>

    /** The server is unreachable (or down). */
    data object Offline : TaskResult<Nothing>

    /** The task (or item) was changed elsewhere since it was loaded: 409 or 412. */
    data object Conflict : TaskResult<Nothing>

    /** The task (or item) is gone: trashed or deleted elsewhere. */
    data object NotFound : TaskResult<Nothing>

    /** The server refused the values: its Problem Details, with the message of each invalid field. */
    data class Invalid(
        val fields: Map<String, String>,
        val message: String?,
    ) : TaskResult<Nothing>

    /** Any other refusal, or an unreadable answer (`status == null`). */
    data class Failed(
        val status: Int?,
    ) : TaskResult<Nothing>
}

/** Where moving a task sends it. */
enum class MoveTarget { NEXT, SOMEDAY, WAITING }

/**
 * A move to [list] from the edit form. The server clears the tickler of a move without one and the scheduled date of
 * a move to another list than SCHEDULED, so both are always sent as the form has them.
 */
data class TaskMove(
    val list: TaskList,
    val scheduledDate: LocalDate? = null,
    val ticklerDate: LocalDate? = null,
    /** The person waited for, only for a move to WAITING. */
    val waitingForText: String? = null,
)

/** The calls the detail screen makes, so its view model can be tested without a server. */
interface TaskDetailSource {
    suspend fun load(id: UUID): TaskResult<TaskDetail>

    /** The copy the app still has of the task (the list it was opened from, or a cached view); for offline reading. */
    suspend fun cached(id: UUID): CachedTask?

    suspend fun update(
        task: GtdTask,
        patch: JsonObject,
    ): TaskResult<GtdTask>

    suspend fun complete(task: GtdTask): TaskResult<Unit>

    suspend fun reopen(task: GtdTask): TaskResult<Unit>

    suspend fun move(
        task: GtdTask,
        target: MoveTarget,
        waitingFor: String? = null,
    ): TaskResult<GtdTask>

    /** Moves the task with the edit form's list, scheduled and tickler dates. */
    suspend fun relist(
        task: GtdTask,
        move: TaskMove,
    ): TaskResult<GtdTask>

    suspend fun delete(task: GtdTask): TaskResult<Unit>

    suspend fun restore(id: UUID): TaskResult<Unit>

    suspend fun addChecklistItem(
        taskId: UUID,
        text: String,
    ): TaskResult<ChecklistItem>

    suspend fun updateChecklistItem(
        taskId: UUID,
        item: ChecklistItem,
        patch: JsonObject,
    ): TaskResult<ChecklistItem>

    suspend fun deleteChecklistItem(
        taskId: UUID,
        item: ChecklistItem,
    ): TaskResult<Unit>

    suspend fun checklist(taskId: UUID): TaskResult<List<ChecklistItem>>

    /** Reloads the cached task views and tells the live lists, after a change. */
    suspend fun changed()
}

/** A task as the app last saw it, and when that view was loaded (`null`: unknown, e.g. a live list's copy). */
data class CachedTask(
    val task: GtdTask,
    val loadedAt: Instant?,
    /** When it was completed, if the copy came from a list that knows (the Logbook); `null` while open or unknown. */
    val completedAt: Instant? = null,
)

/** The task detail screen's server calls (SPEC.md §5): one task with its checklist and links, and changes to it. */
@Singleton
class TaskDetailRepository
    @Inject
    constructor(
        private val api: TasksApi,
        private val writeApi: TaskWriteApi,
        private val dao: GtdCacheDao,
        private val connectivity: GtdConnectivity,
        private val tasks: TaskRepository,
    ) : TaskDetailSource {
        override suspend fun load(id: UUID): TaskResult<TaskDetail> =
            coroutineScope {
                val checklist = async { checklist(id) }
                // Links are extra: when they cannot be loaded, the task still shows. Their request does not go through
                // call(), so a failing one cannot mark the GTD screens offline while the task itself loaded.
                val links = async { links(id) }
                when (val task = call({ api.getTask(id) }) { it!! }) {
                    is TaskResult.Ok -> {
                        val items = checklist.await()
                        val linked = links.await()
                        when (items) {
                            is TaskResult.Ok -> TaskResult.Ok(task.value.toDetail(items.value, linked))
                            else -> items.mapFailure()
                        }
                    }
                    else -> {
                        checklist.cancel()
                        links.cancel()
                        task.mapFailure()
                    }
                }
            }

        /** The other ends of the task's links; none when they cannot be loaded (without touching the offline state). */
        private suspend fun links(id: UUID): List<TaskLink> =
            runCatching {
                val response = writeApi.listTaskLinks(id)
                if (response.isSuccessful) response.body()?.items?.mapNotNull { it.otherEnd(id) } else null
            }.getOrNull().orEmpty()

        override suspend fun cached(id: UUID): CachedTask? {
            TaskSnapshots.cached(id)?.let { return it }
            for (view in TaskView.entries) {
                val rows = dao.taskRows(view.name)
                rows.rows.firstOrNull { it.id == id.toString() }?.let { row ->
                    return CachedTask(row.toDomain(), rows.meta?.let { Instant.ofEpochMilli(it.refreshedAtEpochMs) })
                }
            }
            return null
        }

        override suspend fun update(
            task: GtdTask,
            patch: JsonObject,
        ): TaskResult<GtdTask> = call({ writeApi.patchTask(task.id, patch, etag(task.version)) }) { it!!.toTask() }

        override suspend fun complete(task: GtdTask): TaskResult<Unit> = call({ api.completeTask(task.id, etag(task.version)) }) { }

        override suspend fun reopen(task: GtdTask): TaskResult<Unit> = call({ api.uncompleteTask(task.id, etag(task.version)) }) { }

        override suspend fun move(
            task: GtdTask,
            target: MoveTarget,
            waitingFor: String?,
        ): TaskResult<GtdTask> {
            val request =
                MoveTaskRequest(
                    list = MoveTaskRequest.List.valueOf(target.name),
                    projectId = task.projectId,
                    waitingForText = waitingFor?.trim()?.takeIf { target == MoveTarget.WAITING && it.isNotEmpty() },
                )
            return call({ api.moveTask(task.id, request, etag(task.version)) }) { it!!.toTask() }
        }

        override suspend fun relist(
            task: GtdTask,
            move: TaskMove,
        ): TaskResult<GtdTask> {
            val request =
                MoveTaskRequest(
                    list = MoveTaskRequest.List.valueOf(move.list.name),
                    projectId = task.projectId,
                    scheduledDate = move.scheduledDate.takeIf { move.list == TaskList.SCHEDULED },
                    ticklerDate = move.ticklerDate,
                    waitingForText = move.waitingForText?.trim()?.takeIf { move.list == TaskList.WAITING && it.isNotEmpty() },
                )
            return call({ api.moveTask(task.id, request, etag(task.version)) }) { it!!.toTask() }
        }

        override suspend fun delete(task: GtdTask): TaskResult<Unit> = call({ api.deleteTask(task.id, etag(task.version)) }) { }

        override suspend fun restore(id: UUID): TaskResult<Unit> = call({ writeApi.restoreTask(id) }) { }

        override suspend fun addChecklistItem(
            taskId: UUID,
            text: String,
        ): TaskResult<ChecklistItem> = call({ api.createChecklistItem(taskId, CreateChecklistItemRequest(text = text)) }) { it!!.toItem() }

        override suspend fun updateChecklistItem(
            taskId: UUID,
            item: ChecklistItem,
            patch: JsonObject,
        ): TaskResult<ChecklistItem> = call({ writeApi.patchChecklistItem(taskId, item.id, patch, etag(item.version)) }) { it!!.toItem() }

        override suspend fun deleteChecklistItem(
            taskId: UUID,
            item: ChecklistItem,
        ): TaskResult<Unit> = call({ api.deleteChecklistItem(taskId, item.id, etag(item.version)) }) { }

        override suspend fun checklist(taskId: UUID): TaskResult<List<ChecklistItem>> =
            call({ api.listChecklistItems(taskId) }) { body -> body!!.items.map { it.toItem() }.sortedBy { it.position } }

        override suspend fun changed() {
            TaskEdits.changed()
            tasks.refresh(TaskView.NEXT)
            tasks.refresh(TaskView.TODAY)
            tasks.refresh(TaskView.INBOX)
        }

        /**
         * Sends [request] and reads a successful answer's body with [read]. The outcome also updates the GTD screens'
         * shared offline state, so the detail screen and the lists agree about the connection.
         */
        private suspend fun <B, T> call(
            request: suspend () -> Response<B>,
            read: (B?) -> T,
        ): TaskResult<T> {
            var value: Result<T>? = null
            var problem: TaskResult.Invalid? = null
            val result =
                connectivity.refreshing {
                    val response = request()
                    if (!response.isSuccessful) {
                        if (response.code() in INVALID) problem = problem(response.errorBody()?.string())
                        throw HttpFailure(response.code())
                    }
                    value = Result.success(read(response.body()))
                }
            return when (result) {
                RefreshResult.Refreshed -> TaskResult.Ok(value!!.getOrThrow())
                RefreshResult.Offline -> TaskResult.Offline
                is RefreshResult.Failed ->
                    when (result.status) {
                        HTTP_NOT_FOUND, HTTP_GONE -> TaskResult.NotFound
                        HTTP_CONFLICT, HTTP_PRECONDITION_FAILED -> TaskResult.Conflict
                        in INVALID -> problem ?: TaskResult.Invalid(emptyMap(), null)
                        else -> TaskResult.Failed(result.status)
                    }
            }
        }

        private companion object {
            const val HTTP_NOT_FOUND = 404
            const val HTTP_CONFLICT = 409
            const val HTTP_GONE = 410
            const val HTTP_PRECONDITION_FAILED = 412
            val INVALID = setOf(400, 422)
        }
    }

/** A failed [TaskResult] as one of another type. */
@Suppress("UNCHECKED_CAST")
private fun <T> TaskResult<*>.mapFailure(): TaskResult<T> = this as TaskResult<T>

/** The `If-Match` value of [version] (the server's strong ETag). */
private fun etag(version: Long) = "\"$version\""

private val problemJson = Json { ignoreUnknownKeys = true }

/** The field messages and detail of a Problem Details body (RFC 9457 with `errors`), or `null` when it is none. */
internal fun problem(body: String?): TaskResult.Invalid? =
    runCatching {
        val root = problemJson.parseToJsonElement(body ?: return null).jsonObject
        val fields =
            (root["errors"]?.jsonArray ?: emptyList()).mapNotNull { error ->
                val obj = error.jsonObject
                val field = obj["field"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                field to (obj["message"]?.jsonPrimitive?.contentOrNull ?: "")
            }
        TaskResult.Invalid(fields.toMap(), root["detail"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()

private fun TaskResponse.toDetail(
    checklist: List<ChecklistItem>,
    links: List<TaskLink>,
) = TaskDetail(
    task = toTask(),
    checklist = checklist,
    links = links,
    completedAt = completedAt?.toInstant(),
    ticklerDate = ticklerDate,
    recurrenceRule = recurrenceRule,
    recurrenceMode = recurrenceMode?.let { RecurrenceMode.valueOf(it.name) },
)

private fun ChecklistItemResponse.toItem() = ChecklistItem(id, text, done, position, version)

/** The end of the link that is not the task [taskId]; `null` for a link of the task to itself. */
private fun LinkResponse.otherEnd(taskId: UUID): TaskLink? {
    val other = if (source.id == taskId && source.type.name == "TASK") target else source
    if (other.id == taskId && other.type.name == "TASK") return null
    return TaskLink(id, other.type.name, other.id, other.title, other.subtitle, other.deepLink, other.available)
}

/** A JSON value for a PATCH body. */
internal fun jsonOf(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Collection<*> -> JsonArray(value.map(::jsonOf))
        else -> JsonPrimitive(value.toString())
    }

/** The checklist [items] with [item] moved to [to] (clamped to the list), as the order to save. */
internal fun reordered(
    items: List<ChecklistItem>,
    item: ChecklistItem,
    to: Int,
): List<ChecklistItem> {
    val from = items.indexOfFirst { it.id == item.id }
    if (from < 0) return items
    val list = items.toMutableList()
    val moved = list.removeAt(from)
    list.add(to.coerceIn(0, list.size), moved)
    return list
}

/** Whether the task is on a list it can be moved from to [target]. */
internal fun canMove(
    task: GtdTask,
    target: MoveTarget,
): Boolean = task.list.name != target.name
