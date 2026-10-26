package ch.sonensei.cockpit.review.backup

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.review.domain.ReviewStep
import ch.sonensei.cockpit.review.domain.WeeklyReview
import ch.sonensei.cockpit.review.infra.WeeklyReviewRepository
import java.time.Clock
import java.time.Duration

/** A completed weekly review, one completed earlier, and the open one, resumed at a later step. */
class ReviewBackupFixture(
	private val reviews: WeeklyReviewRepository,
	private val clock: Clock,
) : BackupFixture {
	override val contributor = ReviewBackupContributor.ID

	override fun seed() {
		val now = clock.instant()
		reviews.saveAndFlush(
			WeeklyReview(startedAt = now - Duration.ofDays(14)).apply {
				step = ReviewStep.REFLECTION
				completedAt = now - Duration.ofDays(14) + Duration.ofHours(1)
			},
		)
		reviews.saveAndFlush(
			WeeklyReview(startedAt = now - Duration.ofDays(7)).apply {
				step = ReviewStep.SOMEDAY
				completedAt = now - Duration.ofDays(6)
			},
		)
		reviews.saveAndFlush(WeeklyReview(startedAt = now).apply { step = ReviewStep.WAITING_FOR })
	}
}
