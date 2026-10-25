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
}
