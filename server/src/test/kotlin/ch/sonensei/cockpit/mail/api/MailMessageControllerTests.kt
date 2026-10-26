package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.infra.BlobTooLargeException
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/**
 * The HTTP side of attachment downloads and mail server failures ([MailMessageController]), with the read service
 * replaced: which headers make attachment content safe to serve, and how failures map to status codes.
 */
class MailMessageControllerTests {
	private val reads = mockk<MailReadService>()
	private val attachmentFiles = mockk<MailAttachmentFilesService>()
	private val mockMvc =
		MockMvcBuilders
			.standaloneSetup(
				MailMessageController(reads, mockk(), mockk(), attachmentFiles),
			).setControllerAdvice(MailConnectionAdvice())
			.build()
	private val id = UUID.randomUUID()

	/** The service hands [attachment] to the controller and streams [content] into the stream it opens. */
	private fun serving(
		attachment: MailAttachment,
		content: ByteArray = CONTENT,
	) {
		every { reads.attachment(id, attachment.partId, any()) } answers {
			thirdArg<(MailAttachment) -> OutputStream>()(attachment).write(content)
		}
	}

	private fun download(
		partId: String,
		inline: Boolean? = null,
	): MockHttpServletResponse =
		mockMvc
			.get("/api/v1/mail/messages/$id/attachments/$partId") { inline?.let { param("inline", it.toString()) } }
			.andReturn()
			.response

	private fun MockHttpServletResponse.shouldBeUntrustedContent() {
		getHeader("X-Content-Type-Options") shouldBe "nosniff"
		getHeader("Content-Security-Policy") shouldBe "default-src 'none'; sandbox"
		getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
	}

	@Test
	fun `an image asked for inline is served inline with its own type`() {
		serving(MailAttachment("1.2", "logo.png", "image/png", 3, inline = true, contentId = "logo@example.test"))

		val response = download("1.2", inline = true)

		response.status shouldBe 200
		response.contentType shouldBe "image/png"
		response.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain "inline"
		response.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain "logo.png"
		response.contentAsByteArray.toList() shouldBe CONTENT.toList()
		response.shouldBeUntrustedContent()
	}

	@Test
	fun `an image is a download without inline=true`() {
		serving(MailAttachment("2", "photo.jpg", "image/jpeg", 3, inline = false, contentId = null))

		val response = download("2")

		response.contentType shouldBe "application/octet-stream"
		response.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain "attachment"
		response.shouldBeUntrustedContent()
	}

	@Test
	fun `HTML, SVG and other non-raster parts are never served inline`() {
		listOf("text/html", "image/svg+xml", "application/pdf").forEachIndexed { index, type ->
			val partId = "${index + 2}"
			serving(MailAttachment(partId, "file$partId", type, 3, inline = false, contentId = null))

			val response = download(partId, inline = true)

			response.status shouldBe 200
			response.contentType shouldBe "application/octet-stream"
			val disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull()
			disposition shouldContain "attachment"
			disposition shouldNotContain "inline"
			response.shouldBeUntrustedContent()
		}
	}

	@Test
	fun `an unnamed part downloads as attachment and non-ASCII names are encoded`() {
		serving(MailAttachment("2", null, "application/pdf", 3, inline = false, contentId = null))
		download("2").getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain "filename=\"attachment\""

		serving(MailAttachment("3", "Prüfbericht.pdf", "application/pdf", 3, inline = false, contentId = null))
		download("3").getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain
			"filename*=UTF-8''Pr%C3%BCfbericht.pdf"
	}

	@Test
	fun `busy connections are 503 and other mail server failures 502, with a fixed message and error code`() {
		every { reads.read(id, any()) } throws MailConnectionException(MailConnectionError.POOL_EXHAUSTED)
		mockMvc.get("/api/v1/mail/messages/$id").andExpect {
			status { isServiceUnavailable() }
			content { contentTypeCompatibleWith("application/problem+json") }
			jsonPath("$.errorCode") { value("POOL_EXHAUSTED") }
			jsonPath("$.detail") { value(MailConnectionError.POOL_EXHAUSTED.message) }
		}

		every { reads.read(id, any()) } throws
			MailConnectionException(MailConnectionError.TIMEOUT, IOException("* BYE secret server response for jane@example.test"))
		val response =
			mockMvc
				.get("/api/v1/mail/messages/$id")
				.andExpect {
					status { isBadGateway() }
					jsonPath("$.errorCode") { value("TIMEOUT") }
				}.andReturn()
				.response
		response.contentAsString shouldNotContain "secret server response"
	}

