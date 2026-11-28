package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailCredentials
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.MailSessions
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.MailSyncRepository
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Properties
import java.util.UUID

/**
 * Sending and size limits against GreenMail (IMAP + SMTP), for what [DraftComposeGreenMailTests] leaves out: Google and
 * Microsoft accounts get no Sent copy appended (their servers keep one), a failing append after SMTP accepted the
 * message only shows in the result, and drafts larger than `cockpit.mail.compose.max-message-size` are refused.
 *
 * GreenMail speaks no XOAUTH2, so [MailSessions.credentials] is stubbed to the GreenMail password for every account
 * type; everything else (pool, SMTP, IMAP append/expunge, header sync) is the real code.
 */
@ExternalTest
@SpringBootTest(properties = ["cockpit.mail.compose.max-message-size=4KB"])
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class DraftSendGreenMailTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val sync: MailHeaderSyncService,
	@Autowired private val files: FilesService,
	@Autowired private val identities: MailIdentityRepository,
) : PostgresTestBase() {
	@MockkSpyBean
	private lateinit var sessions: MailSessions

	private lateinit var account: MailAccount

	@BeforeEach
	fun setUp() {
		every { sessions.credentials(any()) } returns MailCredentials(USER, PASSWORD, false)
		jdbc.update("delete from mail_account")
		greenMail.setUser(EMAIL, USER, PASSWORD)
		greenMail.setUser(BOB, BOB_USER, PASSWORD)
		account =
			accounts.create(
				NewMailAccount(
					type = MailAccountType.IMAP_SMTP,
					displayName = "Jane Doe",
					emailAddress = EMAIL,
					imap = MailServer(HOST, greenMail.imap.port, MailSecurity.NONE),
					smtp = MailServer(HOST, greenMail.smtp.port, MailSecurity.NONE),
					username = USER,
					password = PASSWORD,
					oauthConnectionId = null,
					signatureMd = null,
					notifyNewMail = null,
					syncWindowMonths = null,
				),
			)
		remote { store -> listOf("Drafts", "Sent").forEach { store.getFolder(it).create(Folder.HOLDS_MESSAGES) } }
		folderService.sync(account.id)
		syncAll()
	}

	// --- Sent copy -----------------------------------------------------------------------------------------------------

	@Test
	fun `a Google account sends without appending to Sent, the provider keeps the copy`() {
		becomeOAuthAccount(MailAccountType.GOOGLE)
		val created = createDraft("Via Gmail")

		val sent = sendDraft(created).also { it.status shouldBe 200 }.body()

		sent["appendedToSent"].asBoolean() shouldBe false
		sent["draftRemoved"].asBoolean() shouldBe true
		// GreenMail does not copy to Sent like Gmail would, so the cache has no copy yet.
		sent["sentMessageId"].isNull shouldBe true
		onServer("INBOX", BOB_USER).map { it.subject } shouldContainExactly listOf("Via Gmail")
		onServer("Sent").shouldBeEmpty()
		onServer("Drafts").shouldBeEmpty()
	}

	@Test
	fun `a Microsoft account sends without appending to Sent`() {
		becomeOAuthAccount(MailAccountType.MICROSOFT)
		val created = createDraft("Via Outlook")

		val sent = sendDraft(created).also { it.status shouldBe 200 }.body()

		sent["appendedToSent"].asBoolean() shouldBe false
		sent["draftRemoved"].asBoolean() shouldBe true
		onServer("INBOX", BOB_USER) shouldHaveSize 1
		onServer("Sent").shouldBeEmpty()
	}

	@Test
	fun `a Sent folder that is gone on the server does not fail a send SMTP accepted`() {
		val created = createDraft("Sent folder vanished")
		remote { store -> store.getFolder("Sent").delete(true) }

		val sent = sendDraft(created).also { it.status shouldBe 200 }.body()

		sent["appendedToSent"].asBoolean() shouldBe false
		sent["draftRemoved"].asBoolean() shouldBe true
		sent["sentMessageId"].isNull shouldBe true
		onServer("INBOX", BOB_USER).map { it.subject } shouldContainExactly listOf("Sent folder vanished")
		onServer("Drafts").shouldBeEmpty()
	}

	@Test
	fun `a Drafts folder deleted on the server by another client fails the save as a client error, nothing is sent`() {
		remote { store -> store.getFolder("Drafts").delete(true) }

		val response = createDraftResponse("Drafts vanished")

		// The cache still lists the folder, so the save only finds out on the server: never a 5xx server failure.
		response.status shouldBeIn listOf(404, 409)
		onServer("INBOX", BOB_USER).shouldBeEmpty()
		onServer("Sent").shouldBeEmpty()
	}

	// --- size limit (4 KB here) ----------------------------------------------------------------------------------------

	@Test
	fun `a new draft with a file over the size limit is refused and nothing is stored`() {
		val big = files.storeFile(null, "big.bin", "application/octet-stream", ByteArray(5 * 1024) { 'x'.code.toByte() }.inputStream())

		val response = createDraftResponse("Too big", attachments = listOf(mapOf("fileId" to big.id)))

		response.status shouldBe 400
		response.errorFields() shouldContain "attachments"
		onServer("Drafts").shouldBeEmpty()
	}

	@Test
	fun `files that are each under the limit but together over it are refused and nothing is stored`() {
		val first = files.storeFile(null, "first.bin", "application/octet-stream", ByteArray(3 * 1024) { 'a'.code.toByte() }.inputStream())
		val second = files.storeFile(null, "second.bin", "application/octet-stream", ByteArray(3 * 1024) { 'b'.code.toByte() }.inputStream())

		val response = createDraftResponse("Two files", attachments = listOf(mapOf("fileId" to first.id), mapOf("fileId" to second.id)))

		response.status shouldBe 400
		response.errorFields() shouldContain "attachments"
		onServer("Drafts").shouldBeEmpty()
	}

	@Test
	fun `a new draft referring to an unknown file is 404 and nothing is stored`() {
		val response = createDraftResponse("Missing file", attachments = listOf(mapOf("fileId" to UUID.randomUUID())))

		response.status shouldBe 404
		onServer("Drafts").shouldBeEmpty()
	}

	@Test
	fun `an upload that makes the draft larger than the limit is refused and the stored version stays`() {
		// Each file alone is under the 4 KB limit, together they are over it.
		val first = files.storeFile(null, "first.bin", "application/octet-stream", ByteArray(3 * 1024) { 'a'.code.toByte() }.inputStream())
		val created = createDraft("Growing", attachments = listOf(mapOf("fileId" to first.id)))

		val response =
			mockMvc
				.multipart("/api/v1/mail/drafts/${created.body["id"].asString()}/attachments") {
					file(MockMultipartFile("file", "second.bin", "application/octet-stream", ByteArray(3 * 1024) { 'b'.code.toByte() }))
					header(HttpHeaders.IF_MATCH, created.etag)
					with(csrf())
				}.andReturn()
				.response

		response.status shouldBe 400
		response.errorFields() shouldContain "attachments"
		leaves(onServer("Drafts").single()).mapNotNull { it.fileName } shouldContainExactly listOf("first.bin")
	}

	@Test
	fun `a draft saved just under the limit with a base64 attachment is sent`() {
		// 4000 binary bytes + ~40 characters of text fit into 4 KB; their base64 form with line breaks (~5.5 KB) does not.
		val file = files.storeFile(null, "almost.bin", "application/octet-stream", binary(4000).inputStream())
		val created = createDraft("Almost full", attachments = listOf(mapOf("fileId" to file.id)))

		val sent = sendDraft(created)

		sent.status shouldBe 200
		val received = onServer("INBOX", BOB_USER).single()
		received.subject shouldBe "Almost full"
		leaves(received).single { it.fileName == "almost.bin" }.inputStream.use { it.readAllBytes() } shouldBe binary(4000)
		onServer("Drafts").shouldBeEmpty()
	}

	@Test
	fun `an upload that brings the draft just under the limit is accepted and the draft is sent`() {
		val first = files.storeFile(null, "first.bin", "application/octet-stream", binary(3000).inputStream())
		val created = createDraft("Filled by upload", attachments = listOf(mapOf("fileId" to first.id)))

		val uploaded =
			mockMvc
				.multipart("/api/v1/mail/drafts/${created.body["id"].asString()}/attachments") {
					file(MockMultipartFile("file", "second.bin", "application/octet-stream", binary(1000)))
					header(HttpHeaders.IF_MATCH, created.etag)
					with(csrf())
				}.andReturn()
				.response

		uploaded.status shouldBe 200
		val saved = Saved(uploaded.body(), checkNotNull(uploaded.getHeader(HttpHeaders.ETAG)))
		sendDraft(saved).status shouldBe 200
		leaves(onServer("INBOX", BOB_USER).single()).mapNotNull { it.fileName } shouldContainExactly listOf("first.bin", "second.bin")
	}

	@Test
	fun `sending a draft another client made larger than the limit is refused and nothing is sent`() {
		remote { store ->
			val oversized = originalWithAttachment(5 * 1024)
			oversized.setFrom(InternetAddress(EMAIL))
			oversized.setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(BOB)))
			oversized.setFlag(Flags.Flag.DRAFT, true)
			store.getFolder("Drafts").appendMessages(arrayOf(oversized))
		}
		syncAll()
		val draftId = cached("Drafts").single().id
		val draft = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response
		draft.status shouldBe 200

		val response = sendDraft(Saved(draft.body(), checkNotNull(draft.getHeader(HttpHeaders.ETAG))))

		response.status shouldBe 400
		response.errorFields() shouldContain "attachments"
		onServer("INBOX", BOB_USER).shouldBeEmpty()
		onServer("Sent").shouldBeEmpty()
		onServer("Drafts") shouldHaveSize 1
	}

	@Test
	fun `forwarding a message whose attachments exceed the limit is refused and no draft is stored`() {
		remote { store -> store.getFolder("INBOX").appendMessages(arrayOf(originalWithAttachment(5 * 1024))) }
		syncAll()
		val inbox = cached("INBOX").single()

		val response = mockMvc.post("/api/v1/mail/messages/${inbox.id}/forward") { with(csrf()) }.andReturn().response

		response.status shouldBe 400
		response.errorFields() shouldContain "attachments"
		onServer("Drafts").shouldBeEmpty()
	}

	@Test
	fun `a reply to a message with a large attachment is fine, a reply carries no attachments`() {
		remote { store -> store.getFolder("INBOX").appendMessages(arrayOf(originalWithAttachment(5 * 1024))) }
		syncAll()
		val inbox = cached("INBOX").single()

		val response = mockMvc.post("/api/v1/mail/messages/${inbox.id}/reply") { with(csrf()) }.andReturn().response

		response.status shouldBe 201
		response.body()["attachments"].size() shouldBe 0
		leaves(onServer("Drafts").single()).mapNotNull { it.fileName }.shouldBeEmpty()
	}

	// --- replies and forwards ------------------------------------------------------------------------------------------

	@Test
	fun `a reply drops inline cid images, a forward keeps them as inline parts`() {
		remote { store -> store.getFolder("INBOX").appendMessages(arrayOf(originalWithInlineImage())) }
		syncAll()
		val inbox = cached("INBOX").single()

		val reply = mockMvc.post("/api/v1/mail/messages/${inbox.id}/reply") { with(csrf()) }.andReturn().response
		reply.status shouldBe 201
		reply.body()["html"].asString() shouldNotContain "cid:"
		reply.body()["html"].asString() shouldContain "Quarterly figures"
		reply.body()["attachments"].size() shouldBe 0

		val forward = mockMvc.post("/api/v1/mail/messages/${inbox.id}/forward") { with(csrf()) }.andReturn().response
		forward.status shouldBe 201
		forward.body()["html"].asString() shouldContain "cid:$LOGO_CID"
		val forwarded = leaves(onServer("Drafts").single { it.subject == "Fwd: Quarterly figures" })
		val image = forwarded.single { it.isMimeType("image/png") }
		image.disposition shouldBe Part.INLINE
		(image as MimeBodyPart).contentID shouldBe "<$LOGO_CID>"
	}

	@Test
	fun `a forward drops the positioned image reference from the html and forwards the image as an attachment`() {
		remote { store -> store.getFolder("INBOX").appendMessages(arrayOf(originalWithPositionedImage())) }
		syncAll()
		val inbox = cached("INBOX").single()

		val forward = mockMvc.post("/api/v1/mail/messages/${inbox.id}/forward") { with(csrf()) }.andReturn().response

		forward.status shouldBe 201
		val html = forward.body()["html"].asString()
		html shouldContain "Fictitious apero"
		html shouldNotContain "cid:cockpit-part-"
		html shouldNotContain "cockpit.invalid"
		forward.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("image0.jpeg")
		val forwarded = leaves(onServer("Drafts").single { it.subject == "Fwd: Apero invitation" })
		forwarded.single { it.isMimeType("image/jpeg") }.fileName shouldBe "image0.jpeg"
		forwarded.filter { it.isMimeType("text/html") }.forEach { (it.content as String) shouldNotContain "cockpit-part-" }
	}

	@Test
	fun `a forward keeps a real cid image next to a dropped positioned image`() {
		remote { store -> store.getFolder("INBOX").appendMessages(arrayOf(originalWithInlineAndPositionedImage())) }
		syncAll()
		val inbox = cached("INBOX").single()

		val forward = mockMvc.post("/api/v1/mail/messages/${inbox.id}/forward") { with(csrf()) }.andReturn().response

		forward.status shouldBe 201
		val html = forward.body()["html"].asString()
		html shouldContain "<img src=\"cid:$LOGO_CID\" alt=\"logo\">"
		html shouldNotContain "cid:cockpit-part-"
		forward.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("logo.png", "image0.jpeg")
	}

	@Test
	fun `a reply and a forward of a plain-text mail with a positioned image quote its text without the image reference`() {
		remote { store -> store.getFolder("INBOX").appendMessages(arrayOf(plainOriginalWithPositionedImage())) }
		syncAll()
		val inbox = cached("INBOX").single()

		val reply = mockMvc.post("/api/v1/mail/messages/${inbox.id}/reply") { with(csrf()) }.andReturn().response
		reply.status shouldBe 201
		reply.body()["html"].asString() shouldContain "Fictitious garden party"
		reply.body()["html"].asString() shouldNotContain "cockpit-part-"
		reply.body()["attachments"].size() shouldBe 0

		val forward = mockMvc.post("/api/v1/mail/messages/${inbox.id}/forward") { with(csrf()) }.andReturn().response
		forward.status shouldBe 201
		val html = forward.body()["html"].asString()
		html shouldContain "Fictitious garden party"
		html shouldContain "&lt;b&gt;bring a chair&lt;/b&gt;"
		html shouldNotContain "cockpit-part-"
		forward.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("image0.jpeg")
		val forwarded = leaves(onServer("Drafts").single { it.subject == "Fwd: Garden party" })
		forwarded.single { it.isMimeType("image/jpeg") }.fileName shouldBe "image0.jpeg"
		forwarded.filter { it.isMimeType("text/html") }.forEach { (it.content as String) shouldNotContain "cockpit-part-" }
	}

	// --- drafts written by other clients ------------------------------------------------------------------------------

	@Test
	fun `an html draft from another client with a positioned image opens, saves and sends without the image reference`() {
		val draftId = appendForeignDraft(MimeMultipart(htmlPart("<p>Fictitious picnic draft</p>"), positionedJpeg()))

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response
		view.status shouldBe 200
		view.contentAsString shouldNotContain "cockpit-part-"
		view.body()["html"].asString() shouldContain "Fictitious picnic draft"
		view.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("image0.jpeg")

		val saved = saveAsIs(draftId, view)
		saved.body.toString() shouldNotContain "cockpit-part-"
		saved.body["html"].asString() shouldContain "Fictitious picnic draft"
		sendDraft(saved).status shouldBe 200

		val received = onServer("INBOX", BOB_USER).single()
		leaves(received).filter { it.isMimeType("text/*") }.forEach { (it.content as String) shouldNotContain "cockpit-part-" }
		leaves(received).single { it.isMimeType("image/jpeg") }.fileName shouldBe "image0.jpeg"
	}

	@Test
	fun `a plain-text draft from another client with a positioned image stays plain text through open, save and send`() {
		val text = MimeBodyPart().apply { setText("Fictitious plain draft, <b>not bold</b>", "UTF-8") }
		val draftId = appendForeignDraft(MimeMultipart(text, positionedJpeg()))

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response
		view.status shouldBe 200
		view.contentAsString shouldNotContain "cockpit-part-"
		view.body()["html"].isNull shouldBe true
		view.body()["text"].asString() shouldBe "Fictitious plain draft, <b>not bold</b>"
		view.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("image0.jpeg")

		val saved = saveAsIs(draftId, view)
		saved.body.toString() shouldNotContain "cockpit-part-"
		saved.body["html"].isNull shouldBe true
		sendDraft(saved).status shouldBe 200

		val received = onServer("INBOX", BOB_USER).single()
		val texts = leaves(received).filter { it.isMimeType("text/*") }
		texts.forEach { (it.content as String) shouldNotContain "cockpit-part-" }
		texts.none { it.isMimeType("text/html") } shouldBe true
		(texts.single().content as String) shouldContain "Fictitious plain draft, <b>not bold</b>"
		leaves(received).single { it.isMimeType("image/jpeg") }.fileName shouldBe "image0.jpeg"
	}

	@Test
	fun `an html-only draft of text blocks with a positioned image keeps its html, it has no plain text to fall back to`() {
		val blocks = "<div>Fictitious bike tour</div><div><br></div><div>Meet at the bridge</div>"
		val draftId = appendForeignDraft(MimeMultipart(htmlPart(blocks), positionedJpeg()))

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response
		view.status shouldBe 200
		view.contentAsString shouldNotContain "cockpit-part-"
		val html = view.body()["html"].asString()
		html shouldContain "Fictitious bike tour"
		html shouldContain "Meet at the bridge"
		html shouldNotContain "<img"
		view.body()["text"].isNull shouldBe true
		view.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("image0.jpeg")

		val saved = saveAsIs(draftId, view)
		saved.body.toString() shouldNotContain "cockpit-part-"
		saved.body["html"].asString() shouldContain "Meet at the bridge"
		sendDraft(saved).status shouldBe 200

		val received = onServer("INBOX", BOB_USER).single()
		val texts = leaves(received).filter { it.isMimeType("text/*") }
		texts.forEach { (it.content as String) shouldNotContain "cockpit-part-" }
		(texts.single { it.isMimeType("text/html") }.content as String) shouldContain "Fictitious bike tour"
		leaves(received).single { it.isMimeType("image/jpeg") }.fileName shouldBe "image0.jpeg"
	}

	@Test
	fun `a draft with a real cid image and a positioned image keeps the cid image and drops only the positioned one`() {
		val related =
			MimeMultipart(
				"related",
				htmlPart("<p>Fictitious logo draft</p><p><img src=\"cid:$LOGO_CID\" alt=\"logo\"></p>"),
				inlineLogo(),
			)
		val draftId = appendForeignDraft(MimeMultipart(MimeBodyPart().apply { setContent(related) }, positionedJpeg()))

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response
		view.status shouldBe 200
		view.contentAsString shouldNotContain "cockpit-part-"
		val html = view.body()["html"].asString()
		html shouldContain "Fictitious logo draft"
		html shouldContain "<img src=\"cid:$LOGO_CID\" alt=\"logo\">"
		val attachments = view.body()["attachments"].toList()
		attachments.map { it["filename"].asString() } shouldContainExactly listOf("logo.png", "image0.jpeg")
		attachments.single { it["filename"].asString() == "logo.png" }["inline"].asBoolean() shouldBe true
		attachments.single { it["filename"].asString() == "image0.jpeg" }["inline"].asBoolean() shouldBe false

		val saved = saveAsIs(draftId, view)
		saved.body.toString() shouldNotContain "cockpit-part-"
		saved.body["html"].asString() shouldContain "cid:$LOGO_CID"
		sendDraft(saved).status shouldBe 200

		val received = leaves(onServer("INBOX", BOB_USER).single())
		received.filter { it.isMimeType("text/*") }.forEach { (it.content as String) shouldNotContain "cockpit-part-" }
		(received.single { it.isMimeType("text/html") }.content as String) shouldContain "cid:$LOGO_CID"
		(received.single { it.isMimeType("image/png") } as MimeBodyPart).contentID shouldBe "<$LOGO_CID>"
		received.single { it.isMimeType("image/jpeg") }.fileName shouldBe "image0.jpeg"
	}

	@Test
	fun `a draft with a real cid image and no positioned image opens with its html unchanged`() {
		val original = "<p>Fictitious agenda</p><p><img src=\"cid:$LOGO_CID\" alt=\"logo\"></p>"
		val draftId = appendForeignDraft(MimeMultipart("related", htmlPart(original), inlineLogo()))

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response

		view.status shouldBe 200
		view.body()["html"].asString() shouldContain original
		view.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("logo.png")
	}

	@Test
	fun `an html draft without any cid reference opens with its html unchanged`() {
		val original = "<div><b>Fictitious</b> shopping list</div><div><br></div><div>Bread</div>"
		val alternative =
			MimeMultipart(
				"alternative",
				MimeBodyPart().apply { setText("Fictitious shopping list\n\nBread", "UTF-8") },
				htmlPart(original),
			)
		val draftId = appendForeignDraft(alternative)

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response

		view.status shouldBe 200
		view.body()["html"].asString() shouldContain original
		view.body()["text"].asString() shouldContain "Fictitious shopping list"
	}

	@Test
	fun `a draft with a text alternative and formatted html plus a positioned image keeps its html`() {
		val alternative =
			MimeMultipart(
				"alternative",
				MimeBodyPart().apply { setText("Fictitious bold plan", "UTF-8") },
				htmlPart("<div><b>Fictitious</b> bold plan</div>"),
			)
		val draftId = appendForeignDraft(MimeMultipart(MimeBodyPart().apply { setContent(alternative) }, positionedJpeg()))

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response

		view.status shouldBe 200
		view.contentAsString shouldNotContain "cockpit-part-"
		view.body()["html"].asString() shouldContain "<b>Fictitious</b> bold plan"
		view.body()["text"].asString() shouldBe "Fictitious bold plan"
		view.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("image0.jpeg")
	}

	@Test
	fun `a draft with a text alternative and html of only text blocks plus a positioned image opens as plain text`() {
		// Recorded assumption: such HTML cannot be told apart from the plain-text rendering, so the text part is kept.
		val alternative =
			MimeMultipart(
				"alternative",
				MimeBodyPart().apply { setText("Fictitious walk", "UTF-8") },
				htmlPart("<div>Fictitious walk</div>"),
			)
		val draftId = appendForeignDraft(MimeMultipart(MimeBodyPart().apply { setContent(alternative) }, positionedJpeg()))

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response

		view.status shouldBe 200
		view.contentAsString shouldNotContain "cockpit-part-"
		view.body()["html"].isNull shouldBe true
		view.body()["text"].asString() shouldBe "Fictitious walk"
		view.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("image0.jpeg")
	}

	@Test
	fun `a plain-text draft with two positioned images opens as plain text and keeps both images`() {
		val text = MimeBodyPart().apply { setText("Fictitious holiday\nphotos", "UTF-8") }
		val second = positionedJpeg().apply { fileName = "image1.jpeg" }
		val draftId = appendForeignDraft(MimeMultipart(text, positionedJpeg(), second))

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response

		view.status shouldBe 200
		view.contentAsString shouldNotContain "cockpit-part-"
		view.body()["html"].isNull shouldBe true
		view.body()["text"].asString() shouldContain "Fictitious holiday"
		view.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("image0.jpeg", "image1.jpeg")
	}

	@Test
	fun `a draft that is only a positioned image opens without an image reference`() {
		val draftId = appendForeignDraft(MimeMultipart(positionedJpeg()))

		val view = mockMvc.get("/api/v1/mail/drafts/$draftId").andReturn().response

		view.status shouldBe 200
		view.contentAsString shouldNotContain "cockpit-part-"
		val html = view.body()["html"]
		if (!html.isNull) html.asString() shouldNotContain "<img"
		view.body()["text"].isNull shouldBe true
		view.body()["attachments"].toList().map { it["filename"].asString() } shouldContainExactly listOf("image0.jpeg")
	}

	@Test
	fun `a reply is sent by default as the identity the original was addressed to`() {
		val support = identities.save(MailIdentity(account.id, "Jane Support", "support@example.test"))
		remote { store ->
			store.getFolder("INBOX").appendMessages(
				arrayOf(
					MimeMessage(Session.getInstance(Properties())).apply {
						setFrom(InternetAddress(BOB, "Bob Example"))
						setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress("support@example.test")))
						subject = "Help request"
						setText("Fictitious question.", "UTF-8")
						saveChanges()
					},
				),
			)
		}
		syncAll()
		val inbox = cached("INBOX").single()

		val reply = mockMvc.post("/api/v1/mail/messages/${inbox.id}/reply") { with(csrf()) }.andReturn().response

		reply.status shouldBe 201
		reply.body()["identityId"].asString() shouldBe support.id.toString()
		reply.body()["from"]["address"].asString() shouldBe "support@example.test"
		reply.body()["to"].single()["address"].asString() shouldBe BOB
		(onServer("Drafts").single().from.single() as InternetAddress).address shouldBe "support@example.test"
	}

	// --- helpers -------------------------------------------------------------------------------------------------------

	private class Saved(
		val body: JsonNode,
		val etag: String,
	)

	/** Turns the account into an OAuth account of [type] (GreenMail still accepts the stubbed password login). */
	private fun becomeOAuthAccount(type: MailAccountType) {
		jdbc.update(
			"update mail_account set type = ?, password = null, oauth_connection_id = ? where id = ?",
			type.name,
			UUID.randomUUID(),
			account.id,
		)
	}

	private fun syncAll() {
		folders.findByAccountIdOrderByPathAsc(account.id).forEach { sync.syncFolder(it.id) }
	}

	private fun createDraftResponse(
		subject: String,
		attachments: List<Map<String, Any>>? = null,
	): MockHttpServletResponse =
		mockMvc
			.post("/api/v1/mail/drafts") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content =
					json.writeValueAsString(
						mapOf(
							"accountId" to account.id,
							"to" to listOf(mapOf("address" to BOB, "name" to "Bob Example")),
							"subject" to subject,
							"html" to "<p>Fictitious body</p>",
							"attachments" to attachments,
						).filterValues { it != null },
					)
			}.andReturn()
			.response

	private fun createDraft(
		subject: String,
		attachments: List<Map<String, Any>>? = null,
	): Saved {
		val response = createDraftResponse(subject, attachments)
		response.status shouldBe 201
		return Saved(response.body(), checkNotNull(response.getHeader(HttpHeaders.ETAG)))
	}

	/** Appends a draft with [content] to the Drafts folder as another mail client would, and returns its cached id. */
	private fun appendForeignDraft(content: MimeMultipart): String {
		remote { store ->
			val draft =
				MimeMessage(Session.getInstance(Properties())).apply {
					setFrom(InternetAddress(EMAIL, "Jane Doe"))
					setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(BOB, "Bob Example")))
					subject = "Draft from the phone"
					setContent(content)
					setFlag(Flags.Flag.DRAFT, true)
					saveChanges()
				}
			store.getFolder("Drafts").appendMessages(arrayOf(draft))
		}
		syncAll()
		return cached("Drafts").single().id.toString()
	}

	/** Saves draft [id] with what its [view] returned, like the editor does when nothing was changed. */
	private fun saveAsIs(
		id: String,
		view: MockHttpServletResponse,
	): Saved {
		val body = view.body()
		val response =
			mockMvc
				.put("/api/v1/mail/drafts/$id") {
					with(csrf())
					header(HttpHeaders.IF_MATCH, checkNotNull(view.getHeader(HttpHeaders.ETAG)))
					contentType = MediaType.APPLICATION_JSON
					content =
						json.writeValueAsString(
							mapOf(
								"to" to body["to"],
								"subject" to body["subject"].asString(),
								"html" to body["html"]?.takeUnless { it.isNull }?.asString(),
								"text" to body["text"]?.takeUnless { it.isNull }?.asString(),
								"attachments" to body["attachments"].toList().map { mapOf("partId" to it["partId"].asString()) },
							),
						)
				}.andReturn()
				.response
		response.status shouldBe 200
		return Saved(response.body(), checkNotNull(response.getHeader(HttpHeaders.ETAG)))
	}

	private fun sendDraft(draft: Saved): MockHttpServletResponse =
		mockMvc
			.post("/api/v1/mail/drafts/${draft.body["id"].asString()}/send") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, draft.etag)
			}.andReturn()
			.response

	private fun MockHttpServletResponse.body(): JsonNode = json.readTree(contentAsString)

	private fun MockHttpServletResponse.errorFields(): List<String> = body()["errors"]?.toList().orEmpty().map { it["field"].asString() }

	private fun <T> remote(
		user: String = USER,
		block: (Store) -> T,
	): T =
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, user, PASSWORD)
			block(store)
		}

	private fun onServer(
		path: String,
		user: String = USER,
	): List<MimeMessage> =
		remote(user) { store ->
			val folder = store.getFolder(path)
			folder.open(Folder.READ_ONLY)
			try {
				folder.messages.map { MimeMessage(it as MimeMessage) }
			} finally {
				folder.close(false)
			}
		}

	private fun cached(path: String) =
		messages.findByFolderIdAndUidIsNotNull(checkNotNull(folders.findByAccountIdAndPath(account.id, path)).id)

	private fun leaves(part: Part): List<Part> =
		if (part.isMimeType("multipart/*")) {
			val multipart = part.content as Multipart
			(0 until multipart.count).flatMap { leaves(multipart.getBodyPart(it)) }
		} else {
			listOf(part)
		}

	/** [size] bytes that are not ASCII text, so they are always base64-encoded. */
	private fun binary(size: Int): ByteArray = ByteArray(size) { (it * 7 + 128).toByte() }

	private fun originalWithAttachment(size: Int): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress(BOB, "Bob Example"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
			subject = "Large export"
			setContent(
				MimeMultipart(
					MimeBodyPart().apply { setText("See the export.", "UTF-8") },
					MimeBodyPart().apply {
						dataHandler = DataHandler(ByteArrayDataSource(ByteArray(size) { 'z'.code.toByte() }, "application/octet-stream"))
						disposition = Part.ATTACHMENT
						fileName = "export.bin"
					},
				),
			)
			saveChanges()
		}

	private fun originalWithInlineImage(): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress(BOB, "Bob Example"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
			subject = "Quarterly figures"
			val related =
				MimeMultipart(
					"related",
					MimeBodyPart().apply {
						setText("<p>Quarterly figures</p><p><img src=\"cid:$LOGO_CID\" alt=\"logo\"></p>", "UTF-8", "html")
					},
					MimeBodyPart().apply {
						dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), "image/png"))
						disposition = Part.INLINE
						contentID = "<$LOGO_CID>"
						fileName = "logo.png"
					},
				)
			setContent(related)
			saveChanges()
		}

	/** An Apple Mail style message: an HTML part and an inline image no `cid:` references, shown by its position. */
	private fun originalWithPositionedImage(): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress(BOB, "Bob Example"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
			subject = "Apero invitation"
			setContent(MimeMultipart(htmlPart("<p>Fictitious apero</p>"), positionedJpeg()))
			saveChanges()
		}

	/** A `cid:`-referenced logo in a related part, followed by an inline image shown by its position. */
	private fun originalWithInlineAndPositionedImage(): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress(BOB, "Bob Example"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
			subject = "Logo and photo"
			val related =
				MimeMultipart(
					"related",
					htmlPart("<p>Logo and photo</p><p><img src=\"cid:$LOGO_CID\" alt=\"logo\"></p>"),
					MimeBodyPart().apply {
						dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), "image/png"))
						disposition = Part.INLINE
						contentID = "<$LOGO_CID>"
						fileName = "logo.png"
					},
				)
			setContent(MimeMultipart(MimeBodyPart().apply { setContent(related) }, positionedJpeg()))
			saveChanges()
		}

	/** A plain-text-only message with an inline image no `cid:` can reference, so its read body is built HTML. */
	private fun plainOriginalWithPositionedImage(): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress(BOB, "Bob Example"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
			subject = "Garden party"
			val text = MimeBodyPart().apply { setText("Fictitious garden party, <b>bring a chair</b>", "UTF-8") }
			setContent(MimeMultipart(text, positionedJpeg()))
			saveChanges()
		}

	private fun htmlPart(html: String) = MimeBodyPart().apply { setText(html, "UTF-8", "html") }

	/** A PNG logo with Content-ID [LOGO_CID], for an HTML part that references it by `cid:`. */
	private fun inlineLogo() =
		MimeBodyPart().apply {
			dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), "image/png"))
			disposition = Part.INLINE
			contentID = "<$LOGO_CID>"
			fileName = "logo.png"
		}

	private fun positionedJpeg() =
		MimeBodyPart().apply {
			dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(-1, -40, -1, -32), "image/jpeg"))
			disposition = Part.INLINE
			fileName = "image0.jpeg"
		}

	companion object {
		private const val LOGO_CID = "logo-1@example.test"
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val BOB = "bob@example.test"
		private const val BOB_USER = "bob"
		private const val PASSWORD = "fictitious-password"

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
