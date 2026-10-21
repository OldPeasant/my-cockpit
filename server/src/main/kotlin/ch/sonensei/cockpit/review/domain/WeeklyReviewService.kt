package ch.sonensei.cockpit.review.domain

import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.review.infra.WeeklyReviewRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A started (or resumed) review, and whether it was just [created]. */
data class StartedReview(
	val review: WeeklyReview,
	val created: Boolean,
)

/**
 * The open review, if any, and when the last review was completed: at [lastCompletedAt], [daysSinceLast] days ago in the
 * user's time zone (for "Last weekly review: N days ago").
 */
data class ReviewStatus(
	val current: WeeklyReview?,
	val lastCompletedAt: Instant?,
	val daysSinceLast: Long?,
)

/**
 * Weekly reviews and their progress (SPEC.md §8.6). There is at most one open review: starting one while another is open
 * resumes that one. Methods that change a review take a `checkVersion` callback that sees the current version first
 * (If-Match).
 */
@Service
@Transactional
class WeeklyReviewService(
	private val reviews: WeeklyReviewRepository,
	private val settings: SettingsService,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {
	private val isolated =
		TransactionTemplate(transactionManager).apply {
			propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
		}

	@Transactional(readOnly = true)
	fun status(): ReviewStatus {
		val last = reviews.findFirstByCompletedAtIsNotNullOrderByCompletedAtDescIdDesc()?.completedAt
		val zone = settings.timeZone()
		val days = last?.let { ChronoUnit.DAYS.between(it.atZone(zone).toLocalDate(), LocalDate.now(clock.withZone(zone))) }
		return ReviewStatus(reviews.findFirstByCompletedAtIsNull(), last, days)
	}

	/** @throws NotFoundException (404) when no review is open */
	@Transactional(readOnly = true)
	fun current(): WeeklyReview = reviews.findFirstByCompletedAtIsNull() ?: throw NotFoundException("No weekly review is open")

	/** @throws NotFoundException (404) when no such review exists */
	@Transactional(readOnly = true)
	fun get(id: UUID): WeeklyReview = load(id)

	/**
	 * Starts a review at its first step, or returns the open one unchanged. Two concurrent starts create one review: the
	 * loser of the race (unique index on the open review) returns the winner's.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	fun start(): StartedReview {
		reviews.findFirstByCompletedAtIsNull()?.let { return StartedReview(it, created = false) }
		return try {
			val created = checkNotNull(isolated.execute { reviews.saveAndFlush(WeeklyReview(startedAt = now())) })
			StartedReview(created, created = true)
		} catch (_: DataIntegrityViolationException) {
			StartedReview(current(), created = false)
		}
	}

	/**
	 * Moves the open review [id] to [step] (any step, also back).
	 *
	 * @throws NotFoundException (404) when no such review exists
	 * @throws ConflictException (409) when the review is already completed
	 */
	fun moveTo(
		id: UUID,
		step: ReviewStep,
		checkVersion: (Long) -> Unit,
	): WeeklyReview =
		modify(id, checkVersion) {
			it.step = step
		}

	/**
	 * Completes the open review [id]; afterwards a new one can be started.
	 *
	 * @throws NotFoundException (404) when no such review exists
	 * @throws ConflictException (409) when the review is already completed
	 */
	fun complete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	): WeeklyReview =
		modify(id, checkVersion) {
			it.completedAt = now()
		}

	private fun modify(
		id: UUID,
		checkVersion: (Long) -> Unit,
		change: (WeeklyReview) -> Unit,
	): WeeklyReview {
		val review = load(id)
		checkVersion(review.version)
		if (review.isCompleted) throw ConflictException("The weekly review is already completed")
		change(review)
		return reviews.saveAndFlush(review)
	}

	private fun load(id: UUID): WeeklyReview = reviews.findById(id).orElseThrow { NotFoundException("Weekly review not found") }

	private fun now(): Instant = clock.storableInstant()
}
