package ch.sonensei.cockpit.perf

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.ceil

/**
 * Performance baseline (docs/perf.md): seeds realistic volumes (10k tasks, 2k notes, 50k mail headers, 5k events,
 * all in the search index) and asserts that the p95 of every list and search endpoint stays below [P95_LIMIT_MS].
 *
 * Requests go through MockMvc (security filters, controller, service, database; no network). Each endpoint gets
 * [WARMUP] unmeasured calls, then [SAMPLES] measured ones. The measured percentiles are written to
 * `build/reports/perf/perf-baseline.md` and printed, for docs/perf.md. Seeding uses a fixed random seed and
 * row-derived ids, so every run gets the same data (dates relative to today). Tagged `external` (slow):
 * `./gradlew integrationTest --tests '*PerfBaselineTests'`.
 */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerfBaselineTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private lateinit var contextId: UUID
	private lateinit var projectId: UUID
	private lateinit var folderId: UUID
	private lateinit var accountId: UUID

	@BeforeAll
	fun seed() {
		clean()
		PerfSeed(jdbc).seed().let {
			contextId = it.contextId
			projectId = it.projectId
			folderId = it.inboxFolderId
			accountId = it.accountId
		}
	}

	@AfterAll
	fun clean() {
		PerfSeed(jdbc).clean()
	}

	@Test
	fun `seeded volumes match the baseline`() {
		count("task") shouldBe PerfSeed.TASKS
		count("note") shouldBe PerfSeed.NOTES
		count("mail_message") shouldBe PerfSeed.MAIL_HEADERS
		count("calendar_event") shouldBe PerfSeed.EVENTS
		count("search_document") shouldBe PerfSeed.TASKS + PerfSeed.NOTES + PerfSeed.MAIL_HEADERS + PerfSeed.EVENTS
	}

	@Test
	fun `p95 of list and search endpoints is below the limit`() {
		val today = LocalDate.now(ZoneOffset.UTC)
		val monthStart = today.withDayOfMonth(1)
		val weekStart = today.minusDays(today.dayOfWeek.value - 1L)
		val endpoints =
			listOf(
				"tasks: inbox" to "/api/v1/tasks?list=INBOX",
				"tasks: next actions" to "/api/v1/tasks?list=NEXT",
				"tasks: by context" to "/api/v1/tasks?context=$contextId",
				"tasks: by project" to "/api/v1/tasks?projectId=$projectId",
				"tasks: due this week" to "/api/v1/tasks?due=week",
				"tasks: logbook" to "/api/v1/tasks?completed=true",
				"tasks: calendar overlay" to "/api/v1/tasks?from=$monthStart&to=${monthStart.plusMonths(1).minusDays(1)}",
				"notes: list" to "/api/v1/notes",
				"notes: by tag" to "/api/v1/notes?tag=${PerfSeed.TAGS[0]}",
				"mail: unified inbox" to "/api/v1/mail/messages",
				"mail: unified inbox threaded" to "/api/v1/mail/messages?threaded=true",
				"mail: account inbox" to "/api/v1/mail/messages?accountId=$accountId",
				"mail: folder" to "/api/v1/mail/messages?folderId=$folderId",
				"mail: folder threaded" to "/api/v1/mail/messages?folderId=$folderId&threaded=true",
				"mail: search common word" to "/api/v1/mail/search?q=${PerfSeed.COMMON_WORD}",
				"mail: search rare word" to "/api/v1/mail/search?q=${PerfSeed.RARE_WORD}",
				"mail: search two words" to "/api/v1/mail/search?q=${PerfSeed.COMMON_WORD}+${PerfSeed.MEDIUM_WORD}",
				"calendar: month" to "/api/v1/calendar/events?from=${monthStart}T00:00:00Z&to=${monthStart.plusMonths(1)}T00:00:00Z",
				"calendar: week" to "/api/v1/calendar/events?from=${weekStart}T00:00:00Z&to=${weekStart.plusDays(7)}T00:00:00Z",
				"search: common word" to "/api/v1/search?q=${PerfSeed.COMMON_WORD}",
				"search: rare word" to "/api/v1/search?q=${PerfSeed.RARE_WORD}",
				"search: two words" to "/api/v1/search?q=${PerfSeed.COMMON_WORD}+${PerfSeed.MEDIUM_WORD}",
				"search: prefix" to "/api/v1/search?q=${PerfSeed.COMMON_WORD.take(3)}",
				"search: typo (fuzzy)" to "/api/v1/search?q=${PerfSeed.TYPO_WORD}",
			)
		val results = endpoints.map { (name, url) -> measure(name, url) }
		report(results)
		results.filter { it.p95 >= P95_LIMIT_MS }.map { "${it.name}: p95 ${it.p95} ms" }.shouldBeEmpty()
	}

	private fun measure(
		name: String,
		url: String,
	): Result {
		repeat(WARMUP) { call(url) }
		val millis =
			List(SAMPLES) {
				val start = System.nanoTime()
				call(url)
				(System.nanoTime() - start) / 1_000_000.0
			}.sorted()
		return Result(name, url, percentile(millis, 50.0), percentile(millis, 95.0), millis.last())
	}

	private fun call(url: String) {
		mockMvc.get(url).andExpect { status { isOk() } }
	}

	private fun report(results: List<Result>) {
		val table =
			buildString {
				appendLine("| Endpoint | Request | p50 ms | p95 ms | max ms |")
				appendLine("|---|---|---:|---:|---:|")
				results.forEach {
					appendLine(
						"| ${it.name} | `GET ${it.url.replace(
							UUID_PATTERN,
							"{id}",
						).replace(DATE_PATTERN, "{date}")}` | ${fmt(it.p50)} | ${fmt(it.p95)} | ${fmt(it.max)} |",
					)
				}
			}
		val file = Path.of("build", "reports", "perf", "perf-baseline.md")
		Files.createDirectories(file.parent)
		Files.writeString(file, table)
		println("Performance baseline ($SAMPLES samples after $WARMUP warm-up calls per endpoint):\n$table")
	}

	private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java) ?: 0

	private data class Result(
		val name: String,
		val url: String,
		val p50: Double,
		val p95: Double,
		val max: Double,
	)

	companion object {
		const val P95_LIMIT_MS = 200.0
		const val WARMUP = 10
		const val SAMPLES = 50
		private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
		private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

		/** Nearest-rank percentile of [sorted] values. */
		fun percentile(
			sorted: List<Double>,
			p: Double,
		): Double = sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]

		private fun fmt(value: Double) = "%.1f".format(java.util.Locale.ROOT, value)
	}
}
