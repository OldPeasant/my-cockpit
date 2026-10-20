package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
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
import ch.sonensei.cockpit.files.domain.Folder
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.FileItemResolver
import ch.sonensei.cockpit.files.infra.FileRepository
import ch.sonensei.cockpit.files.infra.FileTrashContributor
import ch.sonensei.cockpit.files.infra.FolderItemResolver
import ch.sonensei.cockpit.files.infra.FolderRepository
import ch.sonensei.cockpit.files.infra.FolderTrashContributor
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

@SpringBootTest
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
class FilesServiceTests(
	@Autowired private val service: FilesService,
	@Autowired private val folders: FolderRepository,
	@Autowired private val files: FileRepository,
	@Autowired private val blobs: BlobStore,
	@Autowired private val resolvers: ItemResolverRegistry,
	@Autowired private val contributors: TrashContributorRegistry,
	@Autowired private val trash: TrashService,
	@Autowired private val search: SearchService,
	@Autowired private val linkService: LinkService,
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
		// Leaves first: parent_id references folder.
		while ((jdbc.queryForObject("select count(*) from folder", Long::class.java) ?: 0) > 0) {
			jdbc.execute("delete from folder f where not exists (select 1 from folder c where c.parent_id = f.id)")
		}
		clock.set(MutableClockTestConfiguration.START)
	}

	private fun store(
		name: String,
		folderId: UUID? = null,
		content: String = "Fictitious content of $name",
		mime: String = "text/plain",
	): FileItem = service.storeFile(folderId, name, mime, content.byteInputStream())

	private fun searchIds(query: String): List<ItemRef> =
		search.search(query, setOf(ItemType.FILE, ItemType.FOLDER), 20).flatMap { group -> group.results.map { it.hit.item } }

	@Test
	fun `FILE and FOLDER resolvers and trash contributors are registered`() {
		resolvers.find(ItemType.FILE).shouldBeInstanceOf<FileItemResolver>()
		resolvers.find(ItemType.FOLDER).shouldBeInstanceOf<FolderItemResolver>()
		contributors.find(ItemType.FILE).shouldBeInstanceOf<FileTrashContributor>()
		contributors.find(ItemType.FOLDER).shouldBeInstanceOf<FolderTrashContributor>()
	}

	@Test
	fun `a stored file keeps its metadata and its blob on disk`() {
		val folder = service.createFolder("Invoices", null)
		val file = store("invoice-2026.pdf", folder.id, "PDF-ish bytes", "application/pdf")

		val loaded = files.findById(file.id).orElseThrow()
		loaded.name shouldBe "invoice-2026.pdf"
		loaded.folderId shouldBe folder.id
		loaded.mime shouldBe "application/pdf"
		loaded.size shouldBe 13
		loaded.sha256 shouldBe HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("PDF-ish bytes".toByteArray()))
		blobs.open(loaded.storageKey).use { it.readAllBytes().decodeToString() } shouldBe "PDF-ish bytes"
		blobs.path(loaded.storageKey).startsWith(blobs.root) shouldBe true
	}

	@Test
	fun `a blank mime type becomes octet-stream`() {
		store("data.bin", mime = " ").mime shouldBe FilesService.DEFAULT_MIME
	}

	@Test
	fun `the blob is removed again when the transaction rolls back`() {
		var key: String? = null
		runCatching {
			tx.executeWithoutResult {
				key = store("rolled-back.txt").storageKey
				error("simulated failure after the upload")
			}
		}

		key.shouldNotBeNull()
		blobs.exists(key!!) shouldBe false
		files.findAll().shouldBeEmpty()
	}

	@Test
	fun `folder names are unique per parent regardless of case, also enforced by the database`() {
		val parent = service.createFolder("Projects", null)
		service.createFolder("Holiday", parent.id)

		shouldThrow<ConflictException> { service.createFolder("holiday", parent.id) }
		shouldThrow<ConflictException> { service.createFolder("PROJECTS", null) }
		service.createFolder("Holiday", null).parentId.shouldBeNull()

		shouldThrow<DataIntegrityViolationException> { folders.saveAndFlush(Folder("HOLIDAY", parent.id)) }
		shouldThrow<DataIntegrityViolationException> { folders.saveAndFlush(Folder("projects", null)) }
	}

	@Test
	fun `a name in the Trash can be reused`() {
		val old = service.createFolder("Archive", null)
		service.deleteFolder(old.id)

		service.createFolder("Archive", null).id shouldBe folders.findActiveRootByName("archive")!!.id
	}

	@Test
	fun `names that could escape their folder are rejected`() {
		val parent = service.createFolder("Docs", null)
		listOf("..", ".", "../secret", "a/b", "..\\x", "nul\u0000byte", " ").forEach { name ->
			shouldThrow<InvalidRequestException> { service.createFolder(name, parent.id) }
			shouldThrow<InvalidRequestException> { store(name, parent.id) }
			shouldThrow<InvalidRequestException> { service.renameFolder(parent.id, name) }
		}
		val file = store("ok.txt", parent.id)
		shouldThrow<InvalidRequestException> { service.renameFile(file.id, "../../etc/passwd") }

		files.findAll().map { it.name } shouldContainExactly listOf("ok.txt")
		folders.findAll().map { it.name } shouldContainExactly listOf("Docs")
	}

	@Test
	fun `a file or folder in a missing or trashed folder is rejected`() {
		shouldThrow<NotFoundException> { service.createFolder("Child", UUID.randomUUID()) }
		val gone = service.createFolder("Gone", null)
		service.deleteFolder(gone.id)

		shouldThrow<NotFoundException> { service.createFolder("Child", gone.id) }
		shouldThrow<NotFoundException> { store("a.txt", gone.id) }
	}

	@Test
	fun `special folders are created on demand below Attachments, once`() {
		val tasks = service.specialFolder(SpecialFolder.TASKS)
		val notes = service.specialFolder(SpecialFolder.NOTES)
		val mail = service.specialFolder(SpecialFolder.MAIL)
		val journal = service.specialFolder(SpecialFolder.JOURNAL)

		val attachments = folders.findBySpecial(SpecialFolder.ATTACHMENTS).shouldNotBeNull()
		attachments.parentId.shouldBeNull()
		attachments.name shouldBe "Attachments"
		listOf(tasks, notes, mail, journal).map { it.parentId }.toSet() shouldBe setOf(attachments.id)
		listOf(tasks, notes, mail, journal).map { service.pathOf(it.id) } shouldContainExactly
			listOf("/Attachments/Tasks", "/Attachments/Notes", "/Attachments/Mail", "/Attachments/Journal")
		service.specialFolder(SpecialFolder.TASKS).id shouldBe tasks.id
		folders.count() shouldBe 5
	}

	@Test
	fun `concurrent first uses create each special folder once`() {
		val barrier = CyclicBarrier(4)
		val pool = Executors.newFixedThreadPool(4)
		try {
			val ids =
				pool
					.invokeAll(
						List(4) {
							Callable {
								barrier.await()
								tx.execute { service.specialFolder(SpecialFolder.MAIL).id }
							}
						},
					).map { it.get() }
			ids.toSet().size shouldBe 1
		} finally {
			pool.shutdownNow()
		}
		folders.count() shouldBe 2
	}

	@Test
	fun `a user folder at the special folder's place becomes the special folder`() {
		val mine = service.createFolder("attachments", null)

		service.specialFolder(SpecialFolder.JOURNAL).parentId shouldBe mine.id
		folders.findBySpecial(SpecialFolder.ATTACHMENTS)!!.id shouldBe mine.id
	}

	@Test
	fun `special folders cannot be deleted or renamed, their content can`() {
		val tasks = service.specialFolder(SpecialFolder.TASKS)
		val attachments = folders.findBySpecial(SpecialFolder.ATTACHMENTS)!!

		listOf(tasks, attachments).forEach {
			shouldThrow<ConflictException> { service.deleteFolder(it.id) }
			shouldThrow<ConflictException> { service.renameFolder(it.id, "Renamed") }
		}
		folders.findAllActive().size shouldBe 2

		val sub = service.createFolder("Sub", tasks.id)
		val file = store("scan.png", tasks.id)
		service.deleteFolder(sub.id)
		service.deleteFile(file.id)
		folders.findActiveById(sub.id).shouldBeNull()
		files.findActiveById(file.id).shouldBeNull()
	}

	@Test
	fun `renaming checks siblings but allows a change of case`() {
		val a = service.createFolder("Alpha", null)
		service.createFolder("Beta", null)

		shouldThrow<ConflictException> { service.renameFolder(a.id, "beta") }
		service.renameFolder(a.id, "ALPHA").name shouldBe "ALPHA"
	}

	@Test
	fun `resolvers show name, path, icon and deep link, trashed items do not resolve`() {
		val tasks = service.specialFolder(SpecialFolder.TASKS)
		val file = store("photo.jpg", tasks.id, mime = "image/jpeg")
		val plain = service.createFolder("Plain", null)

		resolvers.find(ItemType.FILE)!!.resolve(file.id) shouldBe
			ResolvedItem("photo.jpg", "/Attachments/Tasks", "image", "/files/${file.id}")
		resolvers.find(ItemType.FOLDER)!!.resolve(tasks.id) shouldBe
			ResolvedItem("Tasks", "/Attachments", "folder_special", "/files/folders/${tasks.id}")
		resolvers.find(ItemType.FOLDER)!!.resolve(plain.id) shouldBe ResolvedItem("Plain", "/", "folder", "/files/folders/${plain.id}")
		resolvers.find(ItemType.FILE)!!.resolve(store("doc.pdf", mime = "application/pdf").id)!!.icon shouldBe "picture_as_pdf"
		resolvers.find(ItemType.FILE)!!.resolve(store("x.bin", mime = "application/zip").id)!!.icon shouldBe "draft"

		service.deleteFile(file.id)
		service.deleteFolder(plain.id)
		resolvers.find(ItemType.FILE)!!.resolve(file.id).shouldBeNull()
		resolvers.find(ItemType.FOLDER)!!.resolve(plain.id).shouldBeNull()
		resolvers.find(ItemType.FILE)!!.exists(UUID.randomUUID()) shouldBe false
	}

	@Test
	fun `names are searchable, renames are reindexed and trashed items leave the index`() {
		val folder = service.createFolder("Tax returns", null)
		val file = store("Quarterly statement.pdf", folder.id)

		searchIds("statement") shouldContainExactly listOf(ItemRef(ItemType.FILE, file.id))
		searchIds("tax") shouldContainExactly listOf(ItemRef(ItemType.FOLDER, folder.id))

		service.renameFile(file.id, "Annual summary.pdf")
		searchIds("summary") shouldContainExactly listOf(ItemRef(ItemType.FILE, file.id))
		searchIds("quarterly").shouldBeEmpty()

		service.deleteFolder(folder.id)
		searchDocumentCount() shouldBe 0
		trash.restore(ItemRef(ItemType.FOLDER, folder.id))
		searchIds("summary") shouldContainExactly listOf(ItemRef(ItemType.FILE, file.id))
		searchIds("tax") shouldContainExactly listOf(ItemRef(ItemType.FOLDER, folder.id))
	}

	@Test
	fun `deleting a folder trashes its subtree, only the folder is listed in the Trash`() {
		val root = service.createFolder("Root", null)
		val child = service.createFolder("Child", root.id)
		val inner = store("inner.txt", child.id)
		val top = store("top.txt", root.id)
		val loose = store("loose.txt")

		service.deleteFolder(root.id)
		service.deleteFile(loose.id)

		folders.findActiveById(child.id).shouldBeNull()
		files.findActiveById(inner.id).shouldBeNull()
		files.findActiveById(top.id).shouldBeNull()
		contributors.find(ItemType.FOLDER)!!.listDeleted().map { it.id } shouldContainExactly listOf(root.id)
		contributors.find(ItemType.FILE)!!.listDeleted().map { it.id } shouldContainExactly listOf(loose.id)
		trash.entries().map { it.ref } shouldContainExactlyInAnyOrder
			listOf(ItemRef(ItemType.FOLDER, root.id), ItemRef(ItemType.FILE, loose.id))
		contributors
			.find(ItemType.FILE)!!
			.listDeleted()
			.single()
			.subtitle shouldBe "/"
	}

	@Test
	fun `restoring a folder brings back what was deleted with it, not what was deleted before`() {
		val root = service.createFolder("Root", null)
		val earlier = store("earlier.txt", root.id)
		service.deleteFile(earlier.id)
		clock.advance(Duration.ofMinutes(5))
		val kept = store("kept.txt", root.id)
		val child = service.createFolder("Child", root.id)
		service.deleteFolder(root.id)

		trash.restore(ItemRef(ItemType.FOLDER, root.id))

		folders.findActiveById(root.id).shouldNotBeNull()
		folders.findActiveById(child.id).shouldNotBeNull()
		files.findActiveById(kept.id).shouldNotBeNull()
		files.findActiveById(earlier.id).shouldBeNull()
		contributors.find(ItemType.FILE)!!.listDeleted().map { it.id } shouldContainExactly listOf(earlier.id)
	}

	@Test
	fun `a restored folder whose name is taken again gets a number`() {
		val first = service.createFolder("Reports", null)
		service.deleteFolder(first.id)
		service.createFolder("reports", null)

		trash.restore(ItemRef(ItemType.FOLDER, first.id))

		folders.findActiveById(first.id)!!.name shouldBe "Reports (2)"
	}

	@Test
	fun `items deleted only with their folder cannot be restored or purged on their own`() {
		val root = service.createFolder("Root", null)
		val child = service.createFolder("Child", root.id)
		val inner = store("inner.txt", child.id)
		service.deleteFolder(root.id)

		shouldThrow<NotFoundException> { trash.restore(ItemRef(ItemType.FOLDER, child.id)) }
		shouldThrow<NotFoundException> { trash.restore(ItemRef(ItemType.FILE, inner.id)) }
		shouldThrow<NotFoundException> { trash.purge(ItemRef(ItemType.FILE, inner.id)) }
		shouldThrow<NotFoundException> { trash.purge(ItemRef(ItemType.FILE, store("active.txt").id)) }
	}

	@Test
	fun `purging a file deletes its row, its blob and its links`(events: ApplicationEvents) {
		val file = store("purge-me.txt")
		val other = service.createFolder("Other", null)
		linkService.create(ItemRef(ItemType.FILE, file.id), ItemRef(ItemType.FOLDER, other.id), null)
		service.deleteFile(file.id)

		trash.purge(ItemRef(ItemType.FILE, file.id))

		files.findById(file.id).isPresent shouldBe false
		blobs.exists(file.storageKey) shouldBe false
		links.findAll().shouldBeEmpty()
		events
			.stream(ChangeEvent::class.java)
			.filter { it.id == file.id }
			.map { it.op }
			.toList()
			.last() shouldBe ChangeOp.DELETED
	}

	@Test
	fun `purging a folder deletes the whole subtree with blobs, links and search documents`() {
		val root = service.createFolder("Root", null)
		val child = service.createFolder("Child", root.id)
		val grandchild = service.createFolder("Grandchild", child.id)
		val a = store("a.txt", root.id)
		val b = store("b.txt", grandchild.id)
		val earlier = store("earlier.txt", child.id)
		service.deleteFile(earlier.id)
		val outside = store("outside.txt")
		linkService.create(ItemRef(ItemType.FILE, b.id), ItemRef(ItemType.FILE, outside.id), null)
		service.deleteFolder(root.id)

		trash.purge(ItemRef(ItemType.FOLDER, root.id))

		folders.findAll().shouldBeEmpty()
		files.findAll().map { it.id } shouldContainExactly listOf(outside.id)
		listOf(a, b, earlier).forEach { blobs.exists(it.storageKey) shouldBe false }
		blobs.exists(outside.storageKey) shouldBe true
		links.findAll().shouldBeEmpty()
		searchDocumentCount() shouldBe 1
		trash.entries().shouldBeEmpty()
	}

	@Test
	fun `a purge that rolls back keeps the blob`() {
		val file = store("kept.txt")
		service.deleteFile(file.id)

		runCatching {
			tx.executeWithoutResult {
				service.purgeFile(file.id) shouldBe true
				error("simulated failure after the purge")
			}
		}

		files.findById(file.id).isPresent shouldBe true
		blobs.exists(file.storageKey) shouldBe true
	}

	@Test
	fun `the purge job purges files older than the retention`() {
		val file = store("old.txt")
		service.deleteFile(file.id)
		clock.advance(TrashService.RETENTION.plusDays(1))

		trash.entries().single().ref shouldBe ItemRef(ItemType.FILE, file.id)
		trash.purge(ItemRef(ItemType.FILE, file.id))
		blobs.exists(file.storageKey) shouldBe false
	}

	@Test
	fun `changes are announced as change events`(events: ApplicationEvents) {
		val folder = service.createFolder("Announced", null)
		val file = store("announced.txt", folder.id)
		service.renameFile(file.id, "renamed.txt")
		service.deleteFolder(folder.id)

		val recorded = events.stream(ChangeEvent::class.java).map { Triple(it.type, it.id, it.op) }.toList()
		recorded shouldContainExactly
			listOf(
				Triple("FOLDER", folder.id, ChangeOp.CREATED),
				Triple("FILE", file.id, ChangeOp.CREATED),
				Triple("FILE", file.id, ChangeOp.UPDATED),
				Triple("FOLDER", folder.id, ChangeOp.DELETED),
				Triple("FILE", file.id, ChangeOp.DELETED),
			)
	}

	private fun searchDocumentCount(): Long =
		jdbc.queryForObject("select count(*) from search_document where item_type in ('FILE', 'FOLDER')", Long::class.java)!!
}
