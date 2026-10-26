package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.web.PreconditionFailedException
import ch.sonensei.cockpit.core.web.PreconditionRequiredException
import ch.sonensei.cockpit.core.web.ProblemDetailsAdvice
import ch.sonensei.cockpit.mail.api.MailAttachment
import ch.sonensei.cockpit.mail.api.MailConnectionAdvice
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailMessage
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.MessagingException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.util.unit.DataSize
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * The HTTP side of compose ([DraftController]) with the services replaced: request validation (400 with field errors),
 * how the request reaches the service, the response shape with `Location`/`ETag`, and the mapping of mail failures.
 */
class DraftControllerTests {
	private val drafts = mockk<DraftService>()
	private val correspondents = mockk<CorrespondentService>()
	private val mockMvc =
		MockMvcBuilders
			.standaloneSetup(DraftController(drafts, correspondents, ComposeProperties(DataSize.ofBytes(MAX_SIZE))))
			.setControllerAdvice(MailConnectionAdvice(), ProblemDetailsAdvice())
			.build()
	private val json = JsonMapper.builder().build()

	private fun draft(version: Long = 7) =
		Draft(
			message =
				MailMessage(ACCOUNT, FOLDER, version, DRAFT).apply {
					from = listOf(MailAddress("jane.doe@example.test", "Jane Doe"))
					to = listOf(MailAddress("bob@example.test", "Bob Example"))
					subject = "Project status"
					inReplyTo = "<orig@example.test>"
					referencesHeader = "<root@example.test> <orig@example.test>"
					sentAt = Instant.parse("2026-03-05T14:07:00Z")
				},
			identityId = IDENTITY,
			html = "<p>Hello</p>",
			text = "Hello\n",
			attachments = listOf(MailAttachment("2", "agenda.pdf", "application/pdf", 4, inline = false, contentId = null)),
		)

	private fun send(
		method: String,
		path: String,
		body: String? = null,
		ifMatch: String? = null,
	): MockHttpServletResponse {
		val dsl: MockHttpServletRequestDsl.() -> Unit = {
			ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
			body?.let {
				contentType = MediaType.APPLICATION_JSON
				content = it
			}
		}
		return when (method) {
			"POST" -> mockMvc.post(path, dsl = dsl)
			"PUT" -> mockMvc.put(path, dsl = dsl)
			else -> mockMvc.get(path, dsl = dsl)
		}.andReturn().response
	}

	private fun MockHttpServletResponse.body(): JsonNode = json.readTree(contentAsString)

	private fun MockHttpServletResponse.errorFields(): List<String> = body()["errors"].toList().map { it["field"].asString() }

	private fun create(body: String) = send("POST", "/api/v1/mail/drafts", body)

