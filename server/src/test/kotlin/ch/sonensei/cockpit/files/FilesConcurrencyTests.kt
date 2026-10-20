package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.trash.domain.TrashService
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.MoveTarget
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.FileRepository
import ch.sonensei.cockpit.files.infra.FolderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
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
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.walk

/**
 * Races between uploads, folder creation, folder deletion, restoring and purging (review findings CR-1 to CR-3 of task
 * files-storage, and CR-1 of its re-review). Each test forces the interleaving with latches and waits for the database lock instead of relying on
 * timing.
 */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
class FilesConcurrencyTests(
	@Autowired private val service: FilesService,
	@Autowired private val folders: FolderRepository,
	@Autowired private val files: FileRepository,
	@Autowired private val blobs: BlobStore,
	@Autowired private val trash: TrashService,
	@Autowired private val links: LinkRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val tx: TransactionTemplate,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val pool = Executors.newFixedThreadPool(2)

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

	@AfterEach
	fun stopPool() {
		pool.shutdownNow()
	}

	/** Content that blocks on its first read until [proceed] opens, after announcing the read in [reading]. */
	private class BlockingContent(
		bytes: ByteArray,
		private val reading: CountDownLatch,
		private val proceed: CountDownLatch,
	) : InputStream() {
		private val delegate = ByteArrayInputStream(bytes)

		private fun gate() {
			reading.countDown()
			check(proceed.await(TIMEOUT, TimeUnit.SECONDS)) { "test did not release the upload" }
		}

		override fun read(): Int {
			gate()
			return delegate.read()
		}

		override fun read(
			b: ByteArray,
			off: Int,
			len: Int,
		): Int {
			gate()
			return delegate.read(b, off, len)
		}
	}

	private fun blobFiles(): Set<String> {
		if (!Files.isDirectory(blobs.root)) return emptySet()
		return blobs.root
			.walk()
			.filter { Files.isRegularFile(it) && !blobs.root.relativize(it).startsWith("tmp") }
			.map { blobs.root.relativize(it).toString() }
			.toSet()
	}

	/** Waits until another connection of this database waits for a lock (a row lock or a unique index entry). */
	private fun awaitLockWait() {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT)
		while (System.nanoTime() < deadline) {
			val waiting =
				jdbc.queryForObject(
					"select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
					Long::class.java,
				) ?: 0
			if (waiting > 0) return
			Thread.sleep(10)
		}
		error("no connection started waiting for a lock")
	}

	/** The ids of FILE and FOLDER entries in the Trash (other modules' tests may leave their own items). */
	private fun filesTrash(): List<UUID> = trash.entries().filter { it.type == ItemType.FILE || it.type == ItemType.FOLDER }.map { it.item.id }

	private fun <T> Future<T>.failure(): Throwable = shouldThrow<ExecutionException> { get(TIMEOUT, TimeUnit.SECONDS) }.cause.shouldNotBeNull()

	@Test
	fun `an upload into a folder deleted while its blob is written fails and leaves neither row nor blob`() {
		val before = blobFiles()
		val folder = service.createFolder("Inbox", null)
		val reading = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val upload =
			pool.submit<UUID> {
				service
					.storeFile(folder.id, "scan.pdf", "application/pdf", BlockingContent("scan bytes".toByteArray(), reading, proceed))
					.id
			}
		reading.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true

		service.deleteFolder(folder.id)
		proceed.countDown()

		upload.failure().shouldBeInstanceOf<NotFoundException>()
		files.findAll().shouldBeEmpty()
		blobFiles() shouldBe before
		filesTrash() shouldBe listOf(folder.id)
	}

	@Test
	fun `a folder delete waits for an upload that passed the check, and trashes the new file with the folder`() {
		val before = blobFiles()
		val parent = service.createFolder("Projects", null)
		val folder = service.createFolder("Inbox", parent.id)
		val stored = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val upload =
			pool.submit<UUID> {
				tx.execute {
					service.storeFile(folder.id, "scan.pdf", "application/pdf", "scan bytes".byteInputStream()).id.also {
						stored.countDown()
						check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
					}
				}
			}
		stored.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		val delete = pool.submit { service.deleteFolder(parent.id) }
		awaitLockWait()
		delete.isDone shouldBe false

		proceed.countDown()
		val fileId = upload.get(TIMEOUT, TimeUnit.SECONDS).shouldNotBeNull()
		delete.get(TIMEOUT, TimeUnit.SECONDS)

		val file = files.findById(fileId).orElseThrow()
		file.deletedAt shouldBe folders.findById(parent.id).orElseThrow().deletedAt
		files.findActiveById(fileId).shouldBeNull()
		filesTrash() shouldBe listOf(parent.id)

		trash.purge(ItemRef(ItemType.FOLDER, parent.id))
		files.findAll().shouldBeEmpty()
		blobFiles() shouldBe before
	}

	@Test
	fun `a folder created in a folder that is being deleted waits for the delete and fails`() {
		val folder = service.createFolder("Inbox", null)
		val deleted = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val delete =
			pool.submit {
				tx.execute {
					service.deleteFolder(folder.id)
					deleted.countDown()
					check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
				}
			}
		deleted.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		val create = pool.submit<UUID> { service.createFolder("Late", folder.id).id }
		awaitLockWait()
		create.isDone shouldBe false

		proceed.countDown()
		delete.get(TIMEOUT, TimeUnit.SECONDS)

		create.failure().shouldBeInstanceOf<NotFoundException>()
		folders.findAll().map { it.id } shouldBe listOf(folder.id)
		filesTrash() shouldBe listOf(folder.id)
	}

	@Test
	fun `a folder delete waits for a subfolder created meanwhile and trashes it with the folder`() {
		val parent = service.createFolder("Projects", null)
		val created = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val create =
			pool.submit<UUID> {
				tx.execute {
					service.createFolder("Late", parent.id).id.also {
						created.countDown()
						check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
					}
				}
			}
		created.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		// The delete does not see the uncommitted subfolder yet and waits on the parent's share lock.
		val delete = pool.submit { service.deleteFolder(parent.id) }
		awaitLockWait()
		delete.isDone shouldBe false

		proceed.countDown()
		val childId = create.get(TIMEOUT, TimeUnit.SECONDS).shouldNotBeNull()
		delete.get(TIMEOUT, TimeUnit.SECONDS)

		val deletedAt =
			folders
				.findById(parent.id)
				.orElseThrow()
				.deletedAt
				.shouldNotBeNull()
		folders.findById(childId).orElseThrow().deletedAt shouldBe deletedAt
		folders.findActiveById(childId).shouldBeNull()
		filesTrash() shouldBe listOf(parent.id)

		trash.purge(ItemRef(ItemType.FOLDER, parent.id))
		folders.findAll().shouldBeEmpty()
	}

	@Test
	fun `purging a folder never deletes an active file or folder found below it`() {
		val before = blobFiles()
		val folder = service.createFolder("Old", null)
		val loose = service.storeFile(folder.id, "keep.txt", "text/plain", "keep me".byteInputStream())
		val sub = service.createFolder("Sub", folder.id)
		val nested = service.storeFile(sub.id, "nested.txt", "text/plain", "nested".byteInputStream())
		val gone = service.storeFile(folder.id, "gone.txt", "text/plain", "gone".byteInputStream())
		service.createFolder("Sub", null)
		service.deleteFolder(folder.id)
		// The state an unguarded upload or folder creation racing the delete could leave behind.
		jdbc.update("update file set deleted_at = null where id in (?, ?)", loose.id, nested.id)
		jdbc.update("update folder set deleted_at = null where id = ?", sub.id)

		trash.purge(ItemRef(ItemType.FOLDER, folder.id))

		folders.findById(folder.id).isPresent shouldBe false
		files.findById(gone.id).isPresent shouldBe false
		val kept = files.findActiveById(loose.id).shouldNotBeNull()
		kept.folderId.shouldBeNull()
		blobs.open(kept.storageKey).use { it.readAllBytes().decodeToString() } shouldBe "keep me"
		val rescued = folders.findActiveById(sub.id).shouldNotBeNull()
		rescued.parentId.shouldBeNull()
		rescued.name shouldBe "Sub (2)"
		val inside = files.findActiveById(nested.id).shouldNotBeNull()
		inside.folderId shouldBe sub.id
		blobs.open(inside.storageKey).use { it.readAllBytes().decodeToString() } shouldBe "nested"
		(blobFiles() - before) shouldBe setOf(kept.storageKey, inside.storageKey)
	}

	@Test
	fun `a purge racing a restore of the same file that commits first does nothing`() {
		val file = service.storeFile(null, "scan.pdf", "application/pdf", "scan bytes".byteInputStream())
		service.deleteFile(file.id)
		val restored = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val restore =
			pool.submit<Boolean> {
				tx.execute {
					service.restoreFile(file.id).also {
						// The UPDATE a committing restore runs, while its transaction is still open.
						files.flush()
						restored.countDown()
						check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
					}
				}
			}
		restored.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		val purge = pool.submit<Boolean> { service.purgeFile(file.id) }
		awaitLockWait()
		purge.isDone shouldBe false

		proceed.countDown()
		restore.get(TIMEOUT, TimeUnit.SECONDS) shouldBe true

		purge.get(TIMEOUT, TimeUnit.SECONDS) shouldBe false
		val kept = files.findActiveById(file.id).shouldNotBeNull()
		blobs.open(kept.storageKey).use { it.readAllBytes().decodeToString() } shouldBe "scan bytes"
		filesTrash().shouldBeEmpty()
	}

	@Test
	fun `a restore racing a purge of the same file that commits first fails and the file is gone`() {
		val before = blobFiles()
		val file = service.storeFile(null, "scan.pdf", "application/pdf", "scan bytes".byteInputStream())
		service.deleteFile(file.id)
		val purged = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val purge =
			pool.submit<Boolean> {
				tx.execute {
					service.purgeFile(file.id).also {
						purged.countDown()
						check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
					}
				}
			}
		purged.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		val restore = pool.submit<Boolean> { service.restoreFile(file.id) }
		awaitLockWait()
		restore.isDone shouldBe false

		proceed.countDown()
		purge.get(TIMEOUT, TimeUnit.SECONDS) shouldBe true

		restore.failure().shouldBeInstanceOf<OptimisticLockingFailureException>()
		files.findById(file.id).isPresent shouldBe false
		blobFiles() shouldBe before
		filesTrash().shouldBeEmpty()
	}

	@Test
	fun `a purge racing a restore of the same folder that commits first does nothing`() {
		val folder = service.createFolder("Old", null)
		val sub = service.createFolder("Sub", folder.id)
		val file = service.storeFile(sub.id, "keep.txt", "text/plain", "keep me".byteInputStream())
		service.deleteFolder(folder.id)
		val restored = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val restore =
			pool.submit<Boolean> {
				tx.execute {
					service.restoreFolder(folder.id).also {
						// The UPDATE a committing restore runs, while its transaction is still open.
						files.flush()
						restored.countDown()
						check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
					}
				}
			}
		restored.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		val purge = pool.submit<Boolean> { service.purgeFolder(folder.id) }
		awaitLockWait()
		purge.isDone shouldBe false

		proceed.countDown()
		restore.get(TIMEOUT, TimeUnit.SECONDS) shouldBe true

		purge.get(TIMEOUT, TimeUnit.SECONDS) shouldBe false
		folders
			.findActiveById(folder.id)
			.shouldNotBeNull()
			.parentId
			.shouldBeNull()
		folders.findActiveById(sub.id).shouldNotBeNull().parentId shouldBe folder.id
		val kept = files.findActiveById(file.id).shouldNotBeNull()
		kept.folderId shouldBe sub.id
		blobs.open(kept.storageKey).use { it.readAllBytes().decodeToString() } shouldBe "keep me"
		filesTrash().shouldBeEmpty()
	}

	@Test
	fun `a restore racing a purge of the same folder that commits first fails and the folder is gone`() {
		val before = blobFiles()
		val folder = service.createFolder("Old", null)
		val sub = service.createFolder("Sub", folder.id)
		val file = service.storeFile(sub.id, "gone.txt", "text/plain", "gone".byteInputStream())
		service.deleteFolder(folder.id)
		val purged = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val purge =
			pool.submit<Boolean> {
				tx.execute {
					service.purgeFolder(folder.id).also {
						purged.countDown()
						check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
					}
				}
			}
		purged.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		val restore = pool.submit<Boolean> { service.restoreFolder(folder.id) }
		awaitLockWait()
		restore.isDone shouldBe false

		proceed.countDown()
		purge.get(TIMEOUT, TimeUnit.SECONDS) shouldBe true

		restore.failure().shouldBeInstanceOf<OptimisticLockingFailureException>()
		folders.findAll().shouldBeEmpty()
		files.findById(file.id).isPresent shouldBe false
		blobFiles() shouldBe before
		filesTrash().shouldBeEmpty()
	}

	@Test
	fun `two parallel creates of the same folder name give one folder and one conflict`() {
		val created = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val first =
			pool.submit<UUID> {
				tx.execute {
					service.createFolder("Reports", null).id.also {
						created.countDown()
						check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
					}
				}
			}
		created.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		// Does not see the uncommitted folder, so it passes the name check and waits on the unique index.
		val second = pool.submit<UUID> { service.createFolder("reports", null).id }
		awaitLockWait()

		proceed.countDown()

		val id = first.get(TIMEOUT, TimeUnit.SECONDS).shouldNotBeNull()
		second.failure().shouldBeInstanceOf<ConflictException>()
		folders.findAll().map { it.id } shouldBe listOf(id)
	}

	@Test
	fun `a rename racing a create of the same name is a conflict`() {
		val other = service.createFolder("Drafts", null)
		val created = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val first =
			pool.submit<UUID> {
				tx.execute {
					service.createFolder("Reports", null).id.also {
						created.countDown()
						check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
					}
				}
			}
		created.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		val rename = pool.submit<UUID> { service.renameFolder(other.id, "Reports").id }
		awaitLockWait()

		proceed.countDown()

		first.get(TIMEOUT, TimeUnit.SECONDS).shouldNotBeNull()
		rename.failure().shouldBeInstanceOf<ConflictException>()
		folders.findById(other.id).orElseThrow().name shouldBe "Drafts"
	}

	@Test
	fun `an adopted user folder gets the special spelling, is reindexed and announced`(events: ApplicationEvents) {
		val attachments = service.createFolder("attachments", null)
		val tasks = service.createFolder("TASKS", attachments.id)
		events.clear()

		service.specialFolder(SpecialFolder.TASKS).id shouldBe tasks.id

		folders.findById(attachments.id).orElseThrow().name shouldBe "Attachments"
		folders.findById(tasks.id).orElseThrow().name shouldBe "Tasks"
		service.pathOf(tasks.id) shouldBe "/Attachments/Tasks"
		jdbc.queryForList(
			"select title from search_document where item_type = 'FOLDER' and item_id in (?, ?)",
			String::class.java,
			attachments.id,
			tasks.id,
		) shouldContainExactlyInAnyOrder listOf("Attachments", "Tasks")
		events.stream(ChangeEvent::class.java).map { Triple(it.type, it.id, it.op) }.toList() shouldContainExactlyInAnyOrder
			listOf(
				Triple("FOLDER", attachments.id, ChangeOp.UPDATED),
				Triple("FOLDER", tasks.id, ChangeOp.UPDATED),
			)
		shouldThrow<ConflictException> { service.renameFolder(tasks.id, "tasks") }
	}

	// --- concurrent folder moves (task files-api)

	@Test
	fun `two crossing folder moves cannot create a cycle - the second waits and is rejected`() {
		val a = service.createFolder("A", null)
		val b = service.createFolder("B", null)
		val moved = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val first =
			pool.submit {
				tx.execute {
					service.updateFolder(a.id, null, MoveTarget(b.id))
					moved.countDown()
					check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
				}
			}
		moved.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		// Before the first move commits, B's subtree does not yet contain A: only the move lock stops the cycle.
		val second = pool.submit { service.updateFolder(b.id, null, MoveTarget(a.id)) }
		awaitLockWait()
		second.isDone shouldBe false

		proceed.countDown()
		first.get(TIMEOUT, TimeUnit.SECONDS)
		second.failure().shouldBeInstanceOf<ConflictException>()

		folders.findById(a.id).orElseThrow().parentId shouldBe b.id
		folders
			.findById(b.id)
			.orElseThrow()
			.parentId
			.shouldBeNull()
		service.pathOf(a.id) shouldBe "/B/A"
	}

	@Test
	fun `a folder move into a folder being trashed waits and then fails with 404`() {
		val target = service.createFolder("Target", null)
		val moving = service.createFolder("Moving", null)
		val deleted = CountDownLatch(1)
		val proceed = CountDownLatch(1)
		val delete =
			pool.submit {
				tx.execute {
					service.deleteFolder(target.id)
					deleted.countDown()
					check(proceed.await(TIMEOUT, TimeUnit.SECONDS))
				}
			}
		deleted.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
		val move = pool.submit { service.updateFolder(moving.id, null, MoveTarget(target.id)) }
		awaitLockWait()

		proceed.countDown()
		delete.get(TIMEOUT, TimeUnit.SECONDS)
		move.failure().shouldBeInstanceOf<NotFoundException>()
		folders
			.findById(moving.id)
			.orElseThrow()
			.parentId
			.shouldBeNull()
	}

	private companion object {
		const val TIMEOUT = 30L
	}
}
