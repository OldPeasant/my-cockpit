package ch.sonensei.cockpit.calendar.backup

import ch.sonensei.cockpit.backup.RestoreTestDatabases
import ch.sonensei.cockpit.backup.Zips
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.api.EventChanges
import ch.sonensei.cockpit.calendar.api.FieldChange
import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter.Companion.remote
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapterConfiguration
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.web.ConflictException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.util.CompatibilityHints
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import net.fortuna.ical4j.model.Calendar as IcsCalendar

/**
 * [CalendarBackupContributor] (SPEC.md §10.2): `data/calendar_sources.json` without credentials (those only in the
 * encrypted secrets), `data/calendars.json`, `data/events.jsonl` (LOCAL events fully, external ones as id refs), the ICS
 * mirror `readable/calendar/` (parsed back strictly with ical4j), and the restore: rows with their ids and versions,
 * search documents, credentials, and placeholders that the first sync fills in.
 *
 * Rows are inserted with JDBC so ids, times and versions are under the test's control. Runs against a database of its
 * own, because every test empties all tables.
 */
@SpringBootTest
@Import(FakeCalendarAdapterConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class CalendarBackupContributorTests(
	@Autowired private val backups: BackupService,
	@Autowired private val contributor: CalendarBackupContributor,
	@Autowired private val eventService: CalendarEventService,
	@Autowired private val cipher: SecretCipher,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val fake: FakeCalendarAdapter,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
) {
	@TempDir lateinit var work: Path

	@BeforeEach
	fun wipe() {
		RestoreTestDatabases.wipe(jdbc, dataDir)
		fake.reset()
	}

	// --- data/calendar_sources.json, data/calendars.json ------------------------------------------------------------

	@Test
	fun `sources json holds every source with its config but no credentials, calendars json every calendar`() {
		seedLocal()
		seedExternal()

		val exported = export()
		val sources = exported.json("data/calendar_sources.json")
		val calendars = exported.json("data/calendars.json")

		sources.map { it["id"].asString() } shouldContainExactly listOf(LOCAL.toString(), CALDAV.toString())
		sources[1]["type"].asString() shouldBe "CALDAV"
		sources[1]["name"].asString() shouldBe "Work CalDAV"
		sources[1]["config"]["url"].asString() shouldBe "https://caldav.example.test/dav/"
		sources[1]["config"]["username"].asString() shouldBe "owner"
		sources[1]["version"].asLong() shouldBe 2
		sources[1]["createdAt"].asString() shouldBe T0.toString()
		sources.forEach { it.has("credentials") shouldBe false }
		calendars.map { it["id"].asString() } shouldContainExactly listOf(HOME, SPORT, WORK).map { it.toString() }
		calendars[2]["sourceId"].asString() shouldBe CALDAV.toString()
		calendars[2]["externalId"].asString() shouldBe "/dav/work/"
		calendars[2]["externalEtag"].asString() shouldBe "\"cal-etag\""
		calendars[0]["isDefault"].asBoolean() shouldBe true
		calendars[0]["color"].asString() shouldBe "#3366ff"
		exported.entryNames.filter { it.startsWith("data/secrets") }.shouldBeEmpty()
		exported.texts().forEach { (name, text) ->
			withClue(name) { text shouldNotContain PASSWORD }
		}
	}

	@Test
	fun `with a passphrase the credentials are only in the encrypted secrets, neither in plain text nor as stored`() {
		seedLocal()
		seedExternal()
		val stored = jdbc.queryForObject("select credentials from calendar_source where id = ?", String::class.java, CALDAV)!!

		val exported = export(PASSPHRASE)

		exported.entryNames shouldContain "data/secrets.enc.json"
		exported.texts().forEach { (name, text) ->
			withClue(name) {
				text shouldNotContain PASSWORD
				text shouldNotContain stored
			}
		}
		exported.json("data/calendar_sources.json").forEach { it.has("credentials") shouldBe false }
	}

	@Test
	fun `an install without calendar sources writes no calendar data and no mirror`() {
		val exported = export()

		exported.entryNames.filter { it.startsWith("data/calendar") || it == "data/events.jsonl" }.shouldBeEmpty()
		exported.entryNames.filter { it.startsWith("readable/calendar/") }.shouldBeEmpty()
	}

	// --- data/events.jsonl ------------------------------------------------------------------------------------------

	@Test
	fun `local events are complete, with Trash, EXDATEs, overrides, attendees and reminders, masters before overrides`() {
		seedLocal()

		val events = export().lines("data/events.jsonl")
		val byId = events.associateBy { UUID.fromString(it["id"].asString()) }

		val firstOverride = events.indexOfFirst { !it["parentEventId"].isNull }
		events.drop(firstOverride).forEach { it["parentEventId"].isNull shouldBe false }
		events.map { it["id"].asString() } shouldContainExactlyInAnyOrder (20..28).map { id(it).toString() }

		val series = byId.getValue(id(20))
		series["calendarId"].asString() shouldBe HOME.toString()
		val content = series["content"]
		content["title"].asString() shouldBe "Weekly planning"
		content["description"].asString() shouldBe "Plan the week\nwith coffee"
		content["rrule"].asString() shouldBe "FREQ=WEEKLY;COUNT=10"
		content["startAt"].asString() shouldBe "2026-03-02T08:00:00Z"
		content["timeZone"].asString() shouldBe ZURICH
		content["version"].asLong() shouldBe 4
		content["createdAt"].asString() shouldBe T0.toString()
		content["updatedAt"].asString() shouldBe T1.toString()
		content["reminderMinutes"].values().map { it.asInt() } shouldContainExactly listOf(10, 0)
		content["exdates"].values().map { it["at"].asString() } shouldContainExactly listOf("2026-03-16T08:00:00Z")
		content["syncState"].asString() shouldBe "LOCAL"

		val late = byId.getValue(id(21))
		late["parentEventId"].asString() shouldBe id(20).toString()
		late["recurrenceIdAt"].asString() shouldBe "2026-03-09T08:00:00Z"
		late["content"]["location"].asString() shouldBe "Room B"
		byId.getValue(id(22))["content"]["status"].asString() shouldBe "CANCELLED"

		val run = byId.getValue(id(23))["content"]
		run["allDay"].asBoolean() shouldBe true
		run["startDate"].asString() shouldBe "2026-03-07"
		run["endDate"].asString() shouldBe "2026-03-08"
		run["transparency"].asString() shouldBe "TRANSPARENT"
		run["exdates"].values().map { it["date"].asString() } shouldContainExactly listOf("2026-03-21")
		byId.getValue(id(24))["recurrenceIdDate"].asString() shouldBe "2026-03-14"

		val dinner = byId.getValue(id(25))["content"]
		dinner["attendees"].values().map { it["email"].asString() } shouldContainExactly
			listOf("alex@example.test", "sam@example.test")
		dinner["attendees"][0]["name"].asString() shouldBe "Alex Example"
		dinner["attendees"][0]["status"].asString() shouldBe "ACCEPTED"
		dinner["conferenceUrl"].asString() shouldBe "https://meet.example.test/dinner"
		dinner["status"].asString() shouldBe "TENTATIVE"
		dinner["exdates"].size() shouldBe 0

		byId.getValue(id(26))["content"]["deletedAt"].asString() shouldBe T2.toString()
		byId.getValue(id(27))["content"]["deletedAt"].asString() shouldBe T2.toString()
		byId.getValue(id(28))["content"]["title"].asString() shouldBe "Trashed series (moved)"
	}

	@Test
	fun `events of external sources are only id refs, without title or other content`() {
		seedExternal()

		val events = export().lines("data/events.jsonl")
		val byId = events.associateBy { UUID.fromString(it["id"].asString()) }

		byId.keys shouldBe (30..34).map(::id).toSet()
		events.forEach { it["content"].isNull shouldBe true }
		byId.getValue(id(30))["externalId"].asString() shouldBe "series-1@caldav.example.test"
		byId.getValue(id(30))["calendarId"].asString() shouldBe WORK.toString()
		byId.getValue(id(31))["parentEventId"].asString() shouldBe id(30).toString()
		byId.getValue(id(31))["externalId"].asString() shouldBe "series-1@caldav.example.test"
		byId.getValue(id(31))["recurrenceIdAt"].asString() shouldBe "2026-03-04T08:00:00Z"
		byId.getValue(id(33))["recurrenceIdDate"].asString() shouldBe "2026-04-14"
		val text = events.joinToString("\n") { it.toString() }
		listOf("Team sync", "Confidential agenda", "Offsite", "etag-").forEach { text shouldNotContain it }
	}

	// --- readable/calendar/ -----------------------------------------------------------------------------------------

	@Test
	fun `each local calendar is an ICS file that ical4j parses strictly, with series, overrides and singles not in the Trash`() {
		seedLocal()
		seedExternal()

		val files = export().readableCalendars()

		files.keys shouldBe setOf("Home _ Family.ics", "Sport.ics")
		CompatibilityHints.isHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING) shouldBe false
		val home = parse(files.getValue("Home _ Family.ics"))
		home.validate().hasErrors() shouldBe false
		val homeEvents = home.getComponents<VEvent>(Component.VEVENT)
		homeEvents.map { it.text(Property.UID) to it.text(Property.RECURRENCE_ID) } shouldContainExactlyInAnyOrder
			listOf(
				id(20).toString() to null,
				id(20).toString() to "20260309T090000",
				id(20).toString() to "20260323T090000",
				id(25).toString() to null,
			)
		val series = homeEvents.single { it.text(Property.UID) == id(20).toString() && it.text(Property.RECURRENCE_ID) == null }
		series.text(Property.SUMMARY) shouldBe "Weekly planning"
		series.text(Property.RRULE) shouldBe "FREQ=WEEKLY;COUNT=10"
		series.text(Property.DTSTART) shouldBe "20260302T090000"
		series.texts(Property.EXDATE) shouldContainExactly listOf("20260316T090000")
		series.alarms shouldHaveSize 2
		home.getComponents<VTimeZone>(Component.VTIMEZONE).map { it.text(Property.TZID) } shouldContainExactly listOf(ZURICH)
		val late = homeEvents.single { it.text(Property.RECURRENCE_ID) == "20260309T090000" }
		late.text(Property.SUMMARY) shouldBe "Weekly planning (late)"
		late.text(Property.LOCATION) shouldBe "Room B"
		homeEvents.single { it.text(Property.RECURRENCE_ID) == "20260323T090000" }.text(Property.STATUS) shouldBe "CANCELLED"
		val dinner = homeEvents.single { it.text(Property.UID) == id(25).toString() }
		dinner.text(Property.SUMMARY) shouldBe "Dinner with Alex; bring \"wine\""
		dinner.texts(Property.ATTENDEE) shouldContainExactlyInAnyOrder listOf("mailto:alex@example.test", "mailto:sam@example.test")
		dinner.text(Property.STATUS) shouldBe "TENTATIVE"
		val ics = files.getValue("Home _ Family.ics")
		listOf("Cancelled appointment", "Trashed series", id(26).toString(), id(27).toString()).forEach { ics shouldNotContain it }

		val sport = parse(files.getValue("Sport.ics"))
		sport.validate().hasErrors() shouldBe false
		val sportEvents = sport.getComponents<VEvent>(Component.VEVENT)
		sportEvents.map { it.text(Property.RECURRENCE_ID) } shouldContainExactlyInAnyOrder listOf(null, "20260314")
		val run = sportEvents.single { it.text(Property.RECURRENCE_ID) == null }
		run.text(Property.UID) shouldBe id(23).toString()
		run.text(Property.DTSTART) shouldBe "20260307"
		run.texts(Property.EXDATE) shouldContainExactly listOf("20260321")
		run.text(Property.TRANSP) shouldBe "TRANSPARENT"
		sportEvents.single { it.text(Property.RECURRENCE_ID) == "20260314" }.text(Property.SUMMARY) shouldBe "Long run (hills)"
	}

	@Test
	fun `ICS keeps UTC and zero-length events and escaped multi-line text readable for ical4j`() {
		source(LOCAL, "LOCAL", "Local")
		calendar(HOME, LOCAL, "Home")
		event(id(20), HOME, "Notes, ideas; and \\ more", description = "Line 1\nLine 2, with; separators", location = "Café «Zürich»")
		jdbc.update(
			"update calendar_event set time_zone = 'UTC', end_at = start_at where id = ?",
			id(20),
		)

		val ics = export().readableCalendars().getValue("Home.ics")
		val calendar = parse(ics)

		calendar.validate().hasErrors() shouldBe false
		val event = calendar.getComponents<VEvent>(Component.VEVENT).single()
		event.text(Property.SUMMARY) shouldBe "Notes, ideas; and \\ more"
		event.text(Property.DESCRIPTION) shouldBe "Line 1\nLine 2, with; separators"
		event.text(Property.LOCATION) shouldBe "Café «Zürich»"
		event.text(Property.DTSTART) shouldBe "20260302T080000Z"
		event.text(Property.DTEND) shouldBe "20260302T080000Z"
		calendar.getComponents<VTimeZone>(Component.VTIMEZONE).shouldBeEmpty()
	}

	@Test
	fun `file names replace reserved characters and number clashes case-insensitively, so no calendar is overwritten`() {
		source(LOCAL, "LOCAL", "Local")
		val names = listOf("A/B", "A:B", "a_b", "A_B (2)", "...", "Tab\there", "Empty")
		names.forEachIndexed { i, name -> calendar(id(40 + i), LOCAL, name) }
		names.forEachIndexed { i, name -> event(id(60 + i), id(40 + i), "Event of $name") }

		val files = export().readableCalendars()

		files.keys shouldBe
			setOf(
				"A_B.ics",
				"A_B (2).ics",
				"a_b (3).ics",
				"A_B (2) (2).ics",
				"calendar.ics",
				"Tab_here.ics",
				"Empty.ics",
			)
		val titles = files.mapValues { (_, text) -> parse(text).getComponents<VEvent>(Component.VEVENT).map { it.text(Property.SUMMARY) } }
		titles shouldBe
			mapOf(
				"A_B.ics" to listOf("Event of A/B"),
				"A_B (2).ics" to listOf("Event of A:B"),
				"a_b (3).ics" to listOf("Event of a_b"),
				"A_B (2) (2).ics" to listOf("Event of A_B (2)"),
				"calendar.ics" to listOf("Event of ..."),
				"Tab_here.ics" to listOf("Event of Tab\there"),
				"Empty.ics" to listOf("Event of Empty"),
			)
	}

	@Test
	fun `every mirrored ICS file passes ical4j validation, also for local calendars without events or with only Trash`() {
		source(LOCAL, "LOCAL", "Local")
		calendar(HOME, LOCAL, "Home")
		calendar(SPORT, LOCAL, "Nothing planned")
		calendar(id(13), LOCAL, "Only Trash")
		// Sorted before "Home" and without events: it must not take the name "Home.ics" away.
		calendar(id(14), LOCAL, "HOME")
		event(id(20), HOME, "Visible")
		event(id(21), id(13), "Gone", deletedAt = T2)
		event(id(22), id(13), "Trashed series", rrule = "FREQ=DAILY;COUNT=3", deletedAt = T2)
		event(id(23), id(13), "Override of the trashed series", parent = id(22), recurrenceAt = Instant.parse("2026-03-03T08:00:00Z"))

		val files = export().readableCalendars()

		// A VCALENDAR needs a component (RFC 5545 §3.6): calendars with nothing to show get no file.
		files.keys shouldBe setOf("Home.ics")
		files.forEach { (name, text) ->
			withClue(name) { parse(text).validate().hasErrors() shouldBe false }
		}
	}

	@Test
	fun `fileName keeps names usable on common systems and at most 100 characters`() {
		CalendarBackupContributor.fileName("Home / Family") shouldBe "Home _ Family"
		CalendarBackupContributor.fileName("""a\b:c*d?e"f<g>h|i""") shouldBe "a_b_c_d_e_f_g_h_i"
		CalendarBackupContributor.fileName("  .hidden  ") shouldBe "hidden"
		CalendarBackupContributor.fileName("..") shouldBe "calendar"
		CalendarBackupContributor.fileName("   ") shouldBe "calendar"
		CalendarBackupContributor.fileName("Sport «Zürich»") shouldBe "Sport «Zürich»"
		CalendarBackupContributor.fileName("x".repeat(100)) shouldBe "x".repeat(100)
		CalendarBackupContributor.fileName("y".repeat(101)) shouldBe "y".repeat(100)
		CalendarBackupContributor.fileName("z".repeat(99) + " tail") shouldBe "z".repeat(99)
	}

	// --- import -----------------------------------------------------------------------------------------------------

	@Test
	fun `import restores sources, calendars, local events and EXDATEs with their ids, times, versions and Trash state`() {
		seedLocal()
		seedExternal()
		val zip = export()
		val sources = rows("select to_jsonb(s) - 'credentials' from calendar_source s order by id")
		val calendars = rows("select to_jsonb(c) from calendar c order by id")
		val local = rows("select to_jsonb(e) from calendar_event e where calendar_id in (?, ?) order by id", HOME, SPORT)
		val exdates = rows("select to_jsonb(x) from calendar_event_exdate x order by event_id, occurrence_at, occurrence_date")
		wipe()

		import(zip.path)

		rows("select to_jsonb(s) - 'credentials' from calendar_source s order by id") shouldBe sources
		rows("select to_jsonb(c) from calendar c order by id") shouldBe calendars
		rows("select to_jsonb(e) from calendar_event e where calendar_id in (?, ?) order by id", HOME, SPORT) shouldBe local
		rows("select to_jsonb(x) from calendar_event_exdate x order by event_id, occurrence_at, occurrence_date") shouldBe exdates
	}

	@Test
	fun `import restores credentials with the passphrase and leaves them empty without, warning about external sources`() {
		seedLocal()
		seedExternal()
		val withSecrets = export(PASSPHRASE)

		wipe()
		import(withSecrets.path, PASSPHRASE)
		cipher.decrypt(credentials(CALDAV).shouldNotBeNull()) shouldBe PASSWORD
		credentials(LOCAL).shouldBeNull()

		wipe()
		import(withSecrets.path)
		credentials(CALDAV).shouldBeNull()
		jdbc.queryForObject("select count(*) from calendar_source", Int::class.java) shouldBe 2

		warnings(withSecrets) shouldContainExactly
			listOf(
				"The events of 1 external calendar source are fetched again with the next sync",
				"Without the backup's passphrase, 1 CalDAV calendar source must be reconnected (their passwords are in the secrets)",
			)
		warnings(export()) shouldContainExactly
			listOf(
				"The events of 1 external calendar source are fetched again with the next sync",
				"The backup has no calendar passwords: 1 CalDAV calendar source must be reconnected",
			)
	}

	@Test
	fun `restore warnings and the log count only CalDAV sources as missing their password, OAuth and feed sources need none`(
		output: CapturedOutput,
	) {
		source(id(3), "GOOGLE", "Google", config = """{"connectionId": "${id(90)}"}""")
		source(id(4), "MICROSOFT", "Microsoft", config = """{"connectionId": "${id(91)}"}""")
		source(id(5), "ICS_FEED", "Holidays", config = """{"url": "https://feeds.example.test/holidays.ics"}""")
		val oauthAndFeeds = export(PASSPHRASE)

		warnings(oauthAndFeeds) shouldContainExactly listOf("The events of 3 external calendar sources are fetched again with the next sync")
		wipe()
		import(oauthAndFeeds.path, PASSPHRASE)
		output.out shouldNotContain "without a password"

		source(CALDAV, "CALDAV", "Work CalDAV", credential = PASSWORD)
		source(id(6), "CALDAV", "Private CalDAV", credential = PASSWORD)
		val withCalDav = export()
		warnings(withCalDav) shouldContainExactly
			listOf(
				"The events of 5 external calendar sources are fetched again with the next sync",
				"The backup has no calendar passwords: 2 CalDAV calendar sources must be reconnected",
			)
		wipe()
		import(withCalDav.path)
		output.out shouldContain "Restored 2 CalDAV calendar sources without a password"
		output.out shouldNotContain PASSWORD
	}

	@Test
	fun `a backup with only local sources has no restore warnings`() {
		seedLocal()

		ZipBackupReader.open(export().path, backups.canonicalJson).use { contributor.restoreWarnings(it) }.shouldBeEmpty()
	}

	@Test
	fun `import restores external events as placeholders with their ids, external ids and recurrence ids`() {
		seedExternal()
		val zip = export()
		wipe()

		import(zip.path)

		val rows =
			jdbc
				.queryForList(
					"""select id, title, description, version, external_id, external_etag, sync_state, status, parent_event_id,
					all_day, start_at, start_date, end_date, recurrence_id_at, recurrence_id_date, rrule
					from calendar_event order by id""",
				).associateBy { it["id"] as UUID }
		rows.keys shouldBe (30..34).map(::id).toSet()
		rows.values.forEach {
			it["title"] shouldBe CalendarBackupContributor.PLACEHOLDER_TITLE
			it["description"].shouldBeNull()
			it["rrule"].shouldBeNull()
			it["version"] shouldBe 0L
			// Inert until the sync fills them in: hidden from the views, and no provider version matches the etag.
			it["status"] shouldBe "CANCELLED"
			it["external_etag"] shouldBe CalendarBackupContributor.PLACEHOLDER_ETAG
			it["sync_state"] shouldBe "SYNCED"
		}
		rows.getValue(id(30))["external_id"] shouldBe "series-1@caldav.example.test"
		(rows.getValue(id(30))["start_at"] as Timestamp).toInstant() shouldBe Instant.EPOCH
		rows.getValue(id(31))["parent_event_id"] shouldBe id(30)
		rows.getValue(id(31))["external_id"] shouldBe "series-1@caldav.example.test"
		(rows.getValue(id(31))["recurrence_id_at"] as Timestamp).toInstant() shouldBe Instant.parse("2026-03-04T08:00:00Z")
		(rows.getValue(id(31))["start_at"] as Timestamp).toInstant() shouldBe Instant.parse("2026-03-04T08:00:00Z")
		val allDay = rows.getValue(id(33))
		allDay["all_day"] shouldBe true
		allDay["parent_event_id"] shouldBe id(32)
		(allDay["recurrence_id_date"] as Date).toLocalDate() shouldBe LocalDate.parse("2026-04-14")
		(allDay["start_date"] as Date).toLocalDate() shouldBe LocalDate.parse("2026-04-14")
		(allDay["end_date"] as Date).toLocalDate() shouldBe LocalDate.parse("2026-04-15")
	}

	@Test
	fun `import indexes local events for search, but not placeholders, events in the Trash or cancelled occurrences`() {
		seedLocal()
		seedExternal()
		val zip = export()
		wipe()

		import(zip.path)

		jdbc.queryForList("select item_type, item_id, title, body from search_document order by item_id") shouldBe
			listOf(
				doc(id(20), "Weekly planning", "Plan the week\nwith coffee"),
				doc(id(21), "Weekly planning (late)", "Plan the week\nwith coffee\nRoom B"),
				doc(id(23), "Long run", ""),
				doc(id(24), "Long run (hills)", ""),
				doc(id(25), "Dinner with Alex; bring \"wine\"", "Rue du Lac 1, Lausanne"),
			)
	}

	// --- restore, then sync -----------------------------------------------------------------------------------------

	@Test
	fun `the first sync after a restore fills in placeholders with the same ids and removes the ones the provider no longer has`() {
		fake.addCalendar("/dav/work/", "Work")
		val second = OccurrenceId.of(Instant.parse("2026-03-09T08:00:00Z"))
		val third = OccurrenceId.of(Instant.parse("2026-03-16T08:00:00Z"))
		val saturday = OccurrenceId.of(LocalDate.parse("2026-03-14"))
		fake.put("/dav/work/", remote("weekly", "Weekly", start = "2026-03-02T08:00:00Z", rrule = "FREQ=WEEKLY;COUNT=5"))
		fake.put("/dav/work/", remote("weekly", "Weekly (late)", start = "2026-03-09T10:00:00Z").copy(key = RemoteEventKey("weekly", second)))
		fake.put("/dav/work/", remote("weekly", "Weekly (gone)", start = "2026-03-16T10:00:00Z").copy(key = RemoteEventKey("weekly", third)))
		fake.put("/dav/work/", allDay("runs", "Runs", "2026-03-07", rrule = "FREQ=WEEKLY"))
		fake.put("/dav/work/", allDay("runs", "Runs (hills)", "2026-03-14").copy(key = RemoteEventKey("runs", saturday)))
		fake.put("/dav/work/", remote("lunch", "Lunch"))
		fake.put("/dav/work/", remote("old", "Old meeting"))
		source(CALDAV, "CALDAV", "Work CalDAV", credential = PASSWORD)
		sync.sync(CALDAV)
		val before = eventIds()
		before.keys shouldBe
			setOf("weekly", "weekly@2026-03-09T08:00:00Z", "weekly@2026-03-16T08:00:00Z", "runs", "runs@2026-03-14", "lunch", "old")
		val zip = export(PASSPHRASE)
		// The provider keeps its events; only the install is emptied.
		RestoreTestDatabases.wipe(jdbc, dataDir)
		fake.put("/dav/work/", remote("lunch", "Lunch (moved)", start = "2026-03-02T11:00:00Z"))
		fake.remove("/dav/work/", RemoteEventKey("old"))
		fake.remove("/dav/work/", RemoteEventKey("weekly", third))
		import(zip.path, PASSPHRASE)
		eventIds() shouldBe before
		titles().values.toSet() shouldBe setOf(CalendarBackupContributor.PLACEHOLDER_TITLE)

		val status = sync.sync(CALDAV).status.shouldNotBeNull()
		status.lastError.shouldBeNull()

		eventIds() shouldBe before - setOf("old", "weekly@2026-03-16T08:00:00Z")
		titles() shouldBe
			mapOf(
				before.getValue("weekly") to "Weekly",
				before.getValue("weekly@2026-03-09T08:00:00Z") to "Weekly (late)",
				before.getValue("runs") to "Runs",
				before.getValue("runs@2026-03-14") to "Runs (hills)",
				before.getValue("lunch") to "Lunch (moved)",
			)
		jdbc.queryForObject("select rrule from calendar_event where id = ?", String::class.java, before.getValue("weekly")) shouldBe
			"FREQ=WEEKLY;COUNT=5"
		val lunchStart = jdbc.queryForObject("select start_at from calendar_event where id = ?", Timestamp::class.java, before.getValue("lunch"))
		lunchStart.shouldNotBeNull().toInstant() shouldBe Instant.parse("2026-03-02T11:00:00Z")
		jdbc
			.queryForList("select external_etag from calendar_event where external_etag is null", String::class.java)
			.shouldBeEmpty()
		jdbc.queryForList("select title from search_document order by title", String::class.java) shouldContainExactly
			listOf("Lunch (moved)", "Runs", "Runs (hills)", "Weekly", "Weekly (late)")
	}

	@Test
	fun `until a sync fills them in, placeholders are not shown and edits or deletes never overwrite the provider's events`() {
		fake.addCalendar("/dav/work/", "Work")
		val second = OccurrenceId.of(Instant.parse("2026-03-09T08:00:00Z"))
		fake.put("/dav/work/", remote("weekly", "Weekly", start = "2026-03-02T08:00:00Z", rrule = "FREQ=WEEKLY;COUNT=5"))
		fake.put("/dav/work/", remote("weekly", "Weekly (late)", start = "2026-03-09T10:00:00Z").copy(key = RemoteEventKey("weekly", second)))
		fake.put("/dav/work/", remote("lunch", "Lunch"))
		source(CALDAV, "CALDAV", "Work CalDAV", credential = PASSWORD)
		sync.sync(CALDAV)
		val ids = eventIds()
		val zip = export(PASSPHRASE)
		RestoreTestDatabases.wipe(jdbc, dataDir)
		import(zip.path, PASSPHRASE)
		// The provider is unreachable for now: neither a sync nor the refetch after a refused write fills them in.
		fake.failFetch["/dav/work/"] = CalendarProviderException("Server answered 503")
		fake.writes.clear()

		eventService.occurrences(Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"), null).shouldBeEmpty()

		val edit = EventChanges(title = FieldChange("Edited before the sync"))
		shouldThrow<ConflictException> { eventService.update(ids.getValue("lunch"), null, null, edit) {} }
		shouldThrow<ConflictException> { eventService.update(ids.getValue("weekly@2026-03-09T08:00:00Z"), null, null, edit) {} }
		shouldThrow<ConflictException> { eventService.delete(ids.getValue("lunch"), null, null) {} }
		shouldThrow<ConflictException> { eventService.delete(ids.getValue("weekly"), null, null) {} }

		val ifMatch = "if-match=${CalendarBackupContributor.PLACEHOLDER_ETAG}"
		fake.writes shouldContainExactly
			listOf("update lunch $ifMatch", "update weekly@2026-03-09T08:00:00Z $ifMatch", "delete lunch $ifMatch", "delete weekly $ifMatch")
		fake.events("/dav/work/").values.map { it.title } shouldContainExactlyInAnyOrder listOf("Weekly", "Weekly (late)", "Lunch")
		eventIds() shouldBe ids
		titles().values.toSet() shouldBe setOf(CalendarBackupContributor.PLACEHOLDER_TITLE)

		fake.failFetch.clear()
		sync
			.sync(CALDAV)
			.status
			.shouldNotBeNull()
			.lastError
			.shouldBeNull()
		eventService.update(ids.getValue("lunch"), null, null, edit) {}

		fake.event("/dav/work/", RemoteEventKey("lunch")).shouldNotBeNull().title shouldBe "Edited before the sync"
		eventService
			.occurrences(Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"), null)
			.map { it.event.title } shouldContain "Weekly (late)"
	}

	// --- helpers ----------------------------------------------------------------------------------------------------

	private fun warnings(exported: Exported): List<String> =
		ZipBackupReader.open(exported.path, backups.canonicalJson).use { contributor.restoreWarnings(it) }

	private class Exported(
		val path: Path,
		private val json: tools.jackson.databind.json.JsonMapper,
	) {
		private val entries: Map<String, ByteArray> = Zips.read(path.readBytes())

		val entryNames: Set<String> get() = entries.keys

		fun texts(): Map<String, String> = entries.mapValues { String(it.value, Charsets.UTF_8) }

		fun json(name: String): List<JsonNode> = json.readTree(entries.getValue(name)).values().toList()

		fun lines(name: String): List<JsonNode> =
			String(entries.getValue(name), Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { json.readTree(it) }

		fun readableCalendars(): Map<String, String> =
			entries
				.filterKeys { it.startsWith("readable/calendar/") }
				.map { (name, bytes) -> name.removePrefix("readable/calendar/") to String(bytes, Charsets.UTF_8) }
				.toMap()
	}

	private fun export(passphrase: String? = null): Exported {
		val target = Files.createTempFile(work, "backup-", ".zip")
		target.outputStream().use { backups.export(it, passphrase) }
		return Exported(target, backups.canonicalJson)
	}

	/** Runs only the calendar contributor's import, as restore does (one transaction, into an empty install). */
	private fun import(
		zip: Path,
		passphrase: String? = null,
	) = ZipBackupReader.open(zip, backups.canonicalJson, passphrase).use { reader ->
		transactions.executeWithoutResult { contributor.import(reader) }
	}

	/**
	 * Parses [ics] strictly with ical4j. Its builder registers each VTIMEZONE as a JVM-wide `ical4j~<uuid>` zone whose
	 * rules live only as long as the builder: once collected, `ZoneId.of` of that id fails, which breaks tests in the
	 * same JVM that go through every available zone (`CalDavEventMappingTests`). So the builders are kept.
	 */
	private fun parse(ics: String): IcsCalendar = CalendarBuilder().also { builders += it }.build(StringReader(ics))

	private fun Component.text(name: String): String? = getProperty<Property>(name).map { it.value }.orElse(null)

	private fun Component.texts(name: String): List<String> = getProperties<Property>(name).map { it.value }

	private fun allDay(
		uid: String,
		title: String,
		date: String,
		rrule: String? = null,
	) = RemoteEvent(
		RemoteEventKey(uid),
		etag = null,
		title = title,
		timing = Timing.AllDay(LocalDate.parse(date), LocalDate.parse(date).plusDays(1)),
		rrule = rrule,
	)

	/** Event ids by `<external id>[@<recurrence id>]`. */
	private fun eventIds(): Map<String, UUID> =
		jdbc
			.queryForList("select id, external_id, recurrence_id_at, recurrence_id_date from calendar_event")
			.associate { row ->
				val occurrence = (row["recurrence_id_at"] as Timestamp?)?.toInstant() ?: (row["recurrence_id_date"] as Date?)?.toLocalDate()
				(row["external_id"] as String) + (occurrence?.let { "@$it" } ?: "") to row["id"] as UUID
			}

	private fun titles(): Map<UUID, String> =
		jdbc.queryForList("select id, title from calendar_event").associate { it["id"] as UUID to it["title"] as String }

	private fun credentials(source: UUID): String? =
		jdbc.queryForObject("select credentials from calendar_source where id = ?", String::class.java, source)

	private fun doc(
		id: UUID,
		title: String,
		body: String,
	) = mapOf("item_type" to "EVENT", "item_id" to id, "title" to title, "body" to body)

	private fun rows(
		sql: String,
		vararg args: Any,
	): List<String?> = jdbc.queryForList(sql, String::class.java, *args)

	/**
	 * A LOCAL source with two calendars: a timed weekly series (EXDATE, a moved and a cancelled occurrence, reminders), an
	 * all-day series (EXDATE, a changed occurrence), a single event with attendees, an event in the Trash and a series in
	 * the Trash with an override.
	 */
	private fun seedLocal() {
		source(LOCAL, "LOCAL", "Local")
		calendar(HOME, LOCAL, "Home / Family", isDefault = true)
		calendar(SPORT, LOCAL, "Sport")
		event(
			id(20),
			HOME,
			"Weekly planning",
			start = Instant.parse("2026-03-02T08:00:00Z"),
			rrule = "FREQ=WEEKLY;COUNT=10",
			description = "Plan the week\nwith coffee",
			reminders = listOf(10, 0),
		)
		exdate(id(20), at = Instant.parse("2026-03-16T08:00:00Z"))
		event(
			id(21),
			HOME,
			"Weekly planning (late)",
			start = Instant.parse("2026-03-09T09:00:00Z"),
			parent = id(20),
			recurrenceAt = Instant.parse("2026-03-09T08:00:00Z"),
			description = "Plan the week\nwith coffee",
			location = "Room B",
		)
		event(
			id(22),
			HOME,
			"Weekly planning",
			start = Instant.parse("2026-03-23T08:00:00Z"),
			parent = id(20),
			recurrenceAt = Instant.parse("2026-03-23T08:00:00Z"),
			status = "CANCELLED",
		)
		event(id(23), SPORT, "Long run", allDay = LocalDate.parse("2026-03-07"), rrule = "FREQ=WEEKLY;BYDAY=SA", transparency = "TRANSPARENT")
		exdate(id(23), date = LocalDate.parse("2026-03-21"))
		val hills = LocalDate.parse("2026-03-14")
		event(id(24), SPORT, "Long run (hills)", allDay = hills, parent = id(23), recurrenceDate = hills)
		event(
			id(25),
			HOME,
			"Dinner with Alex; bring \"wine\"",
			start = Instant.parse("2026-04-01T17:30:00Z"),
			location = "Rue du Lac 1, Lausanne",
			attendees =
				"""[{"email": "alex@example.test", "name": "Alex Example", "status": "ACCEPTED"},
				{"email": "sam@example.test", "name": null, "status": "NEEDS_ACTION"}]""",
			conferenceUrl = "https://meet.example.test/dinner",
			status = "TENTATIVE",
		)
		event(id(26), HOME, "Cancelled appointment", allDay = LocalDate.parse("2026-06-01"), deletedAt = T2)
		event(id(27), HOME, "Trashed series", start = Instant.parse("2026-05-04T08:00:00Z"), rrule = "FREQ=DAILY;COUNT=3", deletedAt = T2)
		event(
			id(28),
			HOME,
			"Trashed series (moved)",
			start = Instant.parse("2026-05-05T10:00:00Z"),
			parent = id(27),
			recurrenceAt = Instant.parse("2026-05-05T08:00:00Z"),
		)
	}

	/** A CalDAV source with credentials, its calendar, a timed series with an override, an all-day one, and a single event. */
	private fun seedExternal() {
		source(
			CALDAV,
			"CALDAV",
			"Work CalDAV",
			config = """{"url": "https://caldav.example.test/dav/", "username": "owner"}""",
			credential = PASSWORD,
		)
		calendar(WORK, CALDAV, "Work", externalId = "/dav/work/")
		val synced = { n: Int -> "\"etag-$n\"" }
		event(
			id(30),
			WORK,
			"Team sync",
			start = Instant.parse("2026-03-03T08:00:00Z"),
			rrule = "FREQ=DAILY;COUNT=5",
			description = "Confidential agenda",
			externalId = "series-1@caldav.example.test",
			externalEtag = synced(1),
			syncState = "SYNCED",
		)
		event(
			id(31),
			WORK,
			"Team sync (moved)",
			start = Instant.parse("2026-03-04T09:00:00Z"),
			parent = id(30),
			recurrenceAt = Instant.parse("2026-03-04T08:00:00Z"),
			externalId = "series-1@caldav.example.test",
			externalEtag = synced(1),
			syncState = "SYNCED",
		)
		event(
			id(32),
			WORK,
			"Offsite days",
			allDay = LocalDate.parse("2026-04-07"),
			rrule = "FREQ=WEEKLY;COUNT=3",
			externalId = "offsite-days@caldav.example.test",
			externalEtag = synced(2),
			syncState = "SYNCED",
		)
		event(
			id(33),
			WORK,
			"Offsite days (shifted)",
			allDay = LocalDate.parse("2026-04-15"),
			parent = id(32),
			recurrenceDate = LocalDate.parse("2026-04-14"),
			externalId = "offsite-days@caldav.example.test",
			externalEtag = synced(2),
			syncState = "SYNCED",
		)
		event(id(34), WORK, "Offsite", allDay = LocalDate.parse("2026-04-10"), externalId = "offsite@caldav.example.test", syncState = "PENDING")
	}

	private fun source(
		id: UUID,
		type: String,
		name: String,
		config: String = "{}",
		credential: String? = null,
	) {
		jdbc.update(
			"""insert into calendar_source (id, created_at, updated_at, version, type, name, config, credentials)
			values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			2,
			type,
			name,
			config,
			credential?.let(cipher::encrypt),
		)
	}

	private fun calendar(
		id: UUID,
		source: UUID,
		name: String,
		externalId: String? = null,
		isDefault: Boolean = false,
	) {
		jdbc.update(
			"""insert into calendar (id, created_at, updated_at, version, source_id, name, color, visible, read_only, is_default,
			external_id, external_etag) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			1,
			source,
			name,
			"#3366ff",
			true,
			false,
			isDefault,
			externalId,
			externalId?.let { "\"cal-etag\"" },
		)
	}

	private fun event(
		id: UUID,
		calendar: UUID,
		title: String,
		start: Instant? = Instant.parse("2026-03-02T08:00:00Z"),
		allDay: LocalDate? = null,
		rrule: String? = null,
		parent: UUID? = null,
		recurrenceAt: Instant? = null,
		recurrenceDate: LocalDate? = null,
		deletedAt: Instant? = null,
		description: String? = null,
		location: String? = null,
		attendees: String = "[]",
		reminders: List<Int> = emptyList(),
		status: String = "CONFIRMED",
		transparency: String = "OPAQUE",
		conferenceUrl: String? = null,
		externalId: String? = null,
		externalEtag: String? = null,
		syncState: String = "LOCAL",
	) {
		val timed = allDay == null
		jdbc.update(
			"""insert into calendar_event
			(id, created_at, updated_at, version, deleted_at, calendar_id, title, description, location, all_day, start_at, end_at,
			time_zone, start_date, end_date, rrule, parent_event_id, recurrence_id_at, recurrence_id_date, attendees,
			reminder_minutes, status, transparency, conference_url, external_id, external_etag, sync_state)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)""",
		) { ps ->
			var i = 0
			ps.setObject(++i, id)
			ps.setTimestamp(++i, Timestamp.from(T0))
			ps.setTimestamp(++i, Timestamp.from(T1))
			ps.setLong(++i, 4)
			ps.setTimestamp(++i, deletedAt?.let(Timestamp::from))
			ps.setObject(++i, calendar)
			ps.setString(++i, title)
			ps.setString(++i, description)
			ps.setString(++i, location)
			ps.setBoolean(++i, !timed)
			ps.setTimestamp(++i, if (timed) Timestamp.from(start) else null)
			ps.setTimestamp(++i, if (timed) Timestamp.from(start!!.plusSeconds(3600)) else null)
			ps.setString(++i, if (timed) ZURICH else null)
			ps.setDate(++i, allDay?.let(Date::valueOf))
			ps.setDate(++i, allDay?.plusDays(1)?.let(Date::valueOf))
			ps.setString(++i, rrule)
			ps.setObject(++i, parent)
			ps.setTimestamp(++i, recurrenceAt?.let(Timestamp::from))
			ps.setDate(++i, recurrenceDate?.let(Date::valueOf))
			ps.setString(++i, attendees)
			ps.setArray(++i, ps.connection.createArrayOf("integer", reminders.toTypedArray()))
			ps.setString(++i, status)
			ps.setString(++i, transparency)
			ps.setString(++i, conferenceUrl)
			ps.setString(++i, externalId)
			ps.setString(++i, externalEtag)
			ps.setString(++i, syncState)
		}
	}

	private fun exdate(
		event: UUID,
		at: Instant? = null,
		date: LocalDate? = null,
	) {
		jdbc.update(
			"insert into calendar_event_exdate (event_id, occurrence_at, occurrence_date) values (?, ?, ?)",
			event,
			at?.let(Timestamp::from),
			date?.let(Date::valueOf),
		)
	}

	companion object {
		private const val DATABASE = "calendar_backup_contributor"

		/** Every ical4j builder [parse] used, kept reachable for the JVM's lifetime (see [parse]). */
		private val builders = mutableListOf<CalendarBuilder>()
		private val dataDir: Path = Files.createTempDirectory("cockpit-calendar-backup-data")

		private const val ZURICH = "Europe/Zurich"
		private const val PASSWORD = "fictitious-caldav-password"
		private const val PASSPHRASE = "fictitious backup passphrase"

		private val T0 = Instant.parse("2026-03-01T08:15:30.123456Z")
		private val T1 = Instant.parse("2026-03-02T09:00:00Z")
		private val T2 = Instant.parse("2026-03-03T10:00:00Z")

		/** Fictitious UUIDv7-shaped ids, ordered by [n]. */
		private fun id(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

		private val LOCAL = id(1)
		private val CALDAV = id(2)
		private val HOME = id(10)
		private val SPORT = id(11)
		private val WORK = id(12)

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
