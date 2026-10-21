package ch.sonensei.cockpit.gtd.projects

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.ActionOutcome
import ch.sonensei.cockpit.gtd.lists.ActionSnackbar
import ch.sonensei.cockpit.gtd.lists.Live
import ch.sonensei.cockpit.gtd.lists.LoadError
import ch.sonensei.cockpit.gtd.lists.TaskListsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val home = Area(id(90), "Home", 1)
    private val work = Area(id(91), "Work", 0)
    private val book = GtdTask(id(1), "Book the flights", TaskList.NEXT, 0, 1, projectId = id(10))
    private val hotel = GtdTask(id(2), "Compare hotels", TaskList.NEXT, 1, 1, projectId = id(10))
    private val holiday =
        GtdProject(id(10), "Plan holiday", ProjectStatus.ACTIVE, stalled = false, version = 2, areaId = home.id, nextTaskId = book.id)
    private val garden = GtdProject(id(11), "Redo the garden", ProjectStatus.ACTIVE, stalled = true, version = 2, areaId = home.id)
    private val report = GtdProject(id(12), "Annual report", ProjectStatus.ACTIVE, stalled = false, version = 2, areaId = work.id)
    private val move = GtdProject(id(13), "Move house", ProjectStatus.COMPLETED, stalled = false, version = 2)
    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")

    private val projectFlow = MutableStateFlow(Cached(listOf(holiday, garden, report, move), offline = false, refreshedAt = loadedAt))
    private val projects = mockk<ProjectRepository> { every { observe() } returns projectFlow }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, loadedAt)) }
    private val taskFlow = MutableStateFlow(Live(listOf(book, hotel)))
    private val taskReloads = mutableListOf<Flow<Unit>>()
    private val lists = mockk<TaskListsRepository> { every { observeProject(any(), capture(taskReloads)) } returns taskFlow }
    private val work0 = mockk<ProjectWorkRepository> { every { observeAreas(any()) } returns MutableStateFlow(Live(listOf(home, work))) }
    private val clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(ProjectsViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = ProjectsViewModel(projects, contexts, lists, work0, clock)
            backgroundScope.launch { viewModel.uiState.collect {} }
            block(viewModel)
        }

    @Test
    fun `groups by area in area order, stalled first, finished hidden until asked`() =
        test { vm ->
            val groups = vm.uiState.value.groups
            assertEquals(listOf(work, home), groups.map { it.area })
            assertEquals(listOf(garden, holiday), groups[1].projects)
            assertEquals(1, groups[1].stalled)
            assertEquals(1, vm.uiState.value.finished)
            vm.toggleFinished()
            assertEquals(
                listOf(work, home, null),
                vm.uiState.value.groups
                    .map { it.area },
            )
        }

    @Test
    fun `opening a project shows its tasks and next step`() =
        test { vm ->
            vm.open(holiday)
            val detail = vm.uiState.value.detail!!
            assertEquals(listOf(book, hotel), detail.tasks)
            assertEquals(book, detail.nextStep)
            assertEquals(home, detail.area)
            vm.close()
            assertNull(vm.uiState.value.detail)
        }

    @Test
    fun `completing the next step opens the choose-next-step prompt`() =
        test { vm ->
            coEvery { work0.complete(book) } returns ProjectCompletion.Completed(needsNextStep = true)
            vm.open(holiday)
            vm.complete(book)
            val detail = vm.uiState.value.detail!!
            assertEquals(NextStepPrompt(done = "Book the flights"), detail.prompt)
            assertEquals(listOf(hotel), detail.tasks)
        }

    @Test
    fun `completing another task does not prompt`() =
        test { vm ->
            coEvery { work0.complete(hotel) } returns ProjectCompletion.Completed(needsNextStep = false)
            vm.open(holiday)
            vm.complete(hotel)
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
            assertEquals(ActionSnackbar(ActionSnackbar.Kind.COMPLETED, "Compare hotels", ActionOutcome.DONE), vm.uiState.value.message)
        }

    @Test
    fun `a refused completion keeps the task and says why`() =
        test { vm ->
            coEvery { work0.complete(book) } returns ProjectCompletion.NotDone(ActionOutcome.CONFLICT)
            vm.open(holiday)
            vm.complete(book)
            assertEquals(
                listOf(book, hotel),
                vm.uiState.value.detail!!
                    .tasks,
            )
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
            assertEquals(
                ActionOutcome.CONFLICT,
                vm.uiState.value.message!!
                    .outcome,
            )
        }

    @Test
    fun `choosing a task in the prompt sets the next step and closes it`() =
        test { vm ->
            coEvery { work0.setNextStep(garden, hotel.id) } returns ActionOutcome.DONE
            vm.open(garden)
            vm.choose()
            vm.makeNextStep(hotel)
            coVerify { work0.setNextStep(garden, hotel.id) }
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
        }

    @Test
    fun `adding a new next step creates it, a blank title does nothing, failure keeps the prompt`() =
        test { vm ->
            coEvery { work0.addNextStep(garden, "Buy seeds") } returns ActionOutcome.OFFLINE
            vm.open(garden)
            vm.choose()
            vm.addNextStep("   ")
            coVerify(exactly = 0) { work0.addNextStep(any(), any()) }
            vm.addNextStep(" Buy seeds ")
            assertEquals(
                NextStepPrompt(),
                vm.uiState.value.detail!!
                    .prompt,
            )
            assertEquals(
                ActionOutcome.OFFLINE,
                vm.uiState.value.message!!
                    .outcome,
            )
            vm.skip()
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
        }

    @Test
    fun `offline nothing is changed`() =
        test { vm ->
            projectFlow.value = projectFlow.value.copy(offline = true)
            vm.open(holiday)
            vm.complete(book)
            coVerify(exactly = 0) { work0.complete(any()) }
        }

    private fun TestScope.countReloads(): () -> Int {
        var count = 0
        backgroundScope.launch { taskReloads.last().collect { count++ } }
        return { count }
    }

    @Test
    fun `a completion runs once at a time, the task is busy meanwhile, then the tasks reload`() =
        test { vm ->
            val answer = CompletableDeferred<ProjectCompletion>()
            coEvery { work0.complete(book) } coAnswers { answer.await() }
            vm.open(holiday)
            val reloaded = countReloads()
            vm.complete(book)
            vm.complete(book)
            assertEquals(setOf(book.id), vm.uiState.value.busy)
            answer.complete(ProjectCompletion.Completed(needsNextStep = false))
            coVerify(exactly = 1) { work0.complete(book) }
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
            assertEquals(1, reloaded())
        }

    @Test
    fun `a next step that could not be set keeps the prompt and says why`() =
        test { vm ->
            coEvery { work0.setNextStep(garden, hotel.id) } returns ActionOutcome.CONFLICT
            vm.open(garden)
            vm.choose()
            vm.makeNextStep(hotel)
            assertEquals(
                NextStepPrompt(),
                vm.uiState.value.detail!!
                    .prompt,
            )
            assertEquals(ActionSnackbar(ActionSnackbar.Kind.NEXT_STEP, "Compare hotels", ActionOutcome.CONFLICT), vm.uiState.value.message)
            vm.dismiss(vm.uiState.value.message!!)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `an added next step closes the prompt with the trimmed title`() =
        test { vm ->
            coEvery { work0.addNextStep(garden, "Buy seeds") } returns ActionOutcome.DONE
            vm.open(garden)
            vm.choose()
            vm.addNextStep("  Buy seeds\t")
            coVerify(exactly = 1) { work0.addNextStep(garden, "Buy seeds") }
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
            assertEquals(ActionSnackbar(ActionSnackbar.Kind.NEXT_STEP, "Buy seeds", ActionOutcome.DONE), vm.uiState.value.message)
        }

    @Test
    fun `without an open project the next-step actions do nothing`() =
        test { vm ->
            vm.makeNextStep(hotel)
            vm.addNextStep("Buy seeds")
            coVerify(exactly = 0) { work0.setNextStep(any(), any()) }
            coVerify(exactly = 0) { work0.addNextStep(any(), any()) }
        }

    @Test
    fun `a detail whose tasks could not reload is read-only`() =
        test { vm ->
            taskFlow.value = Live(listOf(book, hotel), LoadError.FAILED)
            vm.open(holiday)
            val state = vm.uiState.value
            assertTrue(state.readOnly)
            assertEquals(
                listOf(book, hotel),
                state.detail!!.tasks,
            )
            vm.complete(book)
            vm.makeNextStep(hotel)
            coVerify(exactly = 0) { work0.complete(any()) }
            coVerify(exactly = 0) { work0.setNextStep(any(), any()) }
        }

    @Test
    fun `a detail is loading until its tasks arrive, and a first failure is an error`() =
        test { vm ->
            taskFlow.value = Live()
            vm.open(holiday)
            assertEquals(
                true,
                vm.uiState.value.detail!!
                    .loading,
            )
            assertEquals(
                false,
                vm.uiState.value.detail!!
                    .loaded,
            )
            taskFlow.value = Live(error = LoadError.OFFLINE)
            val detail = vm.uiState.value.detail!!
            assertEquals(false, detail.loading)
            assertEquals(LoadError.OFFLINE, detail.error)
            assertNull(detail.nextStep)
            val reloaded = countReloads()
            vm.retry()
            assertEquals(1, reloaded())
        }

    @Test
    fun `opening another project or going back drops the prompt`() =
        test { vm ->
            vm.open(garden)
            vm.choose()
            vm.open(holiday)
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
            vm.choose()
            vm.close()
            vm.open(holiday)
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
        }

    @Test
    fun `a project removed from the list closes its detail`() =
        test { vm ->
            vm.open(garden)
            projectFlow.value = projectFlow.value.copy(data = listOf(holiday, report, move))
            assertNull(vm.uiState.value.detail)
        }

    @Test
    fun `an unknown area puts the project in the group without area, and finished can be hidden again`() =
        test { vm ->
            val orphan = report.copy(areaId = id(99))
            projectFlow.value = projectFlow.value.copy(data = listOf(orphan, move))
            assertEquals(
                listOf(null),
                vm.uiState.value.groups
                    .map { it.area },
            )
            assertEquals(
                listOf(orphan),
                vm.uiState.value.groups
                    .single()
                    .projects,
            )
            vm.toggleFinished()
            assertEquals(
                listOf(orphan, move),
                vm.uiState.value.groups
                    .single()
                    .projects,
            )
            vm.toggleFinished()
            assertEquals(
                listOf(orphan),
                vm.uiState.value.groups
                    .single()
                    .projects,
            )
            assertEquals(1, vm.uiState.value.finished)
        }

    @Test
    fun `the list is loading until projects arrive, offline it is read-only`() =
        test { vm ->
            projectFlow.value = Cached(emptyList(), offline = false, refreshedAt = null)
            assertTrue(vm.uiState.value.loading)
            projectFlow.value = Cached(emptyList(), offline = true, refreshedAt = null)
            assertEquals(false, vm.uiState.value.loading)
            assertTrue(vm.uiState.value.readOnly)
        }
}
