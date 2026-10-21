package ch.sonensei.cockpit.gtd.inbox

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val first = GtdTask(id(1), "Call the plumber", TaskList.INBOX, 0, 1)
    private val second = GtdTask(id(2), "Buy tiles", TaskList.INBOX, 1, 1, projectId = id(51))
    private val kitchen = GtdProject(id(51), "Kitchen", ProjectStatus.ACTIVE, stalled = false, version = 1)
    private val garden = GtdProject(id(50), "Garden", ProjectStatus.ACTIVE, stalled = false, version = 1)
    private val done = GtdProject(id(52), "Old", ProjectStatus.COMPLETED, stalled = false, version = 1)
    private val home = GtdContext(id(80), "home", 0, 1)
    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")

    private val inboxFlow = MutableStateFlow(Cached(listOf(first, second), offline = false, refreshedAt = loadedAt))
    private val inbox = mockk<InboxRepository>()
    private val tasks = mockk<TaskRepository> { every { observe(TaskView.INBOX) } returns inboxFlow }
    private val projects =
        mockk<ProjectRepository> { every { observe() } returns MutableStateFlow(Cached(listOf(garden, kitchen, done), false, loadedAt)) }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(listOf(home), false, loadedAt)) }
    private val clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 20)

    /** Each read moves a second on, so the double-tap guard only applies where a test stops it. */
    private var uptime = 0L
    private var uptimeStep = 1_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Processed
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend (InboxViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel =
                InboxViewModel(inbox, tasks, projects, contexts, clock) {
                    uptime += uptimeStep
                    uptime
                }
            backgroundScope.launch { viewModel.uiState.collect {} }
            block(viewModel)
        }

    @Test
    fun `shows the inbox, the active projects and the contexts`() =
        test { vm ->
            val state = vm.uiState.value
            assertEquals(listOf(first, second), state.tasks)
            assertEquals(first, state.current)
            assertEquals(listOf(garden, kitchen), state.projects)
            assertEquals(listOf(home), state.contexts)
            assertEquals(today, state.today)
            assertTrue(state.actionsEnabled)
        }

    @Test
    fun `each direct action calls the repository with the current item`() {
        listOf(
            InboxActionKind.DONE to InboxAction.Done,
            InboxActionKind.NEXT to InboxAction.Next,
            InboxActionKind.SOMEDAY to InboxAction.Someday,
            InboxActionKind.TRASH to InboxAction.Trash,
        ).forEach { (kind, action) ->
            test { vm ->
                vm.startProcessing()
                vm.choose(first.id, kind)
                coVerify { inbox.process(first, Clarification.of(first), action) }
                assertEquals(1, vm.uiState.value.processed)
            }
        }
    }

    @Test
    fun `each prompted action calls the repository with the prompt's value`() =
        test { vm ->
            vm.startProcessing()

            vm.choose(first.id, InboxActionKind.WAITING)
            vm.updatePrompt { it.copy(text = "Alex") }
            vm.confirmPrompt(first.id)
            coVerify { inbox.process(first, any(), InboxAction.Waiting("Alex")) }

            vm.choose(first.id, InboxActionKind.SCHEDULE)
            assertEquals(
                today.plusDays(1),
                vm.uiState.value.prompt
                    ?.date,
            )
            vm.confirmPrompt(first.id)
            coVerify { inbox.process(first, any(), InboxAction.Schedule(today.plusDays(1))) }

            vm.choose(first.id, InboxActionKind.TICKLER)
            vm.updatePrompt { it.copy(date = today.plusWeeks(1)) }
            vm.confirmPrompt(first.id)
            coVerify { inbox.process(first, any(), InboxAction.Tickler(today.plusWeeks(1))) }

            vm.choose(first.id, InboxActionKind.PROJECT)
            assertEquals(
                garden.id,
                vm.uiState.value.prompt
                    ?.projectId,
            )
            vm.confirmPrompt(first.id)
            coVerify { inbox.process(first, any(), InboxAction.ToProject(ProjectChoice.Existing(garden.id))) }

            vm.choose(first.id, InboxActionKind.PROJECT)
            vm.updatePrompt { it.copy(projectId = null, text = "Bathroom") }
            vm.confirmPrompt(first.id)
            coVerify { inbox.process(first, any(), InboxAction.ToProject(ProjectChoice.New("Bathroom"))) }
        }

    @Test
    fun `to project offers the project named in the capture first`() =
        test { vm ->
            inboxFlow.value = inboxFlow.value.copy(data = listOf(second))
            vm.choose(second.id, InboxActionKind.PROJECT)
            assertEquals(
                kitchen.id,
                vm.uiState.value.prompt
                    ?.projectId,
            )
        }

    @Test
    fun `an invalid prompt does not run`() =
        test { vm ->
            vm.choose(first.id, InboxActionKind.TICKLER)
            vm.updatePrompt { it.copy(date = today) }
            vm.confirmPrompt(first.id)
            vm.choose(first.id, InboxActionKind.PROJECT)
            vm.updatePrompt { it.copy(projectId = null, text = " ") }
            vm.confirmPrompt(first.id)
            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
        }

    @Test
    fun `inline attributes are passed with the action`() =
        test { vm ->
            vm.setEnergy(Energy.LOW)
            vm.setCreativity(Creativity.ROUTINE)
            vm.setEstimate(15)
            vm.toggleContext(home.id)
            assertEquals(Clarification(Energy.LOW, Creativity.ROUTINE, 15, listOf(home.id)), vm.uiState.value.clarification)
            vm.toggleContext(home.id)

            vm.choose(first.id, InboxActionKind.NEXT)

            coVerify { inbox.process(first, Clarification(Energy.LOW, Creativity.ROUTINE, 15, emptyList()), InboxAction.Next) }
        }

    @Test
    fun `the item leaves the queue while its action runs and comes back with a message when it fails`() =
        test { vm ->
            val answer = CompletableDeferred<ProcessResult>()
            coEvery { inbox.process(any(), any(), any()) } coAnswers { answer.await() }

            vm.choose(first.id, InboxActionKind.NEXT)
            assertEquals(second, vm.uiState.value.current)

            answer.complete(ProcessResult.Offline)
            assertEquals(first, vm.uiState.value.current)
            assertEquals(InboxMessage.OFFLINE, vm.uiState.value.message)
            assertEquals(0, vm.uiState.value.processed)
            vm.dismissMessage()
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `conflicts and refusals are reported`() =
        test { vm ->
            coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Conflict
            vm.choose(first.id, InboxActionKind.NEXT)
            assertEquals(InboxMessage.CONFLICT, vm.uiState.value.message)
            coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Rejected(400)
            vm.choose(first.id, InboxActionKind.NEXT)
            assertEquals(InboxMessage.FAILED, vm.uiState.value.message)
        }

    @Test
    fun `offline disables every action`() =
        test { vm ->
            inboxFlow.value = inboxFlow.value.copy(offline = true)
            assertEquals(false, vm.uiState.value.actionsEnabled)

            InboxActionKind.entries.forEach { vm.choose(first.id, it) }

            assertNull(vm.uiState.value.prompt)
            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
        }

    @Test
    fun `offline blocks an open prompt from running`() =
        test { vm ->
            vm.choose(first.id, InboxActionKind.SCHEDULE)
            inboxFlow.value = inboxFlow.value.copy(offline = true)
            vm.confirmPrompt(first.id)
            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
        }

    @Test
    fun `a processed item takes its inline changes with it, the next one shows its own`() =
        test { vm ->
            val tagged = second.copy(energy = Energy.HIGH)
            coEvery { inbox.process(any(), any(), any()) } coAnswers {
                inboxFlow.value = inboxFlow.value.copy(data = listOf(tagged))
                ProcessResult.Processed
            }
            vm.setEnergy(Energy.LOW)

            vm.choose(first.id, InboxActionKind.NEXT)

            assertEquals(tagged, vm.uiState.value.current)
            assertEquals(Clarification(energy = Energy.HIGH), vm.uiState.value.clarification)
        }

    @Test
    fun `a failed action keeps the inline changes for the retry`() =
        test { vm ->
            coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Offline
            vm.setEstimate(30)
            vm.choose(first.id, InboxActionKind.NEXT)
            assertEquals(first, vm.uiState.value.current)
            assertEquals(30, vm.uiState.value.clarification.timeEstimateMin)

            coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Processed
            vm.choose(first.id, InboxActionKind.NEXT)

            coVerify(exactly = 2) { inbox.process(first, Clarification(timeEstimateMin = 30), InboxAction.Next) }
        }

    @Test
    fun `an older item failing after a newer one succeeded comes back first, the count stays`() =
        test { vm ->
            val firstAnswer = CompletableDeferred<ProcessResult>()
            coEvery { inbox.process(first, any(), any()) } coAnswers { firstAnswer.await() }
            coEvery { inbox.process(second, any(), any()) } coAnswers {
                inboxFlow.value = inboxFlow.value.copy(data = listOf(first))
                ProcessResult.Processed
            }

            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.NEXT)
            vm.choose(second.id, InboxActionKind.DONE)
            assertEquals(1, vm.uiState.value.processed)
            assertNull(vm.uiState.value.current)

            firstAnswer.complete(ProcessResult.Conflict)

            assertEquals(first, vm.uiState.value.current)
            assertEquals(1, vm.uiState.value.processed)
            assertEquals(InboxMessage.CONFLICT, vm.uiState.value.message)
        }

    @Test
    fun `an item cannot be processed twice while its action runs`() =
        test { vm ->
            val answer = CompletableDeferred<ProcessResult>()
            coEvery { inbox.process(any(), any(), any()) } coAnswers { answer.await() }
            inboxFlow.value = inboxFlow.value.copy(data = listOf(first))

            vm.choose(first.id, InboxActionKind.NEXT)
            vm.choose(first.id, InboxActionKind.NEXT)
            vm.choose(first.id, InboxActionKind.TRASH)

            coVerify(exactly = 1) { inbox.process(any(), any(), any()) }
            answer.complete(ProcessResult.Processed)
        }

    @Test
    fun `a second tap for an item that already left the queue does not act on the next item`() =
        test { vm ->
            val answer = CompletableDeferred<ProcessResult>()
            coEvery { inbox.process(any(), any(), any()) } coAnswers { answer.await() }
            vm.startProcessing()

            vm.choose(first.id, InboxActionKind.TRASH)
            assertEquals(second, vm.uiState.value.current)
            vm.choose(first.id, InboxActionKind.TRASH)

            coVerify(exactly = 1) { inbox.process(any(), any(), any()) }
            coVerify(exactly = 0) { inbox.process(second, any(), any()) }
            assertEquals(second, vm.uiState.value.current)
            answer.complete(ProcessResult.Processed)
        }

    @Test
    fun `a quick second tap does not act on the next item that just appeared`() =
        test { vm ->
            val answer = CompletableDeferred<ProcessResult>()
            coEvery { inbox.process(first, any(), any()) } coAnswers { answer.await() }
            vm.startProcessing()
            uptimeStep = 100L

            vm.choose(first.id, InboxActionKind.TRASH)
            vm.choose(second.id, InboxActionKind.TRASH)
            vm.choose(second.id, InboxActionKind.SCHEDULE)

            coVerify(exactly = 1) { inbox.process(any(), any(), any()) }
            assertEquals(null, vm.uiState.value.prompt)
            uptime += 1_000L
            vm.choose(second.id, InboxActionKind.TRASH)
            coVerify(exactly = 1) { inbox.process(second, any(), InboxAction.Trash) }
            answer.complete(ProcessResult.Processed)
        }

    @Test
    fun `a stale prompt confirmation is dropped`() =
        test { vm ->
            vm.choose(first.id, InboxActionKind.SCHEDULE)
            vm.confirmPrompt(second.id)

            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
            assertEquals(
                InboxActionKind.SCHEDULE,
                vm.uiState.value.prompt
                    ?.kind,
            )
        }

    @Test
    fun `retry reloads the inbox and a reload that works clears the offline state`() =
        test { vm ->
            inboxFlow.value = inboxFlow.value.copy(offline = true)
            val answer = CompletableDeferred<RefreshResult>()
            coEvery { tasks.refresh(TaskView.INBOX) } coAnswers {
                answer.await().also { if (it == RefreshResult.Refreshed) inboxFlow.value = inboxFlow.value.copy(offline = false) }
            }
            assertEquals(false, vm.uiState.value.actionsEnabled)

            vm.retry()
            assertTrue(vm.uiState.value.retrying)
            vm.retry()
            answer.complete(RefreshResult.Refreshed)

            coVerify(exactly = 1) { tasks.refresh(TaskView.INBOX) }
            assertEquals(false, vm.uiState.value.offline)
            assertEquals(false, vm.uiState.value.retrying)
            assertTrue(vm.uiState.value.actionsEnabled)
        }

    @Test
    fun `a retry that fails keeps the offline state and can be repeated`() =
        test { vm ->
            inboxFlow.value = inboxFlow.value.copy(offline = true)
            coEvery { tasks.refresh(TaskView.INBOX) } returns RefreshResult.Offline

            vm.retry()
            vm.retry()

            coVerify(exactly = 2) { tasks.refresh(TaskView.INBOX) }
            assertTrue(vm.uiState.value.offline)
            assertEquals(false, vm.uiState.value.retrying)
        }

    @Test
    fun `nothing runs on an empty inbox`() =
        test { vm ->
            inboxFlow.value = inboxFlow.value.copy(data = emptyList())
            assertEquals(false, vm.uiState.value.actionsEnabled)

            InboxActionKind.entries.forEach { vm.choose(first.id, it) }
            vm.confirmPrompt(first.id)
            vm.setEnergy(Energy.LOW)

            assertNull(vm.uiState.value.prompt)
            assertEquals(Clarification(), vm.uiState.value.clarification)
            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
        }

    @Test
    fun `to project without active projects proposes a new one`() {
        every { projects.observe() } returns MutableStateFlow(Cached(listOf(done), false, loadedAt))
        test { vm ->
            vm.choose(first.id, InboxActionKind.PROJECT)

            val prompt = vm.uiState.value.prompt
            assertEquals(InboxActionKind.PROJECT, prompt?.kind)
            assertNull(prompt?.projectId)
            assertEquals(false, prompt?.isValid(today))
        }
    }

    @Test
    fun `cancelling a prompt runs nothing and shows the actions again`() =
        test { vm ->
            vm.choose(first.id, InboxActionKind.WAITING)
            vm.cancelPrompt()
            vm.confirmPrompt(first.id)

            assertNull(vm.uiState.value.prompt)
            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
        }

    @Test
    fun `choosing again after a failure clears the old message`() =
        test { vm ->
            coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Rejected(422)
            vm.choose(first.id, InboxActionKind.NEXT)
            assertEquals(InboxMessage.FAILED, vm.uiState.value.message)

            vm.choose(first.id, InboxActionKind.SCHEDULE)

            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `toggling a chosen attribute off clears it`() =
        test { vm ->
            vm.setEnergy(Energy.LOW)
            vm.setEnergy(null)
            vm.setCreativity(Creativity.CREATIVE)
            vm.setCreativity(null)
            vm.setEstimate(60)
            vm.setEstimate(null)

            assertEquals(Clarification.of(first), vm.uiState.value.clarification)
        }

    @Test
    fun `stopping closes the prompt and starting resets the progress`() =
        test { vm ->
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.DONE)
            vm.choose(first.id, InboxActionKind.WAITING)
            vm.stopProcessing()
            assertEquals(false, vm.uiState.value.processing)
            assertNull(vm.uiState.value.prompt)
            vm.startProcessing()
            assertEquals(0, vm.uiState.value.processed)
        }
}
