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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val areaFlow = MutableStateFlow(Live(listOf(home, work)))
    private val work0 = mockk<ProjectWorkRepository> { every { observeAreas(any()) } returns areaFlow }
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
            assertEquals(NextStepPrompt(holiday.id, done = "Book the flights"), detail.prompt)
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
            coEvery { work0.addNextStep(garden, "Buy seeds") } returns NextStepAdded(ActionOutcome.OFFLINE, null)
            vm.open(garden)
            vm.choose()
            vm.addNextStep("   ")
            coVerify(exactly = 0) { work0.addNextStep(any(), any(), any()) }
            vm.addNextStep(" Buy seeds ")
            assertEquals(
                NextStepPrompt(garden.id),
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
                NextStepPrompt(garden.id),
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
            coEvery { work0.addNextStep(garden, "Buy seeds") } returns NextStepAdded(ActionOutcome.DONE, id(5))
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
            coVerify(exactly = 0) { work0.addNextStep(any(), any(), any()) }
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

    @Test
    fun `cached projects show at once without their areas while the areas still load, without an error`() {
        areaFlow.value = Live()
        test { vm ->
            val pending = vm.uiState.value
            assertTrue(pending.areasLoading)
            assertEquals(false, pending.loading)
            assertNull(pending.areasError)
            assertEquals(listOf(ProjectGroup(null, listOf(garden, report, holiday), stalled = 1, areaUnloaded = true)), pending.groups)
            areaFlow.value = Live(listOf(home, work))
            assertEquals(
                listOf(work, home),
                vm.uiState.value.groups
                    .map { it.area },
            )
            assertEquals(false, vm.uiState.value.areasLoading)
        }
    }

    @Test
    fun `without cached projects the list shows loading while the areas still load`() {
        areaFlow.value = Live()
        projectFlow.value = Cached(emptyList(), offline = false, refreshedAt = loadedAt)
        test { vm ->
            assertTrue(vm.uiState.value.loading)
            areaFlow.value = Live(listOf(home, work))
            assertEquals(false, vm.uiState.value.loading)
        }
    }

    @Test
    fun `areas that cannot load keep projects out of No area, with an error`() {
        areaFlow.value = Live()
        test { vm ->
            assertNull(vm.uiState.value.areasError)
            areaFlow.value = Live(error = LoadError.FAILED)
            val failed = vm.uiState.value
            assertEquals(false, failed.loading)
            assertEquals(LoadError.FAILED, failed.areasError)
            assertEquals(listOf(ProjectGroup(null, listOf(garden, report, holiday), stalled = 1, areaUnloaded = true)), failed.groups)
            areaFlow.value = Live(listOf(home, work))
            assertEquals(
                listOf(work, home),
                vm.uiState.value.groups
                    .map { it.area },
            )
            assertNull(vm.uiState.value.areasError)
        }
    }

    @Test
    fun `areas loaded once stay in use when a later load fails`() =
        test { vm ->
            areaFlow.value = Live(listOf(home, work), LoadError.OFFLINE)
            assertEquals(
                listOf(work, home),
                vm.uiState.value.groups
                    .map { it.area },
            )
            // The area load starts over (e.g. the screen came back) and fails before any data.
            areaFlow.value = Live(error = LoadError.OFFLINE)
            assertEquals(
                listOf(work, home),
                vm.uiState.value.groups
                    .map { it.area },
            )
            assertNull(vm.uiState.value.areasError)
            vm.open(holiday)
            assertEquals(
                home,
                vm.uiState.value.detail!!
                    .area,
            )
        }

    @Test
    fun `retrying a new next step whose task was created but not set only sets it`() =
        test { vm ->
            coEvery { work0.addNextStep(garden, "Buy seeds", null) } returns NextStepAdded(ActionOutcome.CONFLICT, id(5))
            coEvery { work0.addNextStep(garden, "Buy seeds", id(5)) } returns NextStepAdded(ActionOutcome.DONE, id(5))
            vm.open(garden)
            vm.choose()
            vm.addNextStep("Buy seeds")
            assertEquals(
                NextStepPrompt(garden.id, created = CreatedStep("Buy seeds", id(5))),
                vm.uiState.value.detail!!
                    .prompt,
            )
            vm.addNextStep(" Buy seeds ")
            coVerify(exactly = 1) { work0.addNextStep(garden, "Buy seeds", null) }
            coVerify(exactly = 1) { work0.addNextStep(garden, "Buy seeds", id(5)) }
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
        }

    @Test
    fun `a different title after a partly failed add creates a new task`() =
        test { vm ->
            coEvery { work0.addNextStep(garden, "Buy seeds", null) } returns NextStepAdded(ActionOutcome.FAILED, id(5))
            coEvery { work0.addNextStep(garden, "Buy bulbs", null) } returns NextStepAdded(ActionOutcome.DONE, id(6))
            vm.open(garden)
            vm.choose()
            vm.addNextStep("Buy seeds")
            vm.addNextStep("Buy bulbs")
            coVerify(exactly = 1) { work0.addNextStep(garden, "Buy bulbs", null) }
            coVerify(exactly = 0) { work0.addNextStep(any(), any(), id(5)) }
        }

    @Test
    fun `a completion that returns after another project was opened does not prompt there`() =
        test { vm ->
            val answer = CompletableDeferred<ProjectCompletion>()
            coEvery { work0.complete(book) } coAnswers { answer.await() }
            vm.open(holiday)
            vm.complete(book)
            vm.open(garden)
            answer.complete(ProjectCompletion.Completed(needsNextStep = true))
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
            vm.open(holiday)
            assertNull(
                vm.uiState.value.detail!!
                    .prompt,
            )
        }

    @Test
    fun `the prompt belongs to the open project, and without one there is none`() =
        test { vm ->
            vm.open(holiday)
            vm.choose()
            assertEquals(
                holiday.id,
                vm.uiState.value.detail!!
                    .prompt!!
                    .projectId,
            )
            vm.close()
            vm.choose()
            assertNull(vm.uiState.value.detail)
        }

    private fun status(s: ProjectStatus) = JsonObject(mapOf("status" to JsonPrimitive(s.name)))

    @Test
    fun `the form's areas are the known areas in their order`() =
        test { vm ->
            assertEquals(listOf(work, home), vm.uiState.value.areas)
        }

    @Test
    fun `a new project needs a title, and typing one clears the error`() =
        test { vm ->
            vm.newProject()
            assertEquals(ProjectFormState(), vm.uiState.value.form)
            vm.editForm { it.copy(title = "   ") }
            vm.saveForm()
            assertTrue(
                vm.uiState.value.form!!
                    .titleMissing,
            )
            coVerify(exactly = 0) { work0.create(any()) }
            vm.editForm { it.copy(title = "Learn Italian") }
            assertEquals(
                false,
                vm.uiState.value.form!!
                    .titleMissing,
            )
        }

    @Test
    fun `a created project closes the form and opens the new project`() =
        test { vm ->
            coEvery { work0.create(any()) } returns ProjectCreated(ActionOutcome.DONE, garden.id)
            vm.newProject()
            vm.editForm { it.copy(title = " Redo the garden ", areaId = home.id) }
            vm.saveForm()
            coVerify(exactly = 1) { work0.create(ProjectDraft(title = " Redo the garden ", areaId = home.id)) }
            val state = vm.uiState.value
            assertNull(state.form)
            assertEquals(garden, state.detail!!.project)
            assertEquals(ProjectMessage(ProjectMessage.Kind.CREATED, "Redo the garden", ActionOutcome.DONE), state.projectMessage)
            vm.dismissProjectMessage(state.projectMessage!!)
            assertNull(vm.uiState.value.projectMessage)
        }

    @Test
    fun `a create that failed keeps the form to try again and says why`() =
        test { vm ->
            coEvery { work0.create(any()) } returns ProjectCreated(ActionOutcome.OFFLINE, null)
            vm.newProject()
            vm.editForm { it.copy(title = "Learn Italian") }
            vm.saveForm()
            val form = vm.uiState.value.form!!
            assertEquals("Learn Italian", form.draft.title)
            assertEquals(false, form.saving)
            assertNull(vm.uiState.value.detail)
            assertEquals(
                ActionOutcome.OFFLINE,
                vm.uiState.value.projectMessage!!
                    .outcome,
            )
        }

    @Test
    fun `a save runs once at a time`() =
        test { vm ->
            val answer = CompletableDeferred<ProjectCreated>()
            coEvery { work0.create(any()) } coAnswers { answer.await() }
            vm.newProject()
            vm.editForm { it.copy(title = "Learn Italian") }
            vm.saveForm()
            assertTrue(
                vm.uiState.value.form!!
                    .saving,
            )
            vm.saveForm()
            answer.complete(ProjectCreated(ActionOutcome.DONE, garden.id))
            coVerify(exactly = 1) { work0.create(any()) }
        }

    @Test
    fun `editing sends only the changed fields at the project's version, then closes the form`() =
        test { vm ->
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.DONE
            vm.open(holiday)
            vm.editProject()
            assertEquals(ProjectFormState.edit(holiday), vm.uiState.value.form)
            vm.editForm { it.copy(title = "Plan the holiday ", areaId = null) }
            vm.saveForm()
            coVerify(exactly = 1) {
                work0.update(holiday.id, 2, JsonObject(mapOf("title" to JsonPrimitive("Plan the holiday"), "areaId" to JsonNull)))
            }
            assertNull(vm.uiState.value.form)
            assertEquals(ProjectMessage(ProjectMessage.Kind.SAVED, "Plan the holiday", ActionOutcome.DONE), vm.uiState.value.projectMessage)
        }

    @Test
    fun `saving an unchanged edit closes the form without a request`() =
        test { vm ->
            vm.open(holiday)
            vm.editProject()
            vm.saveForm()
            assertNull(vm.uiState.value.form)
            coVerify(exactly = 0) { work0.update(any(), any(), any()) }
        }

    @Test
    fun `a save refused because the project changed elsewhere keeps the user's edits on the reloaded project`() =
        test { vm ->
            val current = holiday.copy(version = 3, areaId = work.id, descriptionMd = "Two weeks in Sicily")
            coEvery { work0.update(any(), any(), any()) } coAnswers {
                // The repository reloaded the projects before answering.
                projectFlow.value = projectFlow.value.copy(data = listOf(current, garden, report, move))
                ActionOutcome.CONFLICT
            }
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(title = "Plan the holiday") }
            vm.saveForm()
            val form = vm.uiState.value.form!!
            assertTrue(form.conflict)
            assertEquals(false, form.saving)
            assertEquals(3L, form.version)
            assertEquals("Plan the holiday", form.draft.title)
            assertEquals(work.id, form.draft.areaId)
            assertEquals("Two weeks in Sicily", form.draft.description)
            assertEquals(
                ActionOutcome.CONFLICT,
                vm.uiState.value.projectMessage!!
                    .outcome,
            )

            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.DONE
            vm.saveForm()
            coVerify(exactly = 1) { work0.update(holiday.id, 3, JsonObject(mapOf("title" to JsonPrimitive("Plan the holiday")))) }
            assertNull(vm.uiState.value.form)
        }

    @Test
    fun `a save refused because the project was trashed elsewhere closes the form`() =
        test { vm ->
            coEvery { work0.update(any(), any(), any()) } coAnswers {
                projectFlow.value = projectFlow.value.copy(data = listOf(garden, report, move))
                ActionOutcome.CONFLICT
            }
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(title = "Plan the holiday") }
            vm.saveForm()
            assertNull(vm.uiState.value.form)
            assertEquals(
                ActionOutcome.CONFLICT,
                vm.uiState.value.projectMessage!!
                    .outcome,
            )
        }

    @Test
    fun `a save that failed keeps the form as it was`() =
        test { vm ->
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.FAILED
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(title = "Plan the holiday") }
            vm.saveForm()
            val form = vm.uiState.value.form!!
            assertEquals(false, form.saving)
            assertEquals(false, form.conflict)
            assertEquals("Plan the holiday", form.draft.title)
        }

    @Test
    fun `a save that returns after its form was cancelled leaves the form opened since alone`() =
        test { vm ->
            val first = CompletableDeferred<ActionOutcome>()
            coEvery { work0.update(any(), any(), any()) } coAnswers { first.await() }
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(title = "Plan the holiday") }
            vm.saveForm()
            vm.cancelForm()
            vm.editProject()
            vm.editForm { it.copy(title = "Plan the summer holiday") }
            first.complete(ActionOutcome.DONE)
            val form = vm.uiState.value.form!!
            assertEquals("Plan the summer holiday", form.draft.title)
            assertEquals(false, form.saving)
            assertEquals(false, form.conflict)
            // The first save did happen: the user is told.
            assertEquals(ProjectMessage(ProjectMessage.Kind.SAVED, "Plan the holiday", ActionOutcome.DONE), vm.uiState.value.projectMessage)
        }

    @Test
    fun `a conflict that returns after its form was cancelled does not bring the old draft back`() =
        test { vm ->
            val first = CompletableDeferred<ActionOutcome>()
            coEvery { work0.update(any(), any(), any()) } coAnswers { first.await() }
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(title = "Plan the holiday") }
            vm.saveForm()
            vm.cancelForm()
            first.complete(ActionOutcome.CONFLICT)
            assertNull(vm.uiState.value.form)
            vm.editProject()
            vm.editForm { it.copy(title = "Plan the summer holiday") }
            assertEquals(
                false,
                vm.uiState.value.form!!
                    .conflict,
            )
        }

    @Test
    fun `a create that returns after its form was cancelled neither closes the next form nor opens the project`() =
        test { vm ->
            val first = CompletableDeferred<ProjectCreated>()
            coEvery { work0.create(any()) } coAnswers { first.await() }
            vm.newProject()
            vm.editForm { it.copy(title = "Learn Italian") }
            vm.saveForm()
            vm.cancelForm()
            vm.newProject()
            vm.editForm { it.copy(title = "Learn Spanish") }
            first.complete(ProjectCreated(ActionOutcome.DONE, garden.id))
            assertEquals(
                "Learn Spanish",
                vm.uiState.value.form!!
                    .draft.title,
            )
            assertNull(vm.uiState.value.detail)
            assertEquals(ProjectMessage(ProjectMessage.Kind.CREATED, "Learn Italian", ActionOutcome.DONE), vm.uiState.value.projectMessage)
        }

    @Test
    fun `finishing while the project's tasks are still loading asks first, without a count`() =
        test { vm ->
            taskFlow.value = Live()
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.DONE
            vm.open(holiday)
            vm.setStatus(ProjectStatus.COMPLETED)
            assertEquals(ProjectConfirm.Finish(holiday.id, ProjectStatus.COMPLETED, null, viaForm = false), vm.uiState.value.confirm)
            vm.cancelConfirm()
            vm.editProject()
            vm.editForm { it.copy(status = ProjectStatus.DROPPED) }
            vm.saveForm()
            assertEquals(ProjectConfirm.Finish(holiday.id, ProjectStatus.DROPPED, null, viaForm = true), vm.uiState.value.confirm)
            coVerify(exactly = 0) { work0.update(any(), any(), any()) }
            vm.confirm()
            coVerify(exactly = 1) { work0.update(holiday.id, 2, status(ProjectStatus.DROPPED)) }
        }

    @Test
    fun `a confirmation left open when its project disappeared does not apply to the next project opened`() =
        test { vm ->
            vm.open(holiday)
            vm.requestDelete()
            // Trashed elsewhere: the reload removes it while the dialog is open.
            projectFlow.value = projectFlow.value.copy(data = listOf(garden, report, move))
            assertNull(vm.uiState.value.detail)
            vm.open(garden)
            assertNull(vm.uiState.value.confirm)
            vm.confirm()
            coVerify(exactly = 0) { work0.delete(any()) }
        }

    @Test
    fun `a confirmation for a project that is no longer open is dropped`() =
        test { vm ->
            vm.open(holiday)
            vm.requestDelete()
            projectFlow.value = projectFlow.value.copy(data = listOf(garden, report, move))
            vm.confirm()
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 0) { work0.delete(any()) }
        }

    @Test
    fun `completing in the form with open tasks asks first, confirming saves`() =
        test { vm ->
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.DONE
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(status = ProjectStatus.COMPLETED) }
            vm.saveForm()
            assertEquals(ProjectConfirm.Finish(holiday.id, ProjectStatus.COMPLETED, 2, viaForm = true), vm.uiState.value.confirm)
            coVerify(exactly = 0) { work0.update(any(), any(), any()) }
            vm.confirm()
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 1) { work0.update(holiday.id, 2, status(ProjectStatus.COMPLETED)) }
            assertNull(vm.uiState.value.form)
        }

    @Test
    fun `cancelling the form also drops its pending confirmation`() =
        test { vm ->
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(status = ProjectStatus.DROPPED) }
            vm.saveForm()
            assertEquals(ProjectConfirm.Finish(holiday.id, ProjectStatus.DROPPED, 2, viaForm = true), vm.uiState.value.confirm)
            vm.cancelForm()
            assertNull(vm.uiState.value.form)
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 0) { work0.update(any(), any(), any()) }
        }

    @Test
    fun `finishing in the form without open tasks saves at once`() =
        test { vm ->
            taskFlow.value = Live(emptyList())
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.DONE
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(status = ProjectStatus.DROPPED) }
            vm.saveForm()
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 1) { work0.update(holiday.id, 2, status(ProjectStatus.DROPPED)) }
        }

    @Test
    fun `other status changes in the form need no confirmation`() =
        test { vm ->
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.DONE
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(status = ProjectStatus.ON_HOLD) }
            vm.saveForm()
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 1) { work0.update(holiday.id, 2, status(ProjectStatus.ON_HOLD)) }
        }

    @Test
    fun `completing from the menu with open tasks asks first, cancelling changes nothing`() =
        test { vm ->
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.DONE
            vm.open(holiday)
            vm.setStatus(ProjectStatus.COMPLETED)
            assertEquals(ProjectConfirm.Finish(holiday.id, ProjectStatus.COMPLETED, 2, viaForm = false), vm.uiState.value.confirm)
            vm.cancelConfirm()
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 0) { work0.update(any(), any(), any()) }

            vm.setStatus(ProjectStatus.COMPLETED)
            vm.confirm()
            coVerify(exactly = 1) { work0.update(holiday.id, 2, status(ProjectStatus.COMPLETED)) }
            assertEquals(ProjectMessage(ProjectMessage.Kind.COMPLETED, "Plan holiday", ActionOutcome.DONE), vm.uiState.value.projectMessage)
        }

    @Test
    fun `dropping from the menu without open tasks needs no confirmation`() =
        test { vm ->
            taskFlow.value = Live(emptyList())
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.CONFLICT
            vm.open(garden)
            vm.setStatus(ProjectStatus.DROPPED)
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 1) { work0.update(garden.id, 2, status(ProjectStatus.DROPPED)) }
            assertEquals(
                ProjectMessage(ProjectMessage.Kind.DROPPED, "Redo the garden", ActionOutcome.CONFLICT),
                vm.uiState.value.projectMessage,
            )
        }

    @Test
    fun `reactivating a finished project needs no confirmation, the current status is no change`() =
        test { vm ->
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.DONE
            vm.toggleFinished()
            vm.open(move)
            vm.setStatus(ProjectStatus.COMPLETED)
            coVerify(exactly = 0) { work0.update(any(), any(), any()) }
            vm.setStatus(ProjectStatus.ACTIVE)
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 1) { work0.update(move.id, 2, status(ProjectStatus.ACTIVE)) }
            assertEquals(
                ProjectMessage.Kind.REACTIVATED,
                vm.uiState.value.projectMessage!!
                    .kind,
            )
        }

    @Test
    fun `deleting asks first with the open task count, then trashes the project and closes its detail`() =
        test { vm ->
            coEvery { work0.delete(holiday) } returns ActionOutcome.DONE
            vm.open(holiday)
            vm.requestDelete()
            assertEquals(ProjectConfirm.Delete(holiday.id, 2), vm.uiState.value.confirm)
            coVerify(exactly = 0) { work0.delete(any()) }
            vm.confirm()
            coVerify(exactly = 1) { work0.delete(holiday) }
            assertNull(vm.uiState.value.detail)
            assertNull(vm.uiState.value.confirm)
            assertEquals(ProjectMessage(ProjectMessage.Kind.DELETED, "Plan holiday", ActionOutcome.DONE), vm.uiState.value.projectMessage)
        }

    @Test
    fun `a refused delete keeps the detail open and says why`() =
        test { vm ->
            coEvery { work0.delete(holiday) } returns ActionOutcome.CONFLICT
            vm.open(holiday)
            vm.requestDelete()
            vm.confirm()
            assertEquals(
                holiday,
                vm.uiState.value.detail!!
                    .project,
            )
            assertEquals(
                ProjectMessage(ProjectMessage.Kind.DELETED, "Plan holiday", ActionOutcome.CONFLICT),
                vm.uiState.value.projectMessage,
            )
        }

    @Test
    fun `offline no project can be created, edited, finished or deleted`() =
        test { vm ->
            vm.open(holiday)
            projectFlow.value = projectFlow.value.copy(offline = true)
            vm.newProject()
            assertNull(vm.uiState.value.form)
            vm.editProject()
            assertNull(vm.uiState.value.form)
            vm.setStatus(ProjectStatus.DROPPED, confirmed = true)
            vm.requestDelete()
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 0) { work0.update(any(), any(), any()) }
            coVerify(exactly = 0) { work0.delete(any()) }
        }

    @Test
    fun `a form opened before going offline cannot be saved offline`() =
        test { vm ->
            vm.newProject()
            vm.editForm { it.copy(title = "Learn Italian") }
            projectFlow.value = projectFlow.value.copy(offline = true)
            vm.saveForm()
            coVerify(exactly = 0) { work0.create(any()) }
            assertEquals(
                false,
                vm.uiState.value.form!!
                    .saving,
            )
        }

    @Test
    fun `a detail whose tasks could not reload cannot be edited, finished or deleted`() =
        test { vm ->
            taskFlow.value = Live(listOf(book, hotel), LoadError.FAILED)
            vm.open(holiday)
            vm.editProject()
            vm.setStatus(ProjectStatus.COMPLETED)
            vm.requestDelete()
            assertNull(vm.uiState.value.form)
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 0) { work0.update(any(), any(), any()) }
        }

    @Test
    fun `without an open project the detail's actions do nothing`() =
        test { vm ->
            vm.editProject()
            vm.setStatus(ProjectStatus.COMPLETED)
            vm.requestDelete()
            vm.confirm()
            assertNull(vm.uiState.value.form)
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 0) { work0.update(any(), any(), any()) }
        }

    @Test
    fun `the preview toggles, and going back drops an edit but keeps a new project's form`() =
        test { vm ->
            vm.open(holiday)
            vm.editProject()
            vm.togglePreview()
            assertTrue(
                vm.uiState.value.form!!
                    .preview,
            )
            vm.togglePreview()
            assertEquals(
                false,
                vm.uiState.value.form!!
                    .preview,
            )
            vm.close()
            assertNull(vm.uiState.value.form)

            vm.newProject()
            vm.close()
            assertTrue(
                vm.uiState.value.form!!
                    .isNew,
            )
        }

    @Test
    fun `a failed save that returns after its form was cancelled does not unlock the save running for the next form`() =
        test { vm ->
            val first = CompletableDeferred<ActionOutcome>()
            val second = CompletableDeferred<ActionOutcome>()
            coEvery { work0.update(holiday.id, 2, JsonObject(mapOf("title" to JsonPrimitive("Plan the holiday")))) } coAnswers
                { first.await() }
            coEvery { work0.update(holiday.id, 2, JsonObject(mapOf("title" to JsonPrimitive("Plan the summer holiday")))) } coAnswers
                { second.await() }
            vm.open(holiday)
            vm.editProject()
            vm.editForm { it.copy(title = "Plan the holiday") }
            vm.saveForm()
            vm.cancelForm()
            vm.editProject()
            vm.editForm { it.copy(title = "Plan the summer holiday") }
            vm.saveForm()
            first.complete(ActionOutcome.FAILED)
            assertTrue(
                vm.uiState.value.form!!
                    .saving,
            )
            // Still saving: a second tap does not send the newer form twice.
            vm.saveForm()
            coVerify(exactly = 1) {
                work0.update(holiday.id, 2, JsonObject(mapOf("title" to JsonPrimitive("Plan the summer holiday"))))
            }
            second.complete(ActionOutcome.DONE)
            assertNull(vm.uiState.value.form)
        }

    @Test
    fun `a failed create that returns after its form was cancelled does not unlock the create running for the next form`() =
        test { vm ->
            val first = CompletableDeferred<ProjectCreated>()
            val second = CompletableDeferred<ProjectCreated>()
            coEvery { work0.create(ProjectDraft(title = "Learn Italian")) } coAnswers { first.await() }
            coEvery { work0.create(ProjectDraft(title = "Learn Spanish")) } coAnswers { second.await() }
            vm.newProject()
            vm.editForm { it.copy(title = "Learn Italian") }
            vm.saveForm()
            vm.cancelForm()
            vm.newProject()
            vm.editForm { it.copy(title = "Learn Spanish") }
            vm.saveForm()
            first.complete(ProjectCreated(ActionOutcome.FAILED, null))
            assertTrue(
                vm.uiState.value.form!!
                    .saving,
            )
            vm.saveForm()
            coVerify(exactly = 1) { work0.create(ProjectDraft(title = "Learn Spanish")) }
            second.complete(ProjectCreated(ActionOutcome.DONE, garden.id))
            val state = vm.uiState.value
            assertNull(state.form)
            assertEquals(garden, state.detail?.project)
        }

    @Test
    fun `a confirmation asked about one project does not apply after another project was opened by a create`() =
        test { vm ->
            coEvery { work0.create(any()) } returns ProjectCreated(ActionOutcome.DONE, garden.id)
            vm.open(holiday)
            vm.requestDelete()
            assertEquals(ProjectConfirm.Delete(holiday.id, 2), vm.uiState.value.confirm)
            // The create opens the new project without going through open(): the old question is still pending.
            vm.newProject()
            vm.editForm { it.copy(title = "Redo the garden") }
            vm.saveForm()
            val opened = vm.uiState.value
            assertEquals(garden, opened.detail?.project)
            vm.confirm()
            val after = vm.uiState.value
            assertNull(after.confirm)
            coVerify(exactly = 0) { work0.delete(any()) }
            assertEquals(garden, after.detail?.project)
        }

    @Test
    fun `finishing from the menu while the tasks load is sent once confirmed, and asks no more once none are open`() =
        test { vm ->
            taskFlow.value = Live()
            coEvery { work0.update(any(), any(), any()) } returns ActionOutcome.DONE
            vm.open(garden)
            vm.setStatus(ProjectStatus.DROPPED)
            assertEquals(ProjectConfirm.Finish(garden.id, ProjectStatus.DROPPED, null, viaForm = false), vm.uiState.value.confirm)
            vm.confirm()
            coVerify(exactly = 1) { work0.update(garden.id, 2, status(ProjectStatus.DROPPED)) }
            assertNull(vm.uiState.value.confirm)

            // The tasks have loaded now and none is open: finishing no longer asks.
            taskFlow.value = Live(emptyList())
            vm.setStatus(ProjectStatus.COMPLETED)
            assertNull(vm.uiState.value.confirm)
            coVerify(exactly = 1) { work0.update(garden.id, 2, status(ProjectStatus.COMPLETED)) }
        }

    @Test
    fun `finishing in the form after the project disappeared does not leave the save doing nothing`() =
        test { vm ->
            var sent = 0
            coEvery { work0.update(any(), any(), any()) } coAnswers {
                sent++
                ActionOutcome.CONFLICT
            }
            vm.open(holiday)
            vm.editProject()
            // Trashed elsewhere: a reload removes it from the cache while the form is open.
            projectFlow.value = projectFlow.value.copy(data = listOf(garden, report, move))
            assertNull(vm.uiState.value.detail)
            vm.editForm { it.copy(status = ProjectStatus.COMPLETED) }
            vm.saveForm()
            val state = vm.uiState.value
            val confirm = state.confirm
            // The screen only shows a confirmation for the open project's detail; otherwise the save must reach the server
            // (which answers with a conflict that closes the form and tells the user).
            val shown = confirm != null && confirm.projectId == state.detail?.project?.id
            assertTrue("save neither sent nor a visible confirmation (confirm=$confirm, detail=${state.detail})", shown || sent > 0)
        }

    @Test
    fun `a completed task is hidden until a successful reload no longer holds it, then shows if reopened elsewhere`() =
        test { vm ->
            coEvery { work0.complete(hotel) } returns ProjectCompletion.Completed(needsNextStep = false)
            vm.open(holiday)
            vm.complete(hotel)
            assertEquals(
                listOf(book),
                vm.uiState.value.detail!!
                    .tasks,
            )
            // A failed reload still holds the old list: keep hiding it.
            taskFlow.value = Live(listOf(book, hotel), LoadError.FAILED)
            assertEquals(
                listOf(book),
                vm.uiState.value.detail!!
                    .tasks,
            )
            taskFlow.value = Live(listOf(book))
            assertEquals(
                listOf(book),
                vm.uiState.value.detail!!
                    .tasks,
            )
            // Reopened in the web Logbook: the next reload holds it again, and it shows.
            taskFlow.value = Live(listOf(book, hotel))
            assertEquals(
                listOf(book, hotel),
                vm.uiState.value.detail!!
                    .tasks,
            )
        }
}
