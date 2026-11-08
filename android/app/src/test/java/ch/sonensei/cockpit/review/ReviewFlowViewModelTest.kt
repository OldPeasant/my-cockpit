package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.habits.HabitsRepository
import ch.sonensei.cockpit.habits.HabitsResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The wizard part of the review view model: start or continue, moves, completion, quick-add and step data. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFlowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<ReviewRepository>()
    private val captures = mockk<CaptureQueue>()
    private val habits = mockk<HabitsRepository>()
    private val reflections = mockk<ReflectionSaver>()

    private val reviewId = UUID.fromString("00000000-0000-7000-8000-000000000009")

    private fun open(
        step: ReviewStep,
        version: Long = 1,
    ) = OpenReview(reviewId, step, Instant.EPOCH, version)

    private fun overview(current: OpenReview?) = ReviewOverview(current, 7, emptyList())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.loaded(current: OpenReview?): ReviewViewModel {
        coEvery { repo.overview() } returns ReviewResult.Ok(overview(current))
        val vm = ReviewViewModel(repo, captures, habits, reflections, mockk())
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `start without an open review starts one on the server and shows the wizard`() =
        runTest(dispatcher) {
            val vm = loaded(null)
            coEvery { repo.start() } returns ReviewResult.Ok(open(ReviewStep.GET_CLEAR))
            vm.start()
            advanceUntilIdle()
            assertEquals(open(ReviewStep.GET_CLEAR), vm.uiState.value.wizardReview)
            assertFalse(vm.uiState.value.saving)
        }

    @Test
    fun `an open review is continued at its step without starting another`() =
        runTest(dispatcher) {
            val vm = loaded(open(ReviewStep.PROJECTS))
            assertNull(vm.uiState.value.wizardReview)
            vm.start()
            advanceUntilIdle()
            assertEquals(
                ReviewStep.PROJECTS,
                vm.uiState.value.wizardReview
                    ?.step,
            )
            coVerify(exactly = 0) { repo.start() }
        }

    @Test
    fun `a failed start offline says so and stays on the overview`() =
        runTest(dispatcher) {
            val vm = loaded(null)
            coEvery { repo.start() } returns ReviewResult.Offline
            vm.start()
            advanceUntilIdle()
            assertNull(vm.uiState.value.wizardReview)
            assertEquals(ReviewMessage.SAVE_OFFLINE, vm.uiState.value.message)
        }

    @Test
    fun `next, previous and jumping save the step on the server`() =
        runTest(dispatcher) {
            val vm = loaded(open(ReviewStep.PAST_CALENDAR, version = 1))
            vm.start()
            coEvery { repo.move(open(ReviewStep.PAST_CALENDAR, 1), ReviewStep.UPCOMING_CALENDAR) } returns
                ReviewResult.Ok(open(ReviewStep.UPCOMING_CALENDAR, 2))
            vm.next()
            advanceUntilIdle()
            assertEquals(open(ReviewStep.UPCOMING_CALENDAR, 2), vm.uiState.value.current)

            coEvery { repo.move(open(ReviewStep.UPCOMING_CALENDAR, 2), ReviewStep.PAST_CALENDAR) } returns
                ReviewResult.Ok(open(ReviewStep.PAST_CALENDAR, 3))
            vm.previous()
            advanceUntilIdle()
            assertEquals(
                ReviewStep.PAST_CALENDAR,
                vm.uiState.value.current
                    ?.step,
            )

            coEvery { repo.move(open(ReviewStep.PAST_CALENDAR, 3), ReviewStep.REFLECTION) } returns
                ReviewResult.Ok(open(ReviewStep.REFLECTION, 4))
            vm.goTo(ReviewStep.REFLECTION)
            advanceUntilIdle()
            assertEquals(
                ReviewStep.REFLECTION,
                vm.uiState.value.current
                    ?.step,
            )
        }

    @Test
    fun `previous on the first step and next on the last do nothing`() =
        runTest(dispatcher) {
            val vm = loaded(open(ReviewStep.GET_CLEAR))
            vm.previous()
            advanceUntilIdle()
            val last = loaded(open(ReviewStep.REFLECTION))
            last.next()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.move(any(), any()) }
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a move refused because the review changed elsewhere reloads it and says why`() =
        runTest(dispatcher) {
            val vm = loaded(open(ReviewStep.GET_CLEAR, 1))
            vm.start()
            coEvery { repo.move(any(), any()) } returns ReviewResult.Failed(412)
            coEvery { repo.overview() } returns ReviewResult.Ok(overview(open(ReviewStep.PROJECTS, 5)))
            vm.next()
            advanceUntilIdle()
            assertEquals(open(ReviewStep.PROJECTS, 5), vm.uiState.value.wizardReview)
            assertEquals(ReviewMessage.CONFLICT, vm.uiState.value.message)
        }

    @Test
    fun `a review completed elsewhere closes the wizard after the reload`() =
        runTest(dispatcher) {
            val vm = loaded(open(ReviewStep.GET_CLEAR, 1))
            vm.start()
            coEvery { repo.move(any(), any()) } returns ReviewResult.Failed(409)
            coEvery { repo.overview() } returns ReviewResult.Ok(overview(null))
            vm.next()
            advanceUntilIdle()
            assertNull(vm.uiState.value.wizardReview)
            assertFalse(vm.uiState.value.wizard)
        }

    @Test
    fun `complete saves the reflection in the journal first, then completes the review`() =
        runTest(dispatcher) {
            val review = open(ReviewStep.REFLECTION, 8)
            val vm = loaded(review)
            vm.start()
            vm.setReflection("  A calm week.  ")
            coEvery { reflections.save("A calm week.") } returns ReflectionOutcome.SAVED
            coEvery { repo.complete(review) } returns ReviewResult.Ok(Unit)
            vm.complete()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertTrue(state.completed)
            assertNull(state.current)
            assertEquals(0L, state.overview?.daysSinceLastReview)
            assertEquals("", state.reflection)
            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                reflections.save("A calm week.")
                repo.complete(review)
            }
        }

    @Test
    fun `without a reflection nothing goes to the journal`() =
        runTest(dispatcher) {
            val review = open(ReviewStep.REFLECTION)
            val vm = loaded(review)
            vm.start()
            vm.setReflection("   ")
            coEvery { repo.complete(review) } returns ReviewResult.Ok(Unit)
            vm.complete()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.completed)
            coVerify(exactly = 0) { reflections.save(any()) }
        }

    @Test
    fun `a reflection that could not be saved keeps the review open and the text`() =
        runTest(dispatcher) {
            val review = open(ReviewStep.REFLECTION)
            val vm = loaded(review)
            vm.start()
            vm.setReflection("Notes")
            coEvery { reflections.save("Notes") } returns ReflectionOutcome.FAILED
            vm.complete()
            advanceUntilIdle()
            assertEquals(ReviewMessage.REFLECTION_FAILED, vm.uiState.value.message)
            assertEquals("Notes", vm.uiState.value.reflection)
            assertEquals(review, vm.uiState.value.wizardReview)
            coVerify(exactly = 0) { repo.complete(any()) }
        }

    @Test
    fun `a failed completion after the reflection was saved does not append it again on retry`() =
        runTest(dispatcher) {
            val review = open(ReviewStep.REFLECTION)
            val vm = loaded(review)
            vm.start()
            vm.setReflection("Notes")
            coEvery { reflections.save("Notes") } returns ReflectionOutcome.SAVED
            coEvery { repo.complete(review) } returns ReviewResult.Offline andThen ReviewResult.Ok(Unit)
            vm.complete()
            advanceUntilIdle()
            assertEquals(ReviewMessage.SAVE_OFFLINE, vm.uiState.value.message)
            vm.complete()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.completed)
            coVerify(exactly = 1) { reflections.save(any()) }
        }

    @Test
    fun `quick-add captures the text into the inbox queue and clears the field`() =
        runTest(dispatcher) {
            val vm = loaded(open(ReviewStep.PAST_CALENDAR))
            coEvery { captures.capture("Call the plumber", null, emptyList()) } returns UUID.randomUUID()
            vm.setCaptureText(" Call the plumber ")
            vm.capture()
            advanceUntilIdle()
            assertEquals("", vm.uiState.value.captureText)
            assertEquals("Call the plumber", vm.uiState.value.captured)
        }

    @Test
    fun `a capture the queue rejects keeps the text and says why`() =
        runTest(dispatcher) {
            val vm = loaded(open(ReviewStep.PAST_CALENDAR))
            coEvery { captures.capture(any(), any(), any()) } throws IllegalArgumentException("too long")
            vm.setCaptureText("x")
            vm.capture()
            advanceUntilIdle()
            assertEquals("x", vm.uiState.value.captureText)
            assertEquals(ReviewMessage.CAPTURE_FAILED, vm.uiState.value.message)
        }

    @Test
    fun `the calendar steps load their events, a failed reload keeps what was shown`() =
        runTest(dispatcher) {
            val vm = loaded(open(ReviewStep.PAST_CALENDAR))
            val event = ReviewEvent("e1", "Dentist", LocalDate.of(2026, 11, 3), null)
            coEvery { repo.events(false) } returns ReviewResult.Ok(listOf(event)) andThen ReviewResult.Offline
            coEvery { repo.events(true) } returns ReviewResult.Failed(500)
            vm.loadStep(ReviewStep.PAST_CALENDAR)
            vm.loadStep(ReviewStep.UPCOMING_CALENDAR)
            advanceUntilIdle()
            assertEquals(StepData(listOf(event)), vm.uiState.value.pastEvents)
            assertEquals(StepData<List<ReviewEvent>>(failed = true), vm.uiState.value.upcomingEvents)

            vm.loadStep(ReviewStep.PAST_CALENDAR)
            advanceUntilIdle()
            assertEquals(StepData(listOf(event), failed = true), vm.uiState.value.pastEvents)
        }

    @Test
    fun `the reflection step loads the habits`() =
        runTest(dispatcher) {
            val vm = loaded(open(ReviewStep.REFLECTION))
            coEvery { habits.list() } returns HabitsResult.Ok(emptyList())
            vm.loadStep(ReviewStep.REFLECTION)
            advanceUntilIdle()
            assertEquals(StepData(emptyList<Nothing>()), vm.uiState.value.habits)
        }
}
