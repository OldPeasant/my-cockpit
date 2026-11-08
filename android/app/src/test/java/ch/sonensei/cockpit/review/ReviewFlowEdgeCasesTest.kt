package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.habits.Habit
import ch.sonensei.cockpit.habits.HabitsRepository
import ch.sonensei.cockpit.habits.HabitsResult
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Edge cases of the review wizard's view model: in-flight guards (double taps), refused and conflicting changes,
 * offline reflection, message dismissal, and step loads that overlap or fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFlowEdgeCasesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<ReviewRepository>()
    private val captures = mockk<CaptureQueue>()
    private val habits = mockk<HabitsRepository>()
    private val reflections = mockk<ReflectionSaver>()

    private val reviewId = UUID.fromString("00000000-0000-7000-8000-000000000019")

    private fun open(
        step: ReviewStep,
        version: Long = 1,
    ) = OpenReview(reviewId, step, Instant.EPOCH, version)

    private fun overview(current: OpenReview?) = ReviewOverview(current, 5, emptyList())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.inWizard(current: OpenReview?): ReviewViewModel {
        coEvery { repo.overview() } returns ReviewResult.Ok(overview(current))
        val vm = ReviewViewModel(repo, captures, habits, reflections)
        vm.refresh()
        advanceUntilIdle()
        if (current != null) vm.start()
        return vm
    }

    @Test
    fun `jumping to the step the review is on saves nothing`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.PROJECTS))
            vm.goTo(ReviewStep.PROJECTS)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.move(any(), any()) }
            assertFalse(vm.uiState.value.saving)
        }

    @Test
    fun `a second tap on next while the first move is saved is ignored`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.GET_CLEAR, 1))
            val answer = CompletableDeferred<ReviewResult<OpenReview>>()
            coEvery { repo.move(any(), any()) } coAnswers { answer.await() }
            vm.next()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.saving)
            vm.next()
            vm.goTo(ReviewStep.SOMEDAY)
            vm.previous()
            answer.complete(ReviewResult.Ok(open(ReviewStep.PAST_CALENDAR, 2)))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.move(any(), any()) }
            assertEquals(open(ReviewStep.PAST_CALENDAR, 2), vm.uiState.value.current)
            assertFalse(vm.uiState.value.saving)
        }

    @Test
    fun `a double tap on complete saves the reflection and completes only once`() =
        runTest(dispatcher) {
            val review = open(ReviewStep.REFLECTION, 7)
            val vm = inWizard(review)
            vm.setReflection("Fictitious notes")
            val saved = CompletableDeferred<ReflectionOutcome>()
            coEvery { reflections.save(any()) } coAnswers { saved.await() }
            coEvery { repo.complete(review) } returns ReviewResult.Ok(Unit)
            vm.complete()
            advanceUntilIdle()
            vm.complete()
            saved.complete(ReflectionOutcome.SAVED)
            advanceUntilIdle()
            coVerify(exactly = 1) { reflections.save(any()) }
            coVerify(exactly = 1) { repo.complete(any()) }
            assertTrue(vm.uiState.value.completed)
        }

    @Test
    fun `a double tap on start starts only one review`() =
        runTest(dispatcher) {
            val vm = inWizard(null)
            val started = CompletableDeferred<ReviewResult<OpenReview>>()
            coEvery { repo.start() } coAnswers { started.await() }
            vm.start()
            advanceUntilIdle()
            vm.start()
            started.complete(ReviewResult.Ok(open(ReviewStep.GET_CLEAR)))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.start() }
            assertEquals(open(ReviewStep.GET_CLEAR), vm.uiState.value.wizardReview)
        }

    @Test
    fun `a move the server refuses for another reason keeps the step and says so without reloading`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.WAITING_FOR, 3))
            coEvery { repo.move(any(), any()) } returns ReviewResult.Failed(400)
            vm.next()
            advanceUntilIdle()
            assertEquals(open(ReviewStep.WAITING_FOR, 3), vm.uiState.value.wizardReview)
            assertEquals(ReviewMessage.SAVE_FAILED, vm.uiState.value.message)
            // Only the first load: a refusal that is not a conflict does not reload.
            coVerify(exactly = 1) { repo.overview() }
        }

    @Test
    fun `an unreadable answer to a move is a refusal, not a conflict`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.WAITING_FOR, 3))
            coEvery { repo.move(any(), any()) } returns ReviewResult.Failed(null)
            vm.previous()
            advanceUntilIdle()
            assertEquals(ReviewMessage.SAVE_FAILED, vm.uiState.value.message)
            coVerify(exactly = 1) { repo.overview() }
        }

    @Test
    fun `completing a review deleted elsewhere reloads and closes the wizard`() =
        runTest(dispatcher) {
            val review = open(ReviewStep.REFLECTION, 4)
            val vm = inWizard(review)
            coEvery { repo.complete(review) } returns ReviewResult.Failed(404)
            coEvery { repo.overview() } returns ReviewResult.Ok(overview(null))
            vm.complete()
            advanceUntilIdle()
            assertNull(vm.uiState.value.wizardReview)
            assertFalse(vm.uiState.value.completed)
            assertEquals(ReviewMessage.CONFLICT, vm.uiState.value.message)
        }

    @Test
    fun `a conflict whose reload fails offline keeps what was shown and says it is offline`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.PROJECTS, 2))
            coEvery { repo.move(any(), any()) } returns ReviewResult.Failed(412)
            coEvery { repo.overview() } returns ReviewResult.Offline
            vm.next()
            advanceUntilIdle()
            assertEquals(open(ReviewStep.PROJECTS, 2), vm.uiState.value.wizardReview)
            assertEquals(ReviewMessage.OFFLINE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `a start refused as a conflict reloads the overview`() =
        runTest(dispatcher) {
            val vm = inWizard(null)
            coEvery { repo.start() } returns ReviewResult.Failed(409)
            coEvery { repo.overview() } returns ReviewResult.Ok(overview(open(ReviewStep.SOMEDAY, 6)))
            vm.start()
            advanceUntilIdle()
            assertEquals(ReviewMessage.CONFLICT, vm.uiState.value.message)
            assertEquals(open(ReviewStep.SOMEDAY, 6), vm.uiState.value.current)
            // The reload shows the overview; the user continues with the button.
            assertNull(vm.uiState.value.wizardReview)
        }

    @Test
    fun `a reflection that cannot reach the journal offline keeps the review open and the text`() =
        runTest(dispatcher) {
            val review = open(ReviewStep.REFLECTION)
            val vm = inWizard(review)
            vm.setReflection("Fictitious notes")
            coEvery { reflections.save("Fictitious notes") } returns ReflectionOutcome.OFFLINE
            vm.complete()
            advanceUntilIdle()
            assertEquals(ReviewMessage.SAVE_OFFLINE, vm.uiState.value.message)
            assertEquals("Fictitious notes", vm.uiState.value.reflection)
            assertEquals(review, vm.uiState.value.wizardReview)
            coVerify(exactly = 0) { repo.complete(any()) }
        }

    @Test
    fun `the reflection draft survives closing and reopening the wizard`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.REFLECTION))
            vm.setReflection("Draft")
            vm.closeWizard()
            assertNull(vm.uiState.value.wizardReview)
            assertEquals(open(ReviewStep.REFLECTION), vm.uiState.value.current)
            vm.start()
            advanceUntilIdle()
            assertEquals("Draft", vm.uiState.value.reflection)
            coVerify(exactly = 0) { repo.start() }
        }

    @Test
    fun `a new review starts with an empty reflection and clears the completed state`() =
        runTest(dispatcher) {
            val vm = inWizard(null)
            vm.setReflection("Left over")
            coEvery { repo.start() } returns ReviewResult.Ok(open(ReviewStep.GET_CLEAR))
            vm.start()
            advanceUntilIdle()
            assertEquals("", vm.uiState.value.reflection)
            assertFalse(vm.uiState.value.completed)
        }

    @Test
    fun `dismissing a message clears it and keeps the wizard`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.WAITING_FOR))
            coEvery { repo.move(any(), any()) } returns ReviewResult.Offline
            vm.next()
            advanceUntilIdle()
            assertEquals(ReviewMessage.SAVE_OFFLINE, vm.uiState.value.message)
            vm.dismissMessage()
            assertNull(vm.uiState.value.message)
            assertEquals(open(ReviewStep.WAITING_FOR), vm.uiState.value.wizardReview)
        }

    @Test
    fun `a new change clears the message of the last one`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.WAITING_FOR, 1))
            coEvery { repo.move(any(), any()) } returns ReviewResult.Offline andThen ReviewResult.Ok(open(ReviewStep.PROJECTS, 2))
            vm.next()
            advanceUntilIdle()
            vm.next()
            advanceUntilIdle()
            assertNull(vm.uiState.value.message)
            assertEquals(open(ReviewStep.PROJECTS, 2), vm.uiState.value.current)
        }

    @Test
    fun `a blank quick-add captures nothing`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.PAST_CALENDAR))
            vm.setCaptureText("   ")
            vm.capture()
            advanceUntilIdle()
            coVerify(exactly = 0) { captures.capture(any(), any(), any()) }
            assertNull(vm.uiState.value.captured)
        }

    @Test
    fun `a quick-add the queue cannot store keeps the text and says why`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.UPCOMING_CALENDAR))
            coEvery { captures.capture(any(), any(), any()) } throws IOException("disk full")
            vm.setCaptureText("Book the venue")
            vm.capture()
            advanceUntilIdle()
            assertEquals("Book the venue", vm.uiState.value.captureText)
            assertEquals(ReviewMessage.CAPTURE_FAILED, vm.uiState.value.message)
            assertNull(vm.uiState.value.captured)
        }

    @Test
    fun `the list steps load nothing`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.GET_CLEAR))
            listOf(ReviewStep.GET_CLEAR, ReviewStep.WAITING_FOR, ReviewStep.PROJECTS, ReviewStep.NEXT_ACTIONS, ReviewStep.SOMEDAY)
                .forEach(vm::loadStep)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.events(any()) }
            coVerify(exactly = 0) { habits.list() }
            assertEquals(StepData<List<ReviewEvent>>(), vm.uiState.value.pastEvents)
        }

    @Test
    fun `a step loaded again drops the older load, even when it answers last`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.PAST_CALENDAR))
            val older = CompletableDeferred<ReviewResult<List<ReviewEvent>>>()
            val stale = ReviewEvent("old", "Stale event", LocalDate.of(2026, 11, 1), null)
            val fresh = ReviewEvent("new", "Fresh event", LocalDate.of(2026, 11, 2), null)
            coEvery { repo.events(false) } coAnswers { older.await() } andThen ReviewResult.Ok(listOf(fresh))
            vm.loadStep(ReviewStep.PAST_CALENDAR)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.pastEvents.loading)
            vm.loadStep(ReviewStep.PAST_CALENDAR)
            advanceUntilIdle()
            older.complete(ReviewResult.Ok(listOf(stale)))
            advanceUntilIdle()
            assertEquals(StepData(listOf(fresh)), vm.uiState.value.pastEvents)
        }

    @Test
    fun `habits that cannot be loaded are marked failed, a later load keeps them`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.REFLECTION))
            val habit = mockk<Habit>()
            coEvery { habits.list() } returns HabitsResult.Offline andThen HabitsResult.Ok(listOf(habit)) andThen HabitsResult.Offline
            vm.loadStep(ReviewStep.REFLECTION)
            advanceUntilIdle()
            assertEquals(StepData<List<Habit>>(failed = true), vm.uiState.value.habits)
            vm.loadStep(ReviewStep.REFLECTION)
            advanceUntilIdle()
            assertEquals(StepData(listOf(habit)), vm.uiState.value.habits)
            vm.loadStep(ReviewStep.REFLECTION)
            advanceUntilIdle()
            assertEquals(StepData(listOf(habit), failed = true), vm.uiState.value.habits)
        }

    @Test
    fun `a refresh that finds the review moved elsewhere shows the new step in the wizard`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.GET_CLEAR, 1))
            coEvery { repo.overview() } returns ReviewResult.Ok(overview(open(ReviewStep.NEXT_ACTIONS, 4)))
            vm.refresh()
            advanceUntilIdle()
            assertEquals(open(ReviewStep.NEXT_ACTIONS, 4), vm.uiState.value.wizardReview)
            assertNull(vm.uiState.value.message)
        }
}
