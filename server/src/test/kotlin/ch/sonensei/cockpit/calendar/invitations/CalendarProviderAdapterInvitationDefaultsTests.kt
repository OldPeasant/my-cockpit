package ch.sonensei.cockpit.calendar.invitations

import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarProviderAdapter
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventChanges
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** An adapter that does not override the invitation hook (CalDAV, ICS feeds) does not answer invitations. */
class CalendarProviderAdapterInvitationDefaultsTests {
	private val adapter =
		object : CalendarProviderAdapter {
			override val sourceType = CalendarSourceType.CALDAV
			override val pollInterval: Duration = Duration.ofMinutes(10)

			override fun listCalendars(source: CalendarSource): List<RemoteCalendar> = emptyList()

			override fun fetchEvents(
				source: CalendarSource,
				calendarExternalId: String,
				cursor: String?,
			) = RemoteEventChanges()

			override fun createEvent(
				source: CalendarSource,
				calendarExternalId: String,
				event: RemoteEvent,
			) = event

			override fun updateEvent(
				source: CalendarSource,
				calendarExternalId: String,
				event: RemoteEvent,
				ifMatch: String?,
			) = event

			override fun deleteEvent(
				source: CalendarSource,
				calendarExternalId: String,
				key: RemoteEventKey,
				ifMatch: String?,
			): String? = null
		}

	@Test
	fun `by default an adapter does not answer invitations and refuses to`() {
		adapter.respondsToInvitations shouldBe false
		shouldThrowExactly<CalendarProviderException> {
			adapter.respondToInvitation(
				CalendarSource(CalendarSourceType.CALDAV, "Work"),
				"work",
				RemoteEventKey("caldav-event-1"),
				AttendeeStatus.ACCEPTED,
				null,
			)
		}.message shouldBe "This calendar provider does not support answering invitations"
	}
}
