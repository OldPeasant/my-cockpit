package ch.sonensei.cockpit.calendar.infra.caldav

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * The sync engine with the real [CalDavCalendarAdapter] against a Radicale server (SPEC.md §6.2, acceptance test phase
 * 4.2): discovery, pulling (sync-collection, the ctag/etag fallback, an expired token), events created and changed in
 * the cockpit arriving on the server, changes on the server arriving in the cockpit, and a conflict (412) after a change
 * on the server. Every test starts with an empty calendar `work` of user alice.
 */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class CalDavRadicaleSyncTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val tx: TransactionTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var source: CalendarSource

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		dav("DELETE", "$HOME$CALENDAR/")
		dav("DELETE", "${HOME}other/")
		val created =
			dav(
				"MKCALENDAR",
				"$HOME$CALENDAR/",
				"""<?xml version="1.0" encoding="utf-8"?><C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">""" +
					"""<D:set><D:prop><D:displayname>Work</D:displayname></D:prop></D:set></C:mkcalendar>""",
			)
		created.statusCode() shouldBe 201
		source =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.CALDAV, "Radicale").apply {
					config = """{"url": "${radicale.baseUrl()}/", "username": "$USER"}"""
					credentials = EncryptedSecret(PASSWORD)
				},
			)
	}

	private fun calendarId(): UUID = calendars.findBySourceIdAndExternalId(source.id, "$HOME$CALENDAR/")!!.id

	private fun local(uid: String): CalendarEvent? = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId(), uid)

	private fun overridesOf(event: CalendarEvent) = events.findByParentEventIdOrderByIdAsc(event.id)

	private fun exdatesOf(id: UUID): Set<OccurrenceId> =
		tx.execute {
			events
				.findById(id)
				.get()
				.exdates
				.toSet()
		}

	private fun cursor(): ObjectNode =
		jsonMapper.readTree(
			jdbc.queryForObject("select cursor from calendar_sync_cursor where calendar_id = ?", String::class.java, calendarId()),
		) as ObjectNode

	private fun syncOk() {
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	// --- pull

	@Test
	fun `discovery finds the user's calendars through the principal, and a first sync stores their events`() {
		dav(
			"MKCALENDAR",
			"${HOME}other/",
			"""<?xml version="1.0" encoding="utf-8"?><C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">""" +
				"""<D:set><D:prop><D:displayname>Other</D:displayname></D:prop></D:set></C:mkcalendar>""",
		)
		put("dentist.ics", single("dentist", "Dentist", "20261103T090000", "20261103T100000"))
		put("standup.ics", SERIES)

		syncOk()

		calendars.findBySourceIdOrderByIdAsc(source.id).map { it.name } shouldContainExactlyInAnyOrder listOf("Work", "Other")
		local("dentist").shouldNotBeNull().apply {
			title shouldBe "Dentist"
			startAt shouldBe Instant.parse("2026-11-03T08:00:00Z")
			timeZone shouldBe "Europe/Zurich"
			externalEtag shouldBe etagOf("dentist.ics")
		}
		val series = local("standup").shouldNotBeNull()
		series.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO"
		exdatesOf(series.id) shouldContainExactly setOf(OccurrenceId.of(Instant.parse("2026-11-16T08:00:00Z")))
		overridesOf(series).single().apply {
			title shouldBe "Standup (moved)"
			recurrenceId shouldBe OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z"))
			startAt shouldBe Instant.parse("2026-11-09T10:00:00Z")
			externalEtag shouldBe etagOf("standup.ics")
		}
		cursor().get("mode").asString() shouldBe "sync"
	}

	@Test
	fun `an event changed on the server is in the cockpit after the next sync, a deleted one is gone`() {
		put("dentist.ics", single("dentist", "Dentist", "20261103T090000", "20261103T100000"))
		put("lunch.ics", single("lunch", "Lunch", "20261104T120000", "20261104T130000"))
		syncOk()

		put("dentist.ics", single("dentist", "Dentist (moved)", "20261105T140000", "20261105T150000"))
		dav("DELETE", "$HOME$CALENDAR/lunch.ics")
		put("new.ics", single("new", "Brand new", "20261106T080000", "20261106T090000"))
		syncOk()

		local("dentist").shouldNotBeNull().apply {
			title shouldBe "Dentist (moved)"
			startAt shouldBe Instant.parse("2026-11-05T13:00:00Z")
			externalEtag shouldBe etagOf("dentist.ics")
		}
		local("lunch").shouldBeNull()
		local("new").shouldNotBeNull().title shouldBe "Brand new"
	}

	@Test
	fun `without a sync token the ctag and etags of the resources show the changes`() {
		put("dentist.ics", single("dentist", "Dentist", "20261103T090000", "20261103T100000"))
		put("lunch.ics", single("lunch", "Lunch", "20261104T120000", "20261104T130000"))
		syncOk()
		// As stored by a run on a server without sync-collection.
		val etagCursor = cursor().put("mode", "etag").put("ctag", "outdated")
		etagCursor.remove("token")
		jdbc.update("update calendar_sync_cursor set cursor = ? where calendar_id = ?", jsonMapper.writeValueAsString(etagCursor), calendarId())

		put("dentist.ics", single("dentist", "Dentist (moved)", "20261105T140000", "20261105T150000"))
		dav("DELETE", "$HOME$CALENDAR/lunch.ics")
		syncOk()

		local("dentist").shouldNotBeNull().title shouldBe "Dentist (moved)"
		local("lunch").shouldBeNull()
		cursor().get("mode").asString() shouldBe "etag"
		val ctag = cursor().get("ctag").asString()

		// Nothing changed: the same ctag, nothing fetched.
		syncOk()
		cursor().get("ctag").asString() shouldBe ctag
		local("dentist").shouldNotBeNull().title shouldBe "Dentist (moved)"
	}

	@Test
	fun `a sync token the server does not know starts a complete listing`() {
		put("dentist.ics", single("dentist", "Dentist", "20261103T090000", "20261103T100000"))
		syncOk()
		jdbc.update(
			"update calendar_sync_cursor set cursor = ? where calendar_id = ?",
			jsonMapper.writeValueAsString(cursor().put("token", "http://radicale.org/ns/sync/unknown")),
			calendarId(),
		)
		// Only a complete listing removes an event the server never reported as deleted.
		jdbc.update("update calendar_event set external_id = 'ghost' where external_id = 'dentist'")
		put("lunch.ics", single("lunch", "Lunch", "20261104T120000", "20261104T130000"))

		syncOk()

		local("ghost").shouldBeNull()
		local("dentist").shouldNotBeNull()
		local("lunch").shouldNotBeNull()
	}

	// --- push

	@Test
	fun `an event created in the cockpit is on the server, with the server's etag in the cockpit`() {
		syncOk()

		val created =
			api.createEvent(
				"""{"calendarId":"${calendarId()}","title":"Planning, Q4","description":"Room 3\nBring notes",""" +
					""""start":"2026-10-28T08:00:00Z","end":"2026-10-28T09:00:00Z","timeZone":"Europe/Zurich",""" +
					""""reminderMinutes":[15],"attendees":[{"email":"bob@example.test","name":"Bob"}]}""",
			)

		val stored = events.findById(UUID.fromString(created.id())).get()
		stored.externalId shouldBe created.id()
		created.get("syncState").asString() shouldBe "SYNCED"
		val resource = get("${created.id()}.ics")
		resource.statusCode() shouldBe 200
		stored.externalEtag shouldBe resource.headers().firstValue("ETag").get()
		val body = resource.body().replace("\r\n ", "")
		body shouldContain "SUMMARY:Planning\\, Q4"
		body shouldContain "DESCRIPTION:Room 3\\nBring notes"
		body shouldContain "DTSTART;TZID=Europe/Zurich:20261028T090000"
		body shouldContain "BEGIN:VTIMEZONE"
		body shouldContain "TRIGGER:-PT15M"
		body shouldContain "ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:bob@example.test"

		// The next sync changes nothing: the cockpit has the server's version.
		syncOk()
		events.findById(stored.id).get().version shouldBe stored.version
	}

	@Test
	fun `a series edited in the cockpit keeps its overrides and exceptions in one resource on the server`() {
		syncOk()
		val series =
			api.createEvent(
				"""{"calendarId":"${calendarId()}","title":"Standup","start":"2026-11-02T08:00:00Z","end":"2026-11-02T08:15:00Z",""" +
					""""timeZone":"Europe/Zurich","rrule":"FREQ=WEEKLY;BYDAY=MO"}""",
			)
		val id = series.id()

		api.patchEventOk(
			id,
			"""{"title":"Standup (moved)","start":"2026-11-09T10:00:00Z","end":"2026-11-09T10:15:00Z"}""",
			"?scope=THIS&occurrence=2026-11-09T08:00:00Z",
		)
		api.deleteEvent(id, "?scope=THIS&occurrence=2026-11-16T08:00:00Z").andExpect { status { isNoContent() } }

		val body = get("$id.ics").body().replace("\r\n ", "")
		body shouldContain "RRULE:FREQ=WEEKLY;BYDAY=MO"
		body shouldContain "RECURRENCE-ID;TZID=Europe/Zurich:20261109T090000"
		body shouldContain "SUMMARY:Standup (moved)"
		body shouldContain "EXDATE;TZID=Europe/Zurich:20261116T090000"
		val master = events.findById(UUID.fromString(id)).get()
		val etag = get("$id.ics").headers().firstValue("ETag").get()
		master.externalEtag shouldBe etag
		overridesOf(master).single().externalEtag shouldBe etag

		// Pulling it back changes nothing.
		syncOk()
		overridesOf(local(id)!!).single().title shouldBe "Standup (moved)"
		exdatesOf(master.id) shouldContainExactly setOf(OccurrenceId.of(Instant.parse("2026-11-16T08:00:00Z")))

		api.deleteEvent(id).andExpect { status { isNoContent() } }
		get("$id.ics").statusCode() shouldBe 404
	}

	@Test
	fun `a series of the server changed in the cockpit is written to its own resource, unknown properties kept`() {
		put("standup.ics", SERIES)
		syncOk()
		val series = local("standup")!!

		api.patchEventOk(series.id.toString(), """{"location":"Room 4"}""")

		val body = get("standup.ics").body().replace("\r\n ", "")
		body shouldContain "LOCATION:Room 4"
		body shouldContain "X-CUSTOM:kept"
		body shouldContain "SUMMARY:Standup (moved)"
		get("${series.id}.ics").statusCode() shouldBe 404
	}

	@Test
	fun `an edit of an event changed on the server meanwhile is refused with 409 and shows the server's version`() {
		put("dentist.ics", single("dentist", "Dentist", "20261103T090000", "20261103T100000"))
		syncOk()
		val id = local("dentist")!!.id.toString()
		val etag = etag(api.event(id))
		put("dentist.ics", single("dentist", "Dentist (server)", "20261103T110000", "20261103T120000"))

		api.patchEvent(id, """{"title":"Mine"}""", ifMatch = etag).isProblem(409).andExpect {
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("changed remotely")) }
		}

		local("dentist")!!.title shouldBe "Dentist (server)"
		get("dentist.ics").body() shouldNotContain "Mine"

		// The retry on the new version goes through.
		api.patchEventOk(id, """{"title":"Mine"}""")
		get("dentist.ics").body() shouldContain "SUMMARY:Mine"
	}

	@Test
	fun `a delete of an event changed on the server meanwhile is refused with 409 and the event stays on the server`() {
		put("dentist.ics", single("dentist", "Dentist", "20261103T090000", "20261103T100000"))
		syncOk()
		val id = local("dentist")!!.id.toString()
		val etag = etag(api.event(id))
		put("dentist.ics", single("dentist", "Dentist (server)", "20261103T110000", "20261103T120000"))

		api.deleteEvent(id, ifMatch = etag).isProblem(409)

		get("dentist.ics").statusCode() shouldBe 200
		local("dentist")!!.title shouldBe "Dentist (server)"

		// The retry on the new version deletes it on the server.
		api.deleteEvent(id).andExpect { status { isNoContent() } }
		get("dentist.ics").statusCode() shouldBe 404
		local("dentist").shouldBeNull()
	}

	@Test
	fun `an all-day event round-trips with date values`() {
		syncOk()
		val created =
			api.createEvent(
				"""{"calendarId":"${calendarId()}","title":"Holiday","allDay":true,"startDate":"2026-12-24","endDate":"2026-12-27"}""",
			)

		val body = get("${created.id()}.ics").body()
		body shouldContain "DTSTART;VALUE=DATE:20261224"
		body shouldContain "DTEND;VALUE=DATE:20261227"
		jdbc.update("delete from calendar_event")
		jdbc.update("delete from calendar_sync_cursor")

		syncOk()

		local(created.id()).shouldNotBeNull().apply {
			allDay shouldBe true
			startDate shouldBe LocalDate.of(2026, 12, 24)
			endDate shouldBe LocalDate.of(2026, 12, 27)
		}
	}

	// --- Radicale

	private fun put(
		name: String,
		ics: String,
	) {
		val response = dav("PUT", "$HOME$CALENDAR/$name", ics, "text/calendar; charset=utf-8")
		(response.statusCode() in 200..299) shouldBe true
	}

	private fun get(name: String): HttpResponse<String> = dav("GET", "$HOME$CALENDAR/$name")

	private fun etagOf(name: String): String = get(name).headers().firstValue("ETag").get()

	private fun single(
		uid: String,
		title: String,
		start: String,
		end: String,
	) = """
		BEGIN:VCALENDAR
		VERSION:2.0
		PRODID:-//Example//Test//EN
		BEGIN:VEVENT
		UID:$uid
		DTSTAMP:20261001T000000Z
		SUMMARY:$title
		DTSTART;TZID=Europe/Zurich:$start
		DTEND;TZID=Europe/Zurich:$end
		END:VEVENT
		END:VCALENDAR
		""".trimIndent().replace("\n", "\r\n") + "\r\n"

	companion object {
		private const val USER = "alice"
		private const val PASSWORD = "fictitious-secret"
		private const val HOME = "/$USER/"
		private const val CALENDAR = "work"
		private const val PORT = 5232

		private val SERIES =
			"""
			BEGIN:VCALENDAR
			VERSION:2.0
			PRODID:-//Example//Test//EN
			BEGIN:VEVENT
			UID:standup
			DTSTAMP:20261001T000000Z
			SUMMARY:Standup
			X-CUSTOM:kept
			DTSTART;TZID=Europe/Zurich:20261102T090000
			DTEND;TZID=Europe/Zurich:20261102T091500
			RRULE:FREQ=WEEKLY;BYDAY=MO
			EXDATE;TZID=Europe/Zurich:20261116T090000
			END:VEVENT
			BEGIN:VEVENT
			UID:standup
			DTSTAMP:20261001T000000Z
			RECURRENCE-ID;TZID=Europe/Zurich:20261109T090000
			SUMMARY:Standup (moved)
			DTSTART;TZID=Europe/Zurich:20261109T110000
			DTEND;TZID=Europe/Zurich:20261109T111500
			END:VEVENT
			END:VCALENDAR
			""".trimIndent().replace("\n", "\r\n") + "\r\n"

		private val radicale: GenericContainer<*> =
			GenericContainer(DockerImageName.parse("docker.io/tomsquest/docker-radicale:3.5.4.0"))
				.withExposedPorts(PORT)
				.withCopyToContainer(
					Transferable.of(
						"""
						[server]
						hosts = 0.0.0.0:$PORT
						[auth]
						type = htpasswd
						htpasswd_filename = /config/users
						htpasswd_encryption = plain
						[rights]
						type = owner_only
						[storage]
						filesystem_folder = /data/collections
						""".trimIndent(),
					),
					"/config/config",
				).withCopyToContainer(Transferable.of("$USER:$PASSWORD\n"), "/config/users")
				.waitingFor(Wait.forListeningPort())
				.also { it.start() }

		private fun GenericContainer<*>.baseUrl() = "http://$host:${getMappedPort(PORT)}"

		private val http: HttpClient = HttpClient.newHttpClient()

		private fun dav(
			method: String,
			path: String,
			body: String? = null,
			contentType: String = "application/xml; charset=utf-8",
		): HttpResponse<String> =
			http.send(
				HttpRequest
					.newBuilder(URI.create(radicale.baseUrl() + path))
					.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
					.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray()))
					.apply { if (body != null) header("Content-Type", contentType) }
					.build(),
				HttpResponse.BodyHandlers.ofString(),
			)
	}
}
