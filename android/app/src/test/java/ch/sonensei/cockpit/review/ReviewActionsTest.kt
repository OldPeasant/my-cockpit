package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
import ch.sonensei.cockpit.core.network.generated.models.CompletedTaskResponse
import ch.sonensei.cockpit.core.network.generated.models.CreateTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.MoveTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.NextStepRequest
import ch.sonensei.cockpit.core.network.generated.models.ProjectPatchRequest
import ch.sonensei.cockpit.core.network.generated.models.ProjectResponse
import ch.sonensei.cockpit.core.network.generated.models.ReviewProjectListResponse
import ch.sonensei.cockpit.core.network.generated.models.ReviewSomedayResponse
import ch.sonensei.cockpit.core.network.generated.models.ReviewTaskListResponse
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The server calls behind the decisions of the review's action steps (waiting for, projects, next actions, someday). */
class ReviewActionsTest {
    private val reviews = mockk<WeeklyReviewApi>()
    private val tasks = mockk<TasksApi>()
    private val projects = mockk<ProjectsApi>()
    private val actions = ReviewActions(reviews, tasks, projects)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val at = OffsetDateTime.of(2026, 9, 1, 8, 0, 0, 0, ZoneOffset.UTC)

    private fun taskResponse(
        n: Int,
        title: String,
        version: Long = 3,
        list: TaskResponse.List = TaskResponse.List.NEXT,
        projectId: UUID? = null,
        waitingFor: String? = null,
        since: LocalDate? = null,
    ) = TaskResponse(
        contextIds = emptyList(),
        createdAt = at,
        id = id(n),
        list = list,
        position = 0,
        title = title,
        updatedAt = at,
        version = version,
        projectId = projectId,
        waitingForText = waitingFor,
        waitingSince = since,
    )

    private fun projectResponse(
        n: Int,
        title: String,
        status: ProjectResponse.Status = ProjectResponse.Status.ACTIVE,
        stalled: Boolean = false,
        version: Long = 5,
    ) = ProjectResponse(createdAt = at, id = id(n), stalled = stalled, status = status, title = title, updatedAt = at, version = version)

    private fun completed(n: Int) =
        CompletedTaskResponse(
            contextIds = emptyList(),
            createdAt = at,
            id = id(n),
            list = CompletedTaskResponse.List.WAITING,
            needsNextStep = false,
            position = 0,
            title = "done",
            updatedAt = at,
            version = 4,
        )

    private fun <T> error(code: Int): Response<T> = Response.error(code, "{}".toResponseBody())

    private val parcel = ReviewTask(id(1), "Parcel from the fictitious shop", version = 7, projectId = id(10), waitingForText = "Erika")
    private val garden = ReviewProject(id(10), "Redo the garden", stalled = true, status = ProjectStatus.ACTIVE, version = 9)

    @Test
    fun `waiting for maps the tasks with whom, since, version and project`() =
        runTest {
            coEvery { reviews.getReviewWaitingFor() } returns
                Response.success(
                    ReviewTaskListResponse(
                        listOf(taskResponse(1, "Parcel", 7, TaskResponse.List.WAITING, id(10), "Erika", LocalDate.of(2026, 10, 1))),
                    ),
                )

            val result = actions.waitingFor()

            assertEquals(
                ReviewResult.Ok(
                    listOf(ReviewTask(id(1), "Parcel", 7, id(10), "Erika", LocalDate.of(2026, 10, 1), at.toInstant())),
                ),
                result,
            )
        }

    @Test
    fun `projects map status and version, stale next actions ask for 30 days`() =
        runTest {
            coEvery { reviews.getReviewProjects() } returns
                Response.success(
                    ReviewProjectListResponse(listOf(projectResponse(10, "Redo the garden", stalled = true, version = 9))),
                )
            val stale = ReviewTaskListResponse(listOf(taskResponse(2, "Fix bike")))
            coEvery { reviews.getReviewStaleNextActions(30) } returns Response.success(stale)

            assertEquals(ReviewResult.Ok(listOf(garden)), actions.projects())
            assertEquals(listOf("Fix bike"), (actions.staleNextActions() as ReviewResult.Ok).value.map { it.title })
            coVerify(exactly = 1) { reviews.getReviewStaleNextActions(ReviewActions.STALE_DAYS) }
        }

    @Test
    fun `someday lists the tasks first, then the projects`() =
        runTest {
            coEvery { reviews.getReviewSomeday() } returns
                Response.success(
                    ReviewSomedayResponse(
                        projects = listOf(projectResponse(20, "Learn Italian", ProjectResponse.Status.SOMEDAY, version = 2)),
                        tasks = listOf(taskResponse(3, "Visit Lisbon", 4, TaskResponse.List.SOMEDAY)),
                    ),
                )

            assertEquals(
                ReviewResult.Ok(
                    listOf(SomedayItem(id(3), project = false, "Visit Lisbon", 4), SomedayItem(id(20), project = true, "Learn Italian", 2)),
                ),
                actions.someday(),
            )
        }

