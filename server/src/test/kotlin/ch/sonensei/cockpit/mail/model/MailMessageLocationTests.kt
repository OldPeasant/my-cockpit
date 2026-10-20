package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.persistence.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MailMessageLocationTests {
	private val accountId = UuidV7.generate()
	private val inbox = UuidV7.generate()
	private val archive = UuidV7.generate()

	@Test
	fun `a message may exist without folder and UID, or with a folder but no UID`() {
		MailMessage(accountId, null, null).isLocated shouldBe false
		MailMessage(accountId, inbox, null).isLocated shouldBe false
	}

	@Test
	fun `negative UIDs are rejected`() {
		shouldThrow<IllegalArgumentException> { MailMessage(accountId, inbox, -1) }
	}

	@Test
	fun `locate records the new folder and UID`() {
		val message = MailMessage(accountId, inbox, 10)

		message.locate(archive, 3)

		message.folderId shouldBe archive
		message.uid shouldBe 3
		message.isLocated shouldBe true
	}

	@Test
	fun `locate rejects an invalid UID and keeps the old location`() {
		val message = MailMessage(accountId, inbox, 10)

		shouldThrow<IllegalArgumentException> { message.locate(archive, 0) }

		message.folderId shouldBe inbox
		message.uid shouldBe 10
	}

	@Test
	fun `detach clears UID and modseq but keeps folder and headers for re-resolution`() {
		val message =
			MailMessage(accountId, inbox, 10).apply {
				modSeq = 77
				messageIdHeader = "<a@example.com>"
			}

		message.detach()

		message.isLocated shouldBe false
		message.uid.shouldBeNull()
		message.modSeq.shouldBeNull()
		message.folderId shouldBe inbox
		message.messageIdHeader shouldBe "<a@example.com>"
	}

	@Test
	fun `a detached message can be located again`() {
		val message = MailMessage(accountId, inbox, 10).apply { detach() }

		message.locate(inbox, 11)

		message.uid shouldBe 11
		message.isLocated shouldBe true
	}
}
