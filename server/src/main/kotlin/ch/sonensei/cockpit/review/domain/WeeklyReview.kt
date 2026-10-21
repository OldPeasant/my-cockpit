package ch.sonensei.cockpit.review.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** The steps of the weekly review wizard (SPEC.md §8.6), in order. Stored by name. */
enum class ReviewStep {
	/** Process the Inbox to zero, loose captures from flagged mails. */
	GET_CLEAR,

	/** The calendar of the last 7 days: anything to capture? */
	PAST_CALENDAR,

	/** The calendar of the next 14 days. */
	UPCOMING_CALENDAR,

	/** Each Waiting For item: still waiting / follow up / done. */
	WAITING_FOR,

	/** Each active project, stalled ones first: has a next step? */
	PROJECTS,

	/** Stale next actions: keep / someday / delete. */
	NEXT_ACTIONS,

	/** Someday/Maybe: activate / keep / delete. */
	SOMEDAY,

	/** Habits and journal reflection (optional). */
	REFLECTION,
}

/**
 * One weekly review (SPEC.md §8.6). [step] is the step the user is on, so the review can be resumed on any device. While
 * [completedAt] is `null` the review is open; there is at most one open review.
 */
@Entity
@Table(name = "weekly_review")
class WeeklyReview(
	@Column(name = "started_at", nullable = false, updatable = false)
	val startedAt: Instant,
	id: UUID? = null,
) : BaseEntity(id) {
	@Enumerated(EnumType.STRING)
	@Column(name = "step", nullable = false)
	var step: ReviewStep = ReviewStep.GET_CLEAR

	@Column(name = "completed_at")
	var completedAt: Instant? = null

	val isCompleted: Boolean get() = completedAt != null
}
