package ch.sonensei.cockpit.review.backup

import ch.sonensei.cockpit.backup.RestoreTestDatabases
import ch.sonensei.cockpit.backup.Zips
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.RestoreService
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.review.domain.ReviewStep
import ch.sonensei.cockpit.review.domain.WeeklyReviewService
import ch.sonensei.cockpit.review.infra.WeeklyReviewRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * [ReviewBackupContributor] (SPEC.md §8.6, §10.2): `data/weekly_reviews.jsonl`, the restore of open and completed
 * reviews, and the rejection of a backup that would break the at-most-one-open-review rule or holds an unknown step.
 *
 * Rows are inserted with JDBC so ids, times and versions are under the test's control. Invalid backups are hand-edited
 * exports whose manifest lists the edited file again, so only the review data is wrong. Runs against a database of its
 * own, because every test empties all tables. The clock stands at [MutableClockTestConfiguration.START]. All data is
 * fictitious.
 */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class ReviewBackupContributorTests(
	@Autowired private val backups: BackupService,
	@Autowired private val restores: RestoreService,
	@Autowired private val contributor: ReviewBackupContributor,
	@Autowired private val reviewService: WeeklyReviewService,
	@Autowired private val repository: WeeklyReviewRepository,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
) {
	@TempDir lateinit var work: Path

	@BeforeEach
	fun wipe() = RestoreTestDatabases.wipe(jdbc, dataDir)

	// --- data/weekly_reviews.jsonl ----------------------------------------------------------------------------------

	@Test
	fun `weekly reviews jsonl holds every review by id with all fields, the open one with a null completedAt`() {
		review(id(2), startedAt = T0, completedAt = T1, step = "REFLECTION", version = 4)
		review(id(1), startedAt = T2, completedAt = null, step = "WAITING_FOR", version = 2)

		val reviews = export().lines("data/weekly_reviews.jsonl")

		reviews.map { it["id"].asString() } shouldContainExactly listOf(id(1), id(2)).map(UUID::toString)
		val open = reviews[0]
		open.propertyNames().toSet() shouldBe setOf("id", "createdAt", "updatedAt", "version", "startedAt", "completedAt", "step")
		open["startedAt"].asString() shouldBe T2.toString()
		open["completedAt"].isNull shouldBe true
		open["step"].asString() shouldBe "WAITING_FOR"
		open["version"].asLong() shouldBe 2
		open["createdAt"].asString() shouldBe CREATED.toString()
		open["updatedAt"].asString() shouldBe UPDATED.toString()
		val completed = reviews[1]
		completed["startedAt"].asString() shouldBe T0.toString()
		completed["completedAt"].asString() shouldBe T1.toString()
		completed["step"].asString() shouldBe "REFLECTION"
		completed["version"].asLong() shouldBe 4
	}

	@Test
	fun `weekly reviews jsonl is present and empty when there are no reviews`() {
		val exported = export()

		exported.entries.keys shouldContain "data/weekly_reviews.jsonl"
		exported.lines("data/weekly_reviews.jsonl").shouldBeEmpty()
		exported.entries.keys
			.filter { it.startsWith("readable/") && it.contains("review") }
			.shouldBeEmpty()
	}

	// --- import -----------------------------------------------------------------------------------------------------

	@Test
	fun `import restores every review exactly, with its id, times, version, step and completion`() {
		review(id(1), startedAt = T0, completedAt = T1, step = "REFLECTION", version = 4)
		review(id(2), startedAt = T1, completedAt = LAST_COMPLETED, step = "SOMEDAY", version = 1)
		review(id(3), startedAt = T2, completedAt = null, step = "WAITING_FOR", version = 2)
		val zip = export()
		val reviews = rows()
		wipe()

		import(zip.path)

		reviews shouldHaveSize 3
		rows() shouldBe reviews
	}

	@Test
	fun `after a restore the open review is current, the last completed one is right, and starting resumes it`() {
		review(id(1), startedAt = T0, completedAt = T1, step = "REFLECTION")
		review(id(2), startedAt = T1, completedAt = LAST_COMPLETED, step = "SOMEDAY")
		review(id(3), startedAt = T2, completedAt = null, step = "WAITING_FOR", version = 2)
		val zip = export()
		wipe()

		import(zip.path)

		val status = reviewService.status()
		val current = status.current.shouldNotBeNull()
		current.id shouldBe id(3)
		current.step shouldBe ReviewStep.WAITING_FOR
		current.version shouldBe 2
		status.lastCompletedAt shouldBe LAST_COMPLETED
		status.daysSinceLast shouldBe 7
		reviewService.current().id shouldBe id(3)
		repository.findFirstByCompletedAtIsNull().shouldNotBeNull().id shouldBe id(3)
		repository.findFirstByCompletedAtIsNotNullOrderByCompletedAtDescIdDesc().shouldNotBeNull().id shouldBe id(2)
		val started = reviewService.start()
		started.created shouldBe false
		started.review.id shouldBe id(3)
		repository.count() shouldBe 3
	}

	@Test
	fun `after a restore of only completed reviews none is open and a new one can be started`() {
		review(id(1), startedAt = T0, completedAt = T1, step = "REFLECTION")
		val zip = export()
		wipe()

		import(zip.path)

		val status = reviewService.status()
		status.current.shouldBeNull()
		status.lastCompletedAt shouldBe T1
		reviewService.start().created shouldBe true
	}

	@Test
	fun `a backup without weekly reviews restores none and is valid`() {
		review(id(1), startedAt = T0, completedAt = null, step = "GET_CLEAR")
		val zip = edited { it.remove("data/weekly_reviews.jsonl") }
		wipe()

		val report = restores.validate(zip)
		import(zip)

		report.problems.shouldBeEmpty()
		report.valid shouldBe true
		rows().shouldBeEmpty()
		reviewService.status().current.shouldBeNull()
	}

	// --- validation -------------------------------------------------------------------------------------------------

	@Test
	fun `a valid backup with one open and several completed reviews has no restore warnings or problems`() {
		review(id(1), startedAt = T0, completedAt = T1, step = "REFLECTION")
		review(id(2), startedAt = T1, completedAt = LAST_COMPLETED, step = "SOMEDAY")
		review(id(3), startedAt = T2, completedAt = null, step = "WAITING_FOR")
		val zip = export()

		warnings(zip.path).shouldBeEmpty()
		val report = restores.validate(zip.path)
		report.problems.shouldBeEmpty()
		report.valid shouldBe true
	}

	@Test
	fun `a backup with two open reviews is reported invalid at upload and its import fails without restoring any`() {
		val zip =
			withReviews(
				backup(id(1), completedAt = T1),
				backup(id(2), completedAt = null),
				backup(id(3), completedAt = null),
			)

		val report = restores.validate(zip)
		val warningsFailure = shouldThrow<InvalidBackupException> { warnings(zip) }
		val importFailure = shouldThrow<InvalidBackupException> { import(zip) }

		report.valid shouldBe false
		report.id.shouldBeNull()
		report.problems shouldContain "data/weekly_reviews.jsonl has more than one open weekly review"
		warningsFailure.problems shouldContainExactly listOf("data/weekly_reviews.jsonl has more than one open weekly review")
		importFailure.problems shouldContainExactly listOf("data/weekly_reviews.jsonl has more than one open weekly review")
		rows().shouldBeEmpty()
	}

	@Test
	fun `a backup with an unknown step is reported invalid at upload and its import fails without restoring any`() {
		val zip =
			withReviews(
				backup(id(1), completedAt = T1, step = "REFLECTION"),
				backup(id(2), completedAt = null, step = "DAYDREAMING"),
			)

		val report = restores.validate(zip)
		val importFailure = shouldThrow<InvalidBackupException> { import(zip) }

		report.valid shouldBe false
		report.problems shouldContain "data/weekly_reviews.jsonl: weekly review ${id(2)} has an unknown step"
		importFailure.problems shouldContainExactly listOf("data/weekly_reviews.jsonl: weekly review ${id(2)} has an unknown step")
		rows().shouldBeEmpty()
	}

	@Test
	fun `every problem of an invalid backup is reported at once`() {
		val zip =
			withReviews(
				backup(id(1), completedAt = null, step = "get_clear"),
				backup(id(2), completedAt = null, step = ""),
			)

		val failure = shouldThrow<InvalidBackupException> { warnings(zip) }

		failure.problems shouldContainExactlyInAnyOrder
			listOf(
				"data/weekly_reviews.jsonl: weekly review ${id(1)} has an unknown step",
				"data/weekly_reviews.jsonl: weekly review ${id(2)} has an unknown step",
				"data/weekly_reviews.jsonl has more than one open weekly review",
			)
		restores.validate(zip).problems shouldContainExactlyInAnyOrder failure.problems
	}

	@Test
	fun `every review step name is accepted`() {
		val zip =
			withReviews(
				*ReviewStep.entries
					.mapIndexed { index, step -> backup(id(index + 1), completedAt = T1, step = step.name) }
					.toTypedArray(),
			)

		warnings(zip).shouldBeEmpty()
		import(zip)

		jdbc.queryForList("select step from weekly_review order by id", String::class.java) shouldContainExactly
			ReviewStep.entries.map { it.name }
	}

	// --- helpers ----------------------------------------------------------------------------------------------------

	private class Exported(
		val path: Path,
		private val json: tools.jackson.databind.json.JsonMapper,
	) {
		val entries: Map<String, ByteArray> = Zips.read(path.readBytes())

		fun lines(name: String): List<JsonNode> =
			String(entries.getValue(name), Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { json.readTree(it) }
	}

	private fun export(): Exported {
		val target = Files.createTempFile(work, "backup-", ".zip")
		target.outputStream().use { backups.export(it) }
		return Exported(target, backups.canonicalJson)
	}

	/** An export of the current data, [change]d by hand, with a manifest that lists the changed files again. */
	private fun edited(change: (MutableMap<String, ByteArray>) -> Unit): Path {
		val zip = Zips.edit(export().path.readBytes(), backups.canonicalJson, updateManifest = true, change)
		return Files.createTempFile(work, "edited-", ".zip").also { it.writeBytes(zip) }
	}

	/** A backup of an empty install whose `data/weekly_reviews.jsonl` holds exactly [reviews]. */
	private fun withReviews(vararg reviews: WeeklyReviewBackup): Path =
		edited { entries ->
			entries["data/weekly_reviews.jsonl"] =
				reviews.joinToString("") { backups.canonicalJson.writeValueAsString(it) + "\n" }.toByteArray()
		}

	private fun backup(
		id: UUID,
		completedAt: Instant?,
		step: String = "GET_CLEAR",
	) = WeeklyReviewBackup(id, CREATED, UPDATED, 1, T0, completedAt, step)

	private fun warnings(zip: Path): List<String> = ZipBackupReader.open(zip, backups.canonicalJson).use { contributor.restoreWarnings(it) }

	/** Runs only the review contributor's import, as restore does (one transaction, into an empty install). */
	private fun import(zip: Path) =
		ZipBackupReader.open(zip, backups.canonicalJson).use { reader ->
			transactions.executeWithoutResult { contributor.import(reader) }
		}

	private fun review(
		id: UUID,
		startedAt: Instant,
		completedAt: Instant?,
		step: String,
		version: Long = 1,
	) {
		jdbc.update(
			"""insert into weekly_review (id, created_at, updated_at, version, started_at, completed_at, step)
			values (?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(CREATED),
			Timestamp.from(UPDATED),
			version,
			Timestamp.from(startedAt),
			completedAt?.let(Timestamp::from),
			step,
		)
	}

	private fun rows() = jdbc.queryForList("select * from weekly_review order by id")

	companion object {
		private const val DATABASE = "review_backup_contributor"
		private val dataDir: Path = Files.createTempDirectory("cockpit-review-backup-data")

		/** Runs also when a test failed, so no run leaves its data directory behind. */
		@JvmStatic
		@AfterAll
		fun deleteDataDir() {
			dataDir.toFile().deleteRecursively()
		}

		private val CREATED = Instant.parse("2026-02-01T07:00:00.123456Z")
		private val UPDATED = Instant.parse("2026-02-27T18:30:00Z")
		private val T0 = Instant.parse("2026-02-08T09:15:30.654321Z")
		private val T1 = Instant.parse("2026-02-08T10:45:00Z")

		/** Seven days before the clock's [MutableClockTestConfiguration.START] (2026-03-01T08:00Z), in UTC and in Europe. */
		private val LAST_COMPLETED = Instant.parse("2026-02-22T12:00:00Z")
		private val T2 = Instant.parse("2026-02-28T20:00:00Z")

		/** Fictitious UUIDv7-shaped ids, ordered by [n]. */
		private fun id(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
