package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.core.web.ProblemDetailsAdvice
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.sync.OlderMessagesResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/** `POST /folders/{id}/load-older` and `hasOlder` of folder lists ([MailMessageController]), with the services replaced. */
class MailLoadOlderControllerTests {
	private val reads = mockk<MailReadService>()
	private val older = mockk<MailOlderService>()
	private val mockMvc =
		MockMvcBuilders
			.standaloneSetup(MailMessageController(reads, mockk(), older, mockk()))
			.setControllerAdvice(MailConnectionAdvice(), ProblemDetailsAdvice())
			.build()
	private val folderId = UUID.randomUUID()

	@Test
	fun `load-older returns what was loaded, with the default limit`() {
		every { older.loadOlder(folderId, MailOlderService.DEFAULT_LIMIT) } returns OlderMessagesResult(loaded = 3, hasMore = true)

		mockMvc.post("/api/v1/mail/folders/$folderId/load-older").andExpect {
			status { isOk() }
			jsonPath("$.loaded") { value(3) }
			jsonPath("$.hasMore") { value(true) }
		}
	}

	@Test
	fun `load-older passes the limit and rejects one out of range`() {
		every { older.loadOlder(folderId, 200) } returns OlderMessagesResult(loaded = 0, hasMore = false)
		mockMvc.post("/api/v1/mail/folders/$folderId/load-older?limit=200").andExpect {
			status { isOk() }
			jsonPath("$.hasMore") { value(false) }
		}

		listOf("0", "201", "-1").forEach { limit ->
			mockMvc.post("/api/v1/mail/folders/$folderId/load-older?limit=$limit").andExpect {
				status { isBadRequest() }
				content { contentTypeCompatibleWith("application/problem+json") }
			}
		}
		verify(exactly = 1) { older.loadOlder(any(), any()) }
	}

	@Test
	fun `an unknown folder is 404, a malformed id 400`() {
		every { older.loadOlder(folderId, any()) } throws NotFoundException("Mail folder not found")
		mockMvc.post("/api/v1/mail/folders/$folderId/load-older").andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith("application/problem+json") }
		}

		mockMvc.post("/api/v1/mail/folders/not-a-uuid/load-older").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `an unreachable server is 502, busy connections 503, with an error code`() {
		every { older.loadOlder(folderId, any()) } throws MailConnectionException(MailConnectionError.CONNECTION_FAILED)
		mockMvc.post("/api/v1/mail/folders/$folderId/load-older").andExpect {
			status { isBadGateway() }
			jsonPath("$.errorCode") { value("CONNECTION_FAILED") }
		}

		every { older.loadOlder(folderId, any()) } throws MailConnectionException(MailConnectionError.POOL_EXHAUSTED)
		mockMvc.post("/api/v1/mail/folders/$folderId/load-older").andExpect {
			status { isServiceUnavailable() }
			jsonPath("$.errorCode") { value("POOL_EXHAUSTED") }
		}
	}

	@Test
	fun `only the last page of a folder list asks whether there is older mail`() {
		every { older.hasOlder(folderId) } returns true
		every { reads.list(null, folderId, false, any()) } returns CursorPage(emptyList(), null)
		mockMvc.get("/api/v1/mail/messages?folderId=$folderId").andExpect { jsonPath("$.hasOlder") { value(true) } }

		every { reads.list(null, folderId, false, any()) } returns CursorPage(emptyList(), "next")
		mockMvc.get("/api/v1/mail/messages?folderId=$folderId").andExpect { jsonPath("$.hasOlder") { value(false) } }

		every { reads.list(null, null, false, any()) } returns CursorPage(emptyList(), null)
		mockMvc.get("/api/v1/mail/messages").andExpect { jsonPath("$.hasOlder") { value(false) } }

		verify(exactly = 1) { older.hasOlder(any()) }
	}

	@Test
	fun `load-older accepts the smallest limit and rejects a non-numeric one`() {
		every { older.loadOlder(folderId, 1) } returns OlderMessagesResult(loaded = 1, hasMore = true)
		mockMvc.post("/api/v1/mail/folders/$folderId/load-older?limit=1").andExpect {
			status { isOk() }
			jsonPath("$.loaded") { value(1) }
			jsonPath("$.hasMore") { value(true) }
		}

		mockMvc.post("/api/v1/mail/folders/$folderId/load-older?limit=many").andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith("application/problem+json") }
		}
		verify(exactly = 1) { older.loadOlder(any(), any()) }
	}

	@Test
	fun `a complete folder reports no older mail on its last page`() {
		every { older.hasOlder(folderId) } returns false
		every { reads.list(null, folderId, false, any()) } returns CursorPage(emptyList(), null)

		mockMvc.get("/api/v1/mail/messages?folderId=$folderId").andExpect {
			status { isOk() }
			jsonPath("$.nextCursor") { value(null) }
			jsonPath("$.hasOlder") { value(false) }
		}
	}

	@Test
	fun `an account Inbox list never offers older mail`() {
		val accountId = UUID.randomUUID()
		every { reads.list(accountId, null, false, any()) } returns CursorPage(emptyList(), null)

		mockMvc.get("/api/v1/mail/messages?accountId=$accountId").andExpect {
			status { isOk() }
			jsonPath("$.hasOlder") { value(false) }
		}
		verify(exactly = 0) { older.hasOlder(any()) }
	}

	@Test
	fun `a threaded folder list with its account offers older mail on the last page`() {
		val accountId = UUID.randomUUID()
		every { older.hasOlder(folderId) } returns true
		every { reads.list(accountId, folderId, true, any()) } returns CursorPage(emptyList(), null)

		mockMvc.get("/api/v1/mail/messages?accountId=$accountId&folderId=$folderId&threaded=true").andExpect {
			status { isOk() }
			jsonPath("$.hasOlder") { value(true) }
		}
		verify(exactly = 1) { older.hasOlder(folderId) }
	}
}
