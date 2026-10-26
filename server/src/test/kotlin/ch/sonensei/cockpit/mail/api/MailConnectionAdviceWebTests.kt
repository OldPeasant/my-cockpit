package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.infra.BlobTooLargeException
import ch.sonensei.cockpit.mail.compose.DraftService
import ch.sonensei.cockpit.mail.gtd.MailGtdService
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.support.PostgresTestBase
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.io.IOException
import java.util.UUID

/**
 * The shared [MailConnectionAdvice] in the real application context: every mail controller answers a
 * [MailConnectionException] with 503 (pool exhausted) or 502 Problem Details carrying the `errorCode`, ahead of the
 * global ProblemDetailsAdvice catch-all (which would answer 500), while other exceptions still reach the global advice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class MailConnectionAdviceWebTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	@MockkBean(relaxed = true)
	private lateinit var reads: MailReadService

	@MockkBean(relaxed = true)
	private lateinit var folders: MailFolderService

	@MockkBean(relaxed = true)
	private lateinit var gtd: MailGtdService

	@MockkBean(relaxed = true)
	private lateinit var drafts: DraftService

	@MockkBean(relaxed = true)
	private lateinit var attachmentFiles: MailAttachmentFilesService

	private val id = UUID.fromString("01900000-0000-7000-8000-000000000042")

	private fun serverFailure(error: MailConnectionError) =
		MailConnectionException(error, IOException("* BYE fictitious-server-secret for jane@example.test"))

	/** One request per mail controller, each ending in the given failure of its service. */
	private fun requestsFailingWith(error: MailConnectionError): Map<String, () -> ResultActionsDsl> {
		every { reads.read(id, any()) } throws serverFailure(error)
		every { folders.sync(id) } throws serverFailure(error)
		every { gtd.toTask(id) } throws serverFailure(error)
		every { drafts.send(id, any()) } throws serverFailure(error)
		every { attachmentFiles.saveToFiles(id, "2") } throws serverFailure(error)
		return mapOf(
			"MailMessageController" to { mockMvc.get("/api/v1/mail/messages/$id") },
			"MailMessageController save-to-files" to {
				mockMvc.post("/api/v1/mail/messages/$id/attachments/2/save-to-files") { with(csrf()) }
			},
			"MailAccountController" to { mockMvc.post("/api/v1/mail/accounts/$id/folders/sync") { with(csrf()) } },
			"MailGtdController" to { mockMvc.post("/api/v1/mail/messages/$id/to-task") { with(csrf()) } },
			"DraftController" to {
				mockMvc.post("/api/v1/mail/drafts/$id/send") {
					with(csrf())
					header(HttpHeaders.IF_MATCH, "\"0\"")
				}
			},
		)
	}

	private fun ResultActionsDsl.shouldBeMailProblem(
		status: Int,
		title: String,
		error: MailConnectionError,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentTypeCompatibleWith("application/problem+json") }
		jsonPath("$.status") { value(status) }
		jsonPath("$.title") { value(title) }
		// The default type "about:blank", which the Problem Details serializer leaves out.
		jsonPath("$.type") { doesNotExist() }
		jsonPath("$.detail") { value(error.message) }
		jsonPath("$.errorCode") { value(error.name) }
	}

	/** Runs [block], naming [controller] in any failure so a loop over all controllers points at the broken one. */
	private fun forController(
		controller: String,
		block: () -> Unit,
	) = try {
		block()
	} catch (e: AssertionError) {
		throw AssertionError("$controller: ${e.message}", e)
	}

	@Test
	fun `an exhausted pool answers 503 POOL_EXHAUSTED from every mail controller`() {
		for ((controller, request) in requestsFailingWith(MailConnectionError.POOL_EXHAUSTED)) {
			forController(controller) {
				request().shouldBeMailProblem(503, "Service Unavailable", MailConnectionError.POOL_EXHAUSTED)
			}
		}
	}

	@Test
	fun `every other mail server failure answers 502 with its error code from every mail controller, not the 500 catch-all`() {
		val others = MailConnectionError.entries.filter { it != MailConnectionError.POOL_EXHAUSTED }
		for (error in others) {
			for ((controller, request) in requestsFailingWith(error)) {
				forController("$controller/$error") {
					val body =
						request()
							.shouldBeMailProblem(502, "Bad Gateway", error)
							.andReturn()
							.response.contentAsString
					body shouldNotContain "fictitious-server-secret"
				}
			}
		}
	}

	@Test
	fun `save-to-files answers a missing message or part with 404 and a too large one with 413 Problem Details`() {
		every { attachmentFiles.saveToFiles(id, "9") } throws NotFoundException("Attachment not found")
		mockMvc.post("/api/v1/mail/messages/$id/attachments/9/save-to-files") { with(csrf()) }.andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith("application/problem+json") }
			jsonPath("$.errorCode") { doesNotExist() }
		}

		every { attachmentFiles.saveToFiles(id, "3") } throws BlobTooLargeException(2048)
		mockMvc.post("/api/v1/mail/messages/$id/attachments/3/save-to-files") { with(csrf()) }.andExpect {
			status { isEqualTo(413) }
			content { contentTypeCompatibleWith("application/problem+json") }
			jsonPath("$.detail") { value("The file exceeds the maximum size of 2048 bytes") }
		}
	}

	@Test
	fun `save-to-files without a CSRF token is rejected and saves nothing`() {
		mockMvc.post("/api/v1/mail/messages/$id/attachments/2/save-to-files").andExpect {
			status { isForbidden() }
		}
		verify(exactly = 0) { attachmentFiles.saveToFiles(any(), any()) }
	}

	@Test
	fun `other exceptions of a mail controller still reach the global advice`() {
		every { folders.sync(id) } throws NotFoundException("Mail account not found")
		mockMvc.post("/api/v1/mail/accounts/$id/folders/sync") { with(csrf()) }.andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith("application/problem+json") }
			jsonPath("$.errorCode") { doesNotExist() }
		}

		every { reads.read(id, any()) } throws IllegalStateException("fictitious internal failure")
		mockMvc.get("/api/v1/mail/messages/$id").andExpect {
			status { isInternalServerError() }
			jsonPath("$.detail") { value("An unexpected error occurred") }
			jsonPath("$.errorCode") { doesNotExist() }
		}
	}
}