	@Nested
	inner class Create {
		@Test
		fun `a valid draft is created with Location, ETag and the draft as body`() {
			val input = slot<DraftInput>()
			every { drafts.create(ACCOUNT, capture(input)) } returns draft()

			val response =
				create(
					"""
					{"accountId":"$ACCOUNT","identityId":"$IDENTITY",
					 "to":[{"address":" bob@example.test ","name":" Bob Example "}],
					 "cc":[{"address":"carol@example.test","name":"  "}],
					 "subject":"Project status","html":"<p>Hello</p>",
					 "attachments":[{"fileId":"$FILE"}]}
					""".trimIndent(),
				)

			response.status shouldBe 201
			response.getHeader(HttpHeaders.LOCATION)!! shouldEndWith "/api/v1/mail/drafts/$DRAFT"
			response.getHeader(HttpHeaders.ETAG) shouldBe "\"7\""
			val body = response.body()
			body["id"].asString() shouldBe DRAFT.toString()
			body["folderId"].asString() shouldBe FOLDER.toString()
			body["identityId"].asString() shouldBe IDENTITY.toString()
			body["from"]["address"].asString() shouldBe "jane.doe@example.test"
			body["inReplyTo"].asString() shouldBe "<orig@example.test>"
			body["references"].asString() shouldBe "<root@example.test> <orig@example.test>"
			body["attachments"][0]["partId"].asString() shouldBe "2"
			body["attachments"][0]["downloadUrl"].asString() shouldBe "/api/v1/mail/messages/$DRAFT/attachments/2"
			body["savedAt"].asString() shouldBe "2026-03-05T14:07:00Z"

			input.captured.identityId shouldBe IDENTITY
			input.captured.to shouldContainExactly listOf(MailAddress("bob@example.test", "Bob Example"))
			input.captured.cc shouldContainExactly listOf(MailAddress("carol@example.test", null))
			input.captured.attachments shouldBe listOf(DraftAttachmentRef.File(FILE))
		}

		@Test
		fun `without attachments the input has none rather than unchanged`() {
			val input = slot<DraftInput>()
			every { drafts.create(ACCOUNT, capture(input)) } returns draft()

			create("""{"accountId":"$ACCOUNT"}""").status shouldBe 201

			input.captured.attachments shouldBe null
			input.captured.to shouldBe emptyList()
		}

		@Test
		fun `a missing accountId is a validation error`() {
			val response = create("""{"subject":"No account"}""")

			response.status shouldBe 400
			response.errorFields() shouldContain "accountId"
			verify(exactly = 0) { drafts.create(any(), any()) }
		}

		@Test
		fun `an invalid address names the field`() {
			val response = create("""{"accountId":"$ACCOUNT","cc":[{"address":"bob@example.test"},{"address":"not an address"}]}""")

			response.status shouldBe 400
			response.errorFields() shouldContainExactly listOf("cc[1].address")
			verify(exactly = 0) { drafts.create(any(), any()) }
		}

		@Test
		fun `a blank address is rejected by validation`() {
			val response = create("""{"accountId":"$ACCOUNT","to":[{"address":"   "}]}""")

			response.status shouldBe 400
			response.errorFields() shouldContain "to[0].address"
		}

		@Test
		fun `a missing address is rejected`() {
			val response = create("""{"accountId":"$ACCOUNT","bcc":[{"name":"Nobody"}]}""")

			response.status shouldBe 400
			response.errorFields() shouldContain "bcc[0].address"
		}

		@Test
		fun `two addresses in one field are rejected`() {
			val response = create("""{"accountId":"$ACCOUNT","to":[{"address":"bob@example.test, carol@example.test"}]}""")

			response.status shouldBe 400
			response.errorFields() shouldContainExactly listOf("to[0].address")
		}

		@Test
		fun `an address with a display name in the address field is rejected`() {
			// The name belongs into `name`; a full mailbox in `address` would end up verbatim in the headers
			// (`Bob <Bob Example <bob@example.test>>`) and in SMTP `RCPT TO`.
			every { drafts.create(ACCOUNT, any()) } returns draft()

			val response = create("""{"accountId":"$ACCOUNT","to":[{"address":"Bob Example <bob@example.test>","name":"Bob"}]}""")

			response.status shouldBe 400
			response.errorFields() shouldContainExactly listOf("to[0].address")
			verify(exactly = 0) { drafts.create(any(), any()) }
		}

		@Test
		fun `group syntax in the address field is rejected`() {
			// A group would expand to several recipients through one field (or to none at all).
			val members = create("""{"accountId":"$ACCOUNT","to":[{"address":"team: bob@example.test, carol@example.test;"}]}""")
			members.status shouldBe 400
			members.errorFields() shouldContainExactly listOf("to[0].address")

			val empty = create("""{"accountId":"$ACCOUNT","cc":[{"address":"undisclosed-recipients:;"}]}""")
			empty.status shouldBe 400
			empty.errorFields() shouldContainExactly listOf("cc[0].address")
			verify(exactly = 0) { drafts.create(any(), any()) }
		}

		@Test
		fun `an attachment needs exactly one of partId and fileId`() {
			val both = create("""{"accountId":"$ACCOUNT","attachments":[{"fileId":"$FILE"},{"partId":"2","fileId":"$FILE"}]}""")
			both.status shouldBe 400
			both.errorFields() shouldContainExactly listOf("attachments[1]")

			val neither = create("""{"accountId":"$ACCOUNT","attachments":[{}]}""")
			neither.status shouldBe 400
			neither.errorFields() shouldContainExactly listOf("attachments[0]")
			verify(exactly = 0) { drafts.create(any(), any()) }
		}

		@Test
		fun `a too long subject is rejected`() {
			val response = create("""{"accountId":"$ACCOUNT","subject":"${"x".repeat(999)}"}""")

			response.status shouldBe 400
			response.errorFields() shouldContain "subject"
		}

		@Test
		fun `a malformed account id is rejected without echoing it`() {
			val response = create("""{"accountId":"not-a-uuid"}""")

			response.status shouldBe 400
			response.contentAsString shouldNotContain "not-a-uuid"
		}
	}

