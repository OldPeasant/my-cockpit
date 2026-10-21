package ch.sonensei.cockpit.review.api

import ch.sonensei.cockpit.calendar.api.OccurrenceResponse
import ch.sonensei.cockpit.gtd.api.ProjectResponse
import ch.sonensei.cockpit.gtd.api.TaskResponse
import ch.sonensei.cockpit.review.domain.ReviewEvents
import ch.sonensei.cockpit.review.domain.ReviewStep
import ch.sonensei.cockpit.review.domain.SomedayItems
import ch.sonensei.cockpit.review.domain.WeeklyReview
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import java.time.Instant
import java.util.UUID

@Schema(description = "A weekly review with its progress. `version` is also sent as the ETag.")
data class WeeklyReviewResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val startedAt: Instant,
	@get:Schema(description = "When the review was completed; `null` while it is open") val completedAt: Instant?,
	@get:Schema(description = "The step the user is on", requiredMode = RequiredMode.REQUIRED) val step: ReviewStep,
	@get:Schema(description = "Number of [step], 1 to 8", requiredMode = RequiredMode.REQUIRED) val stepNumber: Int,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(review: WeeklyReview) =
			WeeklyReviewResponse(
				id = review.id,
				startedAt = review.startedAt,
				completedAt = review.completedAt,
				step = review.step,
				stepNumber = review.step.ordinal + 1,
				createdAt = checkNotNull(review.createdAt),
				updatedAt = checkNotNull(review.updatedAt),
				version = review.version,
			)
	}
}

@Schema(description = "The open weekly review, if any, and when the last one was completed")
data class WeeklyReviewStatusResponse(
	@get:Schema(description = "The open review; `null` when none is open") val current: WeeklyReviewResponse?,
	@get:Schema(description = "When the last review was completed; `null` before the first one") val lastCompletedAt: Instant?,
	@get:Schema(description = "Days from that completion to today, in the user's time zone; `null` before the first review")
	val daysSinceLastReview: Long?,
)

@Schema(description = "The weekly review's new step")
data class MoveWeeklyReviewRequest(
	@get:Schema(description = "Any step, also an earlier one", requiredMode = RequiredMode.REQUIRED)
	val step: ReviewStep? = null,
)

@Schema(description = "Tasks of a review step, in the order the step shows them")
data class ReviewTaskListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<TaskResponse>,
)

@Schema(description = "Projects of a review step, in the order the step shows them")
data class ReviewProjectListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<ProjectResponse>,
)

@Schema(description = "Someday/Maybe: tasks on the Someday list (list order) and SOMEDAY projects (by title)")
data class ReviewSomedayResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val tasks: List<TaskResponse>,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val projects: List<ProjectResponse>,
) {
	companion object {
		fun of(items: SomedayItems) = ReviewSomedayResponse(items.tasks.map(TaskResponse::of), items.projects.map(ProjectResponse::of))
	}
}

@Schema(description = "The occurrences of a calendar step in [from, to), by start; cancelled ones are left out")
data class ReviewEventsResponse(
	@get:Schema(description = "Start of the first day (inclusive)", requiredMode = RequiredMode.REQUIRED) val from: Instant,
	@get:Schema(description = "Start of the day after the last one (exclusive)", requiredMode = RequiredMode.REQUIRED) val to: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<OccurrenceResponse>,
) {
	companion object {
		fun of(events: ReviewEvents) = ReviewEventsResponse(events.from, events.to, events.occurrences.map(OccurrenceResponse::of))
	}
}
