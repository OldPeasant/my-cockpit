package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
import ch.sonensei.cockpit.core.network.generated.models.ProjectResponse
import ch.sonensei.cockpit.core.network.generated.models.WeeklyReviewResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReviewModule {
    @Provides
    fun weeklyReviewApi(retrofit: Retrofit): WeeklyReviewApi = retrofit.create(WeeklyReviewApi::class.java)
}

/** The eight steps of the weekly review (SPEC.md §8.6), in their order. */
enum class ReviewStep {
    GET_CLEAR,
    PAST_CALENDAR,
    UPCOMING_CALENDAR,
    WAITING_FOR,
    PROJECTS,
    NEXT_ACTIONS,
    SOMEDAY,
    REFLECTION,
    ;

    /** 1 to 8. */
    val number: Int get() = ordinal + 1
}

/** The open review: the step the user is on. */
data class OpenReview(
    val id: UUID,
    val step: ReviewStep,
    val startedAt: Instant,
)

/** An active project of the projects step. */
data class ReviewProject(
    val id: UUID,
    val title: String,
    val stalled: Boolean,
)

/** What the phone shows of the weekly review. */
data class ReviewOverview(
    /** `null` when no review is open. */
    val current: OpenReview?,
    /** `null` before the first completed review. */
    val daysSinceLastReview: Long?,
    /** Active projects, stalled ones first (the server's order). */
    val projects: List<ReviewProject>,
) {
    val stalledProjects: List<ReviewProject> get() = projects.filter { it.stalled }
}

/** The outcome of a load. */
sealed interface ReviewResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ReviewResult<T>

    /** No connection (or the server is down). */
    data object Offline : ReviewResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : ReviewResult<Nothing>
}

/** The weekly review on the server, read only: the phone shows the progress, the wizard itself runs on the web. */
@Singleton
class ReviewRepository
    @Inject
    constructor(
        private val api: WeeklyReviewApi,
    ) {
        /** The open review, the last completion and the active projects, loaded together. */
        suspend fun overview(): ReviewResult<ReviewOverview> =
            load {
                coroutineScope {
                    val projects = async { body(api.getReviewProjects()).items.map { it.toProject() } }
                    val status = body(api.getWeeklyReviewStatus())
                    ReviewOverview(status.current?.toOpenReview(), status.daysSinceLastReview, projects.await())
                }
            }

        private suspend fun <T> load(block: suspend () -> T): ReviewResult<T> =
            try {
                ReviewResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                ReviewResult.Offline
            } catch (e: ReviewHttpFailure) {
                if (e.status >= SERVER_ERROR) ReviewResult.Offline else ReviewResult.Failed(e.status)
            } catch (_: SerializationException) {
                ReviewResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                ReviewResult.Failed(null)
            } catch (_: DateTimeException) {
                ReviewResult.Failed(null)
            }

        private companion object {
            const val SERVER_ERROR = 500
        }
    }

private fun WeeklyReviewResponse.toOpenReview() = OpenReview(id, ReviewStep.valueOf(step.value), startedAt.toInstant())

private fun ProjectResponse.toProject() = ReviewProject(id, title, stalled)

/** A non-2xx answer; its message carries only the status, never the body. */
private class ReviewHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw ReviewHttpFailure(response.code())
    return body
}
