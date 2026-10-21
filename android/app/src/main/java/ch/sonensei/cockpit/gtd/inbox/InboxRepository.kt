package ch.sonensei.cockpit.gtd.inbox

import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.models.CreateProjectRequest
import ch.sonensei.cockpit.core.network.generated.models.MoveTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.NextStepRequest
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdConnectivity
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.HttpFailure
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.data.body
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import java.io.IOException
import java.time.DateTimeException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `PATCH /tasks/{id}` with a hand-built body. The generated client sends every field of `TaskPatchRequest`, and a
 * `null` there clears the field, so a patch of the energy alone would also clear the due date, notes and recurrence.
 */
interface InboxTaskPatchApi {
    @PATCH("api/v1/tasks/{id}")
    suspend fun updateTask(
        @Path("id") id: UUID,
        @Body patch: JsonObject,
        @Header("If-Match") ifMatch: String,
    ): Response<TaskResponse>
}

@Module
@InstallIn(SingletonComponent::class)
object InboxModule {
    @Provides
    fun taskPatchApi(retrofit: Retrofit): InboxTaskPatchApi = retrofit.create(InboxTaskPatchApi::class.java)
}

/** Processes inbox items on the server (SPEC.md §5.3), then reloads the cached Inbox. */
@Singleton
class InboxRepository
    @Inject
    constructor(
        private val tasksApi: TasksApi,
        private val patchApi: InboxTaskPatchApi,
        private val projectsApi: ProjectsApi,
        private val tasks: TaskRepository,
        private val connectivity: GtdConnectivity,
    ) {
        /**
         * Items whose clarification was saved but whose action then failed, with the server's answer: a retry patches
         * (and moves) that version instead of the older cached one, which the server would refuse as stale.
         */
        private val clarified = ConcurrentHashMap<UUID, TaskResponse>()

        /**
         * Projects created by "New project" for an item whose move then failed, with their title: a retry with the same
         * title uses them instead of creating a second one. Dropped once the item is processed or moved elsewhere.
         */
        private val createdProjects = ConcurrentHashMap<UUID, CreatedProject>()

        private data class CreatedProject(
            val title: String,
            val id: UUID,
        )

        /**
         * Saves [clarification] (if it changed) and runs [action] on [task]. Trashing saves nothing: the attributes of
         * a trashed item do not matter. On success the Inbox is reloaded, so the item leaves the cached list.
         */
        suspend fun process(
            task: GtdTask,
            clarification: Clarification,
            action: InboxAction,
        ): ProcessResult {
            val result =
                connectivity.refreshing {
                    apply(task.id, clarify(task, if (action == InboxAction.Trash) null else clarification), action)
                }
            if (result == RefreshResult.Refreshed) {
                clarified.remove(task.id)
                createdProjects.remove(task.id)
            }
            // A conflict means the cached item is outdated: reloading shows its current state.
            if (result != RefreshResult.Offline) tasks.refresh(TaskView.INBOX)
            return when (result) {
                RefreshResult.Refreshed -> ProcessResult.Processed
                RefreshResult.Offline -> ProcessResult.Offline
                is RefreshResult.Failed -> if (result.status in CONFLICTS) ProcessResult.Conflict else ProcessResult.Rejected(result.status)
            }
        }

        /** Saves the changed attributes (none for `null`); returns the task's version afterwards. */
        private suspend fun clarify(
            task: GtdTask,
            clarification: Clarification?,
        ): Long {
            val saved = clarified[task.id]?.takeIf { it.version > task.version }
            val before = saved?.clarification() ?: Clarification.of(task)
            val version = saved?.version ?: task.version
            val patch = clarification?.patchFrom(before) ?: return version
            val updated = body(patchApi.updateTask(task.id, patch, etag(version)))
            clarified[task.id] = updated
            return updated.version
        }

        private suspend fun apply(
            id: UUID,
            version: Long,
            action: InboxAction,
        ) {
            val ifMatch = etag(version)

            suspend fun move(request: MoveTaskRequest) = body(tasksApi.moveTask(id, request, ifMatch))
            when (action) {
                InboxAction.Done -> body(tasksApi.completeTask(id, ifMatch))
                InboxAction.Next -> move(MoveTaskRequest(MoveTaskRequest.List.NEXT))
                InboxAction.Someday -> move(MoveTaskRequest(MoveTaskRequest.List.SOMEDAY))
                is InboxAction.Waiting ->
                    move(MoveTaskRequest(MoveTaskRequest.List.WAITING, waitingForText = action.forText?.trim()?.ifEmpty { null }))
                is InboxAction.Schedule -> move(MoveTaskRequest(MoveTaskRequest.List.SCHEDULED, scheduledDate = action.date))
                is InboxAction.Tickler -> move(MoveTaskRequest(MoveTaskRequest.List.INBOX, ticklerDate = action.date))
                is InboxAction.ToProject -> {
                    val projectId =
                        when (val choice = action.project) {
                            is ProjectChoice.Existing -> choice.id.also { createdProjects.remove(id) }
                            is ProjectChoice.New -> {
                                val title = choice.title.trim()
                                createdProjects[id]?.takeIf { it.title == title }?.id
                                    ?: body(projectsApi.createProject(CreateProjectRequest(title)))
                                        .id
                                        .also { createdProjects[id] = CreatedProject(title, it) }
                            }
                        }
                    move(MoveTaskRequest(MoveTaskRequest.List.NEXT, projectId = projectId))
                    makeNextStepIfStalled(projectId, id)
                }
                InboxAction.Trash -> succeeded(tasksApi.deleteTask(id, ifMatch))
            }
        }

        /**
         * A project without a next step (e.g. a new one) gets the item as its next step. The item has moved even when
         * this fails (no connection, an error status or an unreadable answer): the project then stays stalled, which the
         * Projects screen shows.
         */
        private suspend fun makeNextStepIfStalled(
            projectId: UUID,
            taskId: UUID,
        ) {
            try {
                val project = body(projectsApi.getProject(projectId))
                if (project.stalled) body(projectsApi.setProjectNextStep(projectId, NextStepRequest(taskId), etag(project.version)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
            } catch (_: HttpFailure) {
            } catch (_: SerializationException) {
            } catch (_: IllegalArgumentException) {
            } catch (_: DateTimeException) {
            }
        }

        private companion object {
            /** Not found (trashed elsewhere), conflict, precondition failed (changed elsewhere). */
            val CONFLICTS = setOf(404, 409, 412)
        }
    }

/** The `If-Match` value of [version] (the server's strong ETag). */
private fun etag(version: Long) = "\"$version\""

/** Like [body], for answers without a body (`204 No Content`). */
private fun succeeded(response: Response<*>) {
    if (!response.isSuccessful) throw HttpFailure(response.code())
}

private fun TaskResponse.clarification() =
    Clarification(
        energy = energy?.let { Energy.valueOf(it.name) },
        creativity = creativity?.let { Creativity.valueOf(it.name) },
        timeEstimateMin = timeEstimateMin,
        contextIds = contextIds,
    )
