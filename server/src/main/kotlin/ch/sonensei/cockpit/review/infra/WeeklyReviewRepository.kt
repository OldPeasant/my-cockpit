package ch.sonensei.cockpit.review.infra

import ch.sonensei.cockpit.review.domain.WeeklyReview
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WeeklyReviewRepository : JpaRepository<WeeklyReview, UUID> {
	/** The open review, if any (there is at most one). */
	fun findFirstByCompletedAtIsNull(): WeeklyReview?

	/** The most recently completed review. */
	fun findFirstByCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(): WeeklyReview?
}
