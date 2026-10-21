package ch.sonensei.cockpit.calendar.smoke

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Phase 4 demo script (SPEC.md §13.2), the CalDAV part against a real Radicale server: step 2 (an event created in the
 * cockpit appears on the server, an event changed on the server appears in the cockpit after a sync) and step 4 on
 * CalDAV ("this and following" splits the series on the server, too). The detailed cases are in
 * `CalDavRadicaleSyncTests`; this is the demo as the owner would run it.
 */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class Phase4CalDavDemoTests(
	@Autowired mockMvc: MockMvc,
	@Autowired jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var source: CalendarSource

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		dav("DELETE", COLLECTION)
		dav(
			"MKCALENDAR",
			COLLECTION,
			"""<?xml version="1.0" encoding="utf-8"?><C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">""" +
				"""<D:set><D:prop><D:displayname>Demo</D:displayname></D:prop></D:set></C:mkcalendar>""",
		).statusCode() shouldBe 201
		source =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.CALDAV, "Radicale").apply {
					config = """{"url": "${radicale.baseUrl()}/", "username": "$USER"}"""
					credentials = EncryptedSecret(PASSWORD)
				},
			)
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	private fun calendarId(): UUID = calendars.findBySourceIdAndExternalId(source.id, COLLECTION)!!.id

	private fun syncOk() {
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	@Test
	fun `step 2 - an event created in the cockpit is on the server, and the server's change is in the cockpit after a sync`() {
		val created =
			api.createEvent(
				"""{"calendarId":"${calendarId()}","title":"Dentist","start":"2026-11-03T08:00:00Z","end":"2026-11-03T09:00:00Z",""" +
					""""timeZone":"Europe/Zurich"}""",
			)
		val id = created.id()

		val onServer = get("$id.ics")
		onServer.statusCode() shouldBe 200
		onServer.body() shouldContain "SUMMARY:Dentist"
		onServer.body() shouldContain "DTSTART;TZID=Europe/Zurich:20261103T090000"
		onServer.body() shouldContain "DTEND;TZID=Europe/Zurich:20261103T100000"

		// Someone moves it in another CalDAV client.
		put(
			"$id.ics",
			onServer
				.body()
				.replace("SUMMARY:Dentist", "SUMMARY:Dentist (moved)")
				.replace("DTSTART;TZID=Europe/Zurich:20261103T090000", "DTSTART;TZID=Europe/Zurich:20261103T140000")
				.replace("DTEND;TZID=Europe/Zurich:20261103T100000", "DTEND;TZID=Europe/Zurich:20261103T150000"),
		)
		api.event(id).get("title").asString() shouldBe "Dentist"

		syncOk()

		val changed = api.event(id)
		changed.get("title").asString() shouldBe "Dentist (moved)"
		Instant.parse(changed.get("start").asString()) shouldBe Instant.parse("2026-11-03T13:00:00Z")
		events.findById(UUID.fromString(id)).get().externalEtag shouldBe get("$id.ics").headers().firstValue("ETag").get()
	}

	@Test
	fun `step 4 - this and following on a CalDAV series ends it on the server and continues it as a new one`() {
		val series =
			api.createEvent(
				"""{"calendarId":"${calendarId()}","title":"Standup","start":"2026-11-02T08:00:00Z","end":"2026-11-02T08:15:00Z",""" +
					""""timeZone":"Europe/Zurich","rrule":"FREQ=WEEKLY;BYDAY=MO;COUNT=6"}""",
			)

		val next =
			api
				.patchEventOk(series.id(), """{"title":"Standup (new room)"}""", "?scope=FOLLOWING&occurrence=2026-11-16T08:00:00Z")
				.id()

		next shouldNotBe series.id()
		val old = get("${series.id()}.ics").body().replace("\r\n ", "")
		old shouldContain "SUMMARY:Standup\r\n"
		old shouldContain "UNTIL="
		old shouldNotContain "COUNT=6"
		val continued = get("$next.ics").body().replace("\r\n ", "")
		continued shouldContain "SUMMARY:Standup (new room)"
		continued shouldContain "DTSTART;TZID=Europe/Zurich:20261116T090000"
		continued shouldContain "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4"

		// Pulled back from the server, the six Mondays are unchanged: two old, four new.
		syncOk()
		titles() shouldContainExactly
			listOf("Standup", "Standup", "Standup (new room)", "Standup (new room)", "Standup (new room)", "Standup (new room)")
		events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId(), next).shouldNotBeNull()
	}

	private fun titles(): List<String> =
		api.occurrences("2026-11-01T00:00:00Z", "2027-01-01T00:00:00Z", "calendarIds" to calendarId().toString()).map {
			it.get("title").asString()
		}

	private fun put(
		name: String,
		ics: String,
	) {
		val response = dav("PUT", "$COLLECTION$name", ics, "text/calendar; charset=utf-8")
		(response.statusCode() in 200..299) shouldBe true
	}

	private fun get(name: String): HttpResponse<String> = dav("GET", "$COLLECTION$name")

	companion object {
		private const val USER = "alice"
		private const val PASSWORD = "fictitious-secret"
		private const val COLLECTION = "/$USER/demo/"
		private const val PORT = 5232

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
