package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
import ch.sonensei.cockpit.core.network.generated.models.MoveWeeklyReviewRequest
import ch.sonensei.cockpit.core.network.generated.models.OccurrenceResponse
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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

    /** The step [offset] steps after this one; `null` before the first or after the last. */
    fun after(offset: Int): ReviewStep? = entries.getOrNull(ordinal + offset)
}

/** The open review: the step the user is on. */
data class OpenReview(
    val id: UUID,
    val step: ReviewStep,
    val startedAt: Instant,
    /** Sent back as `If-Match` when the review is moved or completed. */
    val version: Long = 0,
)

/** An active project of the projects step. */
data class ReviewProject(
    val id: UUID,
    val title: String,
    val stalled: Boolean,
)

/** An event occurrence of the past or upcoming calendar step. */
data class ReviewEvent(
    /** Unique within a step: the event id plus the occurrence of a series. */
    val key: String,
    val title: String,
    /** The local day it starts on. */
    val day: LocalDate,
    /** The local start time; `null` for an all-day event. */
    val time: LocalTime?,
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

/**
 * The weekly review on the server (SPEC.md §8.6). It is the same review session the web wizard uses, so a review
 * started on one device continues on the other.
 */
@Singleton
class ReviewRepository internal constructor(
    private val api: WeeklyReviewApi,
    /** The device's time zone, read at each load, for the day an event starts on. */
    private val zone: () -> ZoneId,
) {
    @Inject
    constructor(api: WeeklyReviewApi) : this(api, ZoneId::systemDefault)

    /** The open review, the last completion and the active projects, loaded together. */
    suspend fun overview(): ReviewResult<ReviewOverview> =
        load {
            coroutineScope {
                val projects = async { body(api.getReviewProjects()).items.map { it.toProject() } }
                val status = body(api.getWeeklyReviewStatus())
                ReviewOverview(status.current?.toOpenReview(), status.daysSinceLastReview, projects.await())
            }
        }

    /** Starts a review; the server answers with the open one when a review is already in progress. */
    suspend fun start(): ReviewResult<OpenReview> = load { body(api.startWeeklyReview()).toOpenReview() }

    /** Saves [step] as the current step of [review] (any step, also an earlier one). */
    suspend fun move(
        review: OpenReview,
        step: ReviewStep,
    ): ReviewResult<OpenReview> =
        load {
            val request = MoveWeeklyReviewRequest(MoveWeeklyReviewRequest.Step.valueOf(step.name))
            body(api.moveWeeklyReview(review.id, request, etag(review.version))).toOpenReview()
        }

    /** Completes [review]. */
    suspend fun complete(review: OpenReview): ReviewResult<Unit> =
        load {
            body(api.completeWeeklyReview(review.id, etag(review.version)))
            Unit
        }

    /** The events of the last 7 days ([upcoming] `false`) or of the next 14 days, by start. */
    suspend fun events(upcoming: Boolean): ReviewResult<List<ReviewEvent>> =
        load {
            val response = if (upcoming) api.getReviewUpcomingEvents() else api.getReviewPastEvents()
            val zone = zone()
            body(response).items.map { it.toReviewEvent(zone) }
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

private fun WeeklyReviewResponse.toOpenReview() = OpenReview(id, ReviewStep.valueOf(step.value), startedAt.toInstant(), version)

private fun ProjectResponse.toProject() = ReviewProject(id, title, stalled)

/** An occurrence as the review shows it: all-day events on their date, timed ones on their local start. */
internal fun OccurrenceResponse.toReviewEvent(zone: ZoneId): ReviewEvent {
    val localStart = start?.atZoneSameInstant(zone)
    val date = startDate
    val day =
        when {
            allDay && date != null -> date
            localStart != null -> localStart.toLocalDate()
            date != null -> date
            else -> throw IllegalArgumentException("An occurrence without a start")
        }
    return ReviewEvent(
        key = eventId.toString() + occurrence.orEmpty().let { if (it.isEmpty()) "" else "@$it" },
        title = title,
        day = day,
        time = if (allDay) null else localStart?.toLocalTime(),
    )
}

private fun etag(version: Long) = "\"$version\""

/** A non-2xx answer; its message carries only the status, never the body. */
private class ReviewHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw ReviewHttpFailure(response.code())
    return body
}
