package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import jakarta.activation.DataHandler
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.UUID

/**
 * Streaming an attachment ([MailReadService.attachment]) tells a client that went away apart from a mail server
 * failure: only the latter may leave the IMAP block with an exception (which marks the pooled connection unhealthy).
 */
class MailAttachmentDownloadTests {
	private val message = MailMessage(UUID.randomUUID(), UUID.randomUUID(), 7)
	private val attachment = MailAttachment("2", "budget.csv", "text/csv", 30, inline = false, contentId = null)
	private val locator = mockk<MailMessageLocator> { every { current(message.id) } returns message }
	private val loader = mockk<MailContentLoader>()

	/** How the IMAP block ended: `null` while it did not run, else its result or exception. */
	private var blockOutcome: Result<Any?>? = null

	private val service = MailReadService(locator, mockk(), mockk(), mockk(), mockk(), loader, mockk())

	private fun serverCopy(content: ByteArray): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setContent(
				MimeMultipart(
					MimeBodyPart().apply { setText("Hello", "UTF-8") },
					MimeBodyPart().apply {
						dataHandler = DataHandler(ByteArrayDataSource(content, "application/octet-stream"))
						fileName = "budget.csv"
					},
				),
			)
			saveChanges()
		}

	/** The loader runs the block on [root] like the real one, recording how it ended. */
	private fun remote(root: (Part) -> Part = { it }) {
		every { loader.load(message) } returns MailContent("Hello", null, listOf(attachment))
		every { loader.withRemoteMessage<Any?>(message, any()) } answers {
			val block = secondArg<(Part) -> Any?>()
			val outcome = runCatching { block(root(serverCopy(CONTENT))) }
			blockOutcome = outcome
			outcome.getOrThrow()
		}
	}

	@Test
	fun `the attachment is streamed to the client`() {
		remote()
		val out = ByteArrayOutputStream()

		service.attachment(message.id, "2") { out }

		out.toByteArray().toList() shouldBe CONTENT.toList()
		blockOutcome!!.isSuccess shouldBe true
	}

	@Test
	fun `a client that goes away is no mail server failure`() {
		remote()
		val gone =
			object : OutputStream() {
				override fun write(b: Int) = throw IOException("Broken pipe")

				override fun write(
					b: ByteArray,
					off: Int,
					len: Int,
				) = throw IOException("Broken pipe")
			}

		val e = shouldThrow<AttachmentDownloadAbortedException> { service.attachment(message.id, "2") { gone } }

		e.cause.shouldBeInstanceOf<IOException>().message shouldBe "Broken pipe"
		// The IMAP block ended normally, so the pool keeps the connection.
		blockOutcome!!.isSuccess shouldBe true
	}

	@Test
	fun `a client that goes away when the rest is flushed is no mail server failure either`() {
		remote()
		val failsOnFlush =
			object : ByteArrayOutputStream() {
				override fun flush() = throw IOException("Connection reset")
			}

		shouldThrow<AttachmentDownloadAbortedException> { service.attachment(message.id, "2") { failsOnFlush } }

		blockOutcome!!.isSuccess shouldBe true
	}

	@Test
	fun `a failure reading from the mail server still leaves the IMAP block as a failure`() {
		// The real loader turns this IOException into a MailConnectionException and drops the connection.
		remote { root -> FailingReads(root) }

		shouldThrow<IOException> { service.attachment(message.id, "2") { ByteArrayOutputStream() } }

		blockOutcome!!.exceptionOrNull().shouldBeInstanceOf<IOException>()
	}

	/** A message whose part contents fail to read, as when the IMAP connection drops mid-download. */
	private class FailingReads(
		private val root: Part,
	) : Part by root {
		override fun getContent(): Any {
			val multipart = root.content as MimeMultipart
			val failing = MimeMultipart()
			for (index in 0 until multipart.count) {
				val part = multipart.getBodyPart(index) as MimeBodyPart
				failing.addBodyPart(
					object : MimeBodyPart() {
						override fun isMimeType(mimeType: String) = part.isMimeType(mimeType)

						override fun getInputStream(): InputStream =
							object : FilterInputStream(part.inputStream) {
								override fun read(
									b: ByteArray,
									off: Int,
									len: Int,
								): Int = throw IOException("Connection dropped")
							}
					},
				)
			}
			return failing
		}
	}

	private companion object {
		val CONTENT = "item,amount\nfictitious,42\n".toByteArray()
	}
}
