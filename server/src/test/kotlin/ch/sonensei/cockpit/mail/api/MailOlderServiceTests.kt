package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.OlderMessagesResult
import ch.sonensei.cockpit.mail.sync.RemoteFolderNotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

/** When a folder offers older mail ([MailOlderService]), with the sync service replaced. */
class MailOlderServiceTests {
	private val sync = mockk<MailHeaderSyncService>()
	private val folders = mockk<MailFolderRepository>()
	private val service = MailOlderService(sync, folders)
	private val folder = MailFolder(UUID.randomUUID(), "Archive", "Archive").apply { uidValidity = 7 }

	init {
		every { folders.findById(folder.id) } answers { Optional.of(folder) }
	}

	@Test
	fun `a synced folder offers older mail until a load finds nothing more`() {
		service.hasOlder(folder.id) shouldBe true

		every { sync.loadOlder(folder.id, 10) } returns OlderMessagesResult(loaded = 10, hasMore = true)
		service.loadOlder(folder.id, 10) shouldBe OlderMessagesResult(10, true)
		service.hasOlder(folder.id) shouldBe true

		every { sync.loadOlder(folder.id, 10) } returns OlderMessagesResult(loaded = 4, hasMore = false)
		service.loadOlder(folder.id, 10) shouldBe OlderMessagesResult(4, false)
		service.hasOlder(folder.id) shouldBe false
	}

	@Test
	fun `a changed UIDVALIDITY offers older mail again`() {
		every { sync.loadOlder(folder.id, any()) } returns OlderMessagesResult(loaded = 0, hasMore = false)
		service.loadOlder(folder.id, 100)
		service.hasOlder(folder.id) shouldBe false

		// A resync caches only the sync window again.
		folder.uidValidity = 8
		service.hasOlder(folder.id) shouldBe true
	}

	@Test
	fun `a never synced folder offers older mail, a folder without messages or an unknown one does not`() {
		folder.uidValidity = null
		service.hasOlder(folder.id) shouldBe true

		folder.selectable = false
		service.hasOlder(folder.id) shouldBe false

		val unknown = UUID.randomUUID()
		every { folders.findById(unknown) } returns Optional.empty()
		service.hasOlder(unknown) shouldBe false
	}

	@Test
	fun `a folder gone from the server is not found`() {
		every { sync.loadOlder(folder.id, any()) } throws RemoteFolderNotFoundException("Archive")

		shouldThrow<NotFoundException> { service.loadOlder(folder.id, 100) }
	}

	@Test
	fun `a folder gone from the server keeps offering older mail`() {
		every { sync.loadOlder(folder.id, any()) } throws RemoteFolderNotFoundException("Archive")

		shouldThrow<NotFoundException> { service.loadOlder(folder.id, 100) }
		service.hasOlder(folder.id) shouldBe true
	}

	@Test
	fun `an unknown folder is not found and nothing is recorded`() {
		val unknown = UUID.randomUUID()
		every { folders.findById(unknown) } returns Optional.empty()
		every { sync.loadOlder(unknown, any()) } throws NotFoundException("Folder not found")

		shouldThrow<NotFoundException> { service.loadOlder(unknown, 100) }
		service.hasOlder(unknown) shouldBe false
	}

	@Test
	fun `a load that finds more after a complete one offers older mail again`() {
		every { sync.loadOlder(folder.id, any()) } returns OlderMessagesResult(loaded = 0, hasMore = false)
		service.loadOlder(folder.id, 100)
		service.hasOlder(folder.id) shouldBe false

		// For example, older messages were moved into the folder on the server.
		every { sync.loadOlder(folder.id, any()) } returns OlderMessagesResult(loaded = 1, hasMore = true)
		service.loadOlder(folder.id, 100) shouldBe OlderMessagesResult(1, true)
		service.hasOlder(folder.id) shouldBe true
	}

	@Test
	fun `completeness is recorded for the UIDVALIDITY the load ended with`() {
		// The load found a changed UIDVALIDITY and resynced the folder before loading older mail.
		every { sync.loadOlder(folder.id, any()) } answers {
			folder.uidValidity = 9
			OlderMessagesResult(loaded = 2, hasMore = false)
		}
		service.loadOlder(folder.id, 100)
		service.hasOlder(folder.id) shouldBe false

		folder.uidValidity = 7
		service.hasOlder(folder.id) shouldBe true
	}

	@Test
	fun `a load that leaves the folder without UIDVALIDITY does not mark it complete`() {
		folder.uidValidity = null
		every { sync.loadOlder(folder.id, any()) } returns OlderMessagesResult(loaded = 0, hasMore = false)

		service.loadOlder(folder.id, 100) shouldBe OlderMessagesResult(0, false)
		service.hasOlder(folder.id) shouldBe true

		// Once the folder gets a UIDVALIDITY, nothing recorded earlier makes it look complete.
		folder.uidValidity = 7
		service.hasOlder(folder.id) shouldBe true
	}

	@Test
	fun `a folder deleted from the cache during a load is not marked complete`() {
		every { sync.loadOlder(folder.id, any()) } answers {
			// A concurrent folder sync removed it locally.
			every { folders.findById(folder.id) } returns Optional.empty()
			OlderMessagesResult(loaded = 0, hasMore = false)
		}

		service.loadOlder(folder.id, 100) shouldBe OlderMessagesResult(0, false)

		// It comes back (same id and UIDVALIDITY): nothing recorded makes it look complete.
		every { folders.findById(folder.id) } returns Optional.of(folder)
		service.hasOlder(folder.id) shouldBe true
	}

	@Test
	fun `completeness of one folder does not affect another`() {
		val other = MailFolder(folder.accountId, "Projects", "Projects").apply { uidValidity = 7 }
		every { folders.findById(other.id) } returns Optional.of(other)
		every { sync.loadOlder(folder.id, any()) } returns OlderMessagesResult(loaded = 0, hasMore = false)

		service.loadOlder(folder.id, 100)

		service.hasOlder(folder.id) shouldBe false
		service.hasOlder(other.id) shouldBe true
	}

	@Test
	fun `the limit is passed to the sync service unchanged`() {
		every { sync.loadOlder(folder.id, 1) } returns OlderMessagesResult(loaded = 1, hasMore = true)
		every { sync.loadOlder(folder.id, MailOlderService.MAX_LIMIT) } returns OlderMessagesResult(loaded = 5, hasMore = false)

		service.loadOlder(folder.id, 1) shouldBe OlderMessagesResult(1, true)
		service.loadOlder(folder.id, MailOlderService.MAX_LIMIT) shouldBe OlderMessagesResult(5, false)
	}
}