	@Nested
	inner class Update {
		@Test
		fun `the If-Match header and attachment refs reach the service`() {
			val input = slot<DraftInput>()
			every { drafts.update(DRAFT, "\"7\"", capture(input)) } returns draft(version = 8)

			val response =
				send(
					"PUT",
					"/api/v1/mail/drafts/$DRAFT",
					"""{"subject":"v2","attachments":[{"partId":"2"},{"fileId":"$FILE"}]}""",
					ifMatch = "\"7\"",
				)

			response.status shouldBe 200
			response.getHeader(HttpHeaders.ETAG) shouldBe "\"8\""
			input.captured.subject shouldBe "v2"
			input.captured.attachments shouldBe listOf(DraftAttachmentRef.Existing("2"), DraftAttachmentRef.File(FILE))
		}

		@Test
		fun `omitted attachments mean unchanged`() {
			val input = slot<DraftInput>()
			every { drafts.update(DRAFT, any(), capture(input)) } returns draft()

			send("PUT", "/api/v1/mail/drafts/$DRAFT", """{"subject":"v2"}""", ifMatch = "\"7\"").status shouldBe 200

			input.captured.attachments shouldBe null
		}

		@Test
		fun `a missing If-Match reaches the service, whose 428 is passed on`() {
			every { drafts.update(DRAFT, null, any()) } throws PreconditionRequiredException()

			send("PUT", "/api/v1/mail/drafts/$DRAFT", """{"subject":"v2"}""").status shouldBe 428
		}

		@Test
		fun `a stale If-Match is 412`() {
			every { drafts.update(DRAFT, "\"6\"", any()) } throws PreconditionFailedException()

			send("PUT", "/api/v1/mail/drafts/$DRAFT", """{"subject":"v2"}""", ifMatch = "\"6\"").status shouldBe 412
		}

		@Test
		fun `invalid content is rejected before the service`() {
			val response = send("PUT", "/api/v1/mail/drafts/$DRAFT", """{"to":[{"address":"@example.test"}]}""", ifMatch = "\"7\"")

			response.status shouldBe 400
			response.errorFields() shouldContainExactly listOf("to[0].address")
			verify(exactly = 0) { drafts.update(any(), any(), any()) }
		}

		@Test
		fun `get returns the draft with its ETag`() {
			every { drafts.get(DRAFT) } returns draft(version = 11)

			val response = send("GET", "/api/v1/mail/drafts/$DRAFT")

			response.status shouldBe 200
			response.getHeader(HttpHeaders.ETAG) shouldBe "\"11\""
			response.body()["subject"].asString() shouldBe "Project status"
		}
	}

