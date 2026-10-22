package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
import ch.sonensei.cockpit.core.network.generated.models.ReviewProjectListResponse
import ch.sonensei.cockpit.core.network.generated.models.WeeklyReviewStatusResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** Which review calls the phone makes: only the two reads, and a dropped load is cancelled rather than reported. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRepositoryCallsTest {
    private val api = mockk<WeeklyReviewApi>()

    @Test
    fun `an overview only reads the status and the projects, it never starts, moves or completes a review`() =
        runTest {
            coEvery { api.getWeeklyReviewStatus() } returns Response.success(WeeklyReviewStatusResponse(daysSinceLastReview = 1))
            coEvery { api.getReviewProjects() } returns Response.success(ReviewProjectListResponse(items = emptyList()))

            ReviewRepository(api).overview()

            coVerify(exactly = 1) { api.getWeeklyReviewStatus() }
            coVerify(exactly = 1) { api.getReviewProjects() }
            confirmVerified(api)
        }

    @Test
    fun `a cancelled load is cancelled, not reported as offline or failed`() =
        runTest {
            coEvery { api.getWeeklyReviewStatus() } coAnswers { awaitCancellation() }
            coEvery { api.getReviewProjects() } coAnswers { awaitCancellation() }

            var result: ReviewResult<ReviewOverview>? = null
            val load = launch(start = CoroutineStart.UNDISPATCHED) { result = ReviewRepository(api).overview() }
            runCurrent()
            load.cancel()
            runCurrent()

            assertTrue(load.isCancelled)
            assertNull("a cancelled load must not produce a result", result)
        }
}