	@Test
	fun `a download the client aborted ends without an error answer`() {
		every { reads.attachment(id, "2", any()) } answers {
			thirdArg<(MailAttachment) -> OutputStream>()(MailAttachment("2", "budget.csv", "text/csv", 3, inline = false, contentId = null))
				.write(CONTENT)
			throw AttachmentDownloadAbortedException(IOException("Broken pipe"))
		}

		val response = download("2")

		// No 5xx Problem Details as for a mail server failure: nothing is answered to a client that is gone.
		response.status shouldBe 200
		response.contentAsString shouldNotContain "errorCode"
		(response.contentType ?: "") shouldNotContain "problem"
	}

	@Test
	fun `saving an attachment to Files answers 201 with the file, its location and its version`() {
		val stored = FileItem("budget (2).csv", UUID.randomUUID(), "text/csv", 30, "fictitious-sha256", "fictitious-key")
		// Timestamps and version are set by JPA; the stub answers them like a persisted file.
		val file =
			mockk<FileItem> {
				every { id } returns stored.id
				every { name } returns stored.name
				every { folderId } returns stored.folderId
				every { mime } returns stored.mime
				every { size } returns stored.size
				every { sha256 } returns stored.sha256
				every { createdAt } returns Instant.parse("2026-10-26T08:00:00Z")
				every { updatedAt } returns Instant.parse("2026-10-26T08:00:00Z")
				every { version } returns 3
			}
		every { attachmentFiles.saveToFiles(id, "1.2") } returns file

		val response = saveToFiles("1.2")

		response.status shouldBe 201
		response.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/files/${file.id}"
		response.getHeader(HttpHeaders.ETAG) shouldBe "\"3\""
		response.contentAsString shouldContain "\"name\":\"budget (2).csv\""
		response.contentAsString shouldContain "\"folderId\":\"${file.folderId}\""
		response.contentAsString shouldContain "\"id\":\"${file.id}\""
	}

	@Test
	fun `an attachment over the maximum file size is 413 Problem Details`() {
		every { attachmentFiles.saveToFiles(id, "2") } throws BlobTooLargeException(1024)

		mockMvc.post("/api/v1/mail/messages/$id/attachments/2/save-to-files").andExpect {
			status { isEqualTo(413) }
			content { contentTypeCompatibleWith("application/problem+json") }
			jsonPath("$.status") { value(413) }
			jsonPath("$.detail") { value("The file exceeds the maximum size of 1024 bytes") }
		}
	}

	@Test
	fun `saving to Files while the connections are busy is 503 and on a mail server failure 502`() {
		every { attachmentFiles.saveToFiles(id, "2") } throws MailConnectionException(MailConnectionError.POOL_EXHAUSTED)
		mockMvc.post("/api/v1/mail/messages/$id/attachments/2/save-to-files").andExpect {
			status { isServiceUnavailable() }
			content { contentTypeCompatibleWith("application/problem+json") }
			jsonPath("$.errorCode") { value("POOL_EXHAUSTED") }
		}

		every { attachmentFiles.saveToFiles(id, "2") } throws
			MailConnectionException(MailConnectionError.CONNECTION_FAILED, IOException("* BYE secret server response for jane@example.test"))
		val body =
			mockMvc
				.post("/api/v1/mail/messages/$id/attachments/2/save-to-files")
				.andExpect {
					status { isBadGateway() }
					jsonPath("$.errorCode") { value("CONNECTION_FAILED") }
				}.andReturn()
				.response.contentAsString
		body shouldNotContain "secret server response"
	}

	private fun saveToFiles(partId: String): MockHttpServletResponse =
		mockMvc.post("/api/v1/mail/messages/$id/attachments/$partId/save-to-files").andReturn().response

	private companion object {
		val CONTENT = byteArrayOf(1, 2, 3)
	}
}