	@Nested
	inner class Upload {
		private fun upload(file: MockMultipartFile?): MockHttpServletResponse =
			mockMvc
				.multipart("/api/v1/mail/drafts/$DRAFT/attachments") {
					file?.let { file(it) }
					header(HttpHeaders.IF_MATCH, "\"7\"")
				}.andReturn()
				.response

		@Test
		fun `an upload reaches the service with its name, type and bytes`() {
			val upload = slot<DraftUpload>()
			every { drafts.attach(DRAFT, "\"7\"", capture(upload)) } returns draft(version = 9)

			val response = upload(MockMultipartFile("file", "notes.pdf", "application/pdf", byteArrayOf(1, 2, 3)))

			response.status shouldBe 200
			response.getHeader(HttpHeaders.ETAG) shouldBe "\"9\""
			upload.captured.filename shouldBe "notes.pdf"
			upload.captured.contentType shouldBe "application/pdf"
			upload.captured.bytes.toList() shouldBe listOf<Byte>(1, 2, 3)
		}

		@Test
		fun `a client path in the file name is dropped and a generic type guessed from the name`() {
			val upload = slot<DraftUpload>()
			every { drafts.attach(DRAFT, any(), capture(upload)) } returns draft()

			upload(MockMultipartFile("file", "C:\\Users\\jane\\report.pdf", "application/octet-stream", byteArrayOf(1))).status shouldBe 200

			upload.captured.filename shouldBe "report.pdf"
			upload.captured.contentType shouldBe "application/pdf"
		}

		@Test
		fun `an upload without name is called attachment`() {
			val upload = slot<DraftUpload>()
			every { drafts.attach(DRAFT, any(), capture(upload)) } returns draft()

			upload(MockMultipartFile("file", "", null, byteArrayOf(1))).status shouldBe 200

			upload.captured.filename shouldBe "attachment"
			upload.captured.contentType shouldBe "application/octet-stream"
		}

		@Test
		fun `a file at the size limit is accepted, one byte more is rejected`() {
			every { drafts.attach(DRAFT, any(), any()) } returns draft()

			upload(MockMultipartFile("file", "a.bin", "application/octet-stream", ByteArray(MAX_SIZE.toInt()))).status shouldBe 200

			val tooLarge = upload(MockMultipartFile("file", "b.bin", "application/octet-stream", ByteArray(MAX_SIZE.toInt() + 1)))
			tooLarge.status shouldBe 400
			tooLarge.errorFields() shouldContainExactly listOf("file")
			verify(exactly = 1) { drafts.attach(any(), any(), any()) }
		}

		@Test
		fun `a missing file part is a validation error`() {
			val response = upload(null)

			response.status shouldBe 400
			response.errorFields() shouldContainExactly listOf("file")
		}
	}

	@Nested
	inner class Sending {
		private fun sendDraft(ifMatch: String? = "\"7\"") = send("POST", "/api/v1/mail/drafts/$DRAFT/send", ifMatch = ifMatch)

		@Test
		fun `a sent draft reports what happened besides SMTP`() {
			val copy = UUID.fromString("01900000-0000-7000-8000-0000000000c1")
			every { drafts.send(DRAFT, "\"7\"") } returns SentDraft(copy, appendedToSent = true, draftRemoved = false)

			val response = sendDraft()

			response.status shouldBe 200
			response.body()["sentMessageId"].asString() shouldBe copy.toString()
			response.body()["appendedToSent"].asBoolean() shouldBe true
			response.body()["draftRemoved"].asBoolean() shouldBe false
		}

		@Test
		fun `an SMTP refusal is 422 with SEND_REJECTED and the refused recipients`() {
			every { drafts.send(DRAFT, any()) } throws
				MailSendRejectedException(listOf("nobody@example.test"), MessagingException("550 5.1.1 <nobody@example.test> unknown user"))

			val response = sendDraft()

			response.status shouldBe 422
			response.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
			response.body()["errorCode"].asString() shouldBe "SEND_REJECTED"
			response.body()["rejectedRecipients"].toList().map { it.asString() } shouldContainExactly listOf("nobody@example.test")
			response.contentAsString shouldNotContain "550"
		}

		@Test
		fun `a mail server failure is 502 with its error code, without server details`() {
			every { drafts.send(DRAFT, any()) } throws
				MailConnectionException(MailConnectionError.AUTHENTICATION_FAILED, MessagingException("535 secret-server-detail"))

			val response = sendDraft()

			response.status shouldBe 502
			response.body()["errorCode"].asString() shouldBe "AUTHENTICATION_FAILED"
			response.contentAsString shouldNotContain "secret-server-detail"
		}

		@Test
		fun `busy connections are 503 POOL_EXHAUSTED`() {
			every { drafts.send(DRAFT, any()) } throws MailConnectionException(MailConnectionError.POOL_EXHAUSTED)

			val response = sendDraft()

			response.status shouldBe 503
			response.body()["errorCode"].asString() shouldBe "POOL_EXHAUSTED"
		}

		@Test
		fun `a missing If-Match reaches the service, whose 428 is passed on`() {
			every { drafts.send(DRAFT, null) } throws PreconditionRequiredException()

			sendDraft(ifMatch = null).status shouldBe 428
		}
	}

