package ch.sonensei.cockpit.gtd.projects

import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.models.CreateTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.NextStepRequest
import ch.sonensei.cockpit.gtd.data.ChangeTypes
import ch.sonensei.cockpit.gtd.data.GtdConnectivity
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdRefreshTriggers
import ch.sonensei.cockpit.gtd.data.GtdTask
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

/** The areas and the changes made in a project's detail: completing its tasks and choosing its next step. */
@Singleton
class ProjectWorkRepository
    @Inject
    constructor(
        private val projectsApi: ProjectsApi,
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

        /** Creates a next action titled [title] in [project] and makes it the next step. */
        suspend fun addNextStep(
            project: GtdProject,
            title: String,
        ): ActionOutcome =
            change(withTasks = true) {
                val task =
                    body(tasksApi.createTask(CreateTaskRequest(title = title, list = CreateTaskRequest.List.NEXT, projectId = project.id)))
                // Adding a task may change the project, so the next step is set on its current version.
                val current = body(projectsApi.getProject(project.id))
                body(projectsApi.setProjectNextStep(project.id, NextStepRequest(task.id), etag(current.version)))
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
