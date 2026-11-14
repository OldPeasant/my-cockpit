package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.sync.FolderSyncResult
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** [MailFolderResync] with an executor that runs tasks only when the test says so. */
class MailFolderResyncTests {
	private val sync = mockk<MailHeaderSyncService>()
	private val executor = ManualExecutor()
	private val resync = MailFolderResync(sync, executor)
	private val folderId = UUID.randomUUID()

	init {
		every { sync.syncFolder(any()) } answers { FolderSyncResult(firstArg(), full = false, 0, 0, 1) }
		every { sync.verifyFolder(any()) } answers { FolderSyncResult(firstArg(), full = false, 0, 0, 1) }
	}

	@Test
	fun `a verified request runs the UID-set sync in the background`() {
		resync.requestVerified(folderId)
		verify(exactly = 0) { sync.verifyFolder(any()) }

		executor.runAll()
		verify(exactly = 1) { sync.verifyFolder(folderId) }
		verify(exactly = 0) { sync.syncFolder(any()) }
	}

	@Test
	fun `a plain and a verified request for a pending folder merge into one verified sync, in either order`() {
		val other = UUID.randomUUID()
		resync.request(folderId)
		resync.requestVerified(folderId)
		resync.requestVerified(other)
		resync.request(other)

		executor.tasks.size shouldBe 2
		executor.runAll()
		verify(exactly = 1) { sync.verifyFolder(folderId) }
		verify(exactly = 1) { sync.verifyFolder(other) }
		verify(exactly = 0) { sync.syncFolder(any()) }
	}

	@Test
	fun `a request syncs the folder in the background, not on the calling thread`() {
		resync.request(folderId)
		verify(exactly = 0) { sync.syncFolder(any()) }

		executor.runAll()
		verify(exactly = 1) { sync.syncFolder(folderId) }
	}

	@Test
	fun `requests for a folder whose sync is still pending are merged, other folders get their own`() {
		val other = UUID.randomUUID()
		resync.request(folderId)
		resync.request(folderId)
		resync.request(other)

		executor.tasks.size shouldBe 2
		executor.runAll()
		verify(exactly = 1) { sync.syncFolder(folderId) }
		verify(exactly = 1) { sync.syncFolder(other) }

		// Once the sync started, a new request schedules another one.
		resync.request(folderId)
		executor.runAll()
		verify(exactly = 2) { sync.syncFolder(folderId) }
	}

	@Test
	fun `a request made while the folder's sync runs schedules another sync`() {
		var calls = 0
		every { sync.syncFolder(folderId) } answers {
			calls++
			// Another stale action arrives while the first sync is reading the server.
			if (calls == 1) resync.request(folderId)
			FolderSyncResult(folderId, full = false, 0, 0, 1)
		}
		resync.request(folderId)

		executor.runAll()

		verify(exactly = 2) { sync.syncFolder(folderId) }
	}

	@Test
	fun `a failing sync is swallowed and does not block later requests`() {
		every { sync.syncFolder(folderId) } throws MailConnectionException(MailConnectionError.CONNECTION_FAILED, RuntimeException("down"))
		resync.request(folderId)
		executor.runAll()

		every { sync.syncFolder(folderId) } throws IllegalStateException("boom")
		resync.request(folderId)
		executor.runAll()

		verify(exactly = 2) { sync.syncFolder(folderId) }
	}

	@Test
	fun `a request after shutdown is dropped without failing`() {
		executor.shutdown()
		resync.request(folderId)
		resync.request(folderId)
		verify(exactly = 0) { sync.syncFolder(any()) }
		executor.rejected shouldBe 2
	}

	@Test
	fun `shutdown stops the executor`() {
		resync.shutdown()
		executor.isShutdown shouldBe true
	}

	private class ManualExecutor : AbstractExecutorService() {
		val tasks = ArrayDeque<Runnable>()
		var rejected = 0
		private var down = false

		override fun execute(command: Runnable) {
			if (down) {
				rejected++
				throw RejectedExecutionException("shut down")
			}
			tasks += command
		}

		fun runAll() {
			while (tasks.isNotEmpty()) tasks.removeFirst().run()
		}

		override fun shutdown() {
			down = true
		}

		override fun shutdownNow(): List<Runnable> = emptyList<Runnable>().also { down = true }

		override fun isShutdown() = down

		override fun isTerminated() = down

		override fun awaitTermination(
			timeout: Long,
			unit: TimeUnit,
		) = true
	}
}
