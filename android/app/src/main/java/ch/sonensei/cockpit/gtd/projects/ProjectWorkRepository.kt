package ch.sonensei.cockpit.gtd.projects

import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.models.CreateProjectRequest
import ch.sonensei.cockpit.core.network.generated.models.CreateTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.NextStepRequest
import ch.sonensei.cockpit.gtd.data.ChangeTypes
import ch.sonensei.cockpit.gtd.data.GtdConnectivity
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdRefreshTriggers
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.HttpFailure
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.data.body
import ch.sonensei.cockpit.gtd.lists.ActionOutcome
import ch.sonensei.cockpit.gtd.lists.Live
import ch.sonensei.cockpit.gtd.lists.etag
import ch.sonensei.cockpit.gtd.lists.liveLoad
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An area of focus (SPEC.md §5.1): groups projects. */
data class Area(
    val id: UUID,
    val title: String,
    val position: Int,
)

/** How completing a project task went. */
sealed interface ProjectCompletion {
    /** Done; [needsNextStep]: it was the next step of an active project, which is now stalled. */
    data class Completed(
        val needsNextStep: Boolean,
    ) : ProjectCompletion

    data class NotDone(
        val outcome: ActionOutcome,
    ) : ProjectCompletion
}

/** How adding a new next step went; [taskId] is the task created for it, also when setting it as next step failed. */
data class NextStepAdded(
    val outcome: ActionOutcome,
    val taskId: UUID?,
)

/** How creating a project went; [id] is the new project's, when it was created. */
data class ProjectCreated(
    val outcome: ActionOutcome,
    val id: UUID?,
)

/**
 * The areas and the changes made on projects: creating, editing and deleting them, completing their tasks and
 * choosing their next step. Every change reloads the cached projects, also after a conflict (412: changed elsewhere),
 * so the screen shows the current version.
 */
@Singleton
class ProjectWorkRepository
    @Inject
    constructor(
        private val projectsApi: ProjectsApi,
        private val writeApi: ProjectWriteApi,
        private val tasksApi: TasksApi,
        private val connectivity: GtdConnectivity,
        private val triggers: GtdRefreshTriggers,
        private val projects: ProjectRepository,
        private val tasks: TaskRepository,
    ) {
        /** The areas in their order; reloaded on project changes (areas have no change type of their own) and [reloads]. */
        fun observeAreas(reloads: Flow<Unit>): Flow<Live<List<Area>>> =
            liveLoad(connectivity, triggers.on(ChangeTypes.PROJECT), reloads) {
                body(projectsApi.listAreas()).items.map { Area(it.id, it.title, it.position) }.sortedBy { it.position }
            }

        suspend fun complete(task: GtdTask): ProjectCompletion {
            var needsNextStep = false
            val result = connectivity.refreshing { needsNextStep = body(tasksApi.completeTask(task.id, etag(task.version))).needsNextStep }
            if (result != RefreshResult.Offline) reloadCaches(withTasks = true)
            return if (result ==
                RefreshResult.Refreshed
            ) {
                ProjectCompletion.Completed(needsNextStep)
            } else {
                ProjectCompletion.NotDone(ActionOutcome.of(result))
            }
        }

        /** Makes [taskId] (an open task of [project]) its next step. */
        suspend fun setNextStep(
            project: GtdProject,
            taskId: UUID,
        ): ActionOutcome =
            change(withTasks = false) { body(projectsApi.setProjectNextStep(project.id, NextStepRequest(taskId), etag(project.version))) }

        /**
         * Creates a next action titled [title] in [project] and makes it the next step. With [created] (the task an earlier
         * attempt created before setting it failed) only the next step is set, so a retry does not create the task twice.
         */
        suspend fun addNextStep(
            project: GtdProject,
            title: String,
            created: UUID? = null,
        ): NextStepAdded {
            var taskId = created
            val outcome =
                change(withTasks = true) {
                    val id =
                        taskId ?: body(
                            tasksApi.createTask(
                                CreateTaskRequest(title = title, list = CreateTaskRequest.List.NEXT, projectId = project.id),
                            ),
                        ).id.also { taskId = it }
                    // Adding a task may change the project, so the next step is set on its current version.
                    val current = body(projectsApi.getProject(project.id))
                    body(projectsApi.setProjectNextStep(project.id, NextStepRequest(id), etag(current.version)))
                }
            return NextStepAdded(outcome, taskId)
        }

        /** Creates a project from [draft]. */
        suspend fun create(draft: ProjectDraft): ProjectCreated {
            var id: UUID? = null
            val outcome =
                change(withTasks = false) {
                    val request =
                        CreateProjectRequest(
                            title = draft.title.trim(),
                            areaId = draft.areaId,
                            descriptionMd = draft.description.takeIf { it.isNotBlank() },
                            dueDate = draft.dueDate,
                            reviewDate = draft.reviewDate,
                            status = CreateProjectRequest.Status.valueOf(draft.status.name),
                        )
                    id = body(projectsApi.createProject(request)).id
                }
            return ProjectCreated(outcome, id)
        }

        /** Changes the fields in [patch] of the project [id], if it is still at [version]. */
        suspend fun update(
            id: UUID,
            version: Long,
            patch: JsonObject,
        ): ActionOutcome = change(withTasks = false) { body(writeApi.patchProject(id, patch, etag(version))) }

        /** Moves [project] to the Trash; its tasks keep their project (the server's rule), so the task views reload. */
        suspend fun delete(project: GtdProject): ActionOutcome =
            change(withTasks = true) {
                val response = projectsApi.deleteProject(project.id, etag(project.version))
                if (!response.isSuccessful) throw HttpFailure(response.code())
            }

        private suspend fun change(
            withTasks: Boolean,
            block: suspend () -> Unit,
        ): ActionOutcome {
            val result = connectivity.refreshing(block)
            if (result != RefreshResult.Offline) reloadCaches(withTasks)
            return ActionOutcome.of(result)
        }

        /** The projects (stalled flags, next steps) and, after task changes, the cached task views. */
        private suspend fun reloadCaches(withTasks: Boolean) {
            projects.refresh()
            if (withTasks) {
                tasks.refresh(TaskView.NEXT)
                tasks.refresh(TaskView.TODAY)
            }
        }
    }
