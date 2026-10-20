package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailBodyCache
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/** Lists, conversations and reading cached bodies, without a mail server. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class MailReadWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val bodies: MailBodyCache,
	@Autowired private val attachments: MailBodyAttachmentStore,
) : PostgresTestBase() {
	private lateinit var work: MailAccount
	private lateinit var home: MailAccount
	private lateinit var workInbox: MailFolder
	private lateinit var workArchive: MailFolder
	private lateinit var homeInbox: MailFolder
	private var nextUid = 1L

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		work = account("Work", "ann@work.example")
		home = account("Home", "ann@home.example")
		workInbox = folder(work, "INBOX", MailFolderRole.INBOX)
		workArchive = folder(work, "Archive", MailFolderRole.ARCHIVE)
		homeInbox = folder(home, "INBOX", MailFolderRole.INBOX)
	}

	private fun account(
		name: String,
		email: String,
	) = accounts.save(
		MailAccount(
			MailAccountType.IMAP_SMTP,
			name,
			email,
			"imap.example.test",
			993,
			MailSecurity.SSL_TLS,
			"smtp.example.test",
			587,
			MailSecurity.STARTTLS,
			email,
		),
	)

	private fun folder(
		account: MailAccount,
		path: String,
		role: MailFolderRole?,
	) = folders.save(MailFolder(account.id, path, path).apply { this.role = role })

	private fun message(
		folder: MailFolder,
		subject: String,
		received: String,
		threadKey: String? = null,
		seen: Boolean = false,
		messageId: String = "<${UUID.randomUUID()}@example.test>",
	) = messages.save(
		MailMessage(folder.accountId, folder.id, nextUid++).apply {
			this.subject = subject
			this.receivedAt = Instant.parse(received)
			this.threadKey = threadKey
			this.seen = seen
			this.messageIdHeader = messageId
			this.from = listOf(MailAddress("bob@example.test", "Bob Example"))
			this.snippet = "Snippet of $subject"
		},
	)

	private fun getJson(url: String): JsonNode =
		mockMvc
			.get(url)
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)

	private fun subjects(page: JsonNode) = page["items"].strings("subject")

	private fun JsonNode.strings(field: String) = (0 until size()).map { this[it][field].asString() }

	@Test
	fun `the unified list shows the Inbox of every account, newest first`() {
		message(workInbox, "Work 1", "2026-10-01T08:00:00Z")
		message(homeInbox, "Home 1", "2026-10-02T08:00:00Z")
		message(workArchive, "Archived", "2026-10-03T08:00:00Z")
		message(workInbox, "Work 2", "2026-10-04T08:00:00Z")

		val page = getJson("/api/v1/mail/messages")

		subjects(page) shouldContainExactly listOf("Work 2", "Home 1", "Work 1")
		page["nextCursor"].isNull shouldBe true
		page["items"][0]["from"][0]["address"].asString() shouldBe "bob@example.test"
		page["items"][0]["snippet"].asString() shouldBe "Snippet of Work 2"
		page["items"][0]["threadSize"].asInt() shouldBe 1
	}

	@Test
	fun `an account alone lists its Inbox and a folder lists that folder`() {
		message(workInbox, "Work", "2026-10-01T08:00:00Z")
		message(homeInbox, "Home", "2026-10-02T08:00:00Z")
		message(workArchive, "Archived", "2026-10-03T08:00:00Z")

		subjects(getJson("/api/v1/mail/messages?accountId=${work.id}")) shouldContainExactly listOf("Work")
		subjects(getJson("/api/v1/mail/messages?folderId=${workArchive.id}")) shouldContainExactly listOf("Archived")
		subjects(getJson("/api/v1/mail/messages?accountId=${work.id}&folderId=${workArchive.id}")) shouldContainExactly listOf("Archived")
	}

	@Test
	fun `detached messages are not listed`() {
		message(workInbox, "Here", "2026-10-01T08:00:00Z")
		messages.save(message(workInbox, "Gone", "2026-10-02T08:00:00Z").apply { detach() })

		subjects(getJson("/api/v1/mail/messages?folderId=${workInbox.id}")) shouldContainExactly listOf("Here")
	}

	@Test
	fun `pages follow the cursor without gaps or duplicates, also for equal arrival times`() {
		(1..5).forEach { message(workInbox, "M$it", "2026-10-01T08:00:0${it / 2}Z") }

		val seen = mutableListOf<String>()
		var cursor: String? = null
		do {
			val page = getJson("/api/v1/mail/messages?folderId=${workInbox.id}&limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
			seen += subjects(page)
			cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
		} while (cursor != null)

		seen shouldContainExactly listOf("M5", "M4", "M3", "M2", "M1")
	}

	@Test
	fun `threaded lists show the newest message of each conversation with its counts`() {
		message(workInbox, "Plan", "2026-10-01T08:00:00Z", threadKey = "t1", seen = true)
		message(workInbox, "Other", "2026-10-02T08:00:00Z")
		message(workInbox, "Re: Plan", "2026-10-03T08:00:00Z", threadKey = "t1")
		message(workInbox, "Re: Re: Plan", "2026-10-04T08:00:00Z", threadKey = "t1")
		// Same thread key in another account: another conversation.
		message(homeInbox, "Home plan", "2026-10-05T08:00:00Z", threadKey = "t1")

		val page = getJson("/api/v1/mail/messages?threaded=true")

		subjects(page) shouldContainExactly listOf("Home plan", "Re: Re: Plan", "Other")
		page["items"][1]["threadSize"].asInt() shouldBe 3
		page["items"][1]["threadUnread"].asInt() shouldBe 2
		page["items"][2]["threadSize"].asInt() shouldBe 1

		val first = getJson("/api/v1/mail/messages?threaded=true&limit=1")
		val second = getJson("/api/v1/mail/messages?threaded=true&limit=1&cursor=${first["nextCursor"].asString()}")
		subjects(second) shouldContainExactly listOf("Re: Re: Plan")
	}

	@Test
	fun `the thread endpoint returns the conversation across folders, oldest first, once per Message-ID`() {
		val start = message(workInbox, "Plan", "2026-10-01T08:00:00Z", threadKey = "t1", messageId = "<plan@example.test>")
		message(workArchive, "Plan (copy)", "2026-10-01T08:00:00Z", threadKey = "t1", messageId = "<plan@example.test>")
		val reply = message(workArchive, "Re: Plan", "2026-10-02T08:00:00Z", threadKey = "t1")
		message(workInbox, "Unrelated", "2026-10-03T08:00:00Z")

		val thread = getJson("/api/v1/mail/messages/${reply.id}/thread")

		thread["items"].strings("id") shouldContainExactly listOf(start.id.toString(), reply.id.toString())
	}

	@Test
	fun `list parameters are validated`() {
		mockMvc.get("/api/v1/mail/messages?folderId=${workInbox.id}&accountId=${home.id}").andExpect { status { isBadRequest() } }
		mockMvc.get("/api/v1/mail/messages?cursor=bm90LWEtY3Vyc29y").andExpect { status { isBadRequest() } }
		mockMvc.get("/api/v1/mail/messages?limit=0").andExpect { status { isBadRequest() } }
		mockMvc.get("/api/v1/mail/messages?folderId=${UUID.randomUUID()}").andExpect { status { isNotFound() } }
		mockMvc.get("/api/v1/mail/messages?accountId=${UUID.randomUUID()}").andExpect { status { isNotFound() } }
	}

	@Test
	fun `a cached message is read without the mail server, sanitized, with cid images resolved`() {
		val message = message(workInbox, "Newsletter", "2026-10-01T08:00:00Z")
		bodies.put(
			message.id,
			"Plain version",
			"""<p onclick="steal()">Hi <script>alert(1)</script><img src="cid:logo@example.test"><img src="https://tracker.example.test/p.gif"></p>""",
		)
		attachments.save(
			message.id,
			listOf(
				MailAttachment("1.2", "logo.png", "image/png", 1200, inline = true, contentId = "logo@example.test"),
				MailAttachment("2", "report.pdf", "application/pdf", 5000, inline = false, contentId = null),
			),
		)

		val response =
			mockMvc
				.get("/api/v1/mail/messages/${message.id}")
				.andExpect { status { isOk() } }
				.andReturn()
				.response
		val read = json.readTree(response.contentAsString)

		response.getHeader(HttpHeaders.ETAG) shouldBe "\"${message.version}\""
		val html = read["body"]["html"].asString()
		html shouldNotContain "script"
		html shouldNotContain "onclick"
		html shouldNotContain "tracker.example.test"
		html shouldContain "src=\"/api/v1/mail/messages/${message.id}/attachments/1.2?inline=true\""
		read["body"]["text"].asString() shouldBe "Plain version"
		read["body"]["remoteImageCount"].asInt() shouldBe 1
		read["body"]["remoteImagesBlocked"].asBoolean() shouldBe true
		read["attachments"].strings("filename") shouldContainExactly listOf("logo.png", "report.pdf")
		read["attachments"][1]["downloadUrl"].asString() shouldBe "/api/v1/mail/messages/${message.id}/attachments/2"
		read["seen"].asBoolean() shouldBe false
	}

	@Test
	fun `remote images are rewritten to the image proxy on request`() {
		val message = message(workInbox, "Pictures", "2026-10-01T08:00:00Z")
		bodies.put(message.id, null, """<img src="https://images.example.test/a.png?x=1&amp;y=2">""")
		attachments.save(message.id, emptyList())

		val read = getJson("/api/v1/mail/messages/${message.id}?remoteImages=PROXY")

		read["body"]["html"].asString() shouldContain
			"/api/v1/mail/image-proxy?url=https%3A%2F%2Fimages.example.test%2Fa.png%3Fx%3D1%26y%3D2"
		read["body"]["remoteImagesBlocked"].asBoolean() shouldBe false
		read["body"]["text"].isNull shouldBe true
	}

	@Test
	fun `a moved message resolves to its current copy`() {
		val old = messages.save(message(workInbox, "Moved", "2026-10-01T08:00:00Z", messageId = "<moved@example.test>").apply { detach() })
		val copy = message(workArchive, "Moved", "2026-10-01T08:00:00Z", messageId = "<moved@example.test>")
		bodies.put(copy.id, "Text", null)
		attachments.save(copy.id, emptyList())

		val read = getJson("/api/v1/mail/messages/${old.id}")

		read["id"].asString() shouldBe copy.id.toString()
		read["folderId"].asString() shouldBe workArchive.id.toString()
	}

	@Test
	fun `unknown messages and attachments are 404`() {
		mockMvc.get("/api/v1/mail/messages/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
		val message = message(workInbox, "No attachments", "2026-10-01T08:00:00Z")
		bodies.put(message.id, "Text", null)
		attachments.save(message.id, emptyList())
		mockMvc.get("/api/v1/mail/messages/${message.id}/attachments/2").andExpect { status { isNotFound() } }
	}

	@Test
	fun `cursors with a malformed time or id are rejected`() {
		listOf("yesterday|${UUID.randomUUID()}", "2026-10-01T08:00:00Z|not-a-uuid", "2026-10-01T08:00:00Z|${UUID.randomUUID()}|x")
			.map(CursorRequest::encodeCursor)
			.forEach { cursor ->
				mockMvc.get("/api/v1/mail/messages?cursor=$cursor").andExpect {
					status { isBadRequest() }
					content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
				}
			}
	}

	@Test
	fun `an empty folder lists nothing, also threaded`() {
		subjects(getJson("/api/v1/mail/messages?folderId=${workArchive.id}")) shouldContainExactly emptyList()
		subjects(getJson("/api/v1/mail/messages?folderId=${workArchive.id}&threaded=true")) shouldContainExactly emptyList()
	}

	@Test
	fun `a page exactly as long as the limit has no next cursor`() {
		message(workInbox, "A", "2026-10-01T08:00:00Z")
		message(workInbox, "B", "2026-10-02T08:00:00Z")

		getJson("/api/v1/mail/messages?folderId=${workInbox.id}&limit=2")["nextCursor"].isNull shouldBe true
		getJson("/api/v1/mail/messages?folderId=${workInbox.id}&limit=1")["nextCursor"].isNull shouldBe false
	}

	@Test
	fun `cid references match case-insensitively, with or without brackets, and unknown ones lose their source`() {
		val message = message(workInbox, "Inline images", "2026-10-01T08:00:00Z")
		bodies.put(
			message.id,
			null,
			"""<p><img src="CID:Logo@Example.Test"><img src="cid:<logo@example.test>"><img alt="gone" src="cid:missing@example.test"></p>""",
		)
		attachments.save(message.id, listOf(MailAttachment("1.2", "logo.png", "image/png", 10, inline = true, contentId = "logo@example.test")))

		val html = getJson("/api/v1/mail/messages/${message.id}")["body"]["html"].asString()

		Regex("""/api/v1/mail/messages/${message.id}/attachments/1\.2\?inline=true""").findAll(html).count() shouldBe 2
		html shouldNotContain "missing@example.test"
		html shouldContain "alt=\"gone\""
		html shouldNotContain "cid:"
	}

	@Test
	fun `a plain-text message has no HTML and no remote images`() {
		val message = message(workInbox, "Plain", "2026-10-01T08:00:00Z")
		bodies.put(message.id, "Only <b>text</b>", null)
		attachments.save(message.id, emptyList())

		val body = getJson("/api/v1/mail/messages/${message.id}")["body"]

		body["html"].isNull shouldBe true
		body["text"].asString() shouldBe "Only <b>text</b>"
		body["remoteImageCount"].asInt() shouldBe 0
		body["remoteImagesBlocked"].asBoolean() shouldBe false
	}

	@Test
	fun `a message without thread key is its own conversation`() {
		val single = message(workInbox, "Alone", "2026-10-01T08:00:00Z")

		getJson("/api/v1/mail/messages/${single.id}/thread")["items"].strings("id") shouldContainExactly listOf(single.id.toString())
	}

	@Test
	fun `unknown messages are 404 for the thread and attachment endpoints too`() {
		mockMvc.get("/api/v1/mail/messages/${UUID.randomUUID()}/thread").andExpect { status { isNotFound() } }
		mockMvc.get("/api/v1/mail/messages/${UUID.randomUUID()}/attachments/1").andExpect { status { isNotFound() } }
		mockMvc.get("/api/v1/mail/messages/not-a-uuid").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `the image proxy refuses internal addresses and non-http URLs by default`() {
		listOf("http://127.0.0.1/a.png", "http://169.254.169.254/latest/meta-data", "http://[::1]/a.png", "https://images.example.test:8443/a.png")
			.forEach { url ->
				mockMvc.get("/api/v1/mail/image-proxy?url=${URLEncoder.encode(url, Charsets.UTF_8)}").andExpect {
					status { isBadRequest() }
					content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
					jsonPath("$.errorCode") { value("BLOCKED_ADDRESS") }
				}
			}
		mockMvc.get("/api/v1/mail/image-proxy?url=javascript%3Aalert(1)").andExpect {
			status { isBadRequest() }
			jsonPath("$.errorCode") { value("INVALID_URL") }
		}
	}

	@Test
	@WithAnonymousUser
	fun `reading needs a login`() {
		mockMvc.get("/api/v1/mail/messages").andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/v1/mail/image-proxy?url=https://images.example.test/a.png").andExpect { status { isUnauthorized() } }
	}
}
