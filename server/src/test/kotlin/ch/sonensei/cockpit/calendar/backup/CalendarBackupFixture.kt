package ch.sonensei.cockpit.calendar.backup

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.api.CalendarService
import ch.sonensei.cockpit.calendar.api.EditScope
import ch.sonensei.cockpit.calendar.api.EventChanges
import ch.sonensei.cockpit.calendar.api.FieldChange
import ch.sonensei.cockpit.calendar.api.TimingInput
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventSyncState
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local calendars with a timed and an all-day recurring series (each with a changed, a cancelled and an excluded
 * occurrence), a single event with attendees, reminders and a conference URL, an empty untitled-ish event and one in the
 * Trash; plus an external CalDAV source with credentials, its calendar and synced events (a series with an override and
 * a single event), which the backup keeps as id refs.
 */
class CalendarBackupFixture(
	private val calendars: CalendarService,
	private val events: CalendarEventService,
	private val sources: CalendarSourceRepository,
	private val calendarRows: CalendarRepository,
	private val eventRows: CalendarEventRepository,
) : BackupFixture {
	override val contributor = CalendarBackupContributor.ID

	override fun seed() {
		val home = calendars.create("Home / Family", "#3366ff", visible = true, isDefault = true).calendar
		val sport = calendars.create("Sport «Zürich»", null, visible = false, isDefault = false).calendar

		val standup =
			create(
				EventChanges(
					calendarId = FieldChange(home.id),
					title = FieldChange("Weekly planning"),
					description = FieldChange("Plan the week\nwith coffee ☕"),
					timing = TimingInput(start = MONDAY_9, end = MONDAY_9.plusSeconds(3600), timeZone = ZURICH),
					rrule = FieldChange("FREQ=WEEKLY;BYDAY=MO;COUNT=10"),
					reminderMinutes = FieldChange(listOf(10, 0)),
				),
			)
		events.update(
			standup.id,
			EditScope.THIS,
			"2026-03-09T08:00:00Z",
			EventChanges(location = FieldChange("Room B"), timing = TimingInput(start = Instant.parse("2026-03-09T09:00:00Z"))),
		) {}
		events.delete(standup.id, EditScope.THIS, "2026-03-16T08:00:00Z") {}
		events.update(standup.id, EditScope.THIS, "2026-03-23T08:00:00Z", EventChanges(status = FieldChange(EventStatus.CANCELLED))) {}

		val training =
			create(
				EventChanges(
					calendarId = FieldChange(sport.id),
					title = FieldChange("Long run"),
					timing = TimingInput(allDay = true, startDate = LocalDate.parse("2026-03-07"), endDate = LocalDate.parse("2026-03-08")),
					rrule = FieldChange("FREQ=WEEKLY;BYDAY=SA"),
					transparency = FieldChange(EventTransparency.TRANSPARENT),
				),
			)
		events.update(training.id, EditScope.THIS, "2026-03-14", EventChanges(title = FieldChange("Long run (hills)"))) {}
		events.delete(training.id, EditScope.THIS, "2026-03-21") {}

		create(
			EventChanges(
				calendarId = FieldChange(home.id),
				title = FieldChange("Dinner with Alex; bring \"wine\""),
				location = FieldChange("Rue du Lac 1, Lausanne"),
				timing = TimingInput(start = Instant.parse("2026-04-01T17:30:00Z"), end = Instant.parse("2026-04-01T20:00:00Z"), timeZone = ZURICH),
				attendees =
					FieldChange(
						listOf(
							Attendee("alex@example.test", "Alex Example", AttendeeStatus.ACCEPTED),
							Attendee("sam@example.test"),
						),
					),
				conferenceUrl = FieldChange("https://meet.example.test/dinner"),
				status = FieldChange(EventStatus.TENTATIVE),
			),
		)
		create(
			EventChanges(
				calendarId = FieldChange(home.id),
				title = FieldChange("x"),
				timing = TimingInput(start = Instant.parse("2026-05-01T00:00:00Z"), end = Instant.parse("2026-05-01T00:00:00Z"), timeZone = "UTC"),
			),
		)
		val cancelled =
			create(
				EventChanges(
					calendarId = FieldChange(home.id),
					title = FieldChange("Cancelled appointment"),
					timing = TimingInput(allDay = true, startDate = LocalDate.parse("2026-06-01"), endDate = LocalDate.parse("2026-06-03")),
				),
			)
		events.delete(cancelled.id, null, null) {}

		seedExternal()
	}

	private fun create(fields: EventChanges): CalendarEvent = events.create(null, fields).event

	private fun seedExternal() {
		val source =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.CALDAV, "Work CalDAV").apply {
					config = """{"url": "https://caldav.example.test/dav/", "username": "owner"}"""
					credentials = EncryptedSecret("fictitious-caldav-password")
				},
			)
		val work =
			calendarRows.saveAndFlush(
				Calendar(source.id, "Work").apply {
					color = "#aa0000"
					readOnly = false
					externalId = "/dav/work/"
					externalEtag = "\"cal-etag-1\""
				},
			)
		val series =
			eventRows.saveAndFlush(
				CalendarEvent(work.id, "Team sync").apply {
					setTimed(Instant.parse("2026-03-03T09:00:00Z"), Instant.parse("2026-03-03T09:30:00Z"), ZoneId.of(ZURICH))
					rrule = "FREQ=DAILY;COUNT=5"
					externalId = "series-1@caldav.example.test"
					externalEtag = "\"etag-1\""
					syncState = EventSyncState.SYNCED
				},
			)
		eventRows.saveAndFlush(
			CalendarEvent(work.id, "Team sync (moved)").apply {
				setTimed(Instant.parse("2026-03-04T10:00:00Z"), Instant.parse("2026-03-04T10:30:00Z"), ZoneId.of(ZURICH))
				overrideOf(series, OccurrenceId.of(Instant.parse("2026-03-04T09:00:00Z")))
				externalId = series.externalId
				externalEtag = "\"etag-1\""
				syncState = EventSyncState.SYNCED
			},
		)
		eventRows.saveAndFlush(
			CalendarEvent(work.id, "Offsite").apply {
				setAllDay(LocalDate.parse("2026-04-10"))
				externalId = "offsite@caldav.example.test"
				syncState = EventSyncState.PENDING
			},
		)
	}

	private companion object {
		const val ZURICH = "Europe/Zurich"
		val MONDAY_9: Instant = Instant.parse("2026-03-02T08:00:00Z")
	}
}
