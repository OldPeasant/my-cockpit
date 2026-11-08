package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.projects.ProjectOpenRequests
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** The decisions of the review's action steps (waiting for, projects, next actions, someday) in the view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewDecisionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val actions = mockk<ReviewActions>()
    private val requests = ProjectOpenRequests()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val parcel = ReviewTask(id(1), "Parcel from the fictitious shop", version = 7, projectId = id(10), waitingForText = "Erika")
    private val quote = ReviewTask(id(2), "Quote from the painter", version = 3)
    private val garden = ReviewProject(id(10), "Redo the garden", stalled = true, version = 9)
    private val report = ReviewProject(id(11), "Annual report", stalled = false, version = 4)
    private val bike = ReviewTask(id(3), "Fix the bike", version = 2)
    private val lisbon = SomedayItem(id(4), project = false, "Visit Lisbon", 5)
    private val italian = SomedayItem(id(20), project = true, "Learn Italian", 6)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ReviewViewModel(mockk(), mockk(), mockk(), mockk(), actions, ReviewDrafts(), requests)

    private fun TestScope.waitingVm(vararg items: ReviewTask): ReviewViewModel {
        coEvery { actions.waitingFor() } returns ReviewResult.Ok(items.toList())
        return vm().also {
            it.loadStep(ReviewStep.WAITING_FOR)
            advanceUntilIdle()
        }
    }

    private fun TestScope.projectsVm(vararg items: ReviewProject): ReviewViewModel {
        coEvery { actions.projects() } returns ReviewResult.Ok(items.toList())
        return vm().also {
            it.loadStep(ReviewStep.PROJECTS)
            advanceUntilIdle()
        }
    }

    private val ReviewViewModel.state get() = uiState.value

    @Test
    fun `each step loads its items, and a failed load keeps what was shown and says so`() =
        runTest(dispatcher) {
            coEvery { actions.waitingFor() } returns ReviewResult.Ok(listOf(parcel)) andThen ReviewResult.Offline
            coEvery { actions.projects() } returns ReviewResult.Ok(listOf(garden))
            coEvery { actions.staleNextActions() } returns ReviewResult.Ok(listOf(bike))
            coEvery { actions.someday() } returns ReviewResult.Failed(403)
            val vm = vm()

            vm.loadStep(ReviewStep.WAITING_FOR)
            advanceUntilIdle()
            vm.loadStep(ReviewStep.PROJECTS)
            vm.loadStep(ReviewStep.NEXT_ACTIONS)
            vm.loadStep(ReviewStep.SOMEDAY)
            advanceUntilIdle()

            assertEquals(StepData(listOf(parcel)), vm.state.waiting)
            assertEquals(StepData(listOf(garden)), vm.state.projectList)
            assertEquals(StepData(listOf(bike)), vm.state.staleNext)
            assertEquals(StepData<List<SomedayItem>>(failed = true), vm.state.someday)

            vm.loadStep(ReviewStep.WAITING_FOR)
            advanceUntilIdle()
            assertEquals(StepData(listOf(parcel), failed = true), vm.state.waiting)
        }

    @Test
    fun `still waiting removes the item without a server call and announces it`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel, quote)

            vm.keepWaiting(parcel)

            assertEquals(listOf(quote), vm.state.waiting.value)
            assertEquals(ReviewDecision(ReviewDecision.Kind.STILL_WAITING, parcel.title), vm.state.decision)
            coVerify(exactly = 0) { actions.complete(any()) }
            coVerify(exactly = 0) { actions.followUp(any(), any()) }
        }

    @Test
    fun `done is busy while it is saved, ignores a second tap, then the item leaves the list`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel, quote)
            val pending = CompletableDeferred<ReviewResult<Unit>>()
            coEvery { actions.complete(parcel) } coAnswers { pending.await() }

            vm.completeWaiting(parcel)
            runCurrent()
            assertEquals(setOf(parcel.id), vm.state.busy)
            assertEquals(listOf(parcel, quote), vm.state.waiting.value)
            vm.completeWaiting(parcel)
            runCurrent()

            pending.complete(ReviewResult.Ok(Unit))
            advanceUntilIdle()
            assertEquals(emptySet<UUID>(), vm.state.busy)
            assertEquals(listOf(quote), vm.state.waiting.value)
            assertEquals(ReviewDecision(ReviewDecision.Kind.COMPLETED, parcel.title), vm.state.decision)
            coVerify(exactly = 1) { actions.complete(parcel) }
        }

    @Test
    fun `follow up adds the next action under the given title, and the decision names that title`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel)
            coEvery { actions.followUp(parcel, "Ask Erika about: Parcel") } returns ReviewResult.Ok(Unit)

            vm.followUp(parcel, "Ask Erika about: Parcel")
            advanceUntilIdle()

            assertEquals(emptyList<ReviewTask>(), vm.state.waiting.value)
            assertEquals(ReviewDecision(ReviewDecision.Kind.FOLLOWED_UP, "Ask Erika about: Parcel"), vm.state.decision)
            coVerify(exactly = 1) { actions.followUp(parcel, "Ask Erika about: Parcel") }
        }

    @Test
    fun `a refused or offline decision keeps the item, frees it and says why`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel)
            val answers = listOf(ReviewResult.Failed(403), ReviewResult.Offline, ReviewResult.Failed(null))
            coEvery { actions.complete(parcel) } returnsMany answers

            vm.completeWaiting(parcel)
            advanceUntilIdle()
            assertEquals(listOf(parcel), vm.state.waiting.value)
            assertEquals(ReviewMessage.ACTION_FAILED, vm.state.message)
            assertEquals(emptySet<UUID>(), vm.state.busy)
            assertNull(vm.state.decision)

            vm.completeWaiting(parcel)
            advanceUntilIdle()
            assertEquals(ReviewMessage.SAVE_OFFLINE, vm.state.message)

            vm.completeWaiting(parcel)
            advanceUntilIdle()
            assertEquals(ReviewMessage.ACTION_FAILED, vm.state.message)
            assertEquals(listOf(parcel), vm.state.waiting.value)
            // The step was not loaded again for these failures.
            coVerify(exactly = 1) { actions.waitingFor() }
        }

    @Test
    fun `an item changed or removed elsewhere (404, 409, 412) loads the step again, keeping kept items out and the last decision`() =
        runTest(dispatcher) {
            for (status in listOf(404, 409, 412)) {
                coEvery { actions.waitingFor() } returns ReviewResult.Ok(listOf(parcel, quote)) andThen ReviewResult.Ok(listOf(quote))
                coEvery { actions.complete(parcel) } returns ReviewResult.Failed(status)
                val vm = vm()
                vm.loadStep(ReviewStep.WAITING_FOR)
                advanceUntilIdle()
                vm.keepWaiting(quote)

                vm.completeWaiting(parcel)
                advanceUntilIdle()

                assertEquals("status $status", ReviewMessage.ITEM_CHANGED, vm.state.message)
                // The server still has the kept item; it must not come back.
                assertEquals("status $status", emptyList<ReviewTask>(), vm.state.waiting.value)
                assertEquals("status $status", ReviewDecision(ReviewDecision.Kind.STILL_WAITING, quote.title), vm.state.decision)
                coVerify(exactly = 2) { actions.waitingFor() }
                clearMocks(actions)
            }
        }

    @Test
    fun `an item decided while its step loads stays out of the list the load returns`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel, quote)
            val reload = CompletableDeferred<ReviewResult<List<ReviewTask>>>()
            coEvery { actions.waitingFor() } coAnswers { reload.await() }
            coEvery { actions.complete(parcel) } returns ReviewResult.Ok(Unit)

            vm.loadStep(ReviewStep.WAITING_FOR)
            runCurrent()
            vm.completeWaiting(parcel)
            advanceUntilIdle()
            // The load read the list before the completion was saved.
            reload.complete(ReviewResult.Ok(listOf(parcel, quote)))
            advanceUntilIdle()

            assertEquals(listOf(quote), vm.state.waiting.value)
            assertFalse(vm.state.waiting.loading)
        }

    @Test
    fun `an item kept while its step loads stays out of the list the load returns`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel, quote)
            val reload = CompletableDeferred<ReviewResult<List<ReviewTask>>>()
            coEvery { actions.waitingFor() } coAnswers { reload.await() }

            vm.loadStep(ReviewStep.WAITING_FOR)
            runCurrent()
            vm.keepWaiting(parcel)
            reload.complete(ReviewResult.Ok(listOf(parcel, quote)))
            advanceUntilIdle()

            assertEquals(listOf(quote), vm.state.waiting.value)
        }

    @Test
    fun `a step shown again offers the items kept on its last visit again`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel, quote)
            vm.keepWaiting(parcel)

            vm.loadStep(ReviewStep.WAITING_FOR)
            advanceUntilIdle()

            assertEquals(listOf(parcel, quote), vm.state.waiting.value)
        }

    @Test
    fun `a decision finishing after the user moved to another step is not announced there`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel, quote)
            val pending = CompletableDeferred<ReviewResult<Unit>>()
            coEvery { actions.complete(parcel) } coAnswers { pending.await() }
            coEvery { actions.projects() } returns ReviewResult.Ok(listOf(garden))

            vm.completeWaiting(parcel)
            runCurrent()
            vm.loadStep(ReviewStep.PROJECTS)
            pending.complete(ReviewResult.Ok(Unit))
            advanceUntilIdle()

            assertNull(vm.state.decision)
            assertEquals(listOf(quote), vm.state.waiting.value)
        }

    @Test
    fun `showing a step again clears the last decision`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel)
            vm.keepWaiting(parcel)
            assertEquals(ReviewDecision.Kind.STILL_WAITING, vm.state.decision?.kind)

            vm.loadStep(ReviewStep.WAITING_FOR)
            assertNull(vm.state.decision)
        }

    @Test
    fun `projects - looks good removes it, a new status saves it and removes it, the same status does nothing`() =
        runTest(dispatcher) {
            val vm = projectsVm(garden, report)
            coEvery { actions.setStatus(garden, ProjectStatus.ON_HOLD) } returns ReviewResult.Ok(Unit)

            vm.setProjectStatus(report, ProjectStatus.ACTIVE)
            advanceUntilIdle()
            coVerify(exactly = 0) { actions.setStatus(any(), any()) }
            assertNull(vm.state.decision)

            vm.keepProject(report)
            assertEquals(ReviewDecision(ReviewDecision.Kind.KEPT, report.title), vm.state.decision)

            vm.setProjectStatus(garden, ProjectStatus.ON_HOLD)
            advanceUntilIdle()
            assertEquals(emptyList<ReviewProject>(), vm.state.projectList.value)
            assertEquals(
                ReviewDecision(ReviewDecision.Kind.STATUS_CHANGED, garden.title, status = ProjectStatus.ON_HOLD),
                vm.state.decision,
            )
        }

    @Test
    fun `projects - a row asks the Projects screen to open the project`() =
        runTest(dispatcher) {
            val vm = projectsVm(garden)
            vm.openProject(garden)
            assertEquals(garden.id, requests.requests.value)
        }

    @Test
    fun `add next step - an empty draft does nothing, a saved one replaces the project, clears the draft and announces it`() =
        runTest(dispatcher) {
            val vm = projectsVm(garden, report)
            val updated = garden.copy(stalled = false, version = 10)
            val pending = CompletableDeferred<NextStepOutcome>()
            coEvery { actions.addNextStep(garden, "Buy seeds") } coAnswers { pending.await() }

            vm.setNextStepDraft(garden, "   ")
            vm.addNextStep(garden)
            advanceUntilIdle()
            coVerify(exactly = 0) { actions.addNextStep(any(), any()) }

            vm.setNextStepDraft(garden, "  Buy seeds ")
            vm.addNextStep(garden)
            runCurrent()
            assertEquals(setOf(garden.id), vm.state.busy)
            assertEquals("the draft stays while it is saved", mapOf(garden.id to "  Buy seeds "), vm.state.nextStepDrafts)
            pending.complete(NextStepOutcome(ReviewResult.Ok(updated), taskCreated = true))
            advanceUntilIdle()

            assertEquals(listOf(updated, report), vm.state.projectList.value)
            assertEquals(emptyMap<UUID, String>(), vm.state.nextStepDrafts)
            assertEquals(ReviewDecision(ReviewDecision.Kind.NEXT_STEP_ADDED, "Buy seeds", project = garden.title), vm.state.decision)
            assertEquals(emptySet<UUID>(), vm.state.busy)
        }

    @Test
    fun `add next step - a task not created keeps the draft to try again`() =
        runTest(dispatcher) {
            val vm = projectsVm(garden)
            coEvery { actions.addNextStep(garden, "Buy seeds") } returns
                NextStepOutcome(ReviewResult.Offline, taskCreated = false) andThen
                NextStepOutcome(ReviewResult.Failed(422), taskCreated = false)
            vm.setNextStepDraft(garden, "Buy seeds")

            vm.addNextStep(garden)
            advanceUntilIdle()
            assertEquals(mapOf(garden.id to "Buy seeds"), vm.state.nextStepDrafts)
            assertEquals(ReviewMessage.SAVE_OFFLINE, vm.state.message)

            vm.addNextStep(garden)
            advanceUntilIdle()
            assertEquals(mapOf(garden.id to "Buy seeds"), vm.state.nextStepDrafts)
            assertEquals(ReviewMessage.ACTION_FAILED, vm.state.message)
            assertEquals(listOf(garden), vm.state.projectList.value)
            assertNull(vm.state.decision)
        }

    @Test
    fun `add next step - a task created but not made the next step clears the draft and says it was added but not set`() =
        runTest(dispatcher) {
            val vm = projectsVm(garden)
            coEvery { actions.addNextStep(garden, "Buy seeds") } returns NextStepOutcome(ReviewResult.Offline, taskCreated = true)
            vm.setNextStepDraft(garden, "Buy seeds")

            vm.addNextStep(garden)
            advanceUntilIdle()

            assertEquals(emptyMap<UUID, String>(), vm.state.nextStepDrafts)
            assertEquals(ReviewMessage.NEXT_STEP_NOT_SET, vm.state.message)
            assertNull(vm.state.decision)
        }

    @Test
    fun `add next step - a project changed elsewhere loads the projects again and says the task was added but not set`() =
        runTest(dispatcher) {
            coEvery { actions.projects() } returns ReviewResult.Ok(listOf(garden)) andThen ReviewResult.Ok(emptyList())
            coEvery { actions.addNextStep(garden, "Buy seeds") } returns NextStepOutcome(ReviewResult.Failed(412), taskCreated = true)
            val vm = vm()
            vm.loadStep(ReviewStep.PROJECTS)
            advanceUntilIdle()
            vm.setNextStepDraft(garden, "Buy seeds")

            vm.addNextStep(garden)
            advanceUntilIdle()

            assertEquals(ReviewMessage.NEXT_STEP_NOT_SET, vm.state.message)
            assertEquals(emptyList<ReviewProject>(), vm.state.projectList.value)
            coVerify(exactly = 2) { actions.projects() }
        }

    @Test
    fun `next actions - keep, move to someday and delete each remove the item with their decision`() =
        runTest(dispatcher) {
            val old = ReviewTask(id(5), "Call the fictitious insurer", version = 1)
            val stale = ReviewTask(id(6), "Sort photos", version = 1)
            coEvery { actions.staleNextActions() } returns ReviewResult.Ok(listOf(bike, old, stale))
            coEvery { actions.moveToSomeday(old) } returns ReviewResult.Ok(Unit)
            coEvery { actions.delete(stale) } returns ReviewResult.Ok(Unit)
            val vm = vm()
            vm.loadStep(ReviewStep.NEXT_ACTIONS)
            advanceUntilIdle()

            vm.keepNextAction(bike)
            assertEquals(ReviewDecision(ReviewDecision.Kind.KEPT, bike.title), vm.state.decision)
            vm.moveToSomeday(old)
            advanceUntilIdle()
            assertEquals(ReviewDecision(ReviewDecision.Kind.MOVED_TO_SOMEDAY, old.title), vm.state.decision)
            vm.deleteNextAction(stale)
            advanceUntilIdle()
            assertEquals(ReviewDecision(ReviewDecision.Kind.DELETED, stale.title), vm.state.decision)

            assertEquals(emptyList<ReviewTask>(), vm.state.staleNext.value)
            coVerify(exactly = 1) { actions.moveToSomeday(old) }
            coVerify(exactly = 1) { actions.delete(stale) }
        }

    @Test
    fun `someday - activate, keep and delete each remove the item with their decision`() =
        runTest(dispatcher) {
            val garage = SomedayItem(id(7), project = false, "Clear the garage", 1)
            coEvery { actions.someday() } returns ReviewResult.Ok(listOf(lisbon, italian, garage))
            coEvery { actions.activate(italian) } returns ReviewResult.Ok(Unit)
            coEvery { actions.delete(garage) } returns ReviewResult.Ok(Unit)
            val vm = vm()
            vm.loadStep(ReviewStep.SOMEDAY)
            advanceUntilIdle()

            vm.keepSomeday(lisbon)
            assertEquals(ReviewDecision(ReviewDecision.Kind.KEPT, lisbon.title), vm.state.decision)
            vm.activate(italian)
            advanceUntilIdle()
            assertEquals(ReviewDecision(ReviewDecision.Kind.ACTIVATED, italian.title), vm.state.decision)
            vm.deleteSomeday(garage)
            advanceUntilIdle()
            assertEquals(ReviewDecision(ReviewDecision.Kind.DELETED, garage.title), vm.state.decision)

            assertEquals(emptyList<SomedayItem>(), vm.state.someday.value)
        }

    @Test
    fun `a keep tapped while the item's decision is still saved is ignored, so a refused save leaves it to decide again`() =
        runTest(dispatcher) {
            val vm = waitingVm(parcel, quote)
            val pending = CompletableDeferred<ReviewResult<Unit>>()
            coEvery { actions.complete(parcel) } coAnswers { pending.await() }

            vm.completeWaiting(parcel)
            runCurrent()
            vm.keepWaiting(parcel)
            assertEquals(listOf(parcel, quote), vm.state.waiting.value)
            assertNull(vm.state.decision)

            pending.complete(ReviewResult.Failed(403))
            advanceUntilIdle()
            assertEquals(listOf(parcel, quote), vm.state.waiting.value)
            assertEquals(ReviewMessage.ACTION_FAILED, vm.state.message)
        }

    @Test
    fun `add next step - a second tap while it is saved does not add it twice`() =
        runTest(dispatcher) {
            val vm = projectsVm(garden)
            val pending = CompletableDeferred<NextStepOutcome>()
            coEvery { actions.addNextStep(garden, "Buy seeds") } coAnswers { pending.await() }
            vm.setNextStepDraft(garden, "Buy seeds")

            vm.addNextStep(garden)
            runCurrent()
            vm.addNextStep(garden)
            runCurrent()
            pending.complete(NextStepOutcome(ReviewResult.Ok(garden.copy(stalled = false, version = 10)), taskCreated = true))
            advanceUntilIdle()

            coVerify(exactly = 1) { actions.addNextStep(any(), any()) }
        }

    @Test
    fun `add next step finishing after the user moved on updates the project but is not announced in the new step`() =
        runTest(dispatcher) {
            val vm = projectsVm(garden)
            val updated = garden.copy(stalled = false, version = 10)
            val pending = CompletableDeferred<NextStepOutcome>()
            coEvery { actions.addNextStep(garden, "Buy seeds") } coAnswers { pending.await() }
            coEvery { actions.staleNextActions() } returns ReviewResult.Ok(listOf(bike))
            vm.setNextStepDraft(garden, "Buy seeds")

            vm.addNextStep(garden)
            runCurrent()
            vm.loadStep(ReviewStep.NEXT_ACTIONS)
            pending.complete(NextStepOutcome(ReviewResult.Ok(updated), taskCreated = true))
            advanceUntilIdle()

            assertNull(vm.state.decision)
            assertEquals(listOf(updated), vm.state.projectList.value)
            assertEquals(listOf(bike), vm.state.staleNext.value)
        }

    @Test
    fun `a conflict in the projects, next actions or someday step loads that step again and keeps kept items out`() =
        runTest(dispatcher) {
            val keptProject = report
            coEvery { actions.projects() } returns ReviewResult.Ok(listOf(garden, keptProject))
            coEvery { actions.setStatus(garden, ProjectStatus.DROPPED) } returns ReviewResult.Failed(409)
            val oldTask = ReviewTask(id(5), "Call the fictitious insurer", version = 1)
            coEvery { actions.staleNextActions() } returns ReviewResult.Ok(listOf(bike, oldTask))
            coEvery { actions.moveToSomeday(oldTask) } returns ReviewResult.Failed(412)
            coEvery { actions.someday() } returns ReviewResult.Ok(listOf(lisbon, italian))
            coEvery { actions.delete(italian) } returns ReviewResult.Failed(404)
            val vm = vm()

            vm.loadStep(ReviewStep.PROJECTS)
            advanceUntilIdle()
            vm.keepProject(keptProject)
            vm.setProjectStatus(garden, ProjectStatus.DROPPED)
            advanceUntilIdle()
            assertEquals(ReviewMessage.ITEM_CHANGED, vm.state.message)
            assertEquals(listOf(garden), vm.state.projectList.value)
            coVerify(exactly = 2) { actions.projects() }

            vm.loadStep(ReviewStep.NEXT_ACTIONS)
            advanceUntilIdle()
            vm.keepNextAction(bike)
            vm.moveToSomeday(oldTask)
            advanceUntilIdle()
            assertEquals(ReviewMessage.ITEM_CHANGED, vm.state.message)
            assertEquals(listOf(oldTask), vm.state.staleNext.value)
            coVerify(exactly = 2) { actions.staleNextActions() }

            vm.loadStep(ReviewStep.SOMEDAY)
            advanceUntilIdle()
            vm.keepSomeday(lisbon)
            vm.deleteSomeday(italian)
            advanceUntilIdle()
            assertEquals(ReviewMessage.ITEM_CHANGED, vm.state.message)
            assertEquals(listOf(italian), vm.state.someday.value)
            coVerify(exactly = 2) { actions.someday() }
            // Other steps were not loaded again.
            coVerify(exactly = 0) { actions.waitingFor() }
        }

    @Test
    fun `someday - a refused activation keeps the item`() =
        runTest(dispatcher) {
            coEvery { actions.someday() } returns ReviewResult.Ok(listOf(italian))
            coEvery { actions.activate(italian) } returns ReviewResult.Failed(422)
            val vm = vm()
            vm.loadStep(ReviewStep.SOMEDAY)
            advanceUntilIdle()

            vm.activate(italian)
            advanceUntilIdle()

            assertEquals(listOf(italian), vm.state.someday.value)
            assertEquals(ReviewMessage.ACTION_FAILED, vm.state.message)
        }
}
