package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.support.PostgresTestBase
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

/**
 * The HTTP contract of folder create/rename/delete with a mocked [MailFolderService]: which PATCH bodies rename, which
 * change the role (`{"name"}` alone only renames, `{}` and `{"role": null}` still clear the role as before), the ETag
 * passed from the rename to the role change, Bean Validation of the bodies, and errors mapped to Problem Details.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class MailFolderManageWebTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	@MockkBean(relaxed = true)
	private lateinit var service: MailFolderService

	private val accountId = UuidV7.generate()
	private val folderId = UuidV7.generate()
	private val base = "${MailAccountController.BASE}/$accountId/folders"

	private fun folder(
		path: String,
		version: Long,
		role: MailFolderRole? = null,
	): MailFolder =
		spyk(MailFolder(accountId, path, path.substringAfterLast('/'), folderId).apply { this.role = role }).also {
			every { it.version } returns version
		}

	@BeforeEach
	fun setUp() {
		every { service.rename(any(), any(), any(), any()) } returns folder("Renamed", 2)
		every { service.overrideRole(any(), any(), any(), any(), any()) } returns folder("Renamed", 3, MailFolderRole.ARCHIVE)
		every { service.create(any(), any(), any()) } returns folder("New", 0)
	}

	private fun patch(
		body: String,
		ifMatch: String? = "\"1\"",
	): ResultActionsDsl =
		mockMvc.patch("$base/$folderId") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
			ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
		}

	private fun create(body: String): ResultActionsDsl =
		mockMvc.post(base) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	@Test
	fun `a body with only a name renames without touching the role`() {
		patch("""{"name": "Renamed"}""").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"2\"") }
			jsonPath("$.path") { value("Renamed") }
		}

		verify { service.rename(accountId, folderId, "Renamed", "\"1\"") }
		verify(exactly = 0) { service.overrideRole(any(), any(), any(), any(), any()) }
	}

	@Test
	fun `an empty body and an explicit null role still clear the role without renaming`() {
		patch("{}").andExpect { status { isOk() } }
		patch("""{"role": null}""").andExpect { status { isOk() } }

		verify(exactly = 2) { service.overrideRole(accountId, folderId, null, false, "\"1\"") }
		verify(exactly = 0) { service.rename(any(), any(), any(), any()) }
	}

	@Test
	fun `a role or automatic without a name changes only the role`() {
		patch("""{"role": "ARCHIVE"}""").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"3\"") }
		}
		patch("""{"automatic": true}""").andExpect { status { isOk() } }

		verify { service.overrideRole(accountId, folderId, MailFolderRole.ARCHIVE, false, "\"1\"") }
		verify { service.overrideRole(accountId, folderId, null, true, "\"1\"") }
		verify(exactly = 0) { service.rename(any(), any(), any(), any()) }
	}

	@Test
	fun `a name with a role renames first and changes the role with the renamed folder's ETag`() {
		patch("""{"name": "Renamed", "role": "ARCHIVE"}""").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"3\"") }
			jsonPath("$.role") { value("ARCHIVE") }
		}

		verifyOrder {
			service.rename(accountId, folderId, "Renamed", "\"1\"")
			service.overrideRole(accountId, folderId, MailFolderRole.ARCHIVE, false, "\"2\"")
		}
	}

	@Test
	fun `a name with a null role or automatic renames and then clears or hands back the role`() {
		patch("""{"name": "Renamed", "role": null}""").andExpect { status { isOk() } }
		patch("""{"name": "Renamed", "automatic": true}""").andExpect { status { isOk() } }

		verify(exactly = 2) { service.rename(accountId, folderId, "Renamed", "\"1\"") }
		verify { service.overrideRole(accountId, folderId, null, false, "\"2\"") }
		verify { service.overrideRole(accountId, folderId, null, true, "\"2\"") }
	}

	@Test
	fun `a refused rename does not change the role`() {
		every { service.rename(any(), any(), any(), any()) } throws ConflictException("A folder with this name already exists")

		patch("""{"name": "Taken", "role": "ARCHIVE"}""").andExpect {
			status { isConflict() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}

		verify(exactly = 0) { service.overrideRole(any(), any(), any(), any(), any()) }
	}

	@Test
	fun `a server refusal answers 502 with errorCode SERVER_REJECTED`() {
		every { service.rename(any(), any(), any(), any()) } throws MailConnectionException(MailConnectionError.SERVER_REJECTED)
		every { service.create(any(), any(), any()) } throws MailConnectionException(MailConnectionError.SERVER_REJECTED)
		every { service.delete(any(), any(), any()) } throws MailConnectionException(MailConnectionError.SERVER_REJECTED)

		listOf(
			patch("""{"name": "Renamed"}"""),
			create("""{"name": "New"}"""),
			mockMvc.delete("$base/$folderId") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"1\"")
			},
		).forEach {
			it.andExpect {
				status { isBadGateway() }
				content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
				jsonPath("$.errorCode") { value("SERVER_REJECTED") }
			}
		}
	}

	@Test
	fun `create answers 201 with the folder and its ETag and passes the parent`() {
		val parentId = UuidV7.generate()
		every { service.create(accountId, "Q3", parentId) } returns folder("Projects/Q3", 0)

		create("""{"name": "Q3", "parentId": "$parentId"}""").andExpect {
			status { isCreated() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.path") { value("Projects/Q3") }
			jsonPath("$.name") { value("Q3") }
		}
	}

	@Test
	fun `names are required on create and limited to 200 characters`() {
		val tooLong = "x".repeat(201)

		listOf(create("{}"), create("""{"name": null}"""), create("""{"name": "$tooLong"}"""), patch("""{"name": "$tooLong"}"""))
			.forEach {
				it.andExpect {
					status { isBadRequest() }
					content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
				}
			}
		create("""{"name": "${"x".repeat(200)}"}""").andExpect { status { isCreated() } }

		verify(exactly = 1) { service.create(any(), any(), any()) }
		verify(exactly = 0) { service.rename(any(), any(), any(), any()) }
	}

	@Test
	fun `delete answers 204 and passes If-Match`() {
		mockMvc
			.delete("$base/$folderId") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"4\"")
			}.andExpect { status { isNoContent() } }

		verify { service.delete(accountId, folderId, "\"4\"") }
	}
}
