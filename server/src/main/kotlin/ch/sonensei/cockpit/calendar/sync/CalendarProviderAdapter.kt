package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import java.time.Duration

/**
 * Connects one kind of [CalendarSource] (Google, Microsoft, CalDAV, ICS feed) to the provider-independent sync engine
 * (SPEC.md §6.2). Implementations are Spring beans; [CalendarProviderRegistry] finds them by [sourceType].
 *
 * Events are exchanged iCalendar-style: a series master or single event is identified by its [RemoteEventKey.externalId]
 * (UID), an exception of a series (override) by the series' UID plus the original start of the occurrence
 * ([RemoteEventKey.recurrenceId]). Adapters map this to the provider's semantics (e.g. Google instances).
 *
 * Every method may block on the network; the engine calls them outside database transactions, except the writes, which
 * run while the local change waits uncommitted (write-through). Failures throw [CalendarProviderException] (or
 * [RemoteEventChangedException] for a failed precondition); their messages are shown to the user and stored, so they must
 * not contain secrets or event content.
 */
interface CalendarProviderAdapter {
	val sourceType: CalendarSourceType

	/** How often the scheduler polls a source of this type (Google/Microsoft 5 min, CalDAV 10 min). */
	val pollInterval: Duration

	/**
	 * Whether every event of a series (master and overrides) shares one remote version, as in CalDAV, where a series is
	 * one resource: after a write the engine then gives the returned etag to the whole series.
	 */
	val seriesShareEtag: Boolean get() = false

	/** The calendars of [source] as the provider has them now. */
	fun listCalendars(source: CalendarSource): List<RemoteCalendar>

	/**
	 * The changes of calendar [calendarExternalId] since [cursor] (null: everything, a full fetch). An adapter whose cursor
	 * is no longer accepted (e.g. Google 410 Gone) does a full fetch itself and says so with [RemoteEventChanges.complete].
	 * With [RemoteEventChanges.hasMore] the engine calls again with the returned cursor for the next page of the same run.
	 */
	fun fetchEvents(
		source: CalendarSource,
		calendarExternalId: String,
		cursor: String?,
	): RemoteEventChanges

	/**
	 * Creates [event] in calendar [calendarExternalId]: a new event or series (its key's external id is a fresh id the
	 * adapter may use as UID or replace), or with a recurrence id the exception of that occurrence of an existing series.
	 * Returns the event as the provider stored it, with its real key and etag.
	 */
	fun createEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
	): RemoteEvent

	/**
	 * Changes [event] at the provider, only if its version still is [ifMatch] (null: unconditionally).
	 *
	 * @throws RemoteEventChangedException when the provider has another version (HTTP 412)
	 */
	fun updateEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
		ifMatch: String?,
	): RemoteEvent

	/**
	 * Deletes the event or series [key] (with a recurrence id: removes that occurrence), only if its version still is
	 * [ifMatch]. Deleting something the provider no longer has succeeds. Returns the series' new etag when an occurrence
	 * was removed and the series shares one version ([seriesShareEtag]), else null.
	 *
	 * @throws RemoteEventChangedException when the provider has another version (HTTP 412)
	 */
	fun deleteEvent(
		source: CalendarSource,
		calendarExternalId: String,
		key: RemoteEventKey,
		ifMatch: String?,
	): String?

	/** Whether the user can answer invitations to events of this provider ([respondToInvitation]). */
	val respondsToInvitations: Boolean get() = false

	/**
	 * Answers the invitation to event [key] (a series: every occurrence; with a recurrence id: that occurrence) as the
	 * user's attendee with [response] (ACCEPTED, DECLINED or TENTATIVE), only if its version still is [ifMatch], and
	 * returns the event as the provider has it now, the user's attendee with the new status.
	 *
	 * @throws InvitationNotRespondableException when the user is not invited to the event (or organizes it)
	 * @throws RemoteEventChangedException when the provider has another version or no longer has the event
	 */
	fun respondToInvitation(
		source: CalendarSource,
		calendarExternalId: String,
		key: RemoteEventKey,
		response: AttendeeStatus,
		ifMatch: String?,
	): RemoteEvent = throw CalendarProviderException("This calendar provider does not support answering invitations")
}

/** A calendar at the provider. [readOnly] calendars (shared, subscribed) cannot be changed from the cockpit. */
data class RemoteCalendar(
	val externalId: String,
	val name: String,
	val color: String? = null,
	val readOnly: Boolean = false,
)

/** Identifies a remote event: a series or single event by UID, an override additionally by its occurrence. */
data class RemoteEventKey(
	val externalId: String,
	val recurrenceId: OccurrenceId? = null,
)

/**
 * The provider's copy of an event. An override ([RemoteEventKey.recurrenceId] set) has no [rrule] and no [exdates].
 * [etag] is the provider's version, sent back as `If-Match` on the next write.
 */
data class RemoteEvent(
	val key: RemoteEventKey,
	val etag: String?,
	val title: String,
	val timing: Timing,
	val description: String? = null,
	val location: String? = null,
	val rrule: String? = null,
	val exdates: Set<OccurrenceId> = emptySet(),
	val attendees: List<Attendee> = emptyList(),
	val reminderMinutes: List<Int> = emptyList(),
	val status: EventStatus = EventStatus.CONFIRMED,
	val transparency: EventTransparency = EventTransparency.OPAQUE,
	val conferenceUrl: String? = null,
)

/**
 * One page of changes of a calendar. [events] are new or changed; [deleted] are gone (a key with a recurrence id: that
 * occurrence was removed from its series). With [complete], [events] (over all pages of the run) is the whole calendar
 * and the engine removes local events that are not in it. [cursor] is passed to the next fetch; null keeps none (the
 * next fetch is a full one).
 */
data class RemoteEventChanges(
	val events: List<RemoteEvent> = emptyList(),
	val deleted: List<RemoteEventKey> = emptyList(),
	val cursor: String? = null,
	val complete: Boolean = false,
	val hasMore: Boolean = false,
)

/** The provider failed or could not be reached. [message] is shown to the user: no secrets, no event content. */
open class CalendarProviderException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A conditional write failed: the provider has a newer version of the event (HTTP 412). */
class RemoteEventChangedException(
	message: String = "The event was changed at the provider",
	cause: Throwable? = null,
) : CalendarProviderException(message, cause)

/** The user cannot answer the invitation to an event: they are not one of its attendees, or they organize it. */
class InvitationNotRespondableException(
	message: String,
) : CalendarProviderException(message)
