package ch.sonensei.cockpit.contacts.aggregate

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.web.CursorRequest
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
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import ch.sonensei.cockpit.contacts.domain.Change as ContactChange

/**
 * Edge cases of the contact aggregation (SPEC.md §8.3) on top of [ContactAggregateWebTests]: excluded folders and
 * detached rows, the mail limit, regex metacharacters in addresses, cancelled events, Waiting For paging, the owner's
 * own addresses and the ranking/escaping of the autocomplete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class ContactAggregateEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val clock: MutableClock,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val identities: MailIdentityRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val tasks: TaskService,
	@Autowired private val contactService: ContactService,
) : PostgresTestBase() {
	private val calendar = CalendarApi(mockMvc, jsonMapper)
	private lateinit var account: MailAccount
	private lateinit var inbox: MailFolder
	private lateinit var sent: MailFolder
	private var nextUid = 1L

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from contact")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from mail_identity")
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
	}

	// --- helpers

	private fun folder(
		path: String,
		role: MailFolderRole?,
	) = folders.save(MailFolder(account.id, path, path).apply { this.role = role })

	private fun message(
		folder: MailFolder,
		subject: String,
		from: List<MailAddress>,
		to: List<MailAddress>,
		receivedAt: Instant,
		uid: Long? = nextUid++,
	) = messages.save(
		MailMessage(account.id, folder.id, uid).apply {
			this.subject = subject
			this.from = from
			this.to = to
			this.receivedAt = receivedAt
			this.messageIdHeader = "<${UUID.randomUUID()}@example.test>"
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
	private val t0: Instant = Instant.parse("2026-02-01T10:00:00Z")

	// --- related: mails

	@Test
	fun `related leaves out Spam, Drafts and detached messages but keeps folders without a role`() {
		val id = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"}]}""")
		val ann = MailAddress("ann.doe@example.test", "Ann Doe")
		val spam = folder("Junk", MailFolderRole.SPAM)
		val drafts = folder("Drafts", MailFolderRole.DRAFTS)
		val projects = folder("Projects", null)
		val inInbox = message(inbox, "Hello", listOf(ann), listOf(owner), t0)
		message(spam, "Spam", listOf(ann), listOf(owner), t0.plusSeconds(60))
		message(drafts, "Draft to Ann", listOf(owner), listOf(ann), t0.plusSeconds(120))
		message(inbox, "Moved away", listOf(ann), listOf(owner), t0.plusSeconds(180), uid = null)
		val inProjects = message(projects, "Project", listOf(ann), listOf(owner), t0.plusSeconds(240))

		val mails = get("$CONTACTS/$id/related").get("mails")

		mails.texts("id") shouldContainExactly listOf(inProjects.id, inInbox.id).map(UUID::toString)
	}

	@Test
	fun `related returns only the newest 20 mails`() {
		val id = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"}]}""")
		val ann = MailAddress("ann.doe@example.test")
		val all =
			(0 until ContactAggregateService.MAIL_LIMIT + 3).map { i ->
				message(inbox, "Mail $i", listOf(ann), listOf(owner), t0.plusSeconds(i * 60L))
			}

		val mails = get("$CONTACTS/$id/related").get("mails")

		mails.size() shouldBe ContactAggregateService.MAIL_LIMIT
		mails.texts("id") shouldContainExactly all.reversed().take(ContactAggregateService.MAIL_LIMIT).map { it.id.toString() }
	}

	@Test
	fun `related matches addresses with regex metacharacters literally`() {
		val id =
			contact(
				"""{"displayName":"Plus Dot","emails":[{"address":"a+b@example.test"},{"address":"c.d@example.test"},""" +
					"""{"address":"x^y${'$'}z@example.test"}]}""",
			)
		val plus = message(inbox, "Plus", listOf(MailAddress("a+b@example.test")), listOf(owner), t0)
		val dot = message(inbox, "Dot", listOf(MailAddress("C.D@Example.test", "Cee Dee")), listOf(owner), t0.plusSeconds(60))
		val anchors = message(sent, "Anchors", listOf(owner), listOf(MailAddress("x^y${'$'}z@example.test")), t0.plusSeconds(120))
		message(inbox, "Not plus", listOf(MailAddress("aab@example.test")), listOf(owner), t0.plusSeconds(180))
		message(inbox, "Not plus 2", listOf(MailAddress("ab@example.test")), listOf(owner), t0.plusSeconds(190))
		message(inbox, "Not dot", listOf(MailAddress("cxd@example.test")), listOf(owner), t0.plusSeconds(200))
		message(inbox, "Not dot 2", listOf(MailAddress("c.d@exampleXtest")), listOf(owner), t0.plusSeconds(210))

		val mails = get("$CONTACTS/$id/related").get("mails")

		mails.texts("id") shouldContainExactly listOf(anchors.id, dot.id, plus.id).map(UUID::toString)
		mails.texts("direction") shouldContainExactly listOf("TO", "FROM", "FROM")
	}

	// --- related: events and Waiting For

	@Test
	fun `related leaves out cancelled events`() {
		val id = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"}]}""")
		val kept = calendar.createEvent("""{"title":"Kept","start":"2026-03-02T10:00:00Z","attendees":[{"email":"ann.doe@example.test"}]}""")
		val cancelled =
			calendar.createEvent("""{"title":"Cancelled","start":"2026-03-03T10:00:00Z","attendees":[{"email":"ann.doe@example.test"}]}""")
		jdbc.update("update calendar_event set status = 'CANCELLED' where id = ?", UUID.fromString(cancelled.get("id").asString()))

		val events = get("$CONTACTS/$id/related").get("events")

		events.texts("eventId") shouldContainExactly listOf(kept.get("id").asString())
	}

	@Test
	fun `related collects Waiting For tasks from every page of the list`() {
		val id = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"}]}""")
		val first = waiting("Contract from Ann", id)
		repeat(CursorRequest.MAX_LIMIT) { waiting("Parcel $it", null) }
		val last = waiting("Slides from Ann", id)

		val waitingFor = get("$CONTACTS/$id/related").get("waitingFor")

		waitingFor.texts("id") shouldContainExactly listOf(first.task.id, last.task.id).map(UUID::toString)
	}

	@Test
	fun `related returns only the next 20 occurrences`() {
		val id = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"}]}""")
		val all =
			(0 until ContactAggregateService.EVENT_LIMIT + 3).map { i ->
				val start = Instant.parse("2026-03-02T00:00:00Z").plusSeconds(i * 3600L)
				calendar.createEvent("""{"title":"Meeting $i","start":"$start","attendees":[{"email":"ann.doe@example.test"}]}""")
			}

		val events = get("$CONTACTS/$id/related").get("events")

		events.size() shouldBe ContactAggregateService.EVENT_LIMIT
		events.texts("eventId") shouldContainExactly all.take(ContactAggregateService.EVENT_LIMIT).map { it.get("id").asString() }
	}

	@Test
	fun `related lists each occurrence of recurring timed and all-day series with the contact, by start`() {
		val id = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"}]}""")
		val weekly =
			calendar.createEvent(
				"""{"title":"1:1 with Ann","start":"2026-03-02T08:00:00Z","end":"2026-03-02T08:30:00Z",""" +
					""""timeZone":"Europe/Zurich","rrule":"FREQ=WEEKLY;COUNT=3","attendees":[{"email":"ann.doe@example.test"}]}""",
			)
		val offsite =
			calendar.createEvent(
				"""{"title":"Offsite","allDay":true,"startDate":"2026-03-04","rrule":"FREQ=DAILY;COUNT=2",""" +
					""""attendees":[{"email":"Ann.Doe@Example.test"}]}""",
			)
		val weeklyId = weekly.get("id").asString()
		val offsiteId = offsite.get("id").asString()

		val events = get("$CONTACTS/$id/related").get("events")

		events.texts("title") shouldContainExactly listOf("1:1 with Ann", "Offsite", "Offsite", "1:1 with Ann", "1:1 with Ann")
		events.texts("seriesId") shouldContainExactly listOf(weeklyId, offsiteId, offsiteId, weeklyId, weeklyId)
		events.texts("occurrence") shouldContainExactly
			listOf("2026-03-02T08:00:00Z", "2026-03-04", "2026-03-05", "2026-03-09T08:00:00Z", "2026-03-16T08:00:00Z")
		events.texts("start") shouldContainExactly
			listOf("2026-03-02T08:00:00Z", null, null, "2026-03-09T08:00:00Z", "2026-03-16T08:00:00Z")
		events.texts("startDate") shouldContainExactly listOf(null, "2026-03-04", "2026-03-05", null, null)
		events.texts("endDate") shouldContainExactly listOf(null, "2026-03-05", "2026-03-06", null, null)
		(0 until events.size()).map { events[it].get("allDay").asBoolean() } shouldContainExactly
			listOf(false, true, true, false, false)
	}

	@Test
	fun `related of a contact in the Trash is 404`() {
		val id = contact("""{"displayName":"Ann Doe","emails":[{"address":"ann.doe@example.test"}]}""")
		mockMvc
			.delete("$CONTACTS/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"0\"")
			}.andExpect { status { isNoContent() } }

		mockMvc.get("$CONTACTS/$id/related").andExpect { status { isNotFound() } }
	}

	// --- autocomplete

	@Test
	fun `autocomplete leaves out the owner's account and identity addresses`() {
		identities.save(MailIdentity(account.id, "Owner Alias", "alias.owner@cockpit.example"))
		message(inbox, "From me", listOf(MailAddress("OWNER@Cockpit.Example", "Owner")), listOf(MailAddress("lowner@example.test")), t0)
		message(sent, "Alias", listOf(MailAddress("alias.owner@cockpit.example", "Owner Alias")), listOf(owner), t0.plusSeconds(60))

		val suggestions = get("$CONTACTS/autocomplete?q=owner")

		suggestions.texts("email") shouldContainExactly listOf("lowner@example.test")
		suggestions.texts("contactId") shouldContainExactly listOf(null)
	}

	@Test
	fun `autocomplete lists contacts whose name starts with the query before other matching contacts`() {
		val contains = contact("""{"displayName":"Aaron Annick","emails":[{"address":"aaron@example.test"}]}""")
		val byEmail = contact("""{"displayName":"Carol","emails":[{"address":"carol.ann@example.test"}]}""")
		val prefix = contact("""{"displayName":"Annabel Zed","emails":[{"address":"annabel@example.test"}]}""")

		val suggestions = get("$CONTACTS/autocomplete?q=ANN")

		suggestions.texts("contactId") shouldContainExactly listOf(prefix, contains, byEmail)
	}

	@Test
	fun `autocomplete treats LIKE wildcards in the query literally`() {
		// Unescaped, `_` would match the "x" of Saxby/axb and `%` would match everything.
		contact("""{"displayName":"Max Saxby","emails":[{"address":"max@example.test"}]}""")
		message(inbox, "Hi", listOf(MailAddress("axb@example.test", "Axb")), listOf(owner), t0)

		autocomplete("a_b").texts("email").shouldBeEmpty()
		autocomplete("%").texts("email").shouldBeEmpty()
		autocomplete("axb").texts("email") shouldContainExactly listOf("max@example.test", "axb@example.test")
	}

	@Test
	fun `autocomplete has nothing for a whitespace-only query even though every contact contains a space`() {
		contact("""{"displayName":"Max Saxby","emails":[{"address":"max@example.test"}]}""")
		message(inbox, "Hi", listOf(MailAddress("axb@example.test", "Axb Sender")), listOf(owner), t0)

		autocomplete("   ").size() shouldBe 0
	}

	@Test
	fun `autocomplete finds contacts whose name starts with the query behind more than a page of other matches`() {
		// Every "Aa xm" contact contains "m" and sorts before "Martin": more than one page of CursorRequest.MAX_LIMIT.
		repeat(CursorRequest.MAX_LIMIT + 5) { i ->
			contactService.create(ContactChanges(displayName = ContactChange("Aa xm %03d".format(i))))
		}
		val martin = contact("""{"displayName":"Martin","emails":[{"address":"martin@example.test"}]}""")
		val first = contactService.list("Aa xm 000", CursorRequest(null, 1)).items.single()

		val suggestions = autocomplete("m", limit = 2)

		suggestions.texts("contactId") shouldContainExactly listOf(martin, first.id.toString())
		suggestions.texts("name") shouldContainExactly listOf("Martin", "Aa xm 000")
	}

	@Test
	fun `autocomplete links a correspondent to the contact owning its address`() {
		val bob = contact("""{"displayName":"Bob","emails":[{"address":"X@Example.test"}]}""")
		// Abe's address contains "x@example.test" and sorts first, but is not that address.
		contact("""{"displayName":"Abe","emails":[{"address":"max@example.test"}]}""")
		message(inbox, "Yo", listOf(MailAddress("x@example.test", "Hannah Smith")), listOf(owner), t0)
		message(inbox, "Hi", listOf(MailAddress("hannah.lee@example.test", "Hannah Lee")), listOf(owner), t0.minusSeconds(60))
		message(inbox, "Re", listOf(MailAddress("ax@example.test", "Hannah Ax")), listOf(owner), t0.minusSeconds(120))

		val suggestions = autocomplete("hannah")

		suggestions.texts("email") shouldContainExactly listOf("x@example.test", "hannah.lee@example.test", "ax@example.test")
		suggestions.texts("contactId") shouldContainExactly listOf(bob, null, null)
		suggestions.texts("name") shouldContainExactly listOf("Bob", "Hannah Lee", "Hannah Ax")
	}

	private fun autocomplete(
		query: String,
		limit: Int? = null,
	): JsonNode =
		mockMvc
			.get("$CONTACTS/autocomplete") {
				param("q", query)
				limit?.let { param("limit", it.toString()) }
			}.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)

	@Test
	fun `autocomplete uses the default limit of 10`() {
		(0 until ContactAggregateService.DEFAULT_AUTOCOMPLETE_LIMIT + 2).forEach { i ->
			message(inbox, "Mail $i", listOf(MailAddress("ann$i@example.test")), listOf(owner), t0.plusSeconds(i * 60L))
		}

		val suggestions = get("$CONTACTS/autocomplete?q=ann")

		suggestions.size() shouldBe ContactAggregateService.DEFAULT_AUTOCOMPLETE_LIMIT
		suggestions.texts("email").first() shouldBe "ann${ContactAggregateService.DEFAULT_AUTOCOMPLETE_LIMIT + 1}@example.test"
	}

	private companion object {
		const val CONTACTS = "/api/v1/contacts"
	}
}
