package ch.sonensei.cockpit.files.backup

import ch.sonensei.cockpit.backup.RestoreTestDatabases
import ch.sonensei.cockpit.backup.Zips
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.files.infra.BlobStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * [FilesBackupContributor] (SPEC.md §10.2): `data/folders.json`, `data/files.jsonl` and the blobs under `files/` in the
 * original tree, with clashes resolved by ` (2)`, and the restore of rows, blobs and search documents.
 *
 * Rows are inserted with JDBC so ids, times and versions are fixed and the tie-breaks (smaller id wins) are under the
 * test's control. Runs against a database of its own, because every test empties all tables.
 */
@SpringBootTest
class FilesBackupContributorTests(
	@Autowired private val backups: BackupService,
	@Autowired private val contributor: FilesBackupContributor,
	@Autowired private val blobs: BlobStore,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
) {
	@TempDir lateinit var work: Path

	@BeforeEach
	fun wipe() = RestoreTestDatabases.wipe(jdbc, dataDir)

	// --- export: paths below files/ ---------------------------------------------------------------------------------

	@Test
	fun `blobs are stored in the original folder tree with their original names`() {
		val documents = folder(id(1), "Documents")
		val tax = folder(id(2), "tax", documents)
		file(id(10), "2025.pdf", tax, "%PDF tax")
		file(id(11), "readme.txt", null, "at the root")

		val zip = export()

		zip.blobPaths() shouldContainExactlyInAnyOrder listOf("Documents/tax/2025.pdf", "readme.txt")
		zip.blob("Documents/tax/2025.pdf") shouldBe "%PDF tax"
		zip.blob("readme.txt") shouldBe "at the root"
	}

	@Test
	fun `the same name twice in a folder gives the newer file a numbered name before its extension`() {
		val tax = folder(id(1), "tax")
		// Inserted newest first: the smaller id keeps the name, not the first row written.
		file(id(12), "2025.pdf", tax, "third")
		file(id(11), "2025.pdf", tax, "second")
		file(id(10), "2025.pdf", tax, "first")

		val zip = export()

		zip.pathsById() shouldBe mapOf(id(10) to "tax/2025.pdf", id(11) to "tax/2025 (2).pdf", id(12) to "tax/2025 (3).pdf")
		zip.blob("tax/2025.pdf") shouldBe "first"
		zip.blob("tax/2025 (2).pdf") shouldBe "second"
		zip.blob("tax/2025 (3).pdf") shouldBe "third"
	}

	@Test
	fun `names that differ only in case clash, so the tree extracts on case-insensitive file systems`() {
		val tax = folder(id(1), "tax")
		file(id(10), "Scan.PDF", tax, "upper")
		file(id(11), "scan.pdf", tax, "lower")

		val paths = export().pathsById()

		paths shouldBe mapOf(id(10) to "tax/Scan.PDF", id(11) to "tax/scan (2).pdf")
	}

	@Test
	fun `a numbered name that already exists is skipped`() {
		val tax = folder(id(1), "tax")
		file(id(10), "a.pdf", tax, "a")
		file(id(11), "a (2).pdf", tax, "a (2) uploaded as such")
		file(id(12), "a.pdf", tax, "a again")

		val paths = export().pathsById()

		paths shouldBe mapOf(id(10) to "tax/a.pdf", id(11) to "tax/a (2).pdf", id(12) to "tax/a (3).pdf")
	}

	@Test
	fun `a file named like a sibling folder is renamed, the folder keeps its name`() {
		val documents = folder(id(1), "Documents")
		val tax = folder(id(2), "tax", documents)
		// The file is older than the folder: the folder still wins.
		file(id(0), "TAX", documents, "a file named like its sibling folder")
		file(id(10), "2025.pdf", tax, "%PDF")

		val zip = export()

		zip.pathsById() shouldBe mapOf(id(0) to "Documents/TAX (2)", id(10) to "Documents/tax/2025.pdf")
		zip.folderPaths() shouldBe mapOf(id(1) to "Documents", id(2) to "Documents/tax")
	}

	@Test
	fun `a trashed folder next to an active one of the same name is renamed, the active one keeps the name`() {
		val documents = folder(id(1), "Documents")
		// The trashed folder is older: being active wins over being older.
		val trashedTax = folder(id(2), "Tax", documents, deletedAt = T2)
		val tax = folder(id(3), "tax", documents)
		file(id(10), "old.txt", trashedTax, "in the Trash")
		file(id(11), "new.txt", tax, "active")
		file(id(12), "tax", documents, "a file named like both folders")

		val zip = export()

		zip.folderPaths() shouldBe mapOf(id(1) to "Documents", id(2) to "Documents/Tax (2)", id(3) to "Documents/tax")
		zip.pathsById() shouldBe mapOf(id(10) to "Documents/Tax (2)/old.txt", id(11) to "Documents/tax/new.txt", id(12) to "Documents/tax (3)")
	}

	@Test
	fun `an active file keeps its name over an older trashed file of the same name`() {
		val tax = folder(id(1), "tax")
		file(id(10), "2025.pdf", tax, "deleted draft", deletedAt = T2)
		file(id(11), "2025.pdf", tax, "active")

		val zip = export()

		zip.pathsById() shouldBe mapOf(id(11) to "tax/2025.pdf", id(10) to "tax/2025 (2).pdf")
		zip.blob("tax/2025.pdf") shouldBe "active"
	}

	@Test
	fun `root-level files clash with root-level folders and with each other`() {
		folder(id(1), "Documents")
		file(id(10), "documents", null, "a root file named like a root folder")
		file(id(11), "notes.md", null, "first")
		file(id(12), "Notes.md", null, "second")

		val paths = export().pathsById()

		paths shouldBe mapOf(id(10) to "documents (2)", id(11) to "notes.md", id(12) to "Notes (2).md")
	}

	@Test
	fun `nested folders of the same name in different parents do not clash`() {
		val a = folder(id(1), "a")
		val b = folder(id(2), "b")
		val aInbox = folder(id(3), "inbox", a)
		val bInbox = folder(id(4), "inbox", b)
		file(id(10), "x.txt", aInbox, "a")
		file(id(11), "x.txt", bInbox, "b")

		val zip = export()

		zip.folderPaths() shouldBe mapOf(id(1) to "a", id(2) to "b", id(3) to "a/inbox", id(4) to "b/inbox")
		zip.pathsById() shouldBe mapOf(id(10) to "a/inbox/x.txt", id(11) to "b/inbox/x.txt")
	}

	@Test
	fun `every blob path is unique regardless of case and is the path its record names`() {
		val documents = folder(id(1), "Documents")
		val trashed = folder(id(2), "DOCUMENTS", deletedAt = T2)
		file(id(10), "a.txt", documents, "1")
		file(id(11), "A.TXT", documents, "2")
		file(id(12), "a.txt", trashed, "3")
		file(id(13), "documents", null, "4")

		val zip = export()

		val records = zip.files()
		val paths = records.map { it["path"].asString() }
		paths.map { it.lowercase() }.toSet().size shouldBe paths.size
		zip.blobPaths() shouldContainExactlyInAnyOrder paths
		records.forEach { record ->
			withClue(record["path"].asString()) {
				val bytes = zip.blobBytes(record["path"].asString())
				sha256(bytes) shouldBe record["sha256"].asString()
				bytes.size.toLong() shouldBe record["size"].asLong()
			}
		}
	}

	// --- export: data/ ----------------------------------------------------------------------------------------------

	@Test
	fun `folders json holds every folder with its fields and path, trashed and special ones included`() {
		val attachments = folder(id(1), "Attachments", special = "ATTACHMENTS")
		folder(id(2), "Notes", attachments, special = "NOTES")
		folder(id(3), "Old", deletedAt = T2, version = 4)

		val folders = export().folders()

		folders.map { it["id"].asString() } shouldContainExactly listOf(id(1), id(2), id(3)).map(UUID::toString)
		val notes = folders[1]
		notes["name"].asString() shouldBe "Notes"
		notes["parentId"].asString() shouldBe id(1).toString()
		notes["special"].asString() shouldBe "NOTES"
		notes["path"].asString() shouldBe "Attachments/Notes"
		notes["createdAt"].asString() shouldBe "2026-03-01T08:15:30.123456Z"
		notes["updatedAt"].asString() shouldBe "2026-03-02T09:00:00Z"
		notes["version"].asLong() shouldBe 2
		notes.isNullOrAbsent("deletedAt") shouldBe true
		val old = folders[2]
		old["deletedAt"].asString() shouldBe "2026-03-03T10:00:00Z"
		old["version"].asLong() shouldBe 4
		old.isNullOrAbsent("special") shouldBe true
		old.isNullOrAbsent("parentId") shouldBe true
	}

	@Test
	fun `files jsonl holds every file with its metadata and path, but not its storage key`() {
		val tax = folder(id(1), "tax")
		val key = file(id(10), "2025.pdf", tax, "%PDF", mime = "application/pdf", deletedAt = T2, version = 7)

		val zip = export()

		val record = zip.files().single()
		record.propertyNames().toSet() shouldBe
			setOf("id", "createdAt", "updatedAt", "version", "deletedAt", "folderId", "name", "mime", "size", "sha256", "path")
		record["id"].asString() shouldBe id(10).toString()
		record["folderId"].asString() shouldBe id(1).toString()
		record["name"].asString() shouldBe "2025.pdf"
		record["mime"].asString() shouldBe "application/pdf"
		record["size"].asLong() shouldBe 4
		record["sha256"].asString() shouldBe sha256("%PDF".toByteArray())
		record["version"].asLong() shouldBe 7
		record["deletedAt"].asString() shouldBe "2026-03-03T10:00:00Z"
		record["path"].asString() shouldBe "tax/2025.pdf"
		zip.text("data/files.jsonl") shouldNotContainText key
	}

	@Test
	fun `an empty drive exports an empty folder list and no files, and imports again`() {
		val zip = export()

		zip.folders().shouldBeEmpty()
		zip.files().shouldBeEmpty()
		zip.blobPaths().shouldBeEmpty()
		zip.counts["folders"] shouldBe 0
		zip.counts["files"] shouldBe 0

		wipe()
		import(zip.path)
		count("folder") shouldBe 0
		count("file") shouldBe 0
	}

	@Test
	fun `empty and unicode-named files are exported and restored`() {
		val deep = folder(id(1), "Grüezi «Welt»")
		file(id(10), "empty.txt", deep, "")
		file(id(11), "👋 hello.md", deep, "# Hoi\n")

		val zip = export()
		zip.blob("Grüezi «Welt»/empty.txt") shouldBe ""
		zip.blob("Grüezi «Welt»/👋 hello.md") shouldBe "# Hoi\n"

		wipe()
		import(zip.path)
		content(id(10)) shouldBe ""
		content(id(11)) shouldBe "# Hoi\n"
	}

	// --- import -----------------------------------------------------------------------------------------------------

	@Test
	fun `import restores folder and file rows with their ids, times, versions and Trash state`() {
		val documents = folder(id(1), "Documents", version = 3)
		val trashed = folder(id(2), "Trashed", documents, deletedAt = T2)
		folder(id(3), "Attachments", special = "ATTACHMENTS")
		val tax = folder(id(4), "tax", documents)
		file(id(10), "2025.pdf", tax, "%PDF", mime = "application/pdf", version = 5)
		file(id(11), "2025.pdf", tax, "%PDF corrected", mime = "application/pdf")
		file(id(12), "old.txt", trashed, "in the Trash", deletedAt = T2)
		file(id(13), "root.txt", null, "root")
		val folderRows = folderRows()
		val fileRows = fileRows()
		val oldKeys = storageKeys()

		val zip = export()
		wipe()
		import(zip.path)

		folderRows() shouldBe folderRows
		fileRows() shouldBe fileRows
		content(id(10)) shouldBe "%PDF"
		content(id(11)) shouldBe "%PDF corrected"
		content(id(12)) shouldBe "in the Trash"
		content(id(13)) shouldBe "root"
		val newKeys = storageKeys()
		newKeys.values.forEach { BlobStore.isValidKey(it) shouldBe true }
		newKeys.values.toSet().size shouldBe newKeys.size
		withClue("restore writes every blob under a new key") { (newKeys.values intersect oldKeys.values.toSet()).shouldBeEmpty() }
	}

	@Test
	fun `import indexes active folders and files for search, not the ones in the Trash`() {
		val documents = folder(id(1), "Documents")
		val trashed = folder(id(2), "Trashed folder", documents, deletedAt = T2)
		file(id(10), "Quarterly report.pdf", documents, "%PDF")
		file(id(11), "Draft.pdf", documents, "%PDF draft", deletedAt = T2)
		file(id(12), "inside trashed.txt", trashed, "text")

		val zip = export()
		wipe()
		import(zip.path)

		val documentsIndexed =
			jdbc.queryForList("select item_type || ' ' || item_id || ' ' || title from search_document", String::class.java)
		documentsIndexed shouldContainExactlyInAnyOrder
			listOf("FOLDER ${id(1)} Documents", "FILE ${id(10)} Quarterly report.pdf", "FILE ${id(12)} inside trashed.txt")
	}

	@Test
	fun `import rejects a blob whose SHA-256 does not match its record and leaves no blob behind`() {
		file(id(10), "a.txt", null, "original")
		val zip = export()
		wipe()
		val tampered = edit(zip) { it["files/a.txt"] = "tampered".toByteArray() }

		val error = shouldThrow<InvalidBackupException> { import(tampered) }

		error.message shouldContain id(10).toString()
		error.message shouldContain "SHA-256"
		count("file") shouldBe 0
		storedBlobs().shouldBeEmpty()
	}

	@Test
	fun `import rejects a blob whose size does not match its record and leaves no blob behind`() {
		file(id(10), "a.txt", null, "original")
		val zip = export()
		wipe()
		val tampered = edit(zip) { it["files/a.txt"] = "original plus more".toByteArray() }

		shouldThrow<InvalidBackupException> { import(tampered) }.message shouldContain "size"

		count("file") shouldBe 0
		storedBlobs().shouldBeEmpty()
	}

	@Test
	fun `import fails on a missing blob`() {
		file(id(10), "a.txt", null, "original")
		val zip = export()
		wipe()
		val tampered = edit(zip) { it.remove("files/a.txt") }

		val error = shouldThrow<InvalidBackupException> { import(tampered) }

		error.message shouldContain "missing"
		error.message shouldContain id(10).toString()
		count("file") shouldBe 0
		storedBlobs().shouldBeEmpty()
	}

	@Test
	fun `import finds each blob by the path in its record, not by its name`() {
		val tax = folder(id(1), "tax")
		file(id(10), "2025.pdf", tax, "first")
		file(id(11), "2025.pdf", tax, "second")
		val zip = export()
		wipe()

		import(zip.path)

		content(id(10)) shouldBe "first"
		content(id(11)) shouldBe "second"
	}

	@Test
	fun `import fails when a folder's parent is not in the backup`() {
		val documents = folder(id(1), "Documents")
		folder(id(2), "tax", documents)
		val zip = export()
		wipe()
		val tampered =
			edit(zip) { entries ->
				val folders = backups.canonicalJson.readTree(entries.getValue("data/folders.json"))
				val orphanOnly = backups.canonicalJson.createArrayNode().add(folders[1])
				entries["data/folders.json"] = backups.canonicalJson.writeValueAsBytes(orphanOnly)
			}

		shouldThrow<InvalidBackupException> { import(tampered) }.message shouldContain "parent is missing"
		count("folder") shouldBe 0
	}

	@Test
	fun `import fails when folders form a cycle without a root`() {
		val documents = folder(id(1), "Documents")
		folder(id(2), "tax", documents)
		val zip = export()
		wipe()
		val tampered =
			edit(zip) { entries ->
				val folders = backups.canonicalJson.readTree(entries.getValue("data/folders.json"))
				// Documents becomes the child of its own child.
				(folders[0] as tools.jackson.databind.node.ObjectNode).put("parentId", id(2).toString())
				entries["data/folders.json"] = backups.canonicalJson.writeValueAsBytes(folders)
			}

		shouldThrow<InvalidBackupException> { import(tampered) }.message shouldContain "cycle"
		count("folder") shouldBe 0
	}

	@Test
	fun `a file row that cannot be inserted fails the import and leaves no blob behind`() {
		val tax = folder(id(1), "tax")
		file(id(10), "2025.pdf", tax, "%PDF")
		val zip = export()
		wipe()
		// The file's folder is no longer in the backup, so its row violates the foreign key after the blob is written.
		val tampered =
			edit(zip) { entries -> entries["data/folders.json"] = backups.canonicalJson.writeValueAsBytes(emptyList<Any>()) }

		shouldThrow<Exception> { import(tampered) }

		count("file") shouldBe 0
		storedBlobs().shouldBeEmpty()
	}

	// --- large files ------------------------------------------------------------------------------------------------

	@Test
	fun `a 60 MB file is streamed through export and import with memory use far below its size`() {
		val size = 60L * 1024 * 1024
		val blob = Pattern(size).use { blobs.write(it) }
		insertFile(id(10), "video.mp4", null, "video/mp4", blob.size, blob.sha256, blob.key, null, 1)

		val target = work.resolve("large.zip")
		val exported = allocatedDuring { target.outputStream().use { backups.export(it) } }
		withClue("bytes allocated while exporting a $size byte file") { exported shouldBeLessThan size / 4 }

		ZipFile(target.toFile()).use { it.getEntry("files/video.mp4").size } shouldBe size
		wipe()
		val imported = allocatedDuring { import(target) }
		withClue("bytes allocated while importing a $size byte file") { imported shouldBeLessThan size / 4 }

		val restoredKey = storageKeys().getValue(id(10))
		Files.size(blobs.path(restoredKey)) shouldBe size
		jdbc.queryForObject("select sha256 from file where id = ?", String::class.java, id(10)) shouldBe blob.sha256
	}

	// --- helpers ----------------------------------------------------------------------------------------------------

	private class Exported(
		val path: Path,
		val counts: Map<String, Long>,
		private val json: tools.jackson.databind.json.JsonMapper,
	) {
		private val entries: Map<String, ByteArray> = Zips.read(path.readBytes())

		fun text(name: String) = String(entries.getValue(name), Charsets.UTF_8)

		fun blobPaths() = entries.keys.filter { it.startsWith(BackupFormat.FILES_DIR) }.map { it.removePrefix(BackupFormat.FILES_DIR) }

		fun blobBytes(path: String) = entries.getValue(BackupFormat.FILES_DIR + path)

		fun blob(path: String) = String(blobBytes(path), Charsets.UTF_8)

		fun folders(): List<JsonNode> = json.readTree(text("data/folders.json")).toList()

		fun files(): List<JsonNode> = text("data/files.jsonl").lines().filter { it.isNotBlank() }.map { json.readTree(it) }

		fun pathsById(): Map<UUID, String> = files().associate { UUID.fromString(it["id"].asString()) to it["path"].asString() }

		fun folderPaths(): Map<UUID, String> = folders().associate { UUID.fromString(it["id"].asString()) to it["path"].asString() }
	}

	private fun export(): Exported {
		val target = Files.createTempFile(work, "backup-", ".zip")
		val manifest = target.outputStream().use { backups.export(it) }
		return Exported(target, manifest.counts, backups.canonicalJson)
	}

	/** Runs only the files contributor's import, as restore does (one transaction, into an empty install). */
	private fun import(zip: Path) =
		ZipBackupReader.open(zip, backups.canonicalJson).use { reader ->
			transactions.executeWithoutResult { contributor.import(reader) }
		}

	/** A copy of [zip] with [change]d entries and a manifest that matches them (a careful hand-edit). */
	private fun edit(
		zip: Exported,
		change: (MutableMap<String, ByteArray>) -> Unit,
	): Path {
		val target = Files.createTempFile(work, "edited-", ".zip")
		target.writeBytes(Zips.edit(zip.path.readBytes(), backups.canonicalJson, updateManifest = true, change))
		return target
	}

	private fun folder(
		id: UUID,
		name: String,
		parent: UUID? = null,
		deletedAt: Instant? = null,
		special: String? = null,
		version: Long = 2,
	): UUID {
		jdbc.update(
			"""insert into folder (id, created_at, updated_at, version, deleted_at, parent_id, name, special)
			values (?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			version,
			deletedAt?.let(Timestamp::from),
			parent,
			name,
			special,
		)
		return id
	}

	/** Inserts a file with [content] as its blob; returns the storage key. */
	private fun file(
		id: UUID,
		name: String,
		folder: UUID?,
		content: String,
		mime: String = "text/plain",
		deletedAt: Instant? = null,
		version: Long = 1,
	): String {
		val blob = blobs.write(content.byteInputStream())
		insertFile(id, name, folder, mime, blob.size, blob.sha256, blob.key, deletedAt, version)
		return blob.key
	}

	private fun insertFile(
		id: UUID,
		name: String,
		folder: UUID?,
		mime: String,
		size: Long,
		sha256: String,
		key: String,
		deletedAt: Instant?,
		version: Long,
	) {
		jdbc.update(
			"""insert into file (id, created_at, updated_at, version, deleted_at, folder_id, name, mime, size, sha256, storage_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			version,
			deletedAt?.let(Timestamp::from),
			folder,
			name,
			mime,
			size,
			sha256,
			key,
		)
	}

	private fun folderRows() = jdbc.queryForList("select * from folder order by id")

	private fun fileRows() = jdbc.queryForList("select * from file order by id").map { row -> row.filterKeys { it != "storage_key" } }

	private fun storageKeys(): Map<UUID, String> =
		jdbc.query("select id, storage_key from file") { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("storage_key") }.toMap()

	private fun content(id: UUID): String {
		val key = jdbc.queryForObject("select storage_key from file where id = ?", String::class.java, id)!!
		return blobs.open(key).use { String(it.readAllBytes(), Charsets.UTF_8) }
	}

	private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", Long::class.java)

	/** Blobs in the store, temporary files of unfinished writes included. */
	private fun storedBlobs(): List<Path> =
		if (Files.isDirectory(blobs.root)) Files.walk(blobs.root).use { paths -> paths.filter(Files::isRegularFile).toList() } else emptyList()

	private fun allocatedDuring(block: () -> Unit): Long {
		val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
		val before = threads.currentThreadAllocatedBytes
		block()
		return threads.currentThreadAllocatedBytes - before
	}

	private fun JsonNode.isNullOrAbsent(property: String) = get(property)?.isNull ?: true

	private infix fun String.shouldNotContainText(text: String) = withClue("should not contain '$text'") { contains(text) shouldBe false }

	/** [size] bytes of a repeating, non-constant pattern, generated on the fly. */
	private class Pattern(
		private val size: Long,
	) : InputStream() {
		private var position = 0L

		override fun read(): Int = if (position < size) ((position++ * 31) % 251).toInt() else -1

		override fun read(
			buffer: ByteArray,
			offset: Int,
			length: Int,
		): Int {
			if (position >= size) return -1
			val count = minOf(length.toLong(), size - position).toInt()
			for (i in 0 until count) buffer[offset + i] = ((position++ * 31) % 251).toByte()
			return count
		}
	}

	companion object {
		private const val DATABASE = "files_backup_contributor"
		private val dataDir: Path = Files.createTempDirectory("cockpit-files-backup-data")

		private val T0 = Instant.parse("2026-03-01T08:15:30.123456Z")
		private val T1 = Instant.parse("2026-03-02T09:00:00Z")
		private val T2 = Instant.parse("2026-03-03T10:00:00Z")

		/** Fictitious UUIDv7-shaped ids, ordered by [n]. */
		private fun id(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

		private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