	@Nested
	inner class Responses {
		@Test
		fun `reply, reply all and forward create drafts in their mode, with the chosen identity`() {
			val original = UUID.fromString("01900000-0000-7000-8000-0000000000e1")
			every { drafts.respond(original, any(), any()) } returns draft()

			send("POST", "/api/v1/mail/messages/$original/reply?identityId=$IDENTITY").status shouldBe 201
			send("POST", "/api/v1/mail/messages/$original/reply-all").status shouldBe 201
			val forward = send("POST", "/api/v1/mail/messages/$original/forward")

			forward.status shouldBe 201
			forward.getHeader(HttpHeaders.LOCATION)!! shouldEndWith "/api/v1/mail/drafts/$DRAFT"
			verify {
				drafts.respond(original, ResponseMode.REPLY, IDENTITY)
				drafts.respond(original, ResponseMode.REPLY_ALL, null)
				drafts.respond(original, ResponseMode.FORWARD, null)
			}
		}

		@Test
		fun `a malformed identity id is a validation error`() {
			val response = send("POST", "/api/v1/mail/messages/$DRAFT/reply?identityId=nope")

			response.status shouldBe 400
			response.errorFields() shouldContainExactly listOf("identityId")
		}
	}

	@Nested
	inner class Correspondents {
		@Test
		fun `the default limit is 10 and the query is passed on`() {
			every { correspondents.recent("bob", 10) } returns
				listOf(Correspondent("bob@example.test", "Bob Example", Instant.parse("2026-03-05T14:07:00Z")))

			val response = send("GET", "/api/v1/mail/correspondents?q=bob")

			response.status shouldBe 200
			val item = response.body()["items"].single()
			item["address"].asString() shouldBe "bob@example.test"
			item["name"].asString() shouldBe "Bob Example"
			item["lastAt"].asString() shouldBe "2026-03-05T14:07:00Z"
		}

		@Test
		fun `limits 1 and 50 are accepted`() {
			every { correspondents.recent(null, any()) } returns emptyList()

			send("GET", "/api/v1/mail/correspondents?limit=1").status shouldBe 200
			send("GET", "/api/v1/mail/correspondents?limit=50").status shouldBe 200
			verify {
				correspondents.recent(null, 1)
				correspondents.recent(null, 50)
			}
		}

		@Test
		fun `limits outside 1 to 50 are rejected`() {
			listOf("0", "51", "-1").forEach { limit ->
				val response = send("GET", "/api/v1/mail/correspondents?limit=$limit")
				response.status shouldBe 400
				response.errorFields() shouldContainExactly listOf("limit")
			}
			send("GET", "/api/v1/mail/correspondents?limit=ten").status shouldBe 400
			verify(exactly = 0) { correspondents.recent(any(), any()) }
		}
	}

	private companion object {
		const val MAX_SIZE = 1024L
		val ACCOUNT: UUID = UUID.fromString("01900000-0000-7000-8000-0000000000a1")
		val FOLDER: UUID = UUID.fromString("01900000-0000-7000-8000-0000000000f1")
		val DRAFT: UUID = UUID.fromString("01900000-0000-7000-8000-0000000000d1")
		val IDENTITY: UUID = UUID.fromString("01900000-0000-7000-8000-0000000000b1")
		val FILE: UUID = UUID.fromString("01900000-0000-7000-8000-000000000ff1")
	}
}
