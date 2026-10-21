package ch.sonensei.cockpit.contacts.aggregate

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.gtd.domain.Change
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class ContactAggregateWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val clock: MutableClock,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val tasks: TaskService,
) : PostgresTestBase() {
	private val calendar = CalendarApi(mockMvc, jsonMapper)
	private lateinit var account: MailAccount
	private lateinit var inbox: MailFolder
	private lateinit var sent: MailFolder
	private lateinit var trash: MailFolder
	private var nextUid = 1L

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from contact")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from mail_account")
		CalendarApi.resetCalendarTables(jdbc)
	}

	@BeforeEach
	fun setUp() {
		clock.set(MutableClockTestConfiguration.START)
		calendar.createCalendar()
		account =
			accounts.save(
				MailAccount(
					MailAccountType.IMAP_SMTP,
					"Work",
					"owner@cockpit.example",
					"imap.example.test",
					993,
					MailSecurity.SSL_TLS,
					"smtp.example.test",
					587,
					MailSecurity.STARTTLS,
					"owner",
				),
			)
		inbox = folder("INBOX", MailFolderRole.INBOX)
		sent = folder("Sent", MailFolderRole.SENT)
		trash = folder("Trash", MailFolderRole.TRASH)
	}

	// --- helpers

	private fun folder(
		path: String,
		role: MailFolderRole,
	) = folders.save(MailFolder(account.id, path, path).apply { this.role = role })

	private fun message(
		folder: MailFolder,
		subject: String,
		from: List<MailAddress>,
		to: List<MailAddress>,
		receivedAt: Instant,
		cc: List<MailAddress> = emptyList(),
		messageId: String = "<${UUID.randomUUID()}@example.test>",
	) = messages.save(
		MailMessage(account.id, folder.id, nextUid++).apply {
			this.subject = subject
			this.from = from
			this.to = to
			this.cc = cc
			this.receivedAt = receivedAt
			this.messageIdHeader = messageId
		},
	)

	private fun contact(body: String): String =
		mockMvc
			.post(CONTACTS) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andExpect { status { isCreated() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)
			.get("id")
			.asString()

	private fun get(url: String): JsonNode =
		mockMvc
			.get(url)
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)

	private fun JsonNode.texts(field: String): List<String?> =
		(0 until size()).map {
			get(it).get(field)?.takeIf { v -> !v.isNull }?.asString()
		}

	private fun waiting(
		title: String,
		contactId: String?,
	) = tasks.create(
		null,
		TaskList.WAITING,
		TaskChanges(title = Change(title), waitingForContactId = Change(contactId?.let(UUID::fromString))),
	)

	private val owner = MailAddress("owner@cockpit.example", "Owner")
	private val ann = MailAddress("Ann.Doe@Example.test", "Doe, Ann")

	// --- related

	@Test
	fun `related lists mails from and to the contact newest first, without Trash, other people and duplicate copies`() {
		val id = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"},{"address":"ann@home.test"}]}""")
		val t0 = Instant.parse("2026-02-01T10:00:00Z")
		val fromAnn = message(inbox, "Hello", listOf(ann), listOf(owner), t0)
		val toAnn = message(sent, "Re: Hello", listOf(owner), listOf(MailAddress("ann@home.test")), t0.plusSeconds(60))
		val ccAnn = message(inbox, "Team", listOf(MailAddress("bob@example.test")), listOf(owner), t0.plusSeconds(120), cc = listOf(ann))
		message(trash, "Old", listOf(ann), listOf(owner), t0.plusSeconds(180))
		message(inbox, "Lookalike", listOf(MailAddress("joann.doe@example.test")), listOf(owner), t0.plusSeconds(240))
		message(inbox, "Lookalike 2", listOf(MailAddress("ann.doe@example.test.org")), listOf(owner), t0.plusSeconds(250))
		val copyId = "<copy@example.test>"
		val archived = message(inbox, "Copy", listOf(ann), listOf(owner), t0.plusSeconds(300), messageId = copyId)
		message(sent, "Copy", listOf(ann), listOf(owner), t0.plusSeconds(30), messageId = copyId)

		val related = get("$CONTACTS/$id/related")

		related.get("contactId").asString() shouldBe id
		val mails = related.get("mails")
		mails.texts("id") shouldContainExactly listOf(archived.id, ccAnn.id, toAnn.id, fromAnn.id).map(UUID::toString)
		mails.texts("direction") shouldContainExactly listOf("FROM", "TO", "TO", "FROM")
		mails[3].get("from")[0].get("name").asString() shouldBe "Doe, Ann"
	}

	@Test
	fun `related lists upcoming occurrences with the contact as attendee and its Waiting For tasks`() {
		val id = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"}]}""")
		val other = contact("""{"displayName":"Bob"}""")
		calendar.createEvent("""{"title":"Past","start":"2026-02-27T10:00:00Z","attendees":[{"email":"ann.doe@example.test"}]}""")
		val later = calendar.createEvent("""{"title":"Later","start":"2026-03-10T10:00:00Z","attendees":[{"email":"ANN.DOE@example.test"}]}""")
		val soon = calendar.createEvent("""{"title":"Soon","start":"2026-03-02T10:00:00Z","attendees":[{"email":"ann.doe@example.test"}]}""")
		calendar.createEvent("""{"title":"Without Ann","start":"2026-03-03T10:00:00Z","attendees":[{"email":"bob@example.test"}]}""")
		calendar.createEvent("""{"title":"Too far","start":"2026-07-01T10:00:00Z","attendees":[{"email":"ann.doe@example.test"}]}""")
		val first = waiting("Contract from Ann", id)
		waiting("Invoice from Bob", other)
		waiting("Parcel", null)
		val second = waiting("Slides from Ann", id)

		val related = get("$CONTACTS/$id/related")

		related.get("events").texts("eventId") shouldContainExactly listOf(soon.get("id").asString(), later.get("id").asString())
		related.get("events").texts("title") shouldContainExactly listOf("Soon", "Later")
		related.get("waitingFor").texts("id") shouldContainExactly listOf(first.task.id, second.task.id).map(UUID::toString)
		related.get("waitingFor")[0].get("waitingSince").asString() shouldBe "2026-03-01"
		related.get("mails").shouldBeEmpty()
	}

	@Test
	fun `related of a contact without email has no mails or events, unknown contact is 404`() {
		val id = contact("""{"displayName":"No Mail"}""")
		message(inbox, "Hello", listOf(ann), listOf(owner), Instant.parse("2026-02-01T10:00:00Z"))

		val related = get("$CONTACTS/$id/related")

		related.get("mails").shouldBeEmpty()
		related.get("events").shouldBeEmpty()
		mockMvc.get("$CONTACTS/${UUID.randomUUID()}/related").andExpect { status { isNotFound() } }
	}

	// --- autocomplete

	@Test
	fun `autocomplete merges contacts and recent correspondents without duplicates or own addresses`() {
		val annId = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"},{"address":"ann@home.test"}]}""")
		val joId = contact("""{"displayName":"Jo Anner"}""")
		val t0 = Instant.parse("2026-02-01T10:00:00Z")
		message(inbox, "Hi", listOf(ann), listOf(owner), t0)
		message(inbox, "Hey", listOf(MailAddress("annika@example.test")), listOf(owner), t0.plusSeconds(60))
		message(inbox, "Yo", listOf(MailAddress("x@example.test", "Hannah Smith")), listOf(owner), t0.plusSeconds(120))
		message(inbox, "Old", listOf(MailAddress("annika@example.test", "Annika K")), listOf(owner), t0.minusSeconds(60))
		message(trash, "Spam", listOf(MailAddress("annette@example.test")), listOf(owner), t0.plusSeconds(180))

		val suggestions = get("$CONTACTS/autocomplete?q=ann")

		suggestions.texts("email") shouldContainExactly
			listOf("ann.doe@example.test", "ann@home.test", null, "x@example.test", "annika@example.test")
		suggestions.texts("contactId") shouldContainExactly listOf(annId, annId, joId, null, null)
		suggestions.texts("name") shouldContainExactly listOf("Ann Doe", "Ann Doe", "Jo Anner", "Hannah Smith", "Annika K")
	}

	@Test
	fun `autocomplete honours the limit, has nothing for a blank query and rejects a bad limit`() {
		contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"},{"address":"ann@home.test"}]}""")

		get("$CONTACTS/autocomplete?q=ann&limit=1").texts("email") shouldContainExactly listOf("ann.doe@example.test")
		mockMvc
			.get("$CONTACTS/autocomplete") { param("q", " ") }
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)
			.size() shouldBe 0
		mockMvc.get("$CONTACTS/autocomplete?q=ann&limit=0").andExpect { status { isBadRequest() } }
		mockMvc.get("$CONTACTS/autocomplete?q=ann&limit=51").andExpect { status { isBadRequest() } }
	}

	private companion object {
		const val CONTACTS = "/api/v1/contacts"
	}
}
