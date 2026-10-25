package ch.sonensei.cockpit.journal.backup

import ch.sonensei.cockpit.backup.RestoreTestDatabases
import ch.sonensei.cockpit.backup.Zips
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.files.infra.BlobStore
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.JsonNode
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

/**
 * [JournalBackupContributor] (SPEC.md §8.5, §10.2): `data/journal.jsonl`, `data/habits.json`,
 * `data/habit_checks.jsonl`, the mirrors `readable/journal/<yyyy>/<date>.md` and `readable/habits.csv`, and the restore
 * of rows and search documents.
 *
 * Rows are inserted with JDBC so ids, times and versions are under the test's control. Runs against a database of its
 * own, because every test empties all tables.
 */
@SpringBootTest
class JournalBackupContributorTests(
	@Autowired private val backups: BackupService,
	@Autowired private val contributor: JournalBackupContributor,
	@Autowired private val blobs: BlobStore,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
) {
	@TempDir lateinit var work: Path

	@BeforeEach
	fun wipe() = RestoreTestDatabases.wipe(jdbc, dataDir)

	// --- data/ ------------------------------------------------------------------------------------------------------

	@Test
	fun `journal jsonl holds every entry with its fields and sorted tags, the ones in the Trash included`() {
		entry(id(1), D1, "# Good day", mood = 4, tags = listOf("travel", "Café ☕"))
		entry(id(2), D2, "", deletedAt = T2)

		val entries = export().lines("data/journal.jsonl")

		entries.map { it["id"].asString() } shouldContainExactly listOf(id(1).toString(), id(2).toString())
		entries[0]["date"].asString() shouldBe D1.toString()
		entries[0]["bodyMd"].asString() shouldBe "# Good day"
		entries[0]["mood"].asInt() shouldBe 4
		entries[0]["tags"].values().map { it.asString() } shouldContainExactly listOf("Café ☕", "travel")
		entries[0]["createdAt"].asString() shouldBe T0.toString()
		entries[0]["updatedAt"].asString() shouldBe T1.toString()
		entries[0]["version"].asLong() shouldBe 3
		entries[0]["deletedAt"].isNull shouldBe true
		entries[1]["deletedAt"].asString() shouldBe T2.toString()
		entries[1]["mood"].isNull shouldBe true
		entries[1]["bodyMd"].asString() shouldBe ""
		entries[1]["tags"].size() shouldBe 0
	}

	@Test
	fun `habits json holds every habit with its settings, archived ones included, and checks are by habit and date`() {
		habit(id(10), "Run", "TIMES_PER_WEEK", timesPerWeek = 3, reminderTime = LocalTime.of(7, 30), reminderId = id(90))
		habit(id(11), "Piano", "WEEKDAYS", weekdays = listOf("SATURDAY", "MONDAY"), archived = true, targetCount = 2)
		check(id(21), id(11), D2, 2)
		check(id(22), id(10), D2, 1)
		check(id(20), id(10), D1, 5)

		val zip = export()
		val habits = zip.json("data/habits.json").toList()
		val checks = zip.lines("data/habit_checks.jsonl")

		habits.map { it["id"].asString() } shouldContainExactly listOf(id(10).toString(), id(11).toString())
		habits[0]["name"].asString() shouldBe "Run"
		habits[0]["schedule"].asString() shouldBe "TIMES_PER_WEEK"
		habits[0]["timesPerWeek"].asInt() shouldBe 3
		habits[0]["weekdays"].size() shouldBe 0
		habits[0]["targetCount"].asInt() shouldBe 1
		habits[0]["reminderTime"].asString() shouldBe "07:30:00"
		habits[0]["archived"].asBoolean() shouldBe false
		habits[0]["reminderId"].asString() shouldBe id(90).toString()
		habits[0]["createdAt"].asString() shouldBe T0.toString()
		habits[0]["version"].asLong() shouldBe 3
		habits[1]["timesPerWeek"].isNull shouldBe true
		habits[1]["weekdays"].values().map { it.asString() } shouldContainExactly listOf("SATURDAY", "MONDAY")
		habits[1]["targetCount"].asInt() shouldBe 2
		habits[1]["reminderTime"].isNull shouldBe true
		habits[1]["reminderId"].isNull shouldBe true
		habits[1]["archived"].asBoolean() shouldBe true

		checks.map { it["id"].asString() } shouldContainExactly listOf(id(20), id(22), id(21)).map { it.toString() }
		checks[0]["habitId"].asString() shouldBe id(10).toString()
		checks[0]["date"].asString() shouldBe D1.toString()
		checks[0]["count"].asInt() shouldBe 5
		checks[0]["version"].asLong() shouldBe 1
		checks[0]["createdAt"].asString() shouldBe T1.toString()
	}

	@Test
	fun `an empty journal exports empty data files, a header-only habits csv and no entry mirrors, and imports again`() {
		val zip = export()

		zip.lines("data/journal.jsonl").shouldBeEmpty()
		zip.json("data/habits.json").toList().shouldBeEmpty()
		zip.lines("data/habit_checks.jsonl").shouldBeEmpty()
		zip.readable("habits.csv") shouldBe "$CSV_HEADER\r\n"
		zip.readableJournal().keys.shouldBeEmpty()

		wipe()
		import(zip.path)
		listOf("journal_entry", "journal_entry_tag", "habit", "habit_check").forEach { withClue(it) { count(it) shouldBe 0 } }
	}

	// --- readable/journal/ ------------------------------------------------------------------------------------------

	@Test
	fun `each entry not in the Trash is a Markdown file under its year, named after its date`() {
		entry(id(1), D1, "today")
		entry(id(2), LocalDate.of(2025, 12, 31), "last year")
		entry(id(3), LocalDate.of(2024, 2, 29), "leap day")
		entry(id(4), D2, "to forget", deletedAt = T2)

		val journal = export().readableJournal()

		journal.keys shouldBe setOf("2026/2026-03-01.md", "2025/2025-12-31.md", "2024/2024-02-29.md")
		journal.getValue("2025/2025-12-31.md") shouldContain "last year"
		journal.values.forEach { it shouldNotContain "to forget" }
	}

	@Test
	fun `a trashed entry and an active entry on the same date give the active one's mirror only`() {
		entry(id(1), D1, "trashed version", deletedAt = T2)
		entry(id(2), D1, "current version")

		val journal = export().readableJournal()

		journal.keys shouldBe setOf("2026/2026-03-01.md")
		journal.getValue("2026/2026-03-01.md") shouldContain "id: ${id(2)}\n"
		journal.getValue("2026/2026-03-01.md") shouldContain "current version"
	}

	@Test
	fun `front matter holds id, date, mood, tags, links in both directions each once, and times, then the body`() {
		entry(id(1), D1, "Hello\nworld", mood = 4, tags = listOf("b", "a"))
		link(id(30), "JOURNAL_ENTRY", id(1), "TASK", id(50), T1)
		link(id(31), "NOTE", id(51), "JOURNAL_ENTRY", id(1), T0)
		link(id(32), "TASK", id(50), "JOURNAL_ENTRY", id(1), T2, origin = "SYNC")
		link(id(33), "TASK", id(50), "NOTE", id(51), T0)

		val text = export().readableJournal().getValue("2026/2026-03-01.md")

		text shouldBe
			"""
			---
			id: ${id(1)}
			date: 2026-03-01
			mood: 4
			tags: ["a", "b"]
			links: ["note:${id(51)}", "task:${id(50)}"]
			created: $T0
			updated: $T1
			---

			Hello
			world

			""".trimIndent()
	}

	@Test
	fun `links between two entries appear in both mirrors with the journal_entry type`() {
		entry(id(1), D1, "one")
		entry(id(2), D0, "two")
		link(id(30), "JOURNAL_ENTRY", id(1), "JOURNAL_ENTRY", id(2), T0)

		val journal = export().readableJournal()

		journal.getValue("2026/2026-03-01.md") shouldContain "links: [\"journal_entry:${id(2)}\"]\n"
		journal.getValue("2026/2026-02-28.md") shouldContain "links: [\"journal_entry:${id(1)}\"]\n"
	}

	@Test
	fun `an entry without mood, tags or links has null and empty lists, and an empty body leaves only the front matter`() {
		entry(id(1), D1, "")
		entry(id(2), D0, "line\n")
		entry(id(3), D2, "no final newline")

		val journal = export().readableJournal()

		val empty = journal.getValue("2026/2026-03-01.md")
		empty shouldContain "mood: null\n"
		empty shouldContain "tags: []\n"
		empty shouldContain "links: []\n"
		empty shouldEndWith "updated: $T1\n---\n\n"
		journal.getValue("2026/2026-02-28.md") shouldEndWith "---\n\nline\n"
		journal.getValue("2026/2026-03-02.md") shouldEndWith "---\n\nno final newline\n"
	}

	@Test
	fun `image references point to the file in the backup three levels up, missing files keep their reference`() {
		val attachments = folder(id(40), "Attachments")
		val journal = folder(id(43), "Journal [2026]", parent = attachments)
		file(id(41), "sunrise over the lake (1).png", journal)
		entry(
			id(1),
			D1,
			"![Sunrise](cockpit-file:${id(41)})\n![Gone](cockpit-file:${id(42)})\n" +
				"![Again](cockpit-file:${id(41).toString().uppercase()})",
		)

		val exported = export()
		val text = exported.readableJournal().getValue("2026/2026-03-01.md")

		text shouldContain "![Sunrise](../../../files/Attachments/Journal%20%5B2026%5D/sunrise%20over%20the%20lake%20%281%29.png)"
		text shouldContain "![Gone](cockpit-file:${id(42)})"
		text shouldNotContain "cockpit-file:${id(41)}"
		text shouldNotContain "cockpit-file:${id(41).toString().uppercase()}"
		val targets =
			Regex("""\]\(\.\./\.\./\.\./files/([^)]+)\)""")
				.findAll(text)
				.map { URLDecoder.decode(it.groupValues[1], Charsets.UTF_8) }
				.toList()
		targets shouldHaveSize 2
		// readable/journal/<yyyy>/ is three folders below the archive root, where files/ lives.
		targets.forEach { exported.entryNames shouldContain "files/$it" }
	}

	@Test
	fun `images of trashed entries stay in data but are not resolved in any mirror`() {
		val folder = folder(id(40), "Journal")
		file(id(41), "a.png", folder)
		entry(id(1), D1, "![A](cockpit-file:${id(41)})", deletedAt = T2)

		val exported = export()

		exported.readableJournal().keys.shouldBeEmpty()
		exported.lines("data/journal.jsonl").single()["bodyMd"].asString() shouldBe "![A](cockpit-file:${id(41)})"
	}

	@Test
	fun `front matter is valid YAML that gives back tags with quotes, backslashes and control characters`() {
		val tags =
			listOf(
				"back\\slash",
				"say \"x\"",
				"- [x] list",
				"# not a comment",
				"key: value",
				"tab\tend",
				"line\nbreak",
				"bell\u0007",
				"del\u007f",
				"nel\u0085",
				"ls ps ",
				"nonchar￾",
				"keep ~ �😀",
			)
		entry(id(1), D1, "body", tags = tags)
		link(id(30), "JOURNAL_ENTRY", id(1), "TASK", id(50), T0)

		val text = export().readableJournal().values.single()
		val front = Yaml().load<Map<String, Any?>>(text.removePrefix("---\n").substringBefore("\n---\n"))

		listOf("\\u0007", "\\u007f", "\\u0085", "\\u2028", "\\u2029", "\\ufffe").forEach { text shouldContain it }
		front["id"] shouldBe id(1).toString()
		front["mood"] shouldBe null
		(front["tags"] as List<*>).map { it as String } shouldContainExactlyInAnyOrder tags
		front["links"] shouldBe listOf("task:${id(50)}")
	}

	// --- readable/habits.csv ----------------------------------------------------------------------------------------

	@Test
	fun `habits csv has one row per check by habit name and date, habits without checks get one row without date`() {
		habit(id(10), "running", "TIMES_PER_WEEK", timesPerWeek = 3, reminderTime = LocalTime.of(7, 30))
		habit(id(11), "Piano", "WEEKDAYS", weekdays = listOf("SATURDAY", "MONDAY"), archived = true, targetCount = 2)
		habit(id(12), "Water", "DAILY", targetCount = 8)
		habit(id(13), "piano", "DAILY")
		check(id(20), id(10), D1, 1)
		check(id(21), id(10), D0, 2)
		check(id(22), id(12), D1, 8)

		val csv = export().readable("habits.csv")

		csv shouldBe
			listOf(
				CSV_HEADER,
				"${id(11)},Piano,WEEKDAYS,,SATURDAY MONDAY,2,,true,,",
				"${id(13)},piano,DAILY,,,1,,false,,",
				"${id(10)},running,TIMES_PER_WEEK,3,,1,07:30,false,2026-02-28,2",
				"${id(10)},running,TIMES_PER_WEEK,3,,1,07:30,false,2026-03-01,1",
				"${id(12)},Water,DAILY,,,8,,false,2026-03-01,8",
			).joinToString("") { "$it\r\n" }
	}

	@Test
	fun `habit names with commas, quotes, line breaks or formula starts are quoted and defused in the csv`() {
		habit(id(10), "Drink water, 8 glasses", "DAILY")
		habit(id(11), "Say \"hi\"", "DAILY")
		habit(id(12), "=HYPERLINK(\"x\")", "DAILY")
		habit(id(13), "two\nlines", "DAILY")
		habit(id(14), "@sum", "DAILY")

		val csv = export().readable("habits.csv")

		csv shouldContain "${id(10)},\"Drink water, 8 glasses\",DAILY"
		csv shouldContain "${id(11)},\"Say \"\"hi\"\"\",DAILY"
		csv shouldContain "${id(12)},\"'=HYPERLINK(\"\"x\"\")\",DAILY"
		csv shouldContain "${id(13)},\"two\nlines\",DAILY"
		csv shouldContain "${id(14)},'@sum,DAILY"
	}

	@Test
	fun `csv fields are quoted per RFC 4180 and formula starts get a leading apostrophe`() {
		JournalBackupContributor.csv("plain") shouldBe "plain"
		JournalBackupContributor.csv("") shouldBe ""
		JournalBackupContributor.csv("a,b") shouldBe "\"a,b\""
		JournalBackupContributor.csv("a\"b") shouldBe "\"a\"\"b\""
		JournalBackupContributor.csv("a\nb") shouldBe "\"a\nb\""
		JournalBackupContributor.csv("a\rb") shouldBe "\"a\rb\""
		JournalBackupContributor.csv("=1+1") shouldBe "'=1+1"
		JournalBackupContributor.csv("+1") shouldBe "'+1"
		JournalBackupContributor.csv("-1") shouldBe "'-1"
		JournalBackupContributor.csv("@x") shouldBe "'@x"
		JournalBackupContributor.csv("\tx") shouldBe "'\tx"
		JournalBackupContributor.csv("\rx") shouldBe "\"'\rx\""
		JournalBackupContributor.csv("=a,b") shouldBe "\"'=a,b\""
		JournalBackupContributor.csv("a=b") shouldBe "a=b"
	}

	// --- import -----------------------------------------------------------------------------------------------------

	@Test
	fun `import restores entries, tags, habits and checks with their ids, times, versions and Trash state`() {
		entry(id(1), D1, "# Good day", mood = 4, tags = listOf("travel", "Café ☕"))
		entry(id(2), D1, "gone", deletedAt = T2)
		entry(id(3), D0, "")
		habit(id(10), "Run", "TIMES_PER_WEEK", timesPerWeek = 3, reminderTime = LocalTime.of(7, 30), reminderId = id(90))
		habit(id(11), "Piano", "WEEKDAYS", weekdays = listOf("SATURDAY", "MONDAY"), archived = true, targetCount = 2)
		check(id(20), id(10), D1, 5)
		check(id(21), id(11), D0, 2)
		val zip = export()
		val tables = listOf("journal_entry", "journal_entry_tag", "habit", "habit_check")
		val before = tables.associateWith { rows(it) }
		val weekdays = jdbc.queryForList("select id, weekdays::text from habit order by id")
		wipe()

		import(zip.path)

		tables.forEach { withClue(it) { rows(it) shouldBe before.getValue(it) } }
		jdbc.queryForList("select id, weekdays::text from habit order by id") shouldBe weekdays
	}

	@Test
	fun `import indexes habits and the entries not in the Trash for search`() {
		entry(id(1), D1, "# Good **day**")
		entry(id(2), D2, "gone", deletedAt = T2)
		habit(id(10), "Run", "DAILY", archived = true)
		val zip = export()
		wipe()

		import(zip.path)

		jdbc.queryForList("select item_type, item_id, title, body from search_document order by item_type, item_id") shouldBe
			listOf(
				mapOf("item_type" to "HABIT", "item_id" to id(10), "title" to "Run", "body" to ""),
				mapOf("item_type" to "JOURNAL_ENTRY", "item_id" to id(1), "title" to "Journal $D1", "body" to "Good day"),
			)
	}

	// --- helpers ----------------------------------------------------------------------------------------------------

	private class Exported(
		val path: Path,
		private val json: tools.jackson.databind.json.JsonMapper,
	) {
		private val entries: Map<String, ByteArray> = Zips.read(path.readBytes())

		val entryNames: Set<String> get() = entries.keys

		fun lines(name: String): List<JsonNode> =
			String(entries.getValue(name), Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { json.readTree(it) }

		fun json(name: String): JsonNode = json.readTree(entries.getValue(name))

		fun readable(name: String): String = String(entries.getValue("readable/$name"), Charsets.UTF_8)

		fun readableJournal(): Map<String, String> =
			entries
				.filterKeys { it.startsWith("readable/journal/") }
				.map { (name, bytes) -> name.removePrefix("readable/journal/") to String(bytes, Charsets.UTF_8) }
				.toMap()
	}

	private fun export(): Exported {
		val target = Files.createTempFile(work, "backup-", ".zip")
		target.outputStream().use { backups.export(it) }
		return Exported(target, backups.canonicalJson)
	}

	/** Runs only the journal contributor's import, as restore does (one transaction, into an empty install). */
	private fun import(zip: Path) =
		ZipBackupReader.open(zip, backups.canonicalJson).use { reader ->
			transactions.executeWithoutResult { contributor.import(reader) }
		}

	private fun entry(
		id: UUID,
		date: LocalDate,
		body: String,
		mood: Int? = null,
		tags: List<String> = emptyList(),
		deletedAt: Instant? = null,
	) {
		jdbc.update(
			"""insert into journal_entry (id, created_at, updated_at, version, deleted_at, entry_date, body_md, mood)
			values (?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			3,
			deletedAt?.let(Timestamp::from),
			date,
			body,
			mood,
		)
		tags.forEach { jdbc.update("insert into journal_entry_tag (entry_id, tag) values (?, ?)", id, it) }
	}

	private fun habit(
		id: UUID,
		name: String,
		schedule: String,
		timesPerWeek: Int? = null,
		weekdays: List<String> = emptyList(),
		targetCount: Int = 1,
		reminderTime: LocalTime? = null,
		archived: Boolean = false,
		reminderId: UUID? = null,
	) {
		jdbc.update(
			"""insert into habit (id, created_at, updated_at, version, name, schedule, times_per_week, weekdays,
			target_count, reminder_time, archived, reminder_id)
			values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			3,
			name,
			schedule,
			timesPerWeek,
			weekdays.joinToString(",", "[", "]") { "\"$it\"" },
			targetCount,
			reminderTime,
			archived,
			reminderId,
		)
	}

	private fun check(
		id: UUID,
		habitId: UUID,
		date: LocalDate,
		count: Int,
	) {
		jdbc.update(
			"""insert into habit_check (id, created_at, updated_at, version, habit_id, check_date, count)
			values (?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T1),
			Timestamp.from(T2),
			1,
			habitId,
			date,
			count,
		)
	}

	private fun link(
		id: UUID,
		sourceType: String,
		sourceId: UUID,
		targetType: String,
		targetId: UUID,
		createdAt: Instant,
		origin: String = "MANUAL",
	) {
		jdbc.update(
			"""insert into link (id, created_at, updated_at, version, source_type, source_id, target_type, target_id, label, origin)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(createdAt),
			Timestamp.from(createdAt),
			0,
			sourceType,
			sourceId,
			targetType,
			targetId,
			null,
			origin,
		)
	}

	private fun folder(
		id: UUID,
		name: String,
		parent: UUID? = null,
	): UUID {
		jdbc.update(
			"insert into folder (id, created_at, updated_at, version, parent_id, name) values (?, ?, ?, ?, ?, ?)",
			id,
			Timestamp.from(T0),
			Timestamp.from(T0),
			0,
			parent,
			name,
		)
		return id
	}

	private fun file(
		id: UUID,
		name: String,
		folder: UUID,
	) {
		val blob = blobs.write("png".byteInputStream())
		jdbc.update(
			"""insert into file (id, created_at, updated_at, version, folder_id, name, mime, size, sha256, storage_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T0),
			0,
			folder,
			name,
			"image/png",
			blob.size,
			blob.sha256,
			blob.key,
		)
	}

	private fun rows(table: String) = jdbc.queryForList("select * from $table order by 1, 2")

	private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", Long::class.java)

	companion object {
		private const val DATABASE = "journal_backup_contributor"
		private val dataDir: Path = Files.createTempDirectory("cockpit-journal-backup-data")

		private const val CSV_HEADER = "habit_id,name,schedule,times_per_week,weekdays,target_count,reminder_time,archived,date,count"

		private val T0 = Instant.parse("2026-03-01T08:15:30.123456Z")
		private val T1 = Instant.parse("2026-03-02T09:00:00Z")
		private val T2 = Instant.parse("2026-03-03T10:00:00Z")

		private val D0 = LocalDate.of(2026, 2, 28)
		private val D1 = LocalDate.of(2026, 3, 1)
		private val D2 = LocalDate.of(2026, 3, 2)

		/** Fictitious UUIDv7-shaped ids, ordered by [n]. */
		private fun id(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