    @Test
    fun `a load that fails says offline for no connection or a server error, failed for a refusal`() =
        runTest {
            coEvery { reviews.getReviewWaitingFor() } throws IOException("no network")
            coEvery { reviews.getReviewProjects() } returns error(503)
            coEvery { reviews.getReviewStaleNextActions(any()) } returns error(403)
            coEvery { reviews.getReviewSomeday() } returns Response.success<ReviewSomedayResponse>(200, null)

            assertEquals(ReviewResult.Offline, actions.waitingFor())
            assertEquals(ReviewResult.Offline, actions.projects())
            assertEquals(ReviewResult.Failed(403), actions.staleNextActions())
            assertEquals(ReviewResult.Failed(200), actions.someday())
        }

    @Test
    fun `follow up creates a next action in the waiting task's project, and leaves the waiting task alone`() =
        runTest {
            coEvery { tasks.createTask(any()) } returns Response.success(taskResponse(30, "Ask Erika about: Parcel"))

            assertEquals(ReviewResult.Ok(Unit), actions.followUp(parcel, "Ask Erika about: Parcel"))

            coVerify {
                tasks.createTask(CreateTaskRequest("Ask Erika about: Parcel", list = CreateTaskRequest.List.NEXT, projectId = id(10)))
            }
            confirmVerified(tasks)
        }

    @Test
    fun `follow up of a waiting task outside any project creates it without a project, and a refusal is reported`() =
        runTest {
            val loose = parcel.copy(projectId = null)
            coEvery { tasks.createTask(any()) } returns Response.success(taskResponse(31, "Follow up: Parcel")) andThen error(422)

            assertEquals(ReviewResult.Ok(Unit), actions.followUp(loose, "Follow up: Parcel"))
            assertEquals(ReviewResult.Failed(422), actions.followUp(loose, "Follow up: Parcel"))
            coVerify(exactly = 2) {
                tasks.createTask(CreateTaskRequest("Follow up: Parcel", list = CreateTaskRequest.List.NEXT, projectId = null))
            }
        }

    @Test
    fun `every project status is sent as the same patch status and read back from a project`() =
        runTest {
            for (status in ProjectStatus.entries) {
                val patch = ProjectPatchRequest(status = ProjectPatchRequest.Status.valueOf(status.name))
                val response = projectResponse(10, "Redo the garden", ProjectResponse.Status.valueOf(status.name))
                coEvery { projects.updateProject(id(10), patch, "\"9\"") } returns Response.success(response)
                coEvery { reviews.getReviewProjects() } returns Response.success(ReviewProjectListResponse(listOf(response)))

                assertEquals(status.name, ReviewResult.Ok(Unit), actions.setStatus(garden, status))
                coVerify(exactly = 1) { projects.updateProject(id(10), patch, "\"9\"") }
                assertEquals(status.name, status, (actions.projects() as ReviewResult.Ok).value.single().status)
            }
        }

    @Test
    fun `activating a someday project that changed elsewhere is refused with its status`() =
        runTest {
            coEvery { projects.updateProject(any(), any(), any()) } returns error(412)
            coEvery { tasks.moveTask(any(), any(), any()) } throws IOException("no network")

            assertEquals(ReviewResult.Failed(412), actions.activate(SomedayItem(id(20), project = true, "Learn Italian", 2)))
            assertEquals(ReviewResult.Offline, actions.activate(SomedayItem(id(3), project = false, "Visit Lisbon", 4)))
            coVerify(exactly = 0) { tasks.deleteTask(any(), any()) }
        }

    @Test
    fun `complete sends the task's version as If-Match`() =
        runTest {
            coEvery { tasks.completeTask(id(1), "\"7\"") } returns Response.success(completed(1))

            assertEquals(ReviewResult.Ok(Unit), actions.complete(parcel))
            coVerify(exactly = 1) { tasks.completeTask(id(1), "\"7\"") }
        }

    @Test
    fun `a stale version is refused with its status, so the step can load again`() =
        runTest {
            coEvery { tasks.completeTask(any(), any()) } returns error(412)
            coEvery { tasks.moveTask(any(), any(), any()) } returns error(404)
            coEvery { tasks.deleteTask(any(), any()) } returns error(409)

            assertEquals(ReviewResult.Failed(412), actions.complete(parcel))
            assertEquals(ReviewResult.Failed(404), actions.moveToSomeday(parcel))
            assertEquals(ReviewResult.Failed(409), actions.delete(parcel))
        }

    @Test
    fun `move to someday moves the task with If-Match`() =
        runTest {
            coEvery { tasks.moveTask(id(1), MoveTaskRequest(MoveTaskRequest.List.SOMEDAY), "\"7\"") } returns
                Response.success(taskResponse(1, "Parcel", 8, TaskResponse.List.SOMEDAY))

            assertEquals(ReviewResult.Ok(Unit), actions.moveToSomeday(parcel))
        }

    @Test
    fun `delete accepts a 204 without a body and sends If-Match`() =
        runTest {
            coEvery { tasks.deleteTask(id(1), "\"7\"") } returns Response.success<Unit>(204, null)

            assertEquals(ReviewResult.Ok(Unit), actions.delete(parcel))
            coVerify(exactly = 1) { tasks.deleteTask(id(1), "\"7\"") }
        }

