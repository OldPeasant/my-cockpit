package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
import ch.sonensei.cockpit.core.network.generated.models.CreateTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.MoveTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.NextStepRequest
import ch.sonensei.cockpit.core.network.generated.models.ProjectPatchRequest
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import retrofit2.Response
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A task of the waiting-for or next-actions step. */
data class ReviewTask(
    val id: UUID,
    val title: String,
    /** Sent back as `If-Match` when the task is changed. */
    val version: Long = 0,
    val projectId: UUID? = null,
    /** Whom it waits for (waiting for only). */
    val waitingForText: String? = null,
    val waitingSince: LocalDate? = null,
    val updatedAt: Instant? = null,
)

/** A task or project of the someday/maybe step. */
data class SomedayItem(
    val id: UUID,
    val project: Boolean,
    val title: String,
    /** Sent back as `If-Match` when the item is changed. */
    val version: Long = 0,
)

/**
 * The items of the weekly review's action steps (waiting for, projects, stale next actions, someday/maybe) and the
 * decisions on them, matching the web wizard: each decision is one server call (two for a project's new next step).
 */
@Singleton
class ReviewActions
    @Inject
    constructor(
        private val reviews: WeeklyReviewApi,
        private val tasks: TasksApi,
        private val projects: ProjectsApi,
    ) {
        suspend fun waitingFor(): ReviewResult<List<ReviewTask>> = load { body(reviews.getReviewWaitingFor()).items.map { it.toTask() } }

        /** Active projects, stalled ones first (the server's order). */
        suspend fun projects(): ReviewResult<List<ReviewProject>> = load { body(reviews.getReviewProjects()).items.map { it.toProject() } }

        /** Next actions not changed for more than [STALE_DAYS] days. */
        suspend fun staleNextActions(): ReviewResult<List<ReviewTask>> =
            load { body(reviews.getReviewStaleNextActions(STALE_DAYS)).items.map { it.toTask() } }

        /** The someday/maybe tasks, then the someday projects. */
        suspend fun someday(): ReviewResult<List<SomedayItem>> =
            load {
                val someday = body(reviews.getReviewSomeday())
                someday.tasks.map { SomedayItem(it.id, project = false, it.title, it.version) } +
                    someday.projects.map { SomedayItem(it.id, project = true, it.title, it.version) }
            }

        /** Adds a next action titled [title] to chase [task], in the task's project. */
        suspend fun followUp(
            task: ReviewTask,
            title: String,
        ): ReviewResult<Unit> =
            load {
                body(tasks.createTask(CreateTaskRequest(title, list = CreateTaskRequest.List.NEXT, projectId = task.projectId)))
                Unit
            }

        suspend fun complete(task: ReviewTask): ReviewResult<Unit> =
            load {
                body(tasks.completeTask(task.id, etag(task.version)))
                Unit
            }

        suspend fun moveToSomeday(task: ReviewTask): ReviewResult<Unit> = move(task.id, task.version, MoveTaskRequest.List.SOMEDAY)

        /** Moves the task to the trash. */
        suspend fun delete(task: ReviewTask): ReviewResult<Unit> = load { ok(tasks.deleteTask(task.id, etag(task.version))) }

        /** Creates [title] as a next action of [project] and makes it the project's next step. */
        suspend fun addNextStep(
            project: ReviewProject,
            title: String,
        ): NextStepOutcome {
            val created = load { body(tasks.createTask(CreateTaskRequest(title, list = CreateTaskRequest.List.NEXT, projectId = project.id))) }
            if (created !is ReviewResult.Ok) return NextStepOutcome(created, taskCreated = false)
            val updated = load { body(projects.setProjectNextStep(project.id, NextStepRequest(created.value.id))).toProject() }
            return NextStepOutcome(updated, taskCreated = true)
        }

        suspend fun setStatus(
            project: ReviewProject,
            status: ProjectStatus,
        ): ReviewResult<Unit> = updateStatus(project.id, project.version, status)

        /** A task becomes a next action, a project becomes active. */
        suspend fun activate(item: SomedayItem): ReviewResult<Unit> =
            if (item.project) {
                updateStatus(item.id, item.version, ProjectStatus.ACTIVE)
            } else {
                move(item.id, item.version, MoveTaskRequest.List.NEXT)
            }

        /** Moves the task or project to the trash. */
        suspend fun delete(item: SomedayItem): ReviewResult<Unit> =
            load {
                ok(if (item.project) projects.deleteProject(item.id, etag(item.version)) else tasks.deleteTask(item.id, etag(item.version)))
            }

        private suspend fun move(
            id: UUID,
            version: Long,
            list: MoveTaskRequest.List,
        ): ReviewResult<Unit> =
            load {
                body(tasks.moveTask(id, MoveTaskRequest(list), etag(version)))
                Unit
            }

        private suspend fun updateStatus(
            id: UUID,
            version: Long,
            status: ProjectStatus,
        ): ReviewResult<Unit> =
            load {
                body(projects.updateProject(id, ProjectPatchRequest(status = ProjectPatchRequest.Status.valueOf(status.name)), etag(version)))
                Unit
            }

        companion object {
            /** Next actions untouched for longer than this are reviewed (SPEC.md §8.6). */
            const val STALE_DAYS = 30
        }
    }

/** The outcome of adding a project's next step: [result] is the updated project, or why it failed. */
data class NextStepOutcome(
    val result: ReviewResult<ReviewProject>,
    /** The task was created, also when making it the next step failed: it must not be created a second time. */
    val taskCreated: Boolean,
)

private fun TaskResponse.toTask() = ReviewTask(id, title, version, projectId, waitingForText, waitingSince, updatedAt.toInstant())

/** An answer without a body (204): only its status counts. */
private fun ok(response: Response<Unit>) {
    if (!response.isSuccessful) throw ReviewHttpFailure(response.code())
}
