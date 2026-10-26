package ch.sonensei.cockpit.bookmarks.backup

import ch.sonensei.cockpit.backup.RestoreTestDatabases
import ch.sonensei.cockpit.backup.Zips
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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

/**
 * [BookmarksBackupContributor] (SPEC.md §8.4, §10.2): `data/bookmarks.jsonl`, the Netscape mirror
 * `readable/bookmarks.html` and the restore of rows, tags and search documents.
 *
 * The mirror is checked by parsing it as Firefox's importer does (`BookmarkHTMLUtils`: an HTML parser, then every
 * `DT > A` of a `DL` with its `HREF`, `ADD_DATE`, `LAST_MODIFIED`, `TAGS` split at commas, `ICON_URI`, and a following
 * `DD` as its description). Rows are inserted with JDBC so ids, times and versions are under the test's control. Runs
 * against a database of its own, because every test empties all tables. All data is fictitious.
 */
@SpringBootTest
class BookmarksBackupContributorTests(
	@Autowired private val backups: BackupService,
	@Autowired private val contributor: BookmarksBackupContributor,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
) {
	@TempDir lateinit var work: Path

	@BeforeEach
	fun wipe() = RestoreTestDatabases.wipe(jdbc, dataDir)

	// --- data/bookmarks.jsonl ---------------------------------------------------------------------------------------

	@Test
	fun `bookmarks jsonl holds every bookmark with all fields and sorted tags, the ones in the Trash included`() {
		full(id(1))
		bookmark(id(2), "https://gone.example.test/", deletedAt = T2, tags = listOf("old"))

		val bookmarks = export().lines("data/bookmarks.jsonl")

		bookmarks.map { it["id"].asString() } shouldContainExactly listOf(id(1), id(2)).map(UUID::toString)
		val full = bookmarks[0]
		full["url"].asString() shouldBe URL
		full["title"].asString() shouldBe "Grüezi «Welt» & <friends>"
		full["description"].asString() shouldBe "A long read\nover two lines ☕"
		full["favicon"].asString() shouldBe "https://example.test/favicon.ico"
		full["readLater"].asBoolean() shouldBe true
		full["readAt"].isNull shouldBe true
		full["snapshotFileId"].asString() shouldBe id(41).toString()
		full["metadataStatus"].asString() shouldBe "FETCHED"
		full["metadataFetch"].asLong() shouldBe 2
		full["tags"].values().map { it.asString() } shouldContainExactly listOf("Reading", "a, b", "zürich").sorted()
		full["createdAt"].asString() shouldBe T0.toString()
		full["updatedAt"].asString() shouldBe T1.toString()
		full["version"].asLong() shouldBe 3
		full["deletedAt"].isNull shouldBe true
		bookmarks[1]["deletedAt"].asString() shouldBe T2.toString()
		bookmarks[1]["title"].isNull shouldBe true
		bookmarks[1]["tags"].values().map { it.asString() } shouldContainExactly listOf("old")
	}

	@Test
	fun `bookmarks jsonl is present and empty when there are no bookmarks`() {
		val exported = export()

		exported.lines("data/bookmarks.jsonl").shouldBeEmpty()
		exported.entries shouldNotContainKey "readable/bookmarks.html"
	}

	// --- readable/bookmarks.html ------------------------------------------------------------------------------------

	@Test
	fun `the html is a Netscape bookmark file that Firefox's importer reads back, newest first, without the Trash`() {
		full(id(1), createdAt = T0)
		bookmark(id(2), "https://example.test/", createdAt = T1)
		bookmark(id(3), "https://read.example.test/done", title = "Already read", readLater = true, readAt = T2, createdAt = T1)
		bookmark(id(4), "https://gone.example.test/", deletedAt = T2, title = "In the Trash")

		val exported = export()
		val html = exported.text("readable/bookmarks.html")
		val imported = firefoxImport(html)

		html shouldStartWith "<!DOCTYPE NETSCAPE-Bookmark-file-1>"
		imported.map { it.url } shouldContainExactly listOf("https://read.example.test/done", "https://example.test/", URL)
		val full = imported[2]
		full.title shouldBe "Grüezi «Welt» & <friends>"
		full.addDate shouldBe T0.epochSecond
		full.lastModified shouldBe T1.epochSecond
		full.tags shouldContainExactly listOf("Reading", "a  b", "zürich").sorted()
		full.icon shouldBe "https://example.test/favicon.ico"
		full.description shouldBe "A long read over two lines ☕"
		full.toRead shouldBe true
		imported[0].title shouldBe "Already read"
		imported[0].toRead shouldBe false
		imported[1].title shouldBe "https://example.test/"
		imported[1].tags.shouldBeEmpty()
		imported[1].icon.shouldBeNull()
		imported[1].description.shouldBeNull()
	}

	@Test
	fun `the html falls back to the url for a blank title, drops a blank description and keeps every entry on one line`() {
		bookmark(id(1), "https://blank.example.test/", title = " \t ", description = " \r\n ")
		bookmark(
			id(2),
			"https://lines.example.test/",
			title = "First\r\nsecond\rthird\nfourth",
			description = "</DL><DT><A HREF=\"https://injected.example.test/\">Injected</A>\r\nmore",
			tags = listOf("say \"hi\"", "<b>"),
			createdAt = T1,
		)

		val html = export().text("readable/bookmarks.html")
		val imported = firefoxImport(html)

		html shouldNotContain "\r"
		imported.map { it.url } shouldContainExactly listOf("https://lines.example.test/", "https://blank.example.test/")
		val lines = html.lines()
		val entry = lines.indexOfFirst { it.contains("https://lines.example.test/") }
		lines[entry] shouldContain ">First second third fourth</A>"
		lines[entry] shouldContain " TAGS=\"&lt;b&gt;,say &quot;hi&quot;\""
		lines[entry + 1] shouldBe
			"    <DD>&lt;/DL&gt;&lt;DT&gt;&lt;A HREF=&quot;https://injected.example.test/&quot;&gt;Injected&lt;/A&gt; more"
		imported[0].tags shouldContainExactly listOf("<b>", "say \"hi\"")
		imported[0].description shouldBe "</DL><DT><A HREF=\"https://injected.example.test/\">Injected</A> more"
		imported[1].title shouldBe "https://blank.example.test/"
		imported[1].description.shouldBeNull()
		html.split("<DD>") shouldHaveSize 2
	}

	@Test
	fun `there is no html when every bookmark is in the Trash`() {
		bookmark(id(1), "https://gone.example.test/", deletedAt = T2)

		val exported = export()

		exported.entries shouldNotContainKey "readable/bookmarks.html"
		exported.lines("data/bookmarks.jsonl") shouldHaveSize 1
	}

	// --- import -----------------------------------------------------------------------------------------------------

	@Test
	fun `import restores every bookmark with its id, times, version, tags, Trash and metadata state`() {
		full(id(1))
		bookmark(id(2), "https://gone.example.test/", deletedAt = T2, tags = listOf("old"))
		bookmark(id(3), "https://pending.example.test/", status = "PENDING")
		val zip = export()
		val bookmarks = rows()
		val tags = tagRows()
		wipe()

		import(zip.path)

		rows() shouldBe bookmarks
		tagRows() shouldBe tags
		bookmarks shouldHaveSize 3
	}

	@Test
	fun `import indexes the bookmarks not in the Trash for search, the trashed ones not`() {
		full(id(1))
		bookmark(id(2), "https://gone.example.test/", deletedAt = T2, title = "Forgotten page")
		bookmark(id(3), "https://www.example.test/plain")
		val zip = export()
		wipe()

		import(zip.path)

		val documents = jdbc.queryForList("select item_type, item_id, title from search_document order by item_id")
		documents.map { it["item_id"] } shouldContainExactly listOf(id(1), id(3))
		documents.forEach { it["item_type"] shouldBe "BOOKMARK" }
		documents[0]["title"] shouldBe "Grüezi «Welt» & <friends>"
		documents[1]["title"] shouldBe "example.test"
		matches("zürich") shouldContainExactly listOf(id(1))
		matches("Forgotten").shouldBeEmpty()
	}

	// --- helpers ----------------------------------------------------------------------------------------------------

	/** A bookmark as Firefox's HTML importer reads it. */
	private data class Imported(
		val url: String,
		val title: String,
		val addDate: Long?,
		val lastModified: Long?,
		val tags: List<String>,
		val icon: String?,
		val toRead: Boolean,
		val description: String?,
	)

	/** Walks the top-level `DL` as Firefox's `BookmarkHTMLUtils` does: `DT > A` is a bookmark, a following `DD` its description. */
	private fun firefoxImport(html: String): List<Imported> {
		val document = Jsoup.parse(html)
		val list = document.selectFirst("dl")!!
		val result = mutableListOf<Imported>()
		list.children().forEach { node: Element ->
			when (node.tagName()) {
				"dt" -> {
					val a = node.selectFirst("> a") ?: return@forEach
					result +=
						Imported(
							url = a.attr("href"),
							title = a.text(),
							addDate = a.attr("add_date").toLongOrNull(),
							lastModified = a.attr("last_modified").toLongOrNull(),
							tags =
								a
									.attr("tags")
									.split(',')
									.map { it.trim() }
									.filter { it.isNotEmpty() },
							icon = a.attr("icon_uri").ifEmpty { null },
							toRead = a.attr("toread") == "1",
							description = null,
						)
				}
				"dd" -> result[result.lastIndex] = result.last().copy(description = node.text())
			}
		}
		return result
	}

	private class Exported(
		val path: Path,
		private val json: tools.jackson.databind.json.JsonMapper,
	) {
		val entries: Map<String, ByteArray> = Zips.read(path.readBytes())

		fun text(name: String): String = String(entries.getValue(name), Charsets.UTF_8)

		fun lines(name: String): List<JsonNode> = text(name).lines().filter { it.isNotBlank() }.map { json.readTree(it) }
	}

	private fun export(): Exported {
		val target = Files.createTempFile(work, "backup-", ".zip")
		target.outputStream().use { backups.export(it) }
		return Exported(target, backups.canonicalJson)
	}

	/** Runs only the bookmarks contributor's import, as restore does (one transaction, into an empty install). */
	private fun import(zip: Path) =
		ZipBackupReader.open(zip, backups.canonicalJson).use { reader ->
			transactions.executeWithoutResult { contributor.import(reader) }
		}

	/** The ids of the search documents whose text search matches [word]. */
	private fun matches(word: String): List<UUID> =
		jdbc
			.queryForList(
				"select item_id from search_document where tsv @@ plainto_tsquery('simple', ?) order by item_id",
				UUID::class.java,
				word,
			).filterNotNull()

	/** A bookmark with every field set: unicode, HTML special characters, a multi-line description, tags with a comma. */
	private fun full(
		id: UUID,
		createdAt: Instant = T0,
	) = bookmark(
		id,
		URL,
		title = "Grüezi «Welt» & <friends>",
		description = "A long read\nover two lines ☕",
		favicon = "https://example.test/favicon.ico",
		readLater = true,
		snapshot = id(41),
		status = "FETCHED",
		fetch = 2,
		tags = listOf("zürich", "Reading", "a, b"),
		createdAt = createdAt,
	)

	private fun bookmark(
		id: UUID,
		url: String,
		title: String? = null,
		description: String? = null,
		favicon: String? = null,
		readLater: Boolean = false,
		readAt: Instant? = null,
		snapshot: UUID? = null,
		status: String = "FAILED",
		fetch: Long = 1,
		tags: List<String> = emptyList(),
		createdAt: Instant = T0,
		deletedAt: Instant? = null,
	) {
		jdbc.update(
			"""insert into bookmark (id, created_at, updated_at, version, deleted_at, url, title, description, favicon,
			read_later, read_at, snapshot_file_id, metadata_status, metadata_fetch)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(createdAt),
			Timestamp.from(T1),
			3,
			deletedAt?.let(Timestamp::from),
			url,
			title,
			description,
			favicon,
			readLater,
			readAt?.let(Timestamp::from),
			snapshot,
			status,
			fetch,
		)
		tags.forEach { jdbc.update("insert into bookmark_tag (bookmark_id, tag) values (?, ?)", id, it) }
	}

	private fun rows() = jdbc.queryForList("select * from bookmark order by id")

	private fun tagRows() = jdbc.queryForList("select * from bookmark_tag order by bookmark_id, tag")

	companion object {
		private const val DATABASE = "bookmarks_backup_contributor"
		private val dataDir: Path = Files.createTempDirectory("cockpit-bookmarks-backup-data")

		/** Runs also when a test failed, so no run leaves its data directory behind. */
		@JvmStatic
		@AfterAll
		fun deleteDataDir() {
			dataDir.toFile().deleteRecursively()
		}

		private const val URL = "https://example.test/articles/grüezi?lang=de&x=\"1\""
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
