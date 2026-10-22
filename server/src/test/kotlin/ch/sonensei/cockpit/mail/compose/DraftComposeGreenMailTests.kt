package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/** Drafts, sending, reply and forward against GreenMail (IMAP + SMTP): what the server holds afterwards. */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class DraftComposeGreenMailTests(
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
	private lateinit var account: MailAccount

	@BeforeEach
	fun setUp() {
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
		folders.findByAccountIdOrderByPathAsc(account.id).forEach { sync.syncFolder(it.id) }
	}

	@Test
	fun `a draft is saved to Drafts, replaced on update and keeps its id`() {
		val created = createDraft(subject = "First version", html = "<p>Hello <b>Bob</b></p>")
		val id = created.body["id"].asString()
		created.body["subject"].asString() shouldBe "First version"
		created.body["text"].asString() shouldContain "Hello Bob"
		onServer("Drafts").map { it.subject } shouldContainExactly listOf("First version")
		onServer("Drafts").single().isSet(Flags.Flag.DRAFT) shouldBe true

		val updated = putDraft(id, created.etag, subject = "Second version", html = "<p>Changed</p>")
		updated.body["id"].asString() shouldBe id
		updated.etag shouldNotBe created.etag
		onServer("Drafts").map { it.subject } shouldContainExactly listOf("Second version")
		cached("Drafts").map { it.id.toString() } shouldContainExactly listOf(id)

		// The old version is gone: saving on top of it fails.
		draftRequest(put("/api/v1/mail/drafts/$id", created.etag, content(subject = "Stale")))
			.andExpect { status { isPreconditionFailed() } }
		get(id).body["subject"].asString() shouldBe "Second version"
	}

	@Test
	fun `a sent draft is received with its attachments, appended to Sent and removed from Drafts`() {
		val file = files.storeFile(null, "agenda.txt", "text/plain", "Fictitious agenda".byteInputStream())
		val created = createDraft(subject = "Meeting", html = "<p>See <i>attached</i></p>", attachments = listOf(mapOf("fileId" to file.id)))
		val id = created.body["id"].asString()
		created.body["attachments"].strings("filename") shouldContainExactly listOf("agenda.txt")

		val uploaded =
			mockMvc
				.multipart("/api/v1/mail/drafts/$id/attachments") {
					file(MockMultipartFile("file", "notes.pdf", "application/pdf", byteArrayOf(1, 2, 3, 4)))
					header(HttpHeaders.IF_MATCH, created.etag)
					with(csrf())
				}.andExpect { status { isOk() } }
				.andReturn()
				.response
		val afterUpload = json.readTree(uploaded.contentAsString)
		afterUpload["attachments"].strings("filename") shouldContainExactly listOf("agenda.txt", "notes.pdf")
		afterUpload["html"].asString() shouldContain "attached"

		val sent = draftRequest(post("/api/v1/mail/drafts/$id/send", uploaded.getHeader(HttpHeaders.ETAG))).andExpect { status { isOk() } }.json()
		sent["appendedToSent"].asBoolean() shouldBe true
		sent["draftRemoved"].asBoolean() shouldBe true
		// The draft's Message-ID goes with the sent message, so its id now is the copy in Sent.
		sent["sentMessageId"].asString() shouldBe id

		val received = onServer("INBOX", BOB_USER).single()
		received.subject shouldBe "Meeting"
		received.getHeader("Bcc") shouldBe null
		val parts = leaves(received)
		parts.map { it.contentType.substringBefore(';').lowercase() } shouldContainExactly
			listOf("text/plain", "text/html", "text/plain", "application/pdf")
		parts.mapNotNull { it.fileName } shouldContainExactly listOf("agenda.txt", "notes.pdf")
		String(parts[2].inputStream.readAllBytes()) shouldBe "Fictitious agenda"
		(received.from.single() as InternetAddress).address shouldBe EMAIL

		onServer("Sent").map { it.subject } shouldContainExactly listOf("Meeting")
		onServer("Sent").single().isSet(Flags.Flag.SEEN) shouldBe true
		onServer("Drafts").shouldBeEmpty()
		cached("Drafts").shouldBeEmpty()
		cached("Sent").map { it.id.toString() } shouldContainExactly listOf(id)

		val correspondents = mockMvc.get("/api/v1/mail/correspondents?q=bob").andExpect { status { isOk() } }.json()
		correspondents["items"].strings("address") shouldContainExactly listOf(BOB)
	}

	@Test
	fun `bcc recipients get the message without seeing the header, the Sent copy keeps it`() {
		val created = createDraft(subject = "Hidden", html = "<p>Hi</p>", to = emptyList(), bcc = listOf(BOB))
		draftRequest(post("/api/v1/mail/drafts/${created.body["id"].asString()}/send", created.etag)).andExpect { status { isOk() } }
		onServer("INBOX", BOB_USER).single().getHeader("Bcc") shouldBe null
		onServer("Sent").single().getHeader("Bcc")?.single() shouldContain BOB
	}

	@Test
	fun `a reply quotes the original and threads it, a forward carries its attachments`() {
		remote { store ->
			store.getFolder("INBOX").appendMessages(arrayOf(original()))
		}
		folders.findByAccountIdOrderByPathAsc(account.id).forEach { sync.syncFolder(it.id) }
		val inbox = cached("INBOX").single()

		val reply = draftRequest(post("/api/v1/mail/messages/${inbox.id}/reply")).andExpect { status { isCreated() } }.json()
		reply["to"].strings("address") shouldContainExactly listOf(BOB)
		reply["subject"].asString() shouldBe "Re: Project status"
		reply["inReplyTo"].asString() shouldBe ORIGINAL_ID
		reply["references"].asString() shouldBe "<root@example.test> $ORIGINAL_ID"
		reply["html"].asString() shouldContain "<blockquote"
		reply["text"].asString() shouldContain "> All on track."
		reply["attachments"].size() shouldBe 0

		val replyAll = draftRequest(post("/api/v1/mail/messages/${inbox.id}/reply-all")).andExpect { status { isCreated() } }.json()
		replyAll["to"].strings("address") shouldContainExactly listOf(BOB, "carol@example.test")
		replyAll["cc"].strings("address") shouldContainExactly listOf("dave@example.test")

		val forward = draftRequest(post("/api/v1/mail/messages/${inbox.id}/forward")).andExpect { status { isCreated() } }.json()
		forward["subject"].asString() shouldBe "Fwd: Project status"
		forward["to"].size() shouldBe 0
		forward["attachments"].strings("filename") shouldContainExactly listOf("report.csv")
		forward["text"].asString() shouldContain "---------- Forwarded message ----------"

		val stored = onServer("Drafts")
		stored shouldHaveSize 3
		stored.first { it.subject == "Re: Project status" }.getHeader("In-Reply-To").single() shouldBe ORIGINAL_ID
		val forwarded = stored.first { it.subject == "Fwd: Project status" }
		leaves(forwarded).mapNotNull { it.fileName } shouldContainExactly listOf("report.csv")
	}

	@Test
	fun `a reply keeps its threading headers when edited and the recipient gets them`() {
		remote { store -> store.getFolder("INBOX").appendMessages(arrayOf(original())) }
		folders.findByAccountIdOrderByPathAsc(account.id).forEach { sync.syncFolder(it.id) }
		val inbox = cached("INBOX").single()
		val response =
			draftRequest(post("/api/v1/mail/messages/${inbox.id}/reply"))
				.andExpect { status { isCreated() } }
				.andReturn()
				.response
		val id = json.readTree(response.contentAsString)["id"].asString()

		// The editor saves its own subject and body; the request carries no threading headers.
		val edited =
			putDraft(id, checkNotNull(response.getHeader(HttpHeaders.ETAG)), subject = "Re: Project status (edited)", html = "<p>Agreed.</p>")
		edited.body["id"].asString() shouldBe id
		edited.body["inReplyTo"].asString() shouldBe ORIGINAL_ID
		edited.body["references"].asString() shouldBe "<root@example.test> $ORIGINAL_ID"
		val stored = onServer("Drafts").single()
		stored.subject shouldBe "Re: Project status (edited)"
		stored.getHeader("In-Reply-To").single() shouldBe ORIGINAL_ID

		draftRequest(post("/api/v1/mail/drafts/$id/send", edited.etag)).andExpect { status { isOk() } }

		val received = onServer("INBOX", BOB_USER).single()
		received.subject shouldBe "Re: Project status (edited)"
		received.getHeader("In-Reply-To").single() shouldBe ORIGINAL_ID
		received.getHeader("References").single().split(Regex("\\s+")) shouldContainExactly listOf("<root@example.test>", ORIGINAL_ID)
		storedHtml(received) shouldContain "Agreed."
	}

	@Test
	fun `remote images survive a reply quote and a GET-PUT round trip, scripts do not`() {
		val html = "<p>Signed</p><img src=\"$REMOTE_IMAGE\" alt=\"logo\"><script>alert(1)</script>"
		remote { store ->
			store.getFolder("INBOX").appendMessages(
				arrayOf(
					MimeMessage(Session.getInstance(Properties())).apply {
						setFrom(InternetAddress(BOB, "Bob Example"))
						setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
						subject = "Newsletter"
						setText(html, "UTF-8", "html")
						saveChanges()
					},
				),
			)
		}
		folders.findByAccountIdOrderByPathAsc(account.id).forEach { sync.syncFolder(it.id) }
		val inbox = cached("INBOX").single()

		val reply = draftRequest(post("/api/v1/mail/messages/${inbox.id}/reply")).andExpect { status { isCreated() } }.json()
		reply["html"].asString() shouldContain "src=\"$REMOTE_IMAGE\""
		reply["html"].asString() shouldNotContain "<script"
		storedHtml(onServer("Drafts").single()) shouldContain "src=\"$REMOTE_IMAGE\""

		// The editor reopens its own draft and saves it again.
		val created = createDraft(subject = "With logo", html = "<p>Regards</p><img src=\"$REMOTE_IMAGE\">")
		val id = created.body["id"].asString()
		val opened = get(id)
		opened.body["html"].asString() shouldContain "src=\"$REMOTE_IMAGE\""
		val saved = putDraft(id, opened.etag, subject = "With logo", html = opened.body["html"].asString())
		saved.body["html"].asString() shouldContain "src=\"$REMOTE_IMAGE\""
		storedHtml(onServer("Drafts").single { it.subject == "With logo" }) shouldContain "src=\"$REMOTE_IMAGE\""
	}

	@Test
	fun `deleting a draft removes it from the server and the cache`() {
		val created = createDraft(subject = "Throwaway", html = "<p>x</p>")
		val id = created.body["id"].asString()
		draftRequest(delete("/api/v1/mail/drafts/$id", null)).andExpect { status { isPreconditionRequired() } }
		draftRequest(delete("/api/v1/mail/drafts/$id", created.etag)).andExpect { status { isNoContent() } }
		onServer("Drafts").shouldBeEmpty()
		cached("Drafts").shouldBeEmpty()
		mockMvc.get("/api/v1/mail/drafts/$id").andExpect { status { isNotFound() } }
	}

	@Test
	fun `a message outside Drafts is no draft`() {
		remote { store -> store.getFolder("INBOX").appendMessages(arrayOf(original())) }
		folders.findByAccountIdOrderByPathAsc(account.id).forEach { sync.syncFolder(it.id) }
		val inbox = cached("INBOX").single()
		mockMvc.get("/api/v1/mail/drafts/${inbox.id}").andExpect { status { isNotFound() } }
		draftRequest(post("/api/v1/mail/drafts/${inbox.id}/send", "\"${inbox.uid}\"")).andExpect { status { isNotFound() } }
		onServer("INBOX") shouldHaveSize 1
	}

	@Test
	fun `a draft without recipients cannot be sent`() {
		val created = createDraft(subject = "Nobody", html = "<p>x</p>", to = emptyList())
		draftRequest(post("/api/v1/mail/drafts/${created.body["id"].asString()}/send", created.etag))
			.andExpect { status { isBadRequest() } }
		onServer("Drafts") shouldHaveSize 1
		onServer("Sent").shouldBeEmpty()
	}

	@Test
	fun `an update keeps attachments chosen by partId with their bytes, and all when attachments are omitted`() {
		val agenda = files.storeFile(null, "agenda.txt", "text/plain", "Fictitious agenda".byteInputStream())
		val minutes = files.storeFile(null, "minutes.txt", "text/plain", "Fictitious minutes".byteInputStream())
		val created =
			createDraft(subject = "Two files", html = "<p>x</p>", attachments = listOf(mapOf("fileId" to agenda.id), mapOf("fileId" to minutes.id)))
		val id = created.body["id"].asString()

		// Omitted: both stay.
		val unchanged = putDraft(id, created.etag, subject = "Two files, v2", html = "<p>y</p>")
		unchanged.body["attachments"].strings("filename") shouldContainExactly listOf("agenda.txt", "minutes.txt")

		// Only the minutes, chosen by their part of the current version.
		val minutesPart =
			unchanged.body["attachments"]
				.values()
				.first { it["filename"].asString() == "minutes.txt" }["partId"]
				.asString()
		val kept =
			draftRequest(
				put(
					"/api/v1/mail/drafts/$id",
					unchanged.etag,
					content("One file", "<p>z</p>", attachments = listOf(mapOf("partId" to minutesPart))),
				),
			).andExpect { status { isOk() } }.json()
		kept["attachments"].strings("filename") shouldContainExactly listOf("minutes.txt")

		val stored = onServer("Drafts").single()
		stored.subject shouldBe "One file"
		val attached = leaves(stored).filter { it.fileName != null }
		attached.map { it.fileName } shouldContainExactly listOf("minutes.txt")
		String(attached.single().inputStream.readAllBytes()) shouldBe "Fictitious minutes"

		// A part the current version does not have.
		draftRequest(
			put(
				"/api/v1/mail/drafts/$id",
				get(id).etag,
				content("Unknown part", "<p>z</p>", attachments = listOf(mapOf("partId" to "9.9"))),
			),
		).andExpect { status { isBadRequest() } }
		onServer("Drafts").single().subject shouldBe "One file"
	}

	@Test
	fun `a draft sent as an identity carries its From and Reply-To`() {
		val support =
			identities.save(
				MailIdentity(account.id, "Jane Support", "support@example.test").apply { replyTo = "Help Desk <help@example.test>" },
			)
		val response =
			mockMvc
				.post("/api/v1/mail/drafts") {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content =
						json.writeValueAsString(
							content("From support", "<p>Hi</p>") + mapOf("accountId" to account.id, "identityId" to support.id),
						)
				}.andExpect { status { isCreated() } }
				.andReturn()
				.response
		val created = json.readTree(response.contentAsString)
		created["identityId"].asString() shouldBe support.id.toString()
		created["from"]["address"].asString() shouldBe "support@example.test"

		draftRequest(post("/api/v1/mail/drafts/${created["id"].asString()}/send", response.getHeader(HttpHeaders.ETAG)))
			.andExpect { status { isOk() } }

		val received = onServer("INBOX", BOB_USER).single()
		(received.from.single() as InternetAddress).let {
			it.address shouldBe "support@example.test"
			it.personal shouldBe "Jane Support"
		}
		(received.replyTo.single() as InternetAddress).address shouldBe "help@example.test"
		received.isMimeType("multipart/alternative") shouldBe true
		onServer("Sent").single().getHeader("Message-ID").single() shouldBe received.getHeader("Message-ID").single()
	}

	@Test
	fun `a reply to a sent message threads under it and goes to its recipient`() {
		val created = createDraft(subject = "Kickoff", html = "<p>Let us start.</p>")
		draftRequest(post("/api/v1/mail/drafts/${created.body["id"].asString()}/send", created.etag)).andExpect { status { isOk() } }
		val sentCopy = cached("Sent").single()

		val reply = draftRequest(post("/api/v1/mail/messages/${sentCopy.id}/reply")).andExpect { status { isCreated() } }.json()

		reply["to"].strings("address") shouldContainExactly listOf(BOB)
		reply["subject"].asString() shouldBe "Re: Kickoff"
		reply["inReplyTo"].asString() shouldBe sentCopy.messageIdHeader
		reply["text"].asString() shouldContain "> Let us start."
	}

	@Test
	fun `an upload into a draft without attachments keeps its body and is sent with the file`() {
		val created = createDraft(subject = "Plain draft", html = "<p>Body <b>kept</b></p>")
		val id = created.body["id"].asString()
		onServer("Drafts").single().isMimeType("multipart/alternative") shouldBe true

		val uploaded =
			mockMvc
				.multipart("/api/v1/mail/drafts/$id/attachments") {
					file(MockMultipartFile("file", "invoice.pdf", "application/pdf", byteArrayOf(5, 6, 7)))
					header(HttpHeaders.IF_MATCH, created.etag)
					with(csrf())
				}.andExpect { status { isOk() } }
				.andReturn()
				.response
		val afterUpload = json.readTree(uploaded.contentAsString)
		afterUpload["id"].asString() shouldBe id
		afterUpload["subject"].asString() shouldBe "Plain draft"
		afterUpload["html"].asString() shouldContain "<b>kept</b>"
		afterUpload["attachments"].strings("filename") shouldContainExactly listOf("invoice.pdf")

		val stored = onServer("Drafts").single()
		stored.isMimeType("multipart/mixed") shouldBe true
		stored.isSet(Flags.Flag.DRAFT) shouldBe true
		leaves(stored).map { it.contentType.substringBefore(';').lowercase() } shouldContainExactly
			listOf("text/plain", "text/html", "application/pdf")

		draftRequest(post("/api/v1/mail/drafts/$id/send", uploaded.getHeader(HttpHeaders.ETAG))).andExpect { status { isOk() } }
		val received = leaves(onServer("INBOX", BOB_USER).single())
		received.mapNotNull { it.fileName } shouldContainExactly listOf("invoice.pdf")
		received
			.single { it.fileName == "invoice.pdf" }
			.inputStream
			.readAllBytes()
			.toList() shouldContainExactly listOf<Byte>(5, 6, 7)
		storedHtml(onServer("Sent").single()) shouldContain "<b>kept</b>"
	}

	@Test
	fun `a draft another client deleted on the server is 404 for every change and nothing is sent`() {
		val created = createDraft(subject = "Deleted elsewhere", html = "<p>x</p>")
		val id = created.body["id"].asString()
		// Another mail client deletes it; the cache has not synced yet.
		remote { store ->
			val folder = store.getFolder("Drafts")
			folder.open(Folder.READ_WRITE)
			folder.messages.forEach { it.setFlag(Flags.Flag.DELETED, true) }
			folder.close(true)
		}

		draftRequest(put("/api/v1/mail/drafts/$id", created.etag, content("Lost update", "<p>y</p>")))
			.andExpect { status { isNotFound() } }
		mockMvc
			.multipart("/api/v1/mail/drafts/$id/attachments") {
				file(MockMultipartFile("file", "late.txt", "text/plain", "late".toByteArray()))
				header(HttpHeaders.IF_MATCH, created.etag)
				with(csrf())
			}.andExpect { status { isNotFound() } }
		draftRequest(post("/api/v1/mail/drafts/$id/send", created.etag)).andExpect { status { isNotFound() } }
		draftRequest(delete("/api/v1/mail/drafts/$id", created.etag)).andExpect { status { isNotFound() } }

		onServer("Drafts").shouldBeEmpty()
		onServer("Sent").shouldBeEmpty()
		onServer("INBOX", BOB_USER).shouldBeEmpty()
	}

	@Test
	fun `two saves based on the same version do not both win, the loser is 412`() {
		val created = createDraft(subject = "Base", html = "<p>base</p>")
		val id = created.body["id"].asString()
		val go = CountDownLatch(1)
		val statuses = ConcurrentLinkedQueue<Int>()
		// The test user's security context is per thread, so each saver gets it explicitly.
		val user = TestSecurityContextHolder.getContext()
		val savers =
			listOf("Tab A", "Tab B").map { subject ->
				Thread {
					TestSecurityContextHolder.setContext(user)
					try {
						go.await()
						val status =
							draftRequest(put("/api/v1/mail/drafts/$id", created.etag, content(subject, "<p>$subject</p>")))
								.andReturn()
								.response.status
						statuses.add(status)
					} finally {
						TestSecurityContextHolder.clearContext()
					}
				}.apply { start() }
			}
		go.countDown()
		savers.forEach { it.join(60_000) }

		statuses.sorted() shouldContainExactly listOf(200, 412)
		val stored = onServer("Drafts")
		stored shouldHaveSize 1
		val winner = get(id).body["subject"].asString()
		stored.single().subject shouldBe winner
		(winner == "Tab A" || winner == "Tab B") shouldBe true
	}

	// --- helpers ---------------------------------------------------------------------------------------------------

	private class Saved(
		val body: JsonNode,
		val etag: String,
	)

	private fun content(
		subject: String,
		html: String? = null,
		to: List<String> = listOf(BOB),
		bcc: List<String> = emptyList(),
		attachments: List<Map<String, Any>>? = null,
	): Map<String, Any?> =
		mapOf(
			"to" to to.map { mapOf("address" to it, "name" to "Bob Example") },
			"bcc" to bcc.map { mapOf("address" to it) },
			"subject" to subject,
			"html" to html,
			"attachments" to attachments,
		).filterValues { it != null }

	private fun createDraft(
		subject: String,
		html: String,
		to: List<String> = listOf(BOB),
		bcc: List<String> = emptyList(),
		attachments: List<Map<String, Any>>? = null,
	): Saved {
		val response =
			mockMvc
				.post("/api/v1/mail/drafts") {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = json.writeValueAsString(content(subject, html, to, bcc, attachments) + ("accountId" to account.id))
				}.andExpect { status { isCreated() } }
				.andReturn()
				.response
		response.getHeader(HttpHeaders.LOCATION) shouldContain "/api/v1/mail/drafts/"
		return Saved(json.readTree(response.contentAsString), checkNotNull(response.getHeader(HttpHeaders.ETAG)))
	}

	private fun putDraft(
		id: String,
		etag: String,
		subject: String,
		html: String,
	): Saved {
		val response =
			draftRequest(put("/api/v1/mail/drafts/$id", etag, content(subject, html)))
				.andExpect { status { isOk() } }
				.andReturn()
				.response
		return Saved(json.readTree(response.contentAsString), checkNotNull(response.getHeader(HttpHeaders.ETAG)))
	}

	private fun get(id: String): Saved {
		val response =
			mockMvc
				.get("/api/v1/mail/drafts/$id")
				.andExpect { status { isOk() } }
				.andReturn()
				.response
		response.getHeader(HttpHeaders.ETAG) shouldStartWith "\""
		return Saved(json.readTree(response.contentAsString), checkNotNull(response.getHeader(HttpHeaders.ETAG)))
	}

	private fun post(
		path: String,
		etag: String? = null,
	) = Request("POST", path, etag, null)

	private fun put(
		path: String,
		etag: String?,
		body: Map<String, Any?>,
	) = Request("PUT", path, etag, body)

	private fun delete(
		path: String,
		etag: String?,
	) = Request("DELETE", path, etag, null)

	private class Request(
		val method: String,
		val path: String,
		val etag: String?,
		val body: Map<String, Any?>?,
	)

	private fun draftRequest(request: Request): ResultActionsDsl {
		val build: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
			with(csrf())
			request.etag?.let { header(HttpHeaders.IF_MATCH, it) }
			request.body?.let {
				contentType = MediaType.APPLICATION_JSON
				content = json.writeValueAsString(it)
			}
		}
		return when (request.method) {
			"POST" -> mockMvc.post(request.path, dsl = build)
			"PUT" -> mockMvc.put(request.path, dsl = build)
			else -> mockMvc.delete(request.path, dsl = build)
		}
	}

	private fun ResultActionsDsl.json(): JsonNode = json.readTree(andReturn().response.contentAsString)

	private fun JsonNode.strings(field: String): List<String> = values().map { it[field].asString() }

	private fun <T> remote(
		user: String = USER,
		block: (Store) -> T,
	): T =
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, user, PASSWORD)
			block(store)
		}

	/** The messages of a server folder, fully read. */
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
		messages.findByFolderIdAndUidIsNotNull(checkNotNull(folders.findByAccountIdAndPath(account.id, path)).id).sortedBy { it.subject }

	private fun storedHtml(message: MimeMessage): String = leaves(message).single { it.isMimeType("text/html") }.content as String

	private fun leaves(part: Part): List<Part> =
		if (part.isMimeType("multipart/*")) {
			val multipart = part.content as Multipart
			(0 until multipart.count).flatMap { leaves(multipart.getBodyPart(it)) }
		} else {
			listOf(part)
		}

	private fun original(): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress(BOB, "Bob Example"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL), InternetAddress("carol@example.test")))
			setRecipients(Message.RecipientType.CC, arrayOf(InternetAddress("dave@example.test")))
			subject = "Project status"
			setContent(
				MimeMultipart(
					MimeBodyPart().apply { setText("All on track.\nNext review on Friday.", "UTF-8") },
					MimeBodyPart().apply {
						setContent("a,b\n1,2\n", "text/csv")
						disposition = Part.ATTACHMENT
						fileName = "report.csv"
					},
				),
			)
			saveChanges()
			setHeader("Message-ID", ORIGINAL_ID)
			setHeader("References", "<root@example.test>")
		}

	companion object {
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val BOB = "bob@example.test"
		private const val BOB_USER = "bob"
		private const val PASSWORD = "fictitious-password"
		private const val ORIGINAL_ID = "<status-1@example.test>"
		private const val REMOTE_IMAGE = "https://images.example.test/logo.png"

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
