package ch.sonensei.cockpit.gtd.lists

import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi.ListListTasks
import ch.sonensei.cockpit.core.network.generated.models.CreateTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.MoveTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import ch.sonensei.cockpit.gtd.data.ChangeTypes
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdConnectivity
import ch.sonensei.cockpit.gtd.data.GtdRefreshTriggers
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.PAGE_SIZE
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.data.allPages
import ch.sonensei.cockpit.gtd.data.body
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.merge
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Why loading a live list did not work. */
enum class LoadError { OFFLINE, FAILED }

/**
 * A list loaded from the server while it is shown, not cached (SPEC.md §9.3 caches only Inbox, Next and Today). [data]
 * is `null` until the first load worked; [error] says why the last load did not, so the screen shows the data it has
 * with a banner, or an error with a retry button instead of loading forever.
 */
data class Live<T>(
    val data: T? = null,
    val error: LoadError? = null,
) {
    val loading: Boolean get() = data == null && error == null

    /** Changes need the server: while the last load failed, the shown data may be outdated. */
    val readOnly: Boolean get() = error != null
}

/**
 * Loads with [load] once at once and again on every [triggers] or [reloads] emission, as [Live] values. A failed reload
 * keeps the data loaded before; a reload while nothing is loaded shows "loading" again.
 */
internal fun <T : Any> liveLoad(
    connectivity: GtdConnectivity,
    triggers: Flow<Unit>,
    reloads: Flow<Unit>,
    load: suspend () -> T,
): Flow<Live<T>> =
    channelFlow {
        var current = Live<T>()
        send(current)
        merge(triggers, reloads).conflate().collect {
            if (current.data == null && current.error != null) {
                current = Live()
                send(current)
            }
            var loaded: T? = null
            val result = connectivity.refreshing { loaded = load() }
            current =
                when (result) {
                    RefreshResult.Refreshed -> Live(loaded)
                    RefreshResult.Offline -> current.copy(error = LoadError.OFFLINE)
                    is RefreshResult.Failed -> current.copy(error = LoadError.FAILED)
                }
            send(current)
        }
    }

/** How a change of a task went. */
enum class ActionOutcome {
    DONE,
    OFFLINE,

    /** The task was changed or trashed elsewhere. */
    CONFLICT,
    FAILED,
    ;

    companion object {
        /** Not found (trashed elsewhere), conflict, precondition failed (changed elsewhere). */
        private val CONFLICTS = setOf(404, 409, 412)

        fun of(result: RefreshResult): ActionOutcome =
            when (result) {
                RefreshResult.Refreshed -> DONE
                RefreshResult.Offline -> OFFLINE
                is RefreshResult.Failed -> if (result.status in CONFLICTS) CONFLICT else FAILED
            }
    }
}

/** The task lists that are not cached (Waiting For, Someday, a project's tasks) and the changes made on them. */
@Singleton
class TaskListsRepository
    @Inject
    constructor(
        private val api: TasksApi,
        private val connectivity: GtdConnectivity,
        private val triggers: GtdRefreshTriggers,
        private val cached: TaskRepository,
    ) {
        /** The open tasks of [list] in list order; while collected, reloaded on task changes and on [reloads]. */
        fun observe(
            list: TaskList,
            reloads: Flow<Unit>,
        ): Flow<Live<List<GtdTask>>> = liveLoad(connectivity, triggers.on(ChangeTypes.TASK), reloads) { load(list = list) }

        /** The open tasks of a project in the project's order; reloaded like [observe]. */
        fun observeProject(
            projectId: UUID,
            reloads: Flow<Unit>,
        ): Flow<Live<List<GtdTask>>> =
            liveLoad(connectivity, triggers.on(ChangeTypes.TASK), reloads) { load(projectId = projectId).sortedBy { it.position } }

        /** Someday's "Activate": the task becomes a next action. */
        suspend fun activate(task: GtdTask): ActionOutcome =
            change {
                body(
                    api.moveTask(
                        task.id,
                        MoveTaskRequest(list = MoveTaskRequest.List.NEXT, projectId = task.projectId),
                        etag(task.version),
                    ),
                )
            }

        /** Waiting For's "Follow up": a next action (in the same project) to chase what [task] waits for. */
        suspend fun followUp(
            task: GtdTask,
            title: String,
        ): ActionOutcome =
            change {
                body(
                    api.createTask(CreateTaskRequest(title = title, list = CreateTaskRequest.List.NEXT, projectId = task.projectId)),
                )
            }

        /** Runs [block] and, unless offline, reloads the cached Next view, which the change may have added to. */
        private suspend fun change(block: suspend () -> Unit): ActionOutcome {
            val result = connectivity.refreshing(block)
            if (result != RefreshResult.Offline) cached.refresh(TaskView.NEXT)
            return ActionOutcome.of(result)
        }

        private suspend fun load(
            list: TaskList? = null,
            projectId: UUID? = null,
        ): List<GtdTask> =
            allPages(
                page = { cursor ->
                    api.listTasks(
                        list = list?.let { ListListTasks.valueOf(it.name) },
                        projectId = projectId,
                        cursor = cursor,
                        limit = PAGE_SIZE,
                    )
                },
                items = { it.items },
                nextCursor = { it.nextCursor },
            ).map { it.toTask() }
    }

/** The `If-Match` value of [version] (the server's strong ETag). */
internal fun etag(version: Long) = "\"$version\""

internal fun TaskResponse.toTask() =
    GtdTask(
        id = id,
        title = title,
        list = TaskList.valueOf(list.name),
        position = position,
        version = version,
        projectId = projectId,
        contextIds = contextIds,
        dueDate = dueDate,
        dueTime = dueTime,
        scheduledDate = scheduledDate,
        energy = energy?.let { Energy.valueOf(it.name) },
        creativity = creativity?.let { Creativity.valueOf(it.name) },
        timeEstimateMin = timeEstimateMin,
        waitingForText = waitingForText,
        waitingSince = waitingSince,
        notesMd = notesMd,
    )
