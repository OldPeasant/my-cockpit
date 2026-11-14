package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** "New project…" of the task detail project picker: create once, put it in the draft, save it with the task. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailNewProjectViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val garden = GtdProject(id(20), "Garden", ProjectStatus.ACTIVE, stalled = false, version = 1)
    private val task = GtdTask(id(1), "Buy seeds", TaskList.NEXT, 0, version = 3, projectId = garden.id)
    private val source = FakeTaskDetailSource(TaskDetail(task))
    private val projects = MutableStateFlow(listOf(garden))

    /** What the fake answers for "Kitchen" by default. */
    private val kitchenId: UUID = UUID.nameUUIDFromBytes("Kitchen".toByteArray())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.editing(): TaskDetailViewModel {
        val vm = TaskDetailViewModel(source, projects, flowOf(emptyList()), flowOf(null)).also { it.open(task.id) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.startEditing()
        return vm
    }

    private fun TaskDetailViewModel.draftProject() = uiState.value.draft?.projectId

    @Test
    fun `a new project is created with the trimmed title, put in the draft and offered in the picker`() =
        runTest {
            val vm = editing()
            vm.newProject("  Kitchen ")

            assertEquals(listOf("Kitchen"), source.createdProjects)
            assertEquals(kitchenId, vm.draftProject())
            val state = vm.uiState.value
            assertEquals(listOf(garden.id, kitchenId), state.projects.map { it.id })
            assertFalse(state.busy)
            assertNull(state.message)
            assertTrue(source.updates.isEmpty())
        }

    @Test
    fun `saving after a new project sends the task's new projectId`() =
        runTest {
            val vm = editing()
            vm.newProject("Kitchen")
            vm.save()

            val (_, patch) = source.updates.single()
            assertEquals(JsonPrimitive(kitchenId.toString()), patch["projectId"])
            assertEquals(DetailMessage.Saved, vm.uiState.value.message)
        }

    @Test
    fun `saving into a project made here makes the task its next step if it is stalled`() =
        runTest {
            val vm = editing()
            vm.newProject("Kitchen")
            assertTrue("not before the task is saved", source.nextSteps.isEmpty())
            vm.save()

            assertEquals(listOf(kitchenId to task.id), source.nextSteps)
        }

    @Test
    fun `saving into an existing project leaves its next step alone`() =
        runTest {
            val bathroom = GtdProject(id(21), "Bathroom", ProjectStatus.ACTIVE, stalled = true, version = 1)
            projects.value = listOf(garden, bathroom)
            val vm = editing()
            vm.edit { it.copy(projectId = bathroom.id) }
            vm.save()

            assertEquals(1, source.updates.size)
            assertTrue(source.nextSteps.isEmpty())
        }

    @Test
    fun `a project made here but not chosen in the end gets no next step`() =
        runTest {
            val vm = editing()
            vm.newProject("Kitchen")
            vm.edit { it.copy(title = "Buy more seeds", projectId = garden.id) }
            vm.save()

            assertEquals(1, source.updates.size)
            assertTrue(source.nextSteps.isEmpty())
        }

    @Test
    fun `a failed save into a project made here sets no next step`() =
        runTest {
            val vm = editing()
            vm.newProject("Kitchen")
            source.updateResult = TaskResult.Offline
            vm.save()

            assertTrue(source.nextSteps.isEmpty())
        }

    @Test
    fun `once the projects reload with the new project it is offered only once`() =
        runTest {
            val vm = editing()
            vm.newProject("Kitchen")
            projects.value = listOf(garden, GtdProject(kitchenId, "Kitchen", ProjectStatus.ACTIVE, stalled = true, version = 1))

            val offered = vm.uiState.value.projects
            assertEquals(listOf(garden.id, kitchenId), offered.map { it.id })
        }

    @Test
    fun `a blank title creates nothing and leaves the project`() =
        runTest {
            val vm = editing()
            vm.newProject("")
            vm.newProject("   ")

            assertTrue(source.createdProjects.isEmpty())
            assertEquals(garden.id, vm.draftProject())
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `without the edit form nothing is created`() =
        runTest {
            val vm = editing()
            vm.cancelEditing()
            vm.newProject("Kitchen")

            assertTrue(source.createdProjects.isEmpty())
            assertNull(vm.uiState.value.draft)
        }

    @Test
    fun `a failed create says so and leaves the project unchanged`() =
        runTest {
            val vm = editing()
            source.createProjectResult = TaskResult.Failed(500)
            vm.newProject("Kitchen")

            val state = vm.uiState.value
            assertEquals(DetailMessage.ProjectNotCreated(), state.message)
            assertEquals(garden.id, vm.draftProject())
            assertEquals(listOf(garden.id), state.projects.map { it.id })
            assertFalse(state.busy)
        }

    @Test
    fun `a refused create shows the server's field message, else its detail`() =
        runTest {
            val vm = editing()
            source.createProjectResult = TaskResult.Invalid(mapOf("title" to "Title is too long"), "Validation failed")
            vm.newProject("Kitchen")
            assertEquals(DetailMessage.ProjectNotCreated("Title is too long"), vm.uiState.value.message)

            source.createProjectResult = TaskResult.Invalid(emptyMap(), "Validation failed")
            vm.newProject("Kitchen")
            assertEquals(DetailMessage.ProjectNotCreated("Validation failed"), vm.uiState.value.message)
            assertEquals(garden.id, vm.draftProject())
        }

    @Test
    fun `an offline create says offline and leaves the project unchanged`() =
        runTest {
            val vm = editing()
            source.createProjectResult = TaskResult.Offline
            vm.newProject("Kitchen")

            assertEquals(DetailMessage.Offline, vm.uiState.value.message)
            assertEquals(garden.id, vm.draftProject())
        }

    @Test
    fun `a failed create can be retried and then creates the project once`() =
        runTest {
            val vm = editing()
            source.createProjectResult = TaskResult.Offline
            vm.newProject("Kitchen")
            source.createProjectResult = null
            vm.newProject("Kitchen")

            assertEquals(listOf("Kitchen", "Kitchen"), source.createdProjects)
            assertEquals(kitchenId, vm.draftProject())
        }

    @Test
    fun `saving again after a failed save does not create a second project`() =
        runTest {
            val vm = editing()
            vm.newProject("Kitchen")
            source.updateResult = TaskResult.Offline
            vm.save()
            assertEquals(DetailMessage.Offline, vm.uiState.value.message)
            assertEquals(kitchenId, vm.draftProject())

            source.updateResult = null
            vm.save()

            assertEquals(listOf("Kitchen"), source.createdProjects)
            assertEquals(2, source.updates.size)
            assertEquals(JsonPrimitive(kitchenId.toString()), source.updates.last().second["projectId"])
            assertEquals(DetailMessage.Saved, vm.uiState.value.message)
        }

    @Test
    fun `the same title again reuses the project made here, another title creates a new one`() =
        runTest {
            val vm = editing()
            vm.newProject("Kitchen")
            vm.edit { it.copy(projectId = garden.id) }
            vm.newProject(" Kitchen")

            assertEquals(listOf("Kitchen"), source.createdProjects)
            assertEquals(kitchenId, vm.draftProject())

            vm.newProject("Bathroom")
            assertEquals(listOf("Kitchen", "Bathroom"), source.createdProjects)
            assertEquals(UUID.nameUUIDFromBytes("Bathroom".toByteArray()), vm.draftProject())
            assertEquals(3, vm.uiState.value.projects.size)
        }
}
