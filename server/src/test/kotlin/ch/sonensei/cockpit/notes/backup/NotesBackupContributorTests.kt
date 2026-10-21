package ch.sonensei.cockpit.notes.backup

import ch.sonensei.cockpit.backup.RestoreTestDatabases
import ch.sonensei.cockpit.backup.Zips
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.files.backup.FilesBackupContributor
import ch.sonensei.cockpit.files.infra.BlobStore
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
import java.util.UUID
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

/**
 * [NotesBackupContributor] (SPEC.md §10.2): `data/notes.jsonl`, `data/note_revisions.jsonl`, the Markdown mirror
 * `readable/notes/` and the restore of rows and search documents.
 *
 * Rows are inserted with JDBC so ids, times and versions are under the test's control. Runs against a database of its
 * own, because every test empties all tables.
 */
@SpringBootTest
class NotesBackupContributorTests(
	@Autowired private val backups: BackupService,
	@Autowired private val contributor: NotesBackupContributor,
	@Autowired private val files: FilesBackupContributor,
	@Autowired private val blobs: BlobStore,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
) {
	@TempDir lateinit var work: Path

	@BeforeEach
	fun wipe() = RestoreTestDatabases.wipe(jdbc, dataDir)

	// --- data/ ------------------------------------------------------------------------------------------------------

	@Test
	fun `notes jsonl holds every note with its fields and sorted tags, the ones in the Trash included`() {
		note(id(1), "Trip", "# Packing", tags = listOf("travel", "Café"), pinned = true)
		note(id(2), null, "", deletedAt = T2, archived = true)

		val notes = export().lines("data/notes.jsonl")

		notes.map { it["id"].asString() } shouldContainExactly listOf(id(1).toString(), id(2).toString())
		notes[0]["title"].asString() shouldBe "Trip"
		notes[0]["bodyMd"].asString() shouldBe "# Packing"
		notes[0]["tags"].values().map { it.asString() } shouldContainExactly listOf("Café", "travel")
		notes[0]["pinned"].asBoolean() shouldBe true
		notes[0]["createdAt"].asString() shouldBe T0.toString()
		notes[0]["version"].asLong() shouldBe 3
		notes[1]["title"].isNull shouldBe true
		notes[1]["deletedAt"].asString() shouldBe T2.toString()
		notes[1]["archived"].asBoolean() shouldBe true
		notes[1]["tags"].size() shouldBe 0
	}

	@Test
	fun `note revisions jsonl holds every revision, by note and number`() {
		note(id(1), "Trip", "now")
		revision(id(11), id(1), 2, null, "before")
		revision(id(10), id(1), 1, "Old", "long ago")

		val revisions = export().lines("data/note_revisions.jsonl")

		revisions.map { it["number"].asInt() } shouldContainExactly listOf(1, 2)
		revisions[0]["title"].asString() shouldBe "Old"
		revisions[0]["bodyMd"].asString() shouldBe "long ago"
		revisions[0]["noteId"].asString() shouldBe id(1).toString()
		revisions[0]["savedAt"].asString() shouldBe T1.toString()
		revisions[1]["title"].isNull shouldBe true
	}

	// --- readable/notes/ --------------------------------------------------------------------------------------------

	@Test
	fun `each note not in the Trash is a Markdown file named after its creation day and title`() {
		note(id(1), "Trip to Zürich!", "body")
		note(id(2), null, "## Café ideas\nmore")
		note(id(3), null, "")
		note(id(4), "Trashed", "gone", deletedAt = T2)

		val names = export().readableNotes().keys

		names shouldBe setOf("2026-03-01-trip-to-zurich.md", "2026-03-01-cafe-ideas.md", "2026-03-01-untitled-note.md")
	}

	@Test
	fun `notes with the same day and title get numbered names, the older one keeps the name`() {
		note(id(2), "Same", "newer", createdAt = T0.plusSeconds(60))
		note(id(1), "Same", "older")
		note(id(3), "Same", "newest", createdAt = T0.plusSeconds(120))

		val notes = export().readableNotes()

		notes.getValue("2026-03-01-same.md") shouldContain "older"
		notes.getValue("2026-03-01-same-2.md") shouldContain "newer"
		notes.getValue("2026-03-01-same-3.md") shouldContain "newest"
	}

	@Test
	fun `front matter holds id, title, tags, links in both directions, times and state, then the body`() {
		note(id(1), "Say \"hi\"", "Hello\nworld", tags = listOf("b", "a"), pinned = true)
		note(id(2), "Other", "see [[note:${id(1)}]]")
		link(id(20), "NOTE", id(1), "TASK", id(30), T1)
		link(id(21), "NOTE", id(2), "NOTE", id(1), T0)
		link(id(22), "NOTE", id(1), "NOTE", id(2), T2, origin = "SYNC")

		val text = export().readableNotes().getValue("2026-03-01-say-hi.md")

		text shouldBe
			"""
			---
			id: ${id(1)}
			title: "Say \"hi\""
			tags: ["a", "b"]
			links: ["note:${id(2)}", "task:${id(30)}"]
			created: $T0
			updated: $T1
			pinned: true
			archived: false
			---

			Hello
			world

			""".trimIndent()
	}

	@Test
	fun `front matter lists links from other item types to the note, but not links between other items`() {
		note(id(1), "Target", "body")
		note(id(2), "Trashed", "gone", deletedAt = T2)
		link(id(20), "EVENT", id(31), "NOTE", id(1), T0)
		link(id(21), "TASK", id(30), "EVENT", id(31), T0)
		link(id(22), "NOTE", id(2), "NOTE", id(1), T1)
		link(id(23), "TASK", id(30), "NOTE", id(2), T1)

		val notes = export().readableNotes()

		notes.keys shouldBe setOf("2026-03-01-target.md")
		notes.getValue("2026-03-01-target.md") shouldContain "links: [\"event:${id(31)}\", \"note:${id(2)}\"]\n"
	}

	@Test
	fun `image references point to the file in the backup, other references stay`() {
		val notes = folder(id(40), "Notes")
		file(id(41), "map of the old town (1).png", notes)
		note(id(1), "Trip", "![Map](cockpit-file:${id(41)})\n![Gone](cockpit-file:${id(42)})")

		val text = export().readableNotes().getValue("2026-03-01-trip.md")

		text shouldContain "![Map](../../files/Notes/map%20of%20the%20old%20town%20%281%29.png)"
		text shouldContain "![Gone](cockpit-file:${id(42)})"
		text shouldNotContain "cockpit-file:${id(41)}"
	}

	@Test
	fun `slugs are lower case ASCII words of at most 60 characters`() {
		NotesBackupContributor.slug("Grüezi «Welt» 👋") shouldBe "gruezi-welt"
		NotesBackupContributor.slug("👋 ☕") shouldBe "note"
		NotesBackupContributor.slug("a".repeat(59) + " bcd") shouldBe "a".repeat(59)
	}

	@Test
	fun `slugs keep exactly 60 characters, cut longer ones and trim separators at both ends`() {
		NotesBackupContributor.slug("b".repeat(60)) shouldBe "b".repeat(60)
		NotesBackupContributor.slug("c".repeat(61)) shouldBe "c".repeat(60)
		NotesBackupContributor.slug("  --Hello, World!--  ") shouldBe "hello-world"
		NotesBackupContributor.slug("Ärger über Öl 2026") shouldBe "arger-uber-ol-2026"
		NotesBackupContributor.slug("") shouldBe "note"
		NotesBackupContributor.slug("   ") shouldBe "note"
	}

	@Test
	fun `the file date is the creation day in UTC, so the same title on another day keeps its name`() {
		note(id(1), "Daily", "late", createdAt = Instant.parse("2026-03-01T23:59:59.999999Z"))
		note(id(2), "Daily", "early", createdAt = Instant.parse("2026-03-02T00:00:00Z"))

		val notes = export().readableNotes()

		notes.keys shouldBe setOf("2026-03-01-daily.md", "2026-03-02-daily.md")
		notes.getValue("2026-03-01-daily.md") shouldContain "late"
		notes.getValue("2026-03-02-daily.md") shouldContain "early"
	}

	@Test
	fun `a numbered name never overwrites a note whose own title gives that name`() {
		note(id(1), "Same", "first")
		note(id(2), "Same", "second", createdAt = T0.plusSeconds(60))
		note(id(3), "Same 2", "third", createdAt = T0.plusSeconds(120))

		val notes = export().readableNotes()

		notes.keys shouldHaveSize 3
		notes.values.map { it.substringAfterLast("---\n\n") } shouldContainExactlyInAnyOrder
			listOf("first\n", "second\n", "third\n")
		notes.getValue("2026-03-01-same.md") shouldContain "first"
		notes.getValue("2026-03-01-same-2.md") shouldContain "second"
	}

	@Test
	fun `front matter is valid YAML that gives back titles and tags with quotes, backslashes and control characters`() {
		val title = "Plan: \"A\" \\ B # not a comment\tend\u0085next\u2028line\u0090"
		// Printable neighbours of the escaped ranges stay raw: space, ~, NBSP, U+2027, U+202A, U+FFFD, an emoji.
		val printable = "keep ~\u00a0\u2027\u202a\ufffd\ud83d\ude00"
		val tags =
			listOf(
				"back\\slash",
				"say \"x\"",
				"- [x] list",
				"@at",
				"bell\u0007",
				"line\nbreak",
				"del\u007f",
				"nel\u0085",
				"ls\u2028ps\u2029",
				"c1\u0080\u0090\u009f",
				"nonchar\ufffe\uffff",
				printable,
			)
		note(id(1), title, "body", tags = tags)

		val text = export().readableNotes().values.single()
		val front = Yaml().load<Map<String, Any?>>(text.removePrefix("---\n").substringBefore("\n---\n"))

		listOf("\\u0085", "\\u2028", "\\u2029", "\\u0080", "\\u0090", "\\u009f", "\\ufffe", "\\uffff").forEach {
			text shouldContain it
		}
		text shouldContain "\"$printable\""
		listOf("\\u0020", "\\u007e", "\\u00a0", "\\u2027", "\\u202a", "\\ufffd", "\\ud83d", "\\ude00").forEach {
			text shouldNotContain it
		}
		front["id"] shouldBe id(1).toString()
		front["title"] shouldBe title
		(front["tags"] as List<*>).map { it as String } shouldContainExactlyInAnyOrder tags
		front["links"] shouldBe emptyList<String>()
		front["pinned"] shouldBe false
		front["archived"] shouldBe false
	}

	@Test
	fun `the body keeps a single final newline and an empty body leaves only the front matter`() {
		note(id(1), "Ends", "line\n")
		note(id(2), "Empty", "")

		val notes = export().readableNotes()

		notes.getValue("2026-03-01-ends.md") shouldEndWith "---\n\nline\n"
		notes.getValue("2026-03-01-empty.md") shouldEndWith "archived: false\n---\n\n"
	}

	@Test
	fun `image links resolve to the exported file entries, also for nested folders, clashing names and upper-case ids`() {
		val trips = folder(id(40), "Trips")
		val year = folder(id(43), "2026 [summer]", parent = trips)
		file(id(41), "map.png", year)
		file(id(42), "map.png", year)
		note(
			id(1),
			"Trip",
			"![A](cockpit-file:${id(41)}) ![B](cockpit-file:${id(42).toString().uppercase()}) ![A again](cockpit-file:${id(41)})",
		)

		val exported = export()
		val text = exported.readableNotes().getValue("2026-03-01-trip.md")
		val targets =
			Regex("""\]\(\.\./\.\./files/([^)]+)\)""").findAll(text).map { URLDecoder.decode(it.groupValues[1], Charsets.UTF_8) }.toList()

		targets shouldHaveSize 3
		targets.toSet() shouldHaveSize 2
		targets.forEach { exported.entryNames shouldContain "files/$it" }
		text shouldNotContain "cockpit-file:"
	}

	@Test
	fun `an untitled note uses its derived title and empty tag and link lists in the front matter`() {
		note(id(1), null, "Lonely thought")

		val text = export().readableNotes().getValue("2026-03-01-lonely-thought.md")

		text shouldContain "title: \"Lonely thought\"\n"
		text shouldContain "links: []\n"
		text shouldContain "tags: []\n"
	}

	// --- FilesBackupContributor.paths -------------------------------------------------------------------------------

	@Test
	fun `files paths returns the export path of existing files only, and nothing for no ids`() {
		val docs = folder(id(40), "Docs")
		file(id(41), "a.png", docs)
		file(id(42), "A.png", docs)

		val paths = transactions.execute { files.paths(setOf(id(41), id(42), id(99))) }

		paths.keys shouldBe setOf(id(41), id(42))
		paths.getValue(id(41)) shouldBe "Docs/a.png"
		paths.getValue(id(42)) shouldBe "Docs/A (2).png"
		export().entryNames.filter { it.startsWith("files/") }.toSet() shouldBe paths.values.map { "files/$it" }.toSet()
		transactions.execute { files.paths(emptySet()) } shouldBe emptyMap()
	}

	// --- import -----------------------------------------------------------------------------------------------------

	@Test
	fun `import restores notes, tags and revisions with their ids, times, versions and Trash state`() {
		note(id(1), "Trip", "# Packing", tags = listOf("travel", "Café"), pinned = true)
		note(id(2), null, "", deletedAt = T2, archived = true)
		revision(id(10), id(1), 1, "Old", "long ago")
		val zip = export()
		val notes = rows("note")
		val tags = rows("note_tag")
		val revisions = rows("note_revision")
		wipe()

		import(zip.path)

		rows("note") shouldBe notes
		rows("note_tag") shouldBe tags
		rows("note_revision") shouldBe revisions
	}

	@Test
	fun `import indexes the notes not in the Trash for search, with the body as plain text`() {
		note(id(1), null, "# Packing **list**")
		note(id(2), "Trashed", "gone", deletedAt = T2)
		val zip = export()
		wipe()

		import(zip.path)

		jdbc.queryForList("select item_type, item_id, title, body from search_document") shouldBe
			listOf(mapOf("item_type" to "NOTE", "item_id" to id(1), "title" to "Packing list", "body" to "Packing list"))
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

		fun readableNotes(): Map<String, String> =
			entries
				.filterKeys { it.startsWith("readable/notes/") }
				.map { (name, bytes) -> name.removePrefix("readable/notes/") to String(bytes, Charsets.UTF_8) }
				.toMap()
	}

	private fun export(): Exported {
		val target = Files.createTempFile(work, "backup-", ".zip")
		target.outputStream().use { backups.export(it) }
		return Exported(target, backups.canonicalJson)
	}

	/** Runs only the notes contributor's import, as restore does (one transaction, into an empty install). */
	private fun import(zip: Path) =
		ZipBackupReader.open(zip, backups.canonicalJson).use { reader ->
			transactions.executeWithoutResult { contributor.import(reader) }
		}

	private fun note(
		id: UUID,
		title: String?,
		body: String,
		tags: List<String> = emptyList(),
		pinned: Boolean = false,
		archived: Boolean = false,
		deletedAt: Instant? = null,
		createdAt: Instant = T0,
	) {
		jdbc.update(
			"""insert into note (id, created_at, updated_at, version, deleted_at, title, body_md, pinned, archived)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(createdAt),
			Timestamp.from(T1),
			3,
			deletedAt?.let(Timestamp::from),
			title,
			body,
			pinned,
			archived,
		)
		tags.forEach { jdbc.update("insert into note_tag (note_id, tag) values (?, ?)", id, it) }
	}

	private fun revision(
		id: UUID,
		noteId: UUID,
		number: Int,
		title: String?,
		body: String,
	) {
		jdbc.update(
			"""insert into note_revision (id, created_at, updated_at, version, note_id, number, title, body_md, saved_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T2),
			Timestamp.from(T2),
			0,
			noteId,
			number,
			title,
			body,
			Timestamp.from(T1),
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

	companion object {
		private const val DATABASE = "notes_backup_contributor"
		private val dataDir: Path = Files.createTempDirectory("cockpit-notes-backup-data")

		private val T0 = Instant.parse("2026-03-01T08:15:30.123456Z")
		private val T1 = Instant.parse("2026-03-02T09:00:00Z")
		private val T2 = Instant.parse("2026-03-03T10:00:00Z")

		/** Fictitious UUIDv7-shaped ids, ordered by [n]. */
		private fun id(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
