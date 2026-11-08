package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.habits.HabitsRepository
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
import java.time.Instant
import java.util.UUID

/**
 * Review view model cases where timing or the screen's lifetime matters: an overview load answering after a newer
 * save, the wizard and draft outliving the screen (tab switch), and a quick-add tapped twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelRaceTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<ReviewRepository>()
    private val captures = mockk<CaptureQueue>()
    private val habits = mockk<HabitsRepository>()
    private val reflections = mockk<ReflectionSaver>()

    private val reviewId = UUID.fromString("00000000-0000-7000-8000-000000000029")

    private fun open(
        step: ReviewStep,
        version: Long = 1,
    ) = OpenReview(reviewId, step, Instant.EPOCH, version)

    private fun overview(current: OpenReview?) = ReviewOverview(current, 6, emptyList())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.inWizard(
        current: OpenReview,
        drafts: ReviewDrafts = ReviewDrafts(),
    ): ReviewViewModel {
        coEvery { repo.overview() } returns ReviewResult.Ok(overview(current))
        val vm = ReviewViewModel(repo, captures, habits, reflections, mockk(), drafts)
        vm.refresh()
        advanceUntilIdle()
        vm.start()
        return vm
    }

    @Test
    fun `an overview load answering after a newer move keeps the moved step`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.GET_CLEAR, 1))
            val stale = CompletableDeferred<ReviewResult<ReviewOverview>>()
            coEvery { repo.overview() } coAnswers { stale.await() }
            vm.refresh()
            advanceUntilIdle()
            coEvery { repo.move(open(ReviewStep.GET_CLEAR, 1), ReviewStep.PAST_CALENDAR) } returns
                ReviewResult.Ok(open(ReviewStep.PAST_CALENDAR, 2))
            vm.next()
            advanceUntilIdle()
            stale.complete(ReviewResult.Ok(ReviewOverview(open(ReviewStep.GET_CLEAR, 1), 6, listOf(project))))
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(open(ReviewStep.PAST_CALENDAR, 2), state.wizardReview)
            assertNull(state.message)
            // What else the load brought is still applied.
            assertEquals(listOf(project), state.overview?.projects)
        }

    @Test
    fun `an overview load answering after the completion does not reopen the review`() =
        runTest(dispatcher) {
            val review = open(ReviewStep.REFLECTION, 8)
            val vm = inWizard(review)
            val stale = CompletableDeferred<ReviewResult<ReviewOverview>>()
            coEvery { repo.overview() } coAnswers { stale.await() }
            vm.refresh()
            advanceUntilIdle()
            coEvery { repo.complete(review) } returns ReviewResult.Ok(Unit)
            vm.complete()
            advanceUntilIdle()
            stale.complete(ReviewResult.Ok(overview(review)))
            advanceUntilIdle()
            val state = vm.uiState.value
            assertTrue(state.completed)
            assertNull(state.current)
            assertFalse(state.wizard)
            assertEquals(0L, state.overview?.daysSinceLastReview)
        }

    @Test
    fun `an overview load answering after a new review was started keeps the wizard open`() =
        runTest(dispatcher) {
            coEvery { repo.overview() } returns ReviewResult.Ok(overview(null))
            val vm = ReviewViewModel(repo, captures, habits, reflections, mockk())
            vm.refresh()
            advanceUntilIdle()
            val stale = CompletableDeferred<ReviewResult<ReviewOverview>>()
            coEvery { repo.overview() } coAnswers { stale.await() }
            vm.refresh()
            advanceUntilIdle()
            coEvery { repo.start() } returns ReviewResult.Ok(open(ReviewStep.GET_CLEAR, 0))
            vm.start()
            advanceUntilIdle()
            // The load read the server before the start: no review open yet.
            stale.complete(ReviewResult.Ok(overview(null)))
            advanceUntilIdle()
            assertEquals(open(ReviewStep.GET_CLEAR, 0), vm.uiState.value.wizardReview)
        }

    @Test
    fun `a load started after the move applies the server's newer state`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.GET_CLEAR, 1))
            coEvery { repo.move(any(), any()) } returns ReviewResult.Ok(open(ReviewStep.PAST_CALENDAR, 2))
            vm.next()
            advanceUntilIdle()
            coEvery { repo.overview() } returns ReviewResult.Ok(overview(open(ReviewStep.PROJECTS, 5)))
            vm.refresh()
            advanceUntilIdle()
            assertEquals(open(ReviewStep.PROJECTS, 5), vm.uiState.value.wizardReview)
        }

    @Test
    fun `the open wizard and the reflection draft come back on a new screen after a tab switch`() =
        runTest(dispatcher) {
            val drafts = ReviewDrafts()
            val first = inWizard(open(ReviewStep.REFLECTION), drafts)
            first.setReflection("Fictitious draft")

            // A tab switch drops the review's back-stack entry; coming back creates a new view model.
            val second = ReviewViewModel(repo, captures, habits, reflections, mockk(), drafts)
            second.refresh()
            advanceUntilIdle()
            assertEquals(open(ReviewStep.REFLECTION), second.uiState.value.wizardReview)
            assertEquals("Fictitious draft", second.uiState.value.reflection)
        }

    @Test
    fun `a closed wizard stays closed on a new screen, the draft still comes back`() =
        runTest(dispatcher) {
            val drafts = ReviewDrafts()
            val first = inWizard(open(ReviewStep.REFLECTION), drafts)
            first.setReflection("Fictitious draft")
            first.closeWizard()

            val second = ReviewViewModel(repo, captures, habits, reflections, mockk(), drafts)
            second.refresh()
            advanceUntilIdle()
            assertNull(second.uiState.value.wizardReview)
            assertEquals("Fictitious draft", second.uiState.value.reflection)
        }

    @Test
    fun `a completed review leaves no draft for the next screen`() =
        runTest(dispatcher) {
            val drafts = ReviewDrafts()
            val review = open(ReviewStep.REFLECTION)
            val first = inWizard(review, drafts)
            first.setReflection("Fictitious draft")
            coEvery { reflections.save("Fictitious draft") } returns ReflectionOutcome.SAVED
            coEvery { repo.complete(review) } returns ReviewResult.Ok(Unit)
            first.complete()
            advanceUntilIdle()
            assertEquals("", drafts.reflection(reviewId))
            assertNull(drafts.wizardFor)

            coEvery { repo.overview() } returns ReviewResult.Ok(overview(null))
            val second = ReviewViewModel(repo, captures, habits, reflections, mockk(), drafts)
            second.refresh()
            advanceUntilIdle()
            assertNull(second.uiState.value.wizardReview)
            assertEquals("", second.uiState.value.reflection)
        }

    @Test
    fun `a draft of one review never shows in another`() =
        runTest(dispatcher) {
            val drafts = ReviewDrafts()
            drafts.setReflection(UUID.fromString("00000000-0000-7000-8000-000000000030"), "Other review")
            val vm = inWizard(open(ReviewStep.REFLECTION), drafts)
            assertEquals("", vm.uiState.value.reflection)
        }

    @Test
    fun `a second quick-add while the first is stored captures only once`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.PAST_CALENDAR))
            val stored = CompletableDeferred<UUID>()
            coEvery { captures.capture("Call the plumber", null, emptyList()) } coAnswers { stored.await() }
            vm.setCaptureText("Call the plumber")
            vm.capture()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.capturing)
            vm.capture()
            advanceUntilIdle()
            stored.complete(UUID.randomUUID())
            advanceUntilIdle()
            assertFalse(vm.uiState.value.capturing)
            assertEquals("", vm.uiState.value.captureText)
            coVerify(exactly = 1) { captures.capture(any(), any(), any()) }
        }

    @Test
    fun `text typed while a quick-add is stored stays in the field`() =
        runTest(dispatcher) {
            val vm = inWizard(open(ReviewStep.PAST_CALENDAR))
            val stored = CompletableDeferred<UUID>()
            coEvery { captures.capture("First", null, emptyList()) } coAnswers { stored.await() }
            vm.setCaptureText("First")
            vm.capture()
            advanceUntilIdle()
            vm.setCaptureText("Second")
            stored.complete(UUID.randomUUID())
            advanceUntilIdle()
            assertEquals("Second", vm.uiState.value.captureText)
            assertEquals("First", vm.uiState.value.captured)
        }

    private companion object {
        val project = ReviewProject(UUID.fromString("00000000-0000-7000-8000-000000000031"), "Fictitious project", true)
    }
}
