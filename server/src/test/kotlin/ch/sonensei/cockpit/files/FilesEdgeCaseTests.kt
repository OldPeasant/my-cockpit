package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.search.domain.SearchService
import ch.sonensei.cockpit.core.trash.domain.TrashContributorRegistry
import ch.sonensei.cockpit.core.trash.domain.TrashService
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.BlobTooLargeException
import ch.sonensei.cockpit.files.infra.FileRepository
import ch.sonensei.cockpit.files.infra.FolderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.io.path.walk

/** Edge cases, error paths and database constraints of the files model (task files-storage). */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
class FilesEdgeCaseTests(
	@Autowired private val service: FilesService,
	@Autowired private val folders: FolderRepository,
	@Autowired private val files: FileRepository,
	@Autowired private val blobs: BlobStore,
	@Autowired private val contributors: TrashContributorRegistry,
	@Autowired private val trash: TrashService,
	@Autowired private val search: SearchService,
	@Autowired private val links: LinkRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val tx: TransactionTemplate,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		links.deleteAll()
		jdbc.execute("delete from search_document where item_type in ('FILE', 'FOLDER')")
		jdbc.execute("delete from file")
		while ((jdbc.queryForObject("select count(*) from folder", Long::class.java) ?: 0) > 0) {
			jdbc.execute("delete from folder f where not exists (select 1 from folder c where c.parent_id = f.id)")
		}
		clock.set(MutableClockTestConfiguration.START)
	}

	private fun store(
		name: String,
		folderId: UUID? = null,
		content: String = "Fictitious content of $name",
	): FileItem = service.storeFile(folderId, name, "text/plain", content.byteInputStream())

	private fun searchIds(query: String): List<ItemRef> =
		search.search(query, setOf(ItemType.FILE, ItemType.FOLDER), 20).flatMap { group -> group.results.map { it.hit.item } }

	/** Every blob file on disk (outside `tmp/`), so tests can see that nothing was written. */
	private fun blobFiles(): Set<String> {
		if (!Files.isDirectory(blobs.root)) return emptySet()
		return blobs.root
			.walk()
			.filter { Files.isRegularFile(it) }
			.map { blobs.root.relativize(it).toString() }
			.toSet()
	}

	@Test
	fun `a file deleted on its own is restored from the Trash, searchable again, with its blob`(events: ApplicationEvents) {
		val folder = service.createFolder("Inbox", null)
		val file = store("Fictitious receipt.txt", folder.id, "receipt bytes")
		service.deleteFile(file.id)
		searchIds("receipt").shouldBeEmpty()
		contributors
			.find(ItemType.FILE)!!
			.listDeleted()
			.single()
			.subtitle shouldBe "/Inbox"

		trash.restore(ItemRef(ItemType.FILE, file.id))

		val restored = files.findActiveById(file.id).shouldNotBeNull()
		restored.folderId shouldBe folder.id
		blobs.open(restored.storageKey).use { it.readAllBytes().decodeToString() } shouldBe "receipt bytes"
		searchIds("receipt") shouldContainExactly listOf(ItemRef(ItemType.FILE, file.id))
		trash.entries().shouldBeEmpty()
		events
			.stream(ChangeEvent::class.java)
			.filter { it.id == file.id }
			.map { it.op }
			.toList() shouldContainExactly listOf(ChangeOp.CREATED, ChangeOp.DELETED, ChangeOp.UPDATED)
	}

	@Test
	fun `restoring or purging twice, or items not in the Trash, is a 404`() {
		val file = store("twice.txt")
		val folder = service.createFolder("Twice", null)

		shouldThrow<NotFoundException> { trash.restore(ItemRef(ItemType.FILE, file.id)) }
		shouldThrow<NotFoundException> { trash.restore(ItemRef(ItemType.FOLDER, folder.id)) }
		shouldThrow<NotFoundException> { trash.purge(ItemRef(ItemType.FOLDER, folder.id)) }
		shouldThrow<NotFoundException> { trash.restore(ItemRef(ItemType.FILE, UUID.randomUUID())) }
		shouldThrow<NotFoundException> { trash.purge(ItemRef(ItemType.FOLDER, UUID.randomUUID())) }

		service.deleteFile(file.id)
		trash.restore(ItemRef(ItemType.FILE, file.id))
		shouldThrow<NotFoundException> { trash.restore(ItemRef(ItemType.FILE, file.id)) }

		service.deleteFolder(folder.id)
		trash.purge(ItemRef(ItemType.FOLDER, folder.id))
		shouldThrow<NotFoundException> { trash.purge(ItemRef(ItemType.FOLDER, folder.id)) }
		shouldThrow<NotFoundException> { trash.restore(ItemRef(ItemType.FOLDER, folder.id)) }
	}

	@Test
	fun `a folder deleted only with its parent cannot be purged on its own`() {
		val root = service.createFolder("Outer", null)
		val child = service.createFolder("Inner", root.id)
		val file = store("inner.txt", child.id)
		service.deleteFolder(root.id)

		shouldThrow<NotFoundException> { trash.purge(ItemRef(ItemType.FOLDER, child.id)) }

		folders.findById(child.id).isPresent shouldBe true
		blobs.exists(file.storageKey) shouldBe true
	}

	@Test
	fun `missing or trashed items cannot be read, renamed or deleted`() {
		val missing = UUID.randomUUID()
		shouldThrow<NotFoundException> { service.folder(missing) }
		shouldThrow<NotFoundException> { service.file(missing) }
		shouldThrow<NotFoundException> { service.renameFolder(missing, "x") }
		shouldThrow<NotFoundException> { service.renameFile(missing, "x") }
		shouldThrow<NotFoundException> { service.deleteFolder(missing) }
		shouldThrow<NotFoundException> { service.deleteFile(missing) }

		val folder = service.createFolder("Binned", null)
		val file = store("binned.txt")
		service.deleteFolder(folder.id)
		service.deleteFile(file.id)
		shouldThrow<NotFoundException> { service.renameFolder(folder.id, "Again") }
		shouldThrow<NotFoundException> { service.deleteFolder(folder.id) }
		shouldThrow<NotFoundException> { service.renameFile(file.id, "again.txt") }
		shouldThrow<NotFoundException> { service.deleteFile(file.id) }
	}

	@Test
	fun `deleting a file twice keeps the first deletion time`() {
		val file = store("first.txt")
		service.deleteFile(file.id)
		val first =
			files
				.findById(file.id)
				.orElseThrow()
				.deletedAt
				.shouldNotBeNull()
		clock.advance(Duration.ofHours(1))

		shouldThrow<NotFoundException> { service.deleteFile(file.id) }
		files.findById(file.id).orElseThrow().deletedAt shouldBe first
	}

	@Test
	fun `an invalid name or a missing folder writes no blob`() {
		val before = blobFiles()

		listOf("..", "../../etc/passwd", "a/b", "c\\d", "nul\u0000.txt", "").forEach { name ->
			shouldThrow<InvalidRequestException> { store(name) }
		}
		shouldThrow<NotFoundException> { store("orphan.txt", UUID.randomUUID()) }

		blobFiles() shouldBe before
		files.findAll().shouldBeEmpty()
	}

	@Test
	fun `a file above the maximum size stores neither a row nor a blob`() {
		val before = blobFiles()

		shouldThrow<BlobTooLargeException> {
			service.storeFile(null, "big.bin", "application/octet-stream", ByteArray(65).inputStream(), maxSize = 64)
		}

		files.findAll().shouldBeEmpty()
		blobFiles() shouldBe before
		service.storeFile(null, "exact.bin", "application/octet-stream", ByteArray(64).inputStream(), maxSize = 64).size shouldBe 64
	}

	@Test
	fun `stored blobs never leave the blob folder and get distinct keys for equal content`() {
		val a = store("same.txt", content = "identical")
		val b = store("same.txt", content = "identical")

		(a.storageKey == b.storageKey) shouldBe false
		a.sha256 shouldBe b.sha256
		listOf(a, b).forEach {
			BlobStore.isValidKey(it.storageKey) shouldBe true
			blobs.path(it.storageKey).normalize().startsWith(blobs.root) shouldBe true
		}
	}

	@Test
	fun `file names need not be unique and are trimmed on rename`() {
		val folder = service.createFolder("Scans", null)
		val one = store("scan.png", folder.id)
		val two = store("scan.png", folder.id)

		service.renameFile(two.id, "  scan.png  ").name shouldBe "scan.png"
		files.findAllActive().map { it.id } shouldContainExactlyInAnyOrder listOf(one.id, two.id)
	}

	@Test
	fun `folder names are trimmed and checked against siblings after trimming`() {
		service.createFolder("  Padded  ", null).name shouldBe "Padded"
		shouldThrow<ConflictException> { service.createFolder("padded ", null) }
		val other = service.createFolder("Other", null)
		shouldThrow<ConflictException> { service.renameFolder(other.id, " PADDED") }
		service.renameFolder(other.id, " Other ").name shouldBe "Other"
	}

	@Test
	fun `a restored folder gets the next free number`() {
		val first = service.createFolder("Reports", null)
		service.deleteFolder(first.id)
		service.createFolder("Reports", null)
		service.createFolder("Reports (2)", null)

		trash.restore(ItemRef(ItemType.FOLDER, first.id))

		folders.findActiveById(first.id)!!.name shouldBe "Reports (3)"
	}

	@Test
	fun `restoring a folder announces its restored contents`(events: ApplicationEvents) {
		val root = service.createFolder("Album", null)
		val child = service.createFolder("Summer", root.id)
		val file = store("beach.jpg", child.id)
		service.deleteFolder(root.id)
		events.clear()

		trash.restore(ItemRef(ItemType.FOLDER, root.id))

		events.stream(ChangeEvent::class.java).map { Triple(it.type, it.id, it.op) }.toList() shouldContainExactlyInAnyOrder
			listOf(
				Triple("FOLDER", root.id, ChangeOp.UPDATED),
				Triple("FOLDER", child.id, ChangeOp.UPDATED),
				Triple("FILE", file.id, ChangeOp.UPDATED),
			)
		service.pathOf(child.id) shouldBe "/Album/Summer"
	}

	@Test
	fun `a subfolder deleted before its parent keeps its own deletion and reappears when the parent is restored`() {
		val parent = service.createFolder("Projects", null)
		val sub = service.createFolder("Draft", parent.id)
		service.deleteFolder(sub.id)
		val subDeletedAt = folders.findById(sub.id).orElseThrow().deletedAt
		clock.advance(Duration.ofMinutes(10))
		service.deleteFolder(parent.id)

		folders.findById(sub.id).orElseThrow().deletedAt shouldBe subDeletedAt
		contributors.find(ItemType.FOLDER)!!.listDeleted().map { it.id } shouldContainExactly listOf(parent.id)

		trash.restore(ItemRef(ItemType.FOLDER, parent.id))

		folders.findActiveById(sub.id).shouldBeNull()
		contributors.find(ItemType.FOLDER)!!.listDeleted().map { it.id } shouldContainExactly listOf(sub.id)
		trash.restore(ItemRef(ItemType.FOLDER, sub.id))
		service.pathOf(sub.id) shouldBe "/Projects/Draft"
	}

	@Test
	fun `an empty folder can be purged`() {
		val empty = service.createFolder("Empty", null)
		service.deleteFolder(empty.id)

		trash.purge(ItemRef(ItemType.FOLDER, empty.id))

		folders.findById(empty.id).isPresent shouldBe false
		trash.entries().shouldBeEmpty()
	}

	@Test
	fun `a folder purge that rolls back keeps the folder, its files and their blobs`() {
		val folder = service.createFolder("Archive", null)
		val sub = service.createFolder("2025", folder.id)
		val top = store("top.txt", folder.id)
		val nested = store("nested.txt", sub.id)
		service.deleteFolder(folder.id)

		runCatching {
			tx.executeWithoutResult {
				service.purgeFolder(folder.id) shouldBe true
				error("simulated failure after the purge")
			}
		}

		folders.findById(sub.id).isPresent shouldBe true
		files.findById(top.id).isPresent shouldBe true
		files.findById(nested.id).isPresent shouldBe true
		blobs.exists(top.storageKey) shouldBe true
		blobs.exists(nested.storageKey) shouldBe true
		service.trashedFolders().map { it.id } shouldContainExactly listOf(folder.id)
	}

	@Test
	fun `a blob that cannot be deleted does not fail the purge`() {
		val file = store("stuck.txt")
		service.deleteFile(file.id)
		// Replace the blob with a non-empty directory, so deleting it fails with an IOException.
		val path = blobs.path(file.storageKey)
		Files.delete(path)
		Files.createDirectories(path)
		Files.writeString(path.resolve("keep"), "fictitious")
		try {
			trash.purge(ItemRef(ItemType.FILE, file.id))

			files.findById(file.id).isPresent shouldBe false
		} finally {
			Files.delete(path.resolve("keep"))
			Files.delete(path)
		}
	}

	@Test
	fun `the trash lists folders with their parent path and deletion time`() {
		val parent = service.createFolder("Parent", null)
		val child = service.createFolder("Child", parent.id)
		val deletedAt: Instant = clock.instant()
		service.deleteFolder(child.id)

		val item = contributors.find(ItemType.FOLDER)!!.listDeleted().single()
		item.id shouldBe child.id
		item.title shouldBe "Child"
		item.subtitle shouldBe "/Parent"
		item.deletedAt shouldBe deletedAt
		item.icon shouldBe "folder"
	}

	@Test
	fun `pathOf the root is a slash`() {
		service.pathOf(null) shouldBe "/"
	}

	@Test
	fun `a folder cannot be created next to a special folder with its name`() {
		val tasks = service.specialFolder(SpecialFolder.TASKS)

		shouldThrow<ConflictException> { service.createFolder("tasks", tasks.parentId) }
		shouldThrow<ConflictException> { service.createFolder("ATTACHMENTS", null) }
		val mine = service.createFolder("Mine", null)
		shouldThrow<ConflictException> { service.renameFolder(mine.id, "Attachments") }
	}

	@Test
	fun `a user folder in the Trash is not adopted as special folder`() {
		val old = service.createFolder("Attachments", null)
		service.deleteFolder(old.id)

		val attachments = service.specialFolder(SpecialFolder.ATTACHMENTS)

		(attachments.id == old.id) shouldBe false
		attachments.isSpecial shouldBe true
		folders
			.findById(old.id)
			.orElseThrow()
			.special
			.shouldBeNull()
	}

	@Test
	fun `an existing user folder at a special child place is adopted with its content`() {
		val mine = service.createFolder("Attachments", null)
		val mail = service.createFolder("Mail", mine.id)
		val file = store("letter.eml", mail.id)

		service.specialFolder(SpecialFolder.MAIL).id shouldBe mail.id

		folders.findById(mail.id).orElseThrow().special shouldBe SpecialFolder.MAIL
		files.findActiveById(file.id)!!.folderId shouldBe mail.id
		shouldThrow<ConflictException> { service.deleteFolder(mail.id) }
	}

	@Test
	fun `concurrent first uses of different special folders share one Attachments folder`() {
		val kinds = listOf(SpecialFolder.TASKS, SpecialFolder.NOTES, SpecialFolder.MAIL, SpecialFolder.JOURNAL)
		val barrier = CyclicBarrier(kinds.size)
		val pool = Executors.newFixedThreadPool(kinds.size)
		try {
			pool
				.invokeAll(
					kinds.map { kind ->
						Callable {
							barrier.await()
							tx.execute { service.specialFolder(kind).id }
						}
					},
				).forEach { it.get() }
		} finally {
			pool.shutdownNow()
		}
		val attachments = folders.findBySpecial(SpecialFolder.ATTACHMENTS).shouldNotBeNull()
		folders.count() shouldBe 5
		kinds.map { folders.findBySpecial(it)!!.parentId }.toSet() shouldBe setOf(attachments.id)
	}

	@Test
	fun `the database rejects blank names, bad hashes, negative sizes, duplicate keys and duplicate specials`() {
		val now = java.sql.Timestamp.from(clock.instant())
		val hash = "a".repeat(64)
		val key = "ab/cd/abcd0000-0000-4000-8000-000000000000"

		fun insertFile(
			name: String,
			size: Long,
			sha: String,
			storageKey: String,
		) = jdbc.update(
			"insert into file (id, created_at, updated_at, version, name, mime, size, sha256, storage_key) values (?, ?, ?, 0, ?, 'text/plain', ?, ?, ?)",
			UUID.randomUUID(),
			now,
			now,
			name,
			size,
			sha,
			storageKey,
		)

		fun insertFolder(
			name: String,
			special: String?,
		) = jdbc.update(
			"insert into folder (id, created_at, updated_at, version, name, special) values (?, ?, ?, 0, ?, ?)",
			UUID.randomUUID(),
			now,
			now,
			name,
			special,
		)

		shouldThrow<DataIntegrityViolationException> { insertFile("  ", 1, hash, key) }
		shouldThrow<DataIntegrityViolationException> { insertFile("x.txt", -1, hash, key) }
		shouldThrow<DataIntegrityViolationException> { insertFile("x.txt", 1, "A".repeat(64), key) }
		shouldThrow<DataIntegrityViolationException> { insertFile("x.txt", 1, "abc", key) }
		insertFile("x.txt", 1, hash, key)
		shouldThrow<DataIntegrityViolationException> { insertFile("y.txt", 1, hash, key) }

		shouldThrow<DataIntegrityViolationException> { insertFolder(" ", null) }
		shouldThrow<DataIntegrityViolationException> { insertFolder("Odd", "UNKNOWN") }
		insertFolder("One", "MAIL")
		shouldThrow<DataIntegrityViolationException> { insertFolder("Two", "MAIL") }
	}
}
