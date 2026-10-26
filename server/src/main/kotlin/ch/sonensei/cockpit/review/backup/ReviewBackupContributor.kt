package ch.sonensei.cockpit.review.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.core.backupcontrib.instant
import ch.sonensei.cockpit.core.backupcontrib.instantOrNull
import ch.sonensei.cockpit.core.backupcontrib.toTimestamp
import ch.sonensei.cockpit.core.backupcontrib.toTimestampOrNull
import ch.sonensei.cockpit.core.backupcontrib.uuid
import ch.sonensei.cockpit.review.domain.ReviewStep
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Weekly reviews in backups (SPEC.md §8.6, §10.2): `data/weekly_reviews.jsonl` with every review, the open one (at most
 * one, `completedAt` null) and the completed ones, so "Last weekly review" and an open review survive a restore. No
 * mirror: a review holds no content of its own, only its progress.
 *
 * Restore keeps ids, times, versions and steps. A backup with more than one open review or an unknown step is invalid:
 * the restore validation reports it (and the import fails) instead of breaking the at-most-one-open-review rule.
 *
 * Rows are read and written with JDBC rather than JPA: a restore must keep ids, `created_at`, `updated_at` and
 * `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */
@Component
class ReviewBackupContributor(
	private val jdbc: JdbcTemplate,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		// About one review a week: a few hundred rows in years, so they are read at once rather than streamed.
		val reviews =
			jdbc.query("select * from weekly_review order by id") { rs, _ ->
				WeeklyReviewBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					startedAt = rs.instant("started_at"),
					completedAt = rs.instantOrNull("completed_at"),
					step = rs.getString("step"),
				)
			}
		writer.jsonLines(WEEKLY_REVIEWS, reviews.asSequence())
	}

	override fun import(reader: BackupDataReader) {
		val reviews = validReviews(reader)
		reviews.forEach { review ->
			jdbc.update(
				"""insert into weekly_review (id, created_at, updated_at, version, started_at, completed_at, step)
				values (?, ?, ?, ?, ?, ?, ?)""",
				review.id,
				review.createdAt.toTimestamp(),
				review.updatedAt.toTimestamp(),
				review.version,
				review.startedAt.toTimestamp(),
				review.completedAt.toTimestampOrNull(),
				review.step,
			)
		}
	}

	/** Checked at upload already, so an invalid backup is reported before anything is replaced. */
	override fun restoreWarnings(reader: BackupDataReader): List<String> {
		validReviews(reader)
		return emptyList()
	}

	/** Every review of the backup. @throws InvalidBackupException for an unknown step or more than one open review */
	private fun validReviews(reader: BackupDataReader): List<WeeklyReviewBackup> {
		val reviews = mutableListOf<WeeklyReviewBackup>()
		reader.jsonLines<WeeklyReviewBackup>(WEEKLY_REVIEWS) { reviews += it }
		val steps = ReviewStep.entries.map { it.name }.toSet()
		val problems =
			reviews.filter { it.step !in steps }.map { "data/$WEEKLY_REVIEWS.jsonl: weekly review ${it.id} has an unknown step" } +
				listOfNotNull(
					"data/$WEEKLY_REVIEWS.jsonl has more than one open weekly review".takeIf {
						reviews.count { it.completedAt == null } > 1
					},
				)
		if (problems.isNotEmpty()) throw InvalidBackupException(problems)
		return reviews
	}

	companion object {
		const val ID = "review"
		const val WEEKLY_REVIEWS = "weekly_reviews"
	}
}

/** One line of `data/weekly_reviews.jsonl`. [step] by [ReviewStep] name, as stored; [completedAt] null while open. */
data class WeeklyReviewBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val startedAt: Instant,
	val completedAt: Instant?,
	val step: String,
)
