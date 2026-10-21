package ch.sonensei.cockpit.mail.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.mail.api.MailBodyAttachmentStore
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailBodyCache
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class MailGtdWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val identities: MailIdentityRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val bodies: MailBodyCache,
	@Autowired private val attachments: MailBodyAttachmentStore,
	@Autowired private val clock: Clock,
) : PostgresTestBase() {
	private lateinit var account: MailAccount
	private lateinit var inbox: MailFolder
	private lateinit var sent: MailFolder
	private lateinit var archive: MailFolder
	private var nextUid = 1L

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from link")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from note")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from mail_account")
	}

	@BeforeEach
	fun setUp() {
		account =
			accounts.save(
				MailAccount(
					MailAccountType.IMAP_SMTP,
					"Work",
					"ann@work.example",
					"imap.example.test",
					993,
					MailSecurity.SSL_TLS,
					"smtp.example.test",
					587,
					MailSecurity.STARTTLS,
					"ann",
				),
			)
		inbox = folder("INBOX", MailFolderRole.INBOX)
		sent = folder("Sent", MailFolderRole.SENT)
		archive = folder("Archive", MailFolderRole.ARCHIVE)
	}

	private fun folder(
		path: String,
		role: MailFolderRole,
	) = folders.save(MailFolder(account.id, path, path).apply { this.role = role })

	private fun message(
		folder: MailFolder,
		subject: String? = "Quarterly report",
		from: List<MailAddress> = listOf(MailAddress("bob@example.test", "Bob Example")),
		to: List<MailAddress> = listOf(MailAddress("ann@work.example", "Ann Example")),
		messageId: String = "<${UUID.randomUUID()}@example.test>",
		cc: List<MailAddress> = emptyList(),
		bcc: List<MailAddress> = emptyList(),
		sentAt: Instant? = Instant.parse("2026-02-20T09:30:00Z"),
		receivedAt: Instant? = Instant.parse("2026-02-20T09:31:00Z"),
	) = messages.save(
		MailMessage(account.id, folder.id, nextUid++).apply {
			this.subject = subject
			this.from = from
			this.to = to
			this.cc = cc
			this.bcc = bcc
			this.sentAt = sentAt
			this.receivedAt = receivedAt
			this.messageIdHeader = messageId
		},
	)

	private fun JsonNode.isAbsent(field: String): Boolean = this[field] == null || this[field].isNull

	private fun count(table: String): Long = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!

	private fun post(
		id: Any,
		action: String,
	) = mockMvc.post("/api/v1/mail/messages/$id/$action") { with(csrf()) }

	private fun created(
		id: UUID,
		action: String,
	): JsonNode =
		post(id, action)
			.andExpect { status { isCreated() } }
			.andReturn()
			.response
			.let { response ->
				json.readTree(response.contentAsString).also {
					response.getHeader(HttpHeaders.LOCATION)!! shouldEndWith "/${it["id"].asString()}"
				}
			}

	private fun getJson(url: String): JsonNode =
		mockMvc
			.get(url)
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)

	/** The linked items of [type]:[id] as seen from it (the other end of each link). */
	private fun linkedEnds(
		type: String,
		id: String,
	): List<JsonNode> {
		val items = getJson("/api/v1/links?type=$type&id=$id")["items"]
		return (0 until items.size()).map { items[it] }.map { link ->
			if (link["source"]["id"].asString() == id) link["target"] else link["source"]
		}
	}

	@Test
	fun `to-task creates an Inbox task linked both ways to the mail`() {
		val mail = message(inbox)

		val result = created(mail.id, "to-task")

		result["type"].asString() shouldBe "TASK"
		result["title"].asString() shouldBe "Quarterly report"
		result["messageId"].asString() shouldBe mail.id.toString()
		val taskId = result["id"].asString()
		val task = getJson("/api/v1/tasks/$taskId")
		task["list"].asString() shouldBe "INBOX"
		task["title"].asString() shouldBe "Quarterly report"
		val fromTask = linkedEnds("TASK", taskId).single()
		fromTask["type"].asString() shouldBe "EMAIL"
		fromTask["id"].asString() shouldBe mail.id.toString()
		fromTask["deepLink"].asString() shouldBe "/mail/messages/${mail.id}"
		val fromMail = linkedEnds("EMAIL", mail.id.toString()).single()
		fromMail["type"].asString() shouldBe "TASK"
		fromMail["id"].asString() shouldBe taskId
	}

	@Test
	fun `a mail without subject gives a placeholder title`() {
		val mail = message(inbox, subject = "  ")

		created(mail.id, "to-task")["title"].asString() shouldBe "(no subject)"
	}

	@Test
	fun `to-waiting from a sent mail waits for its recipients since the mail's date`() {
		val mail =
			message(
				sent,
				from = listOf(MailAddress("ann@work.example", "Ann Example")),
				to = listOf(MailAddress("bob@example.test", "Bob Example"), MailAddress("carol@example.test")),
			)

		val result = created(mail.id, "to-waiting")

		val task = getJson("/api/v1/tasks/${result["id"].asString()}")
		task["list"].asString() shouldBe "WAITING"
		task["waitingForText"].asString() shouldBe "Bob Example, carol@example.test"
		task["waitingSince"].asString() shouldBe "2026-02-20"
		linkedEnds("TASK", result["id"].asString()).single()["id"].asString() shouldBe mail.id.toString()
	}

	@Test
	fun `a mail from one of the account's identities counts as sent outside the Sent folder`() {
		identities.save(MailIdentity(account.id, "Ann Alias", "Ann.Alias@work.example"))
		val mail =
			message(
				archive,
				from = listOf(MailAddress("ann.alias@work.example")),
				to = listOf(MailAddress("dave@example.test", "Dave Example")),
			)

		val result = created(mail.id, "to-waiting")

		getJson("/api/v1/tasks/${result["id"].asString()}")["waitingForText"].asString() shouldBe "Dave Example"
	}

	@Test
	fun `to-waiting from a received mail waits for its sender`() {
		val mail = message(inbox)

		val result = created(mail.id, "to-waiting")

		getJson("/api/v1/tasks/${result["id"].asString()}")["waitingForText"].asString() shouldBe "Bob Example"
	}

	@Test
	fun `to-note creates a note with the mail's headers and text, linked to the mail`() {
		val mail = message(inbox)
		bodies.put(mail.id, "Hello Ann,\nthe report is attached.", null)
		attachments.save(mail.id, emptyList())

		val result = created(mail.id, "to-note")

		result["type"].asString() shouldBe "NOTE"
		val note = getJson("/api/v1/notes/${result["id"].asString()}")
		note["title"].asString() shouldBe "Quarterly report"
		val body = note["bodyMd"].asString()
		body shouldContain "**From:** Bob Example <bob@example.test>"
		body shouldContain "**To:** Ann Example <ann@work.example>"
		body shouldContain "**Date:** 2026-02-20 10:30"
		body shouldContain "Hello Ann,\nthe report is attached."
		linkedEnds("NOTE", result["id"].asString()).single()["id"].asString() shouldBe mail.id.toString()
	}

	@Test
	fun `to-note uses the HTML part as text when there is no plain text`() {
		val mail = message(inbox)
		bodies.put(mail.id, null, "<p>Hello <b>Ann</b></p>")
		attachments.save(mail.id, emptyList())

		val result = created(mail.id, "to-note")

		getJson("/api/v1/notes/${result["id"].asString()}")["bodyMd"].asString() shouldContain "Hello Ann"
	}

	@Test
	fun `to-note keeps the block structure of an HTML part written without whitespace`() {
		val mail = message(inbox)
		bodies.put(
			mail.id,
			null,
			"<p>Hello</p><p>World</p><div>first</div><div>second</div>line<br>break" +
				"<ul><li>one</li><li>two</li></ul><table><tr><td>a</td><td>b</td></tr><tr><td>c</td></tr></table>" +
				"<center>Offer</center><center>Details</center>",
		)
		attachments.save(mail.id, emptyList())

		val result = created(mail.id, "to-note")

		val body = getJson("/api/v1/notes/${result["id"].asString()}")["bodyMd"].asString()
		body shouldEndWith "Hello\n\nWorld\n\nfirst\nsecond\nline\nbreak\n\none\ntwo\n\na b\nc\n\nOffer\nDetails"
		body shouldNotContain "\n\n\n"
	}

	@Test
	fun `to-note collapses the source indentation of a pretty-printed HTML part but keeps pre text`() {
		val mail = message(inbox)
		bodies.put(
			mail.id,
			null,
			"""
			<table>
			  <tr>
			    <td>
			      Hello   there,
			      welcome
			    </td>
			    <td>
			      aboard
			    </td>
			  </tr>
			</table>
			<div>
			    <p>
			        First paragraph
			        continues here.
			    </p>
			    <p>
			        Second <b>bold</b>
			        <i>words</i>.
			    </p>
			</div>
			<pre>    indented code</pre>
			""".trimIndent(),
		)
		attachments.save(mail.id, emptyList())

		val result = created(mail.id, "to-note")

		val body = getJson("/api/v1/notes/${result["id"].asString()}")["bodyMd"].asString()
		body shouldEndWith
			"Hello there, welcome aboard\n\nFirst paragraph continues here.\n\nSecond bold words.\n\n    indented code"
		body.lines().dropLast(1).filter { it.startsWith(" ") } shouldBe emptyList()
		body shouldNotContain "\n\n\n"
	}

	@Test
	fun `to-note separates headings, quotes, header cells and rules of an HTML part and drops trailing blanks`() {
		val mail = message(inbox)
		bodies.put(
			mail.id,
			null,
			"<h1>Agenda</h1><blockquote>Quoted line</blockquote><table><tr><th>Name</th><th>Role</th></tr></table>" +
				"<hr><p>spaces   <br>next</p>",
		)
		attachments.save(mail.id, emptyList())

		val result = created(mail.id, "to-note")

		val body = getJson("/api/v1/notes/${result["id"].asString()}")["bodyMd"].asString()
		body shouldEndWith "Agenda\n\nQuoted line\n\nName Role\n\nspaces\nnext"
		body shouldNotContain "\n\n\n"
	}

	@Test
	fun `inline link tokens in a mail's text do not link the note to other items`() {
		val other = UUID.randomUUID()
		val mail = message(inbox)
		bodies.put(mail.id, "See [[task:$other]] and [[[note:$other]]]", null)
		attachments.save(mail.id, emptyList())
		val htmlMail = message(inbox)
		bodies.put(htmlMail.id, null, "<p>See [[task:$other]]</p>")
		attachments.save(htmlMail.id, emptyList())

		for (id in listOf(mail.id, htmlMail.id)) {
			val result = created(id, "to-note")

			linkedEnds("NOTE", result["id"].asString()).single()["id"].asString() shouldBe id.toString()
			val body = getJson("/api/v1/notes/${result["id"].asString()}")["bodyMd"].asString()
			body shouldContain "See [\\[task:$other]]"
			body shouldNotContain "[["
		}
	}

	@Test
	fun `links survive a move that gives the message a new row, re-resolved by Message-ID`() {
		val original = message(inbox, messageId = "<moved@example.test>")
		val taskId = created(original.id, "to-task")["id"].asString()

		// The sync no longer finds the message in the Inbox and caches its copy in the Archive as a new row.
		messages.save(original.apply { detach() })
		val copy = message(archive, messageId = "<moved@example.test>")

		val end = linkedEnds("TASK", taskId).single()
		end["available"].asBoolean() shouldBe true
		end["id"].asString() shouldBe original.id.toString()
		end["title"].asString() shouldBe "Quarterly report"
		end["deepLink"].asString() shouldBe "/mail/messages/${copy.id}"
	}

	@Test
	fun `links survive a move that keeps the row`() {
		val mail = message(inbox)
		val taskId = created(mail.id, "to-task")["id"].asString()

		messages.save(mail.apply { locate(archive.id, 500) })

		val end = linkedEnds("TASK", taskId).single()
		end["available"].asBoolean() shouldBe true
		end["deepLink"].asString() shouldBe "/mail/messages/${mail.id}"
	}

	@Test
	fun `waiting-for and note links survive a move that gives the message a new row`() {
		val original = message(inbox, messageId = "<moved-waiting-note@example.test>")
		bodies.put(original.id, "Text", null)
		attachments.save(original.id, emptyList())
		val waitingId = created(original.id, "to-waiting")["id"].asString()
		val noteId = created(original.id, "to-note")["id"].asString()

		messages.save(original.apply { detach() })
		val copy = message(archive, messageId = "<moved-waiting-note@example.test>")

		for ((type, id) in listOf("TASK" to waitingId, "NOTE" to noteId)) {
			val end = linkedEnds(type, id).single()
			end["available"].asBoolean() shouldBe true
			end["id"].asString() shouldBe original.id.toString()
			end["deepLink"].asString() shouldBe "/mail/messages/${copy.id}"
		}
	}

	@Test
	fun `links keep resolving to the current copy across repeated moves`() {
		val original = message(inbox, messageId = "<moved-twice@example.test>")
		val fromOriginal = created(original.id, "to-task")["id"].asString()
		messages.save(original.apply { detach() })
		val firstCopy = message(archive, messageId = "<moved-twice@example.test>")
		// Created after the first move: the link points at the first copy.
		val fromFirstCopy = created(original.id, "to-task")["id"].asString()
		linkedEnds("TASK", fromFirstCopy).single()["id"].asString() shouldBe firstCopy.id.toString()

		val projects = folders.save(MailFolder(account.id, "Projects", "Projects"))
		messages.save(firstCopy.apply { detach() })
		val secondCopy = message(projects, messageId = "<moved-twice@example.test>")

		for (taskId in listOf(fromOriginal, fromFirstCopy)) {
			val end = linkedEnds("TASK", taskId).single()
			end["available"].asBoolean() shouldBe true
			end["deepLink"].asString() shouldBe "/mail/messages/${secondCopy.id}"
		}
	}

	@Test
	fun `a moved message requested by its old id links its current copy`() {
		val original = message(inbox, messageId = "<old@example.test>")
		messages.save(original.apply { detach() })
		val copy = message(archive, messageId = "<old@example.test>")

		val result = created(original.id, "to-task")

		result["messageId"].asString() shouldBe copy.id.toString()
		linkedEnds("TASK", result["id"].asString()).single()["id"].asString() shouldBe copy.id.toString()
	}

	@Test
	fun `a message that is gone gives 404 and creates nothing`() {
		val gone = message(inbox, messageId = "<gone@example.test>")
		messages.save(gone.apply { detach() })

		for (action in listOf("to-task", "to-waiting", "to-note")) {
			post(gone.id, action).andExpect { status { isNotFound() } }
			post(UUID.randomUUID(), action).andExpect { status { isNotFound() } }
		}
		jdbc.queryForObject("select count(*) from task", Long::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from note", Long::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from link", Long::class.java) shouldBe 0
	}

	@Test
	fun `a malformed id gives 400`() {
		post("not-a-uuid", "to-task").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `a malformed id gives 400 on every action and creates nothing`() {
		for (action in listOf("to-waiting", "to-note")) {
			post("not-a-uuid", action).andExpect { status { isBadRequest() } }
		}
		count("task") shouldBe 0
		count("note") shouldBe 0
	}

	@Test
	fun `a subject of exactly the title limit is kept, one character more is shortened with an ellipsis`() {
		val atLimit = "x".repeat(500)
		created(message(inbox, subject = atLimit).id, "to-task")["title"].asString() shouldBe atLimit

		val tooLong = "y".repeat(501)
		val title = created(message(inbox, subject = tooLong).id, "to-task")["title"].asString()
		title shouldBe "y".repeat(499) + "…"
		title.length shouldBe 500
	}

	@Test
	fun `long subjects are shortened to the limit for waiting-fors and notes too`() {
		val subject = "Budget review for the fictitious Example project ".repeat(30)
		val waiting = created(message(inbox, subject = subject).id, "to-waiting")
		val noteMail = message(inbox, subject = subject)
		bodies.put(noteMail.id, "Body", null)
		attachments.save(noteMail.id, emptyList())
		val note = created(noteMail.id, "to-note")

		for (title in listOf(waiting["title"].asString(), note["title"].asString())) {
			title.length shouldBeLessThanOrEqual 500
			title shouldEndWith "…"
			title shouldStartWith "Budget review for the fictitious Example project"
		}
		getJson("/api/v1/tasks/${waiting["id"].asString()}")["title"].asString() shouldBe waiting["title"].asString()
		getJson("/api/v1/notes/${note["id"].asString()}")["title"].asString() shouldBe note["title"].asString()
	}

	@Test
	fun `the subject is trimmed and a missing subject gives the placeholder for every action`() {
		created(message(inbox, subject = "  Padded subject \t").id, "to-task")["title"].asString() shouldBe "Padded subject"
		created(message(inbox, subject = null).id, "to-waiting")["title"].asString() shouldBe "(no subject)"
		val noteMail = message(inbox, subject = "")
		bodies.put(noteMail.id, "Body", null)
		attachments.save(noteMail.id, emptyList())
		created(noteMail.id, "to-note")["title"].asString() shouldBe "(no subject)"
	}

	@Test
	fun `to-waiting from a sent mail without To waits for Cc, and without To and Cc for Bcc`() {
		val ccOnly =
			message(
				sent,
				from = listOf(MailAddress("ann@work.example")),
				to = emptyList(),
				cc = listOf(MailAddress("erin@example.test", "Erin Example")),
				bcc = listOf(MailAddress("frank@example.test", "Frank Example")),
			)
		val bccOnly =
			message(
				sent,
				from = listOf(MailAddress("ann@work.example")),
				to = emptyList(),
				bcc = listOf(MailAddress("frank@example.test", "Frank Example"), MailAddress("gina@example.test")),
			)

		val fromCc = created(ccOnly.id, "to-waiting")
		val fromBcc = created(bccOnly.id, "to-waiting")

		getJson("/api/v1/tasks/${fromCc["id"].asString()}")["waitingForText"].asString() shouldBe "Erin Example"
		getJson("/api/v1/tasks/${fromBcc["id"].asString()}")["waitingForText"].asString() shouldBe
			"Frank Example, gina@example.test"
	}

	@Test
	fun `to-waiting from a sent mail with To ignores Cc and Bcc`() {
		val mail =
			message(
				sent,
				from = listOf(MailAddress("ann@work.example")),
				to = listOf(MailAddress("bob@example.test", "Bob Example")),
				cc = listOf(MailAddress("erin@example.test", "Erin Example")),
				bcc = listOf(MailAddress("frank@example.test")),
			)

		val result = created(mail.id, "to-waiting")

		getJson("/api/v1/tasks/${result["id"].asString()}")["waitingForText"].asString() shouldBe "Bob Example"
	}

	@Test
	fun `to-waiting from a sent mail without any recipient creates a waiting-for without waitingForText`() {
		val mail = message(sent, from = listOf(MailAddress("ann@work.example")), to = emptyList())

		val result = created(mail.id, "to-waiting")

		val task = getJson("/api/v1/tasks/${result["id"].asString()}")
		task["list"].asString() shouldBe "WAITING"
		task.isAbsent("waitingForText") shouldBe true
		linkedEnds("TASK", result["id"].asString()).single()["id"].asString() shouldBe mail.id.toString()
	}

	@Test
	fun `to-waiting from a received mail without sender creates a waiting-for without waitingForText`() {
		val mail = message(inbox, from = emptyList())

		val task = getJson("/api/v1/tasks/${created(mail.id, "to-waiting")["id"].asString()}")

		task["list"].asString() shouldBe "WAITING"
		task.isAbsent("waitingForText") shouldBe true
	}

	@Test
	fun `sent detection by the account address ignores case, outside the Sent folder`() {
		val mail =
			message(
				archive,
				from = listOf(MailAddress("ANN@Work.Example", "Ann Example")),
				to = listOf(MailAddress("henry@example.test", "Henry Example")),
			)

		val result = created(mail.id, "to-waiting")

		getJson("/api/v1/tasks/${result["id"].asString()}")["waitingForText"].asString() shouldBe "Henry Example"
	}

	@Test
	fun `a received mail outside the Inbox from someone else still waits for the sender`() {
		val mail =
			message(
				archive,
				from = listOf(MailAddress("iris@example.test", "Iris Example")),
				to = listOf(MailAddress("ann@work.example", "Ann Example"), MailAddress("jack@example.test")),
			)

		val result = created(mail.id, "to-waiting")

		getJson("/api/v1/tasks/${result["id"].asString()}")["waitingForText"].asString() shouldBe "Iris Example"
	}

	@Test
	fun `a blank display name falls back to the address in waitingForText`() {
		val mail = message(inbox, from = listOf(MailAddress("kim@example.test", "   ")))

		val result = created(mail.id, "to-waiting")

		getJson("/api/v1/tasks/${result["id"].asString()}")["waitingForText"].asString() shouldBe "kim@example.test"
	}

	@Test
	fun `waitingForText with many recipients is shortened to the limit`() {
		val recipients = (1..60).map { MailAddress("recipient-$it@example.test") }
		val mail = message(sent, from = listOf(MailAddress("ann@work.example")), to = recipients)

		val text = getJson("/api/v1/tasks/${created(mail.id, "to-waiting")["id"].asString()}")["waitingForText"].asString()

		text.length shouldBeLessThanOrEqual 500
		text shouldStartWith "recipient-1@example.test, recipient-2@example.test"
		text shouldEndWith "…"
	}

	@Test
	fun `waitingSince falls back to the received date and is the date in the user's time zone`() {
		// 23:30 UTC on 20 February is already 21 February in the default Europe/Zurich time zone.
		val lateSent = message(inbox, sentAt = Instant.parse("2026-02-20T23:30:00Z"))
		val noSentDate = message(inbox, sentAt = null, receivedAt = Instant.parse("2026-03-05T08:00:00Z"))

		getJson("/api/v1/tasks/${created(lateSent.id, "to-waiting")["id"].asString()}")["waitingSince"].asString() shouldBe
			"2026-02-21"
		getJson("/api/v1/tasks/${created(noSentDate.id, "to-waiting")["id"].asString()}")["waitingSince"].asString() shouldBe
			"2026-03-05"
	}

	@Test
	fun `a mail without any date gives a waiting-for waiting since today, the Waiting For default`() {
		val mail = message(inbox, sentAt = null, receivedAt = null)

		val task = getJson("/api/v1/tasks/${created(mail.id, "to-waiting")["id"].asString()}")

		task["list"].asString() shouldBe "WAITING"
		task["waitingSince"].asString() shouldBe LocalDate.now(clock.withZone(ZoneId.of("Europe/Zurich"))).toString()
	}

	@Test
	fun `to-note lists Cc and leaves out header lines for missing senders, recipients and dates`() {
		val withCc =
			message(
				inbox,
				cc = listOf(MailAddress("erin@example.test", "Erin Example"), MailAddress("lou@example.test")),
			)
		bodies.put(withCc.id, "Text", null)
		attachments.save(withCc.id, emptyList())
		val bare = message(inbox, from = emptyList(), to = emptyList(), sentAt = null, receivedAt = null)
		bodies.put(bare.id, "  Only the text  ", null)
		attachments.save(bare.id, emptyList())

		val ccBody = getJson("/api/v1/notes/${created(withCc.id, "to-note")["id"].asString()}")["bodyMd"].asString()
		val bareBody = getJson("/api/v1/notes/${created(bare.id, "to-note")["id"].asString()}")["bodyMd"].asString()

		ccBody shouldContain "**Cc:** Erin Example <erin@example.test>, lou@example.test"
		bareBody shouldBe "Only the text"
	}

	@Test
	fun `to-note of a mail without any text part has only the header lines`() {
		val mail = message(inbox)
		bodies.put(mail.id, null, null)
		attachments.save(mail.id, emptyList())

		val body = getJson("/api/v1/notes/${created(mail.id, "to-note")["id"].asString()}")["bodyMd"].asString()

		body shouldStartWith "**From:** Bob Example <bob@example.test>"
		body shouldEndWith "**Date:** 2026-02-20 10:30"
	}

	@Test
	fun `to-note prefers the plain text over the HTML part`() {
		val mail = message(inbox)
		bodies.put(mail.id, "Plain version", "<p>HTML version</p>")
		attachments.save(mail.id, emptyList())

		val body = getJson("/api/v1/notes/${created(mail.id, "to-note")["id"].asString()}")["bodyMd"].asString()

		body shouldContain "Plain version"
		body shouldNotContain "HTML version"
	}

	@Test
	fun `to-note from HTML drops scripts and markup`() {
		val mail = message(inbox)
		bodies.put(mail.id, null, "<div>Visible text<script>alert('fictitious')</script><style>p{color:red}</style></div>")
		attachments.save(mail.id, emptyList())

		val body = getJson("/api/v1/notes/${created(mail.id, "to-note")["id"].asString()}")["bodyMd"].asString()

		body shouldContain "Visible text"
		body shouldNotContain "alert"
		body shouldNotContain "<div>"
		body shouldNotContain "<script"
	}

	@Test
	fun `a note body longer than the note limit is shortened with an ellipsis`() {
		val mail = message(inbox)
		bodies.put(mail.id, "z".repeat(150_000), null)
		attachments.save(mail.id, emptyList())

		val body = getJson("/api/v1/notes/${created(mail.id, "to-note")["id"].asString()}")["bodyMd"].asString()

		body.length shouldBe 100_000
		body shouldStartWith "**From:** Bob Example <bob@example.test>"
		body shouldEndWith "z…"
	}

	@Test
	fun `to-note by the old id of a moved message reads and links its current copy`() {
		val original = message(inbox, messageId = "<note-moved@example.test>")
		messages.save(original.apply { detach() })
		val copy = message(archive, messageId = "<note-moved@example.test>")
		bodies.put(copy.id, "Text of the copy", null)
		attachments.save(copy.id, emptyList())

		val result = created(original.id, "to-note")

		result["messageId"].asString() shouldBe copy.id.toString()
		getJson("/api/v1/notes/${result["id"].asString()}")["bodyMd"].asString() shouldContain "Text of the copy"
		linkedEnds("NOTE", result["id"].asString()).single()["id"].asString() shouldBe copy.id.toString()
	}

	@Test
	fun `a repeated request creates another linked item`() {
		val mail = message(inbox)

		val first = created(mail.id, "to-task")
		val second = created(mail.id, "to-task")

		first["id"].asString() shouldNotBe second["id"].asString()
		first["linkId"].asString() shouldNotBe second["linkId"].asString()
		count("task") shouldBe 2
		linkedEnds("EMAIL", mail.id.toString()).map { it["id"].asString() }.toSet() shouldBe
			setOf(first["id"].asString(), second["id"].asString())
	}

	@Test
	fun `the response names the link that the links API returns`() {
		val mail = message(inbox)

		val result = created(mail.id, "to-waiting")

		val links = getJson("/api/v1/links?type=TASK&id=${result["id"].asString()}")["items"]
		links.size() shouldBe 1
		links[0]["id"].asString() shouldBe result["linkId"].asString()
		result["type"].asString() shouldBe "TASK"
	}

	@Test
	fun `a request without CSRF token is rejected and creates nothing`() {
		val mail = message(inbox)

		for (action in listOf("to-task", "to-waiting", "to-note")) {
			mockMvc.post("/api/v1/mail/messages/${mail.id}/$action").andExpect { status { isForbidden() } }
		}
		count("task") shouldBe 0
		count("note") shouldBe 0
		count("link") shouldBe 0
	}
}
