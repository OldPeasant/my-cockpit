package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

/**
 * [MailReadService.inInbox]: the message row itself, only while it is located in a folder with role INBOX. Unlike [MailReadService.get]
 * it never follows a moved message to its current copy.
 */
class MailReadServiceInInboxTests {
	private val messages = mockk<MailMessageRepository>()
	private val folders = mockk<MailFolderRepository>()
	private val service = MailReadService(mockk(), messages, mockk(), folders, mockk(), mockk(), mockk())
	private val accountId = UUID.randomUUID()

	private fun folder(role: MailFolderRole?): MailFolder =
		MailFolder(accountId, role?.name ?: "Projects", role?.name ?: "Projects").also { folder ->
			folder.role = role
			every { folders.findById(folder.id) } returns Optional.of(folder)
		}

	private fun stored(message: MailMessage): MailMessage = message.also { every { messages.findById(it.id) } returns Optional.of(it) }

	@Test
	fun `a message located in the Inbox is returned`() {
		val message = stored(MailMessage(accountId, folder(MailFolderRole.INBOX).id, 42L))

		service.inInbox(message.id) shouldBe message
	}

	@Test
	fun `a message in another folder, or in a folder without a role, is not`() {
		listOf(MailFolderRole.ARCHIVE, MailFolderRole.SENT, null).forEach { role ->
			val message = stored(MailMessage(accountId, folder(role).id, 42L))

			service.inInbox(message.id).shouldBeNull()
		}
	}

	@Test
	fun `a message that is no longer located (moved away or its folder deleted) is not, without looking up a folder`() {
		val inbox = folder(MailFolderRole.INBOX)
		val detached = stored(MailMessage(accountId, inbox.id, 42L).apply { detach() })
		val folderless = stored(MailMessage(accountId, null, null))

		service.inInbox(detached.id).shouldBeNull()
		service.inInbox(folderless.id).shouldBeNull()
		verify(exactly = 0) { folders.findById(any()) }
	}

	@Test
	fun `an unknown message or a folder that is gone gives null`() {
		val unknown = UUID.randomUUID()
		every { messages.findById(unknown) } returns Optional.empty()
		val goneFolder = UUID.randomUUID()
		every { folders.findById(goneFolder) } returns Optional.empty()
		val orphan = stored(MailMessage(accountId, goneFolder, 42L))

		service.inInbox(unknown).shouldBeNull()
		service.inInbox(orphan.id).shouldBeNull()
	}
}
