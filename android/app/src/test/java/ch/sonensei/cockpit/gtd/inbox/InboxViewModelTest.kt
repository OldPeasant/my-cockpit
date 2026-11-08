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
import ch.sonensei.cockpit.gtd.next.Completion
import ch.sonensei.cockpit.gtd.next.CompletionMessage
import ch.sonensei.cockpit.gtd.next.CompletionResult
import ch.sonensei.cockpit.gtd.next.CompletionSnackbar
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
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
    private val completion = mockk<TaskCompletionRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 20)

    /** Each read moves a second on, so the double-tap guard only applies where a test stops it. */
    private var uptime = 0L
    private var uptimeStep = 1_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Processed
        coEvery { tasks.refresh(TaskView.INBOX) } returns RefreshResult.Refreshed
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend (InboxViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel =
                InboxViewModel(inbox, tasks, projects, contexts, completion, clock) {
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
    fun `the double-tap guard ends exactly 500 ms after the last action`() =
        test { vm ->
            val answer = CompletableDeferred<ProcessResult>()
            coEvery { inbox.process(first, any(), any()) } coAnswers { answer.await() }
            uptimeStep = 0L
            uptime = 10_000L

            vm.choose(first.id, InboxActionKind.DONE)
            uptime = 10_499L
            vm.choose(second.id, InboxActionKind.TRASH)
            coVerify(exactly = 0) { inbox.process(second, any(), any()) }

            uptime = 10_500L
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
    fun `a prompt opened on an item that left the inbox meanwhile is neither shown nor run on the next item`() =
        test { vm ->
            vm.choose(first.id, InboxActionKind.SCHEDULE)
            // A reload (e.g. the item was processed on the web) removes the item the prompt was opened on.
            inboxFlow.value = inboxFlow.value.copy(data = listOf(second))

            assertEquals(second, vm.uiState.value.current)
            assertNull(vm.uiState.value.prompt)
            vm.confirmPrompt(second.id)
            vm.confirmPrompt(first.id)

            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
        }

    @Test
    fun `inline changes made on an item that left the inbox meanwhile do not apply to the next item`() =
        test { vm ->
            vm.setEnergy(Energy.HIGH)
            vm.setEstimate(120)
            inboxFlow.value = inboxFlow.value.copy(data = listOf(second))

            assertEquals(Clarification.of(second), vm.uiState.value.clarification)
            vm.choose(second.id, InboxActionKind.NEXT)

            coVerify(exactly = 1) { inbox.process(second, Clarification.of(second), InboxAction.Next) }
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

    @Test
    fun `completing a task from the list hides it and offers undo`() =
        test { vm ->
            val completion = Completion(first.id, first.title, 2)
            coEvery { this@InboxViewModelTest.completion.complete(first) } returns CompletionResult.Completed(completion)

            vm.complete(first)

            assertEquals(listOf(second), vm.uiState.value.tasks)
            assertEquals(CompletionSnackbar.Undo(completion), vm.uiState.value.snackbar)
        }

    @Test
    fun `undoing a completion shows the task again`() =
        test { vm ->
            val completion = Completion(first.id, first.title, 2)
            coEvery { this@InboxViewModelTest.completion.complete(first) } returns CompletionResult.Completed(completion)
            coEvery { this@InboxViewModelTest.completion.undo(completion) } returns CompletionResult.Reopened
            vm.complete(first)

            vm.undo(CompletionSnackbar.Undo(completion))

            assertEquals(listOf(first, second), vm.uiState.value.tasks)
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `a refused completion brings the task back with the reason`() =
        test { vm ->
            coEvery { completion.complete(first) } returns CompletionResult.NotDone(CompletionMessage.CONFLICT)

            vm.complete(first)

            assertEquals(listOf(first, second), vm.uiState.value.tasks)
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.CONFLICT), vm.uiState.value.snackbar)
            vm.dismissSnackbar(CompletionSnackbar.Failed(CompletionMessage.CONFLICT))
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `completing is ignored while offline`() =
        test { vm ->
            inboxFlow.value = inboxFlow.value.copy(offline = true)

            vm.complete(first)

            coVerify(exactly = 0) { completion.complete(any()) }
            assertEquals(listOf(first, second), vm.uiState.value.tasks)
        }

    @Test
    fun `a task being completed is hidden before the server answers and comes back if it refuses`() =
        test { vm ->
            val answer = CompletableDeferred<CompletionResult>()
            coEvery { completion.complete(first) } coAnswers { answer.await() }

            vm.complete(first)
            assertEquals(listOf(second), vm.uiState.value.tasks)
            assertNull(vm.uiState.value.snackbar)

            answer.complete(CompletionResult.NotDone(CompletionMessage.OFFLINE))
            assertEquals(listOf(first, second), vm.uiState.value.tasks)
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.OFFLINE), vm.uiState.value.snackbar)
        }

    @Test
    fun `a second tap on a task being completed sends one completion`() =
        test { vm ->
            val answer = CompletableDeferred<CompletionResult>()
            coEvery { completion.complete(first) } coAnswers { answer.await() }

            vm.complete(first)
            vm.complete(first)
            answer.complete(CompletionResult.Completed(Completion(first.id, first.title, 2)))

            coVerify(exactly = 1) { completion.complete(first) }
            assertEquals(listOf(second), vm.uiState.value.tasks)
        }

    @Test
    fun `the not yet reloaded inbox keeps a completed task hidden, a newer version of it shows again`() =
        test { vm ->
            coEvery { completion.complete(first) } returns CompletionResult.Completed(Completion(first.id, first.title, 2))
            vm.complete(first)

            // The cached Inbox still holds the version before the completion (its reload has not arrived yet).
            inboxFlow.value = inboxFlow.value.copy(data = listOf(first, second), refreshedAt = loadedAt.plusSeconds(60))
            assertEquals(listOf(second), vm.uiState.value.tasks)

            // Reopened elsewhere: the server's newer version is back in the Inbox.
            val reopened = first.copy(version = 3)
            inboxFlow.value = inboxFlow.value.copy(data = listOf(reopened, second))
            assertEquals(listOf(reopened, second), vm.uiState.value.tasks)
        }

    @Test
    fun `a refused undo keeps the task hidden and says why`() =
        test { vm ->
            val done = Completion(first.id, first.title, 2)
            coEvery { completion.complete(first) } returns CompletionResult.Completed(done)
            coEvery { completion.undo(done) } returns CompletionResult.NotDone(CompletionMessage.FAILED)
            vm.complete(first)

            vm.undo(CompletionSnackbar.Undo(done))

            assertEquals(listOf(second), vm.uiState.value.tasks)
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.FAILED), vm.uiState.value.snackbar)
        }

    @Test
    fun `a task completed from the list is not offered when processing starts`() =
        test { vm ->
            coEvery { completion.complete(first) } returns CompletionResult.Completed(Completion(first.id, first.title, 2))
            vm.complete(first)

            vm.startProcessing()

            assertEquals(second, vm.uiState.value.current)
            assertEquals(listOf(second), vm.uiState.value.tasks)
        }

    @Test
    fun `dismissing an older snackbar keeps the newer one`() =
        test { vm ->
            val firstDone = Completion(first.id, first.title, 2)
            val secondDone = Completion(second.id, second.title, 2)
            coEvery { completion.complete(first) } returns CompletionResult.Completed(firstDone)
            coEvery { completion.complete(second) } returns CompletionResult.Completed(secondDone)
            vm.complete(first)
            vm.complete(second)

            vm.dismissSnackbar(CompletionSnackbar.Undo(firstDone))

            assertEquals(CompletionSnackbar.Undo(secondDone), vm.uiState.value.snackbar)
            assertEquals(emptyList<GtdTask>(), vm.uiState.value.tasks)
        }

    @Test
    fun `complete, undo and complete again sends the version of the reloaded inbox`() =
        test { vm ->
            val reopened = first.copy(version = 3)
            val firstDone = Completion(first.id, first.title, 2)
            val secondDone = Completion(first.id, first.title, 4)
            coEvery { completion.complete(first) } returns CompletionResult.Completed(firstDone)
            coEvery { completion.undo(firstDone) } returns CompletionResult.Reopened
            coEvery { completion.complete(reopened) } returns CompletionResult.Completed(secondDone)
            // What the server holds after each step: completed (not in the Inbox), reopened at version 3, completed.
            val reloads = ArrayDeque(listOf(listOf(second), listOf(reopened, second), listOf(second)))
            coEvery { tasks.refresh(TaskView.INBOX) } coAnswers {
                inboxFlow.value = inboxFlow.value.copy(data = reloads.removeFirst())
                RefreshResult.Refreshed
            }

            vm.complete(first)
            vm.undo(CompletionSnackbar.Undo(firstDone))
            assertEquals(listOf(reopened, second), vm.uiState.value.tasks)
            val shown = vm.uiState.value.tasks
            vm.complete(shown.first())

            coVerify(exactly = 1) { completion.complete(reopened) }
            coVerify(exactly = 3) { tasks.refresh(TaskView.INBOX) }
            assertEquals(listOf(second), vm.uiState.value.tasks)
            assertEquals(CompletionSnackbar.Undo(secondDone), vm.uiState.value.snackbar)
        }

    @Test
    fun `a refused completion reloads the inbox, an offline one does not`() =
        test { vm ->
            coEvery { completion.complete(first) } returns CompletionResult.NotDone(CompletionMessage.CONFLICT)
            coEvery { completion.complete(second) } returns CompletionResult.NotDone(CompletionMessage.OFFLINE)

            vm.complete(first)
            coVerify(exactly = 1) { tasks.refresh(TaskView.INBOX) }

            vm.complete(second)
            coVerify(exactly = 1) { tasks.refresh(TaskView.INBOX) }
        }

    @Test
    fun `the inbox is reloaded only once the server answered`() =
        test { vm ->
            val answer = CompletableDeferred<CompletionResult>()
            coEvery { completion.complete(first) } coAnswers { answer.await() }

            vm.complete(first)
            coVerify(exactly = 0) { tasks.refresh(TaskView.INBOX) }

            answer.complete(CompletionResult.Completed(Completion(first.id, first.title, 2)))
            coVerify(exactly = 1) { tasks.refresh(TaskView.INBOX) }
        }
}