    @Test
    fun `delete of a someday item goes to the tasks or the projects endpoint`() =
        runTest {
            coEvery { tasks.deleteTask(id(3), "\"4\"") } returns Response.success<Unit>(204, null)
            coEvery { projects.deleteProject(id(20), "\"2\"") } returns Response.success<Unit>(204, null)

            assertEquals(ReviewResult.Ok(Unit), actions.delete(SomedayItem(id(3), project = false, "Visit Lisbon", 4)))
            assertEquals(ReviewResult.Ok(Unit), actions.delete(SomedayItem(id(20), project = true, "Learn Italian", 2)))
            coVerify(exactly = 1) { tasks.deleteTask(id(3), "\"4\"") }
            coVerify(exactly = 1) { projects.deleteProject(id(20), "\"2\"") }
        }

    @Test
    fun `a refused or offline delete is reported, never as success`() =
        runTest {
            coEvery { projects.deleteProject(any(), any()) } returns error(412)
            coEvery { tasks.deleteTask(any(), any()) } returns error(502)

            assertEquals(ReviewResult.Failed(412), actions.delete(SomedayItem(id(20), project = true, "Learn Italian", 2)))
            assertEquals(ReviewResult.Offline, actions.delete(SomedayItem(id(3), project = false, "Visit Lisbon", 4)))
        }

    @Test
    fun `activate moves a task to Next and makes a project active, both with If-Match`() =
        runTest {
            coEvery { tasks.moveTask(id(3), MoveTaskRequest(MoveTaskRequest.List.NEXT), "\"4\"") } returns
                Response.success(taskResponse(3, "Visit Lisbon", 5))
            coEvery {
                projects.updateProject(id(20), ProjectPatchRequest(status = ProjectPatchRequest.Status.ACTIVE), "\"2\"")
            } returns Response.success(projectResponse(20, "Learn Italian", version = 3))

            assertEquals(ReviewResult.Ok(Unit), actions.activate(SomedayItem(id(3), project = false, "Visit Lisbon", 4)))
            assertEquals(ReviewResult.Ok(Unit), actions.activate(SomedayItem(id(20), project = true, "Learn Italian", 2)))
        }

    @Test
    fun `set status patches only the status, with the project's version`() =
        runTest {
            coEvery {
                projects.updateProject(id(10), ProjectPatchRequest(status = ProjectPatchRequest.Status.ON_HOLD), "\"9\"")
            } returns Response.success(projectResponse(10, "Redo the garden", ProjectResponse.Status.ON_HOLD, version = 10))

            assertEquals(ReviewResult.Ok(Unit), actions.setStatus(garden, ProjectStatus.ON_HOLD))
            coVerify(exactly = 1) {
                projects.updateProject(id(10), ProjectPatchRequest(status = ProjectPatchRequest.Status.ON_HOLD), "\"9\"")
            }
        }

    @Test
    fun `add next step creates the task in the project, then makes it the next step`() =
        runTest {
            coEvery { tasks.createTask(any()) } returns Response.success(taskResponse(40, "Buy seeds", projectId = id(10)))
            coEvery { projects.setProjectNextStep(id(10), NextStepRequest(id(40)), any()) } returns
                Response.success(projectResponse(10, "Redo the garden", stalled = false, version = 10))

            val outcome = actions.addNextStep(garden, "Buy seeds")

            assertEquals(
                NextStepOutcome(ReviewResult.Ok(garden.copy(stalled = false, version = 10)), taskCreated = true),
                outcome,
            )
            coVerify(exactly = 1) {
                tasks.createTask(CreateTaskRequest("Buy seeds", list = CreateTaskRequest.List.NEXT, projectId = id(10)))
            }
        }

    @Test
    fun `add next step that cannot create the task says no task was created and never sets a next step`() =
        runTest {
            coEvery { tasks.createTask(any()) } throws IOException("no network") andThen error(422)

            assertEquals(NextStepOutcome(ReviewResult.Offline, taskCreated = false), actions.addNextStep(garden, "Buy seeds"))
            assertEquals(NextStepOutcome(ReviewResult.Failed(422), taskCreated = false), actions.addNextStep(garden, "Buy seeds"))
            coVerify(exactly = 0) { projects.setProjectNextStep(any(), any(), any()) }
        }

    @Test
    fun `add next step whose second call fails still says the task was created`() =
        runTest {
            coEvery { tasks.createTask(any()) } returns Response.success(taskResponse(40, "Buy seeds", projectId = id(10)))
            coEvery { projects.setProjectNextStep(any(), any(), any()) } returns error(404) andThenThrows IOException("gone")

            assertEquals(NextStepOutcome(ReviewResult.Failed(404), taskCreated = true), actions.addNextStep(garden, "Buy seeds"))
            assertEquals(NextStepOutcome(ReviewResult.Offline, taskCreated = true), actions.addNextStep(garden, "Buy seeds"))
        }
}
