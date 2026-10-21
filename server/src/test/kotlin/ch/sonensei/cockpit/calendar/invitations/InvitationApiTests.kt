package ch.sonensei.cockpit.calendar.invitations

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.changes
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.api.setTiming
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.Companion.CONNECTION
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.FixedTokens
import ch.sonensei.cockpit.calendar.invitations.GoogleInvitationAdapterTests.Companion.invitation
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * `POST /api/v1/calendar/events/{id}/respond` with the real [ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapter]
 * against WireMock playing Google, persisting to Postgres. [DirtiesContext] closes this class's own context afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, InvitationApiTests.TokensConfiguration::class)
@WithCockpitUser
@RecordApplicationEvents
@DirtiesContext
class InvitationApiTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var source: CalendarSource

	@TestConfiguration(proxyBeanMethods = false)
	class TokensConfiguration {
		@Bean
		@Primary
		fun fixedTokens(): OAuthTokenProvider = FixedTokens()
	}

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		wireMock.resetAll()
		stub(get(urlPathEqualTo("$API/users/me/calendarList")), """{"items": [{"id": "primary", "summary": "Alex", "accessRole": "owner"}]}""")
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", absent()),
			"""{"timeZone": "Europe/Zurich", "nextSyncToken": "S1", "items": [${invitation("\"inv-1\"", "needsAction")}]}""",
		)
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction"))
		source =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.GOOGLE, "Google").apply { config = """{"connectionId": "$CONNECTION"}""" },
			)
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	private fun invitationEvent(): CalendarEvent {
		val calendar = calendars.findBySourceIdAndExternalId(source.id, "primary")!!
		return events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendar.id, "inv001")!!
	}

	private fun respond(
		id: Any,
		body: String,
		ifMatch: String?,
	) = mockMvc.post("/api/v1/calendar/events/$id/respond") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	@Test
	fun `accepting answers at Google and the event shows the user's attendee as accepted, with Google's new etag`() {
		val id = invitationEvent().id
		val before = api.event(id.toString())
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-2\"", "accepted"))

		val result =
			respond(id, """{"response":"ACCEPTED"}""", etag(before)).andExpect {
				status { isOk() }
				header { string(HttpHeaders.ETAG, "\"${before.get("version").asLong() + 1}\"") }
				jsonPath("$.attendees[1].email") { value("alex.example@example.test") }
				jsonPath("$.attendees[1].status") { value("ACCEPTED") }
			}

		api.read(result).id() shouldBe id.toString()
		val stored = invitationEvent()
		stored.attendees.single { it.email == "alex.example@example.test" }.status shouldBe AttendeeStatus.ACCEPTED
		stored.externalEtag shouldBe "\"inv-2\""
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/inv001"))
				.withHeader("If-Match", equalTo("\"inv-1\""))
				.withQueryParam("sendUpdates", equalTo("all")),
		)
	}

	@Test
	fun `declining and answering tentatively are stored as well`() {
		val id = invitationEvent().id
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-2\"", "declined"))
		respond(id, """{"response":"DECLINED"}""", "*").andExpect { status { isOk() } }
		invitationEvent().attendees[1].status shouldBe AttendeeStatus.DECLINED

		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-2\"", "declined"))
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-3\"", "tentative"))
		respond(id, """{"response":"TENTATIVE"}""", "*").andExpect { jsonPath("$.attendees[1].status") { value("TENTATIVE") } }
	}

	@Test
	fun `If-Match is required and checked`() {
		val id = invitationEvent().id
		respond(id, """{"response":"ACCEPTED"}""", null).isProblem(428)
		respond(id, """{"response":"ACCEPTED"}""", "\"999\"").isProblem(412)
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `a missing or unknown response is a bad request, an unknown event not found`() {
		val id = invitationEvent().id
		respond(id, """{}""", "*").isProblem(400)
		respond(id, """{"response":"NEEDS_ACTION"}""", "*").isProblem(400)
		respond(UUID.randomUUID(), """{"response":"ACCEPTED"}""", "*").isProblem(404)
	}

	@Test
	fun `events of local calendars cannot be answered`() {
		val calendar = api.createCalendar()
		val event =
			api.createEvent(
				"""{"calendarId":"${calendar.id()}","title":"Lunch","start":"2026-10-28T11:00:00Z","end":"2026-10-28T12:00:00Z"}""",
			)

		respond(event.id(), """{"response":"ACCEPTED"}""", etag(event)).isProblem(409).andExpect {
			jsonPath("$.detail") { value(containsString("Google and Microsoft")) }
		}
	}

	@Test
	fun `an event the user is not invited to is a conflict and stays unchanged`() {
		val id = invitationEvent().id
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction").replace("\"self\": true, ", ""))

		respond(id, """{"response":"ACCEPTED"}""", "*").isProblem(409).andExpect {
			jsonPath("$.detail") { value("You are not invited to this event") }
		}
		invitationEvent().attendees[1].status shouldBe AttendeeStatus.NEEDS_ACTION
	}

	@Test
	fun `412 from Google is a conflict and the cockpit fetches Google's new version`() {
		val id = invitationEvent().id
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), "{}", 412)
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("S1")),
			"""{"nextSyncToken": "S2", "items": [${invitation("\"inv-5\"", "declined")}]}""",
		)

		respond(id, """{"response":"ACCEPTED"}""", "*").isProblem(409).andExpect {
			jsonPath("$.detail") { value(containsString("changed remotely")) }
		}
		invitationEvent().externalEtag shouldBe "\"inv-5\""
		invitationEvent().attendees[1].status shouldBe AttendeeStatus.DECLINED
	}

	@Test
	fun `a failing Google is a bad gateway and nothing changes`() {
		val id = invitationEvent().id
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), "{}", 503)

		respond(id, """{"response":"ACCEPTED"}""", "*").isProblem(502)
		invitationEvent().attendees[1].status shouldBe AttendeeStatus.NEEDS_ACTION
		invitationEvent().externalEtag shouldBe "\"inv-1\""
	}

	@Test
	fun `answering announces the changed event, a failed answer announces nothing`(published: ApplicationEvents) {
		val id = invitationEvent().id
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), "{}", 503)
		published.clear()

		respond(id, """{"response":"ACCEPTED"}""", "*").isProblem(502)
		published.changes() shouldBe emptyList()

		stub(patch(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-2\"", "accepted"))
		respond(id, """{"response":"ACCEPTED"}""", "*").andExpect { status { isOk() } }
		published.changes() shouldContainExactly listOf(ChangeOp.UPDATED to id.toString())
	}

	@Test
	fun `events of read-only or not yet synced calendars, and events not synced yet, cannot be answered`() {
		val event = invitationEvent()
		val calendar = calendars.findById(event.calendarId).orElseThrow()

		calendars.saveAndFlush(calendar.apply { readOnly = true })
		respond(event.id, """{"response":"ACCEPTED"}""", "*").isProblem(409).andExpect {
			jsonPath("$.detail") { value("The calendar is read-only") }
		}

		calendars.saveAndFlush(
			calendars.findById(calendar.id).orElseThrow().apply {
				readOnly = false
				externalId = null
			},
		)
		respond(event.id, """{"response":"ACCEPTED"}""", "*").isProblem(409).andExpect {
			jsonPath("$.detail") { value("The calendar is not synced yet") }
		}

		calendars.saveAndFlush(calendars.findById(calendar.id).orElseThrow().apply { externalId = "primary" })
		events.saveAndFlush(invitationEvent().apply { externalId = null })
		respond(event.id, """{"response":"ACCEPTED"}""", "*").isProblem(409).andExpect {
			jsonPath("$.detail") { value("The event is not synced yet") }
		}

		wireMock.verify(0, getRequestedFor(urlPathEqualTo("$EVENTS/inv001")))
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `events of a provider whose adapter does not answer invitations (CalDAV) cannot be answered`() {
		val caldav = sources.saveAndFlush(CalendarSource(CalendarSourceType.CALDAV, "Work CalDAV").apply { config = "{}" })
		val calendar = calendars.saveAndFlush(Calendar(caldav.id, "Work").apply { externalId = "/calendars/work/" })
		val event =
			events.saveAndFlush(
				CalendarEvent(calendar.id, "Planning").apply {
					setTiming(Timing.Timed(Instant.parse("2026-10-28T09:00:00Z"), Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC))
					externalId = "planning-1@caldav.example.test"
				},
			)

		respond(event.id, """{"response":"ACCEPTED"}""", "*").isProblem(409).andExpect {
			jsonPath("$.detail") { value("Invitations can only be answered for events of Google and Microsoft calendars") }
		}
	}

	@Test
	fun `an event in the Trash is not found and Google is not asked`() {
		val event = invitationEvent()
		events.saveAndFlush(event.apply { softDelete(Clock.systemUTC()) })

		respond(event.id, """{"response":"ACCEPTED"}""", "*").isProblem(404)
		wireMock.verify(0, getRequestedFor(urlPathEqualTo("$EVENTS/inv001")))
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `an override is answered for its occurrence only, through Google's instance`() {
		syncSeries()
		val (master, override) = series()
		stub(get(urlPathEqualTo("$EVENTS/$INSTANCE")), seriesException("\"x-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/$INSTANCE")), seriesException("\"x-2\"", "declined"))

		respond(override.id, """{"response":"DECLINED"}""", "*").andExpect {
			status { isOk() }
			jsonPath("$.id") { value(override.id.toString()) }
			jsonPath("$.attendees[1].status") { value("DECLINED") }
		}

		val (masterAfter, overrideAfter) = series()
		overrideAfter.attendees[1].status shouldBe AttendeeStatus.DECLINED
		overrideAfter.externalEtag shouldBe "\"x-2\""
		masterAfter.attendees[1].status shouldBe AttendeeStatus.NEEDS_ACTION
		masterAfter.externalEtag shouldBe master.externalEtag
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/$INSTANCE"))
				.withHeader("If-Match", equalTo("\"x-1\""))
				.withQueryParam("sendUpdates", equalTo("all")),
		)
		wireMock.verify(0, patchRequestedFor(urlPathEqualTo("$EVENTS/series01")))
		// Not a series: the calendar is not fetched again.
		wireMock.verify(0, getRequestedFor(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("S2")))
	}

	@Test
	fun `answering a series answers it at Google and fetches the calendar again, so its override shows the answer too`() {
		syncSeries()
		val (master, _) = series()
		stub(get(urlPathEqualTo("$EVENTS/series01")), seriesMaster("\"s-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/series01")), seriesMaster("\"s-2\"", "accepted"))
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("S2")),
			"""{"nextSyncToken": "S3", "items": [${seriesMaster("\"s-2\"", "accepted")}, ${seriesException("\"x-2\"", "accepted")}]}""",
		)

		respond(master.id, """{"response":"ACCEPTED"}""", "*").andExpect {
			status { isOk() }
			jsonPath("$.attendees[1].status") { value("ACCEPTED") }
		}

		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/series01")).withHeader("If-Match", equalTo("\"s-1\"")))
		wireMock.verify(getRequestedFor(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("S2")))
		val (masterAfter, overrideAfter) = series()
		masterAfter.attendees[1].status shouldBe AttendeeStatus.ACCEPTED
		masterAfter.externalEtag shouldBe "\"s-2\""
		overrideAfter.attendees[1].status shouldBe AttendeeStatus.ACCEPTED
	}

	@Test
	fun `declining a series that the refetch then removes returns the answer as saved, and the series is gone`() {
		syncSeries()
		val (master, override) = series()
		stub(get(urlPathEqualTo("$EVENTS/series01")), seriesMaster("\"s-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/series01")), seriesMaster("\"s-2\"", "declined"))
		// Google drops the declined series from the user's calendar: the refetch lists it as cancelled.
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("S2")),
			"""{"nextSyncToken": "S3", "items": [{"etag": "\"s-3\"", "id": "series01", "status": "cancelled"}]}""",
		)

		respond(master.id, """{"response":"DECLINED"}""", "*").andExpect {
			status { isOk() }
			jsonPath("$.id") { value(master.id.toString()) }
			jsonPath("$.attendees[1].status") { value("DECLINED") }
		}

		wireMock.verify(getRequestedFor(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("S2")))
		events.findById(master.id).isPresent shouldBe false
		events.findById(override.id).isPresent shouldBe false
	}

	@Test
	fun `an answer Google returns as an unstorable event is a bad gateway and nothing changes`(published: ApplicationEvents) {
		val id = invitationEvent().id
		// A rule without FREQ is not a valid RRULE: the local copy cannot take Google's version.
		stub(
			patch(urlPathEqualTo("$EVENTS/inv001")),
			invitation("\"inv-2\"", "accepted").replace("\"iCalUID\"", "\"recurrence\": [\"RRULE:INTERVAL=2\"], \"iCalUID\""),
		)
		published.clear()

		respond(id, """{"response":"ACCEPTED"}""", "*").isProblem(502).andExpect {
			jsonPath("$.detail") { value(containsString("invalid event")) }
		}

		val stored = invitationEvent()
		stored.attendees[1].status shouldBe AttendeeStatus.NEEDS_ACTION
		stored.externalEtag shouldBe "\"inv-1\""
		stored.rrule.shouldBeNull()
		published.changes() shouldBe emptyList()
	}

	@Test
	fun `an override of a series in the Trash is not found`() {
		syncSeries()
		val (master, override) = series()
		events.saveAndFlush(master.apply { softDelete(Clock.systemUTC()) })

		respond(override.id, """{"response":"ACCEPTED"}""", "*").isProblem(404)
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	/** Syncs Sam's weekly series `series01` with Alex invited, and its moved occurrence of 9 November, incrementally (S1 -> S2). */
	private fun syncSeries() {
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("S1")),
			"""{"nextSyncToken": "S2", "items": [${seriesMaster("\"s-1\"", "needsAction")}, ${seriesException("\"x-1\"", "needsAction")}]}""",
		)
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("iCalUID", equalTo("series01@google.com")), """{"items": []}""")
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	/** The series master `series01` and its one override, as stored. */
	private fun series(): Pair<CalendarEvent, CalendarEvent> {
		val calendar = calendars.findBySourceIdAndExternalId(source.id, "primary")!!
		val master = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendar.id, "series01")!!
		return master to events.findByParentEventIdOrderByIdAsc(master.id).single()
	}

	private fun stub(
		mapping: MappingBuilder,
		body: String,
		status: Int = 200,
	) = wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))

	companion object {
		const val API = "/calendar/v3"
		const val EVENTS = "$API/calendars/primary/events"

		/** Google's instance id of the occurrence of `series01` on 9 November 2026, 09:00 in Zurich. */
		const val INSTANCE = "series01_20261109T080000Z"

		private const val ATTENDEES = """
			"organizer": {"email": "sam.example@example.test", "displayName": "Sam Example"},
			"attendees": [
			  {"email": "sam.example@example.test", "displayName": "Sam Example", "organizer": true, "responseStatus": "accepted"},
			  {"email": "alex.example@example.test", "self": true, "responseStatus": "STATUS"}]"""

		/** Sam's weekly series (three Mondays from 2 November 2026) at version [etag], Alex's answer being [status]. */
		fun seriesMaster(
			etag: String,
			status: String,
		) = """{"etag": ${quoted(etag)}, "id": "series01", "status": "confirmed", "summary": "Weekly sync",
			"recurrence": ["RRULE:FREQ=WEEKLY;COUNT=3"], "iCalUID": "series01@google.com",
			"start": {"dateTime": "2026-11-02T09:00:00+01:00", "timeZone": "Europe/Zurich"},
			"end": {"dateTime": "2026-11-02T10:00:00+01:00", "timeZone": "Europe/Zurich"},
			${ATTENDEES.replace("STATUS", status)}}"""

		/** The series' occurrence of 9 November, moved an hour later, at version [etag], Alex's answer being [status]. */
		fun seriesException(
			etag: String,
			status: String,
		) = """{"etag": ${quoted(etag)}, "id": "$INSTANCE", "status": "confirmed", "summary": "Weekly sync (moved)",
			"recurringEventId": "series01", "originalStartTime": {"dateTime": "2026-11-09T09:00:00+01:00", "timeZone": "Europe/Zurich"},
			"iCalUID": "series01@google.com",
			"start": {"dateTime": "2026-11-09T10:00:00+01:00", "timeZone": "Europe/Zurich"},
			"end": {"dateTime": "2026-11-09T11:00:00+01:00", "timeZone": "Europe/Zurich"},
			${ATTENDEES.replace("STATUS", status)}}"""

		private fun quoted(etag: String) = "\"" + etag.replace("\"", "\\\"") + "\""

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		@JvmStatic
		@DynamicPropertySource
		fun googleApi(registry: DynamicPropertyRegistry) {
			registry.add("cockpit.calendar.google.api-url") { wireMock.baseUrl() + API }
		}
	}
}
