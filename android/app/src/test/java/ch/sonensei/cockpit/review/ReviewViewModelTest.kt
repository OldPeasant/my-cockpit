package ch.sonensei.cockpit.review

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<ReviewRepository>()

    private val overview =
        ReviewOverview(
            current = OpenReview(UUID.fromString("00000000-0000-7000-8000-000000000009"), ReviewStep.PROJECTS, Instant.EPOCH),
            daysSinceLastReview = 7,
            projects = emptyList(),
        )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a load shows the overview`() =
        runTest(dispatcher) {
            coEvery { repo.overview() } returns ReviewResult.Ok(overview)
            val vm = ReviewViewModel(repo)
            assertNull(vm.uiState.value.overview)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(ReviewUiState(overview = overview), vm.uiState.value)
        }

    @Test
    fun `a failed load keeps what was shown and says why, a later one clears the message`() =
        runTest(dispatcher) {
            coEvery { repo.overview() } returnsMany
                listOf(ReviewResult.Ok(overview), ReviewResult.Offline, ReviewResult.Failed(403), ReviewResult.Ok(overview))
            val vm = ReviewViewModel(repo)
            vm.refresh()
            advanceUntilIdle()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(overview, vm.uiState.value.overview)
            assertEquals(ReviewMessage.OFFLINE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loading)

            vm.refresh()
            advanceUntilIdle()
            assertEquals(ReviewMessage.FAILED, vm.uiState.value.message)

            vm.refresh()
            advanceUntilIdle()
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a first load that fails shows nothing but the message`() =
        runTest(dispatcher) {
            coEvery { repo.overview() } returns ReviewResult.Failed(null)
            val vm = ReviewViewModel(repo)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(ReviewUiState(message = ReviewMessage.FAILED), vm.uiState.value)
        }
}
