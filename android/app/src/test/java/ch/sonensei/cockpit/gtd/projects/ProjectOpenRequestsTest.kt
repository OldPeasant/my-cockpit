package ch.sonensei.cockpit.gtd.projects

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.lists.Live
import ch.sonensei.cockpit.gtd.lists.TaskListsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Another screen (the weekly review) asks the Projects screen to show a project's detail. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectOpenRequestsTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")
    private val garden = GtdProject(id(11), "Redo the garden", ProjectStatus.ACTIVE, stalled = true, version = 2)
    private val report = GtdProject(id(12), "Annual report", ProjectStatus.ACTIVE, stalled = false, version = 2)
    private val projects =
        mockk<ProjectRepository> { every { observe() } returns MutableStateFlow(Cached(listOf(garden, report), false, loadedAt)) }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, loadedAt)) }
    private val lists = mockk<TaskListsRepository> { every { observeProject(any(), any()) } returns MutableStateFlow(Live(emptyList())) }
    private val work = mockk<ProjectWorkRepository> { every { observeAreas(any()) } returns MutableStateFlow(Live(emptyList())) }
    private val clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(requests: ProjectOpenRequests) =
        ProjectsViewModel(projects, contexts, lists, work, clock, openRequests = requests)

    private fun ProjectsViewModel.shownProject(): UUID? =
        uiState.value.detail
            ?.project
            ?.id

    @Test
    fun `a request waits until it is taken, and only one taker gets it`() {
        val requests = ProjectOpenRequests()
        assertNull(requests.requests.value)

        requests.request(garden.id)
        assertEquals(garden.id, requests.requests.value)
        assertFalse("another id is not the pending request", requests.take(report.id))
        assertTrue(requests.take(garden.id))
        assertFalse("already taken", requests.take(garden.id))
        assertNull(requests.requests.value)
    }

    @Test
    fun `a later request replaces one not yet taken`() {
        val requests = ProjectOpenRequests()
        requests.request(garden.id)
        requests.request(report.id)
        assertFalse(requests.take(garden.id))
        assertTrue(requests.take(report.id))
    }

    @Test
    fun `the Projects screen created after the request opens that project`() =
        runTest(UnconfinedTestDispatcher()) {
            val requests = ProjectOpenRequests()
            requests.request(garden.id)

            val vm = viewModel(requests)
            backgroundScope.launch { vm.uiState.collect {} }

            assertEquals(garden.id, vm.shownProject())
            assertNull("the request is used up", requests.requests.value)
        }

    @Test
    fun `a Projects screen that is kept opens a project requested later, replacing the one shown`() =
        runTest(UnconfinedTestDispatcher()) {
            val requests = ProjectOpenRequests()
            val vm = viewModel(requests)
            backgroundScope.launch { vm.uiState.collect {} }
            assertNull(vm.uiState.value.detail)
            vm.open(report)

            requests.request(garden.id)

            assertEquals(garden.id, vm.shownProject())
            assertNull(requests.requests.value)

            // Closing it does not reopen the used-up request.
            vm.close()
            assertNull(vm.uiState.value.detail)
        }

    @Test
    fun `without a request the Projects screen opens no project`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel(ProjectOpenRequests())
            backgroundScope.launch { vm.uiState.collect {} }
            assertNull(vm.uiState.value.detail)
        }
}
