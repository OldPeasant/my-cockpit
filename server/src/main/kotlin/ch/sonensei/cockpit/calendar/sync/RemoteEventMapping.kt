package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.api.setTiming
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.EventSyncState

/** The title of a remote event without one. */
internal const val UNTITLED = "(no title)"

/**
 * Makes [this] the local copy of [remote]: content, times, rule and EXDATEs (series masters and single events only),
 * external id and etag; the copy is [EventSyncState.SYNCED]. The recurrence id of an override is fixed at creation.
 */
internal fun CalendarEvent.copyFrom(remote: RemoteEvent) {
	title = remote.title.ifBlank { UNTITLED }
	description = remote.description
	location = remote.location
	setTiming(remote.timing)
	if (!isOverride) {
		rrule = remote.rrule
		if (exdates != remote.exdates) {
			exdates.clear()
			exdates.addAll(remote.exdates)
		}
	}
	attendees = remote.attendees
	reminderMinutes = remote.reminderMinutes
	status = remote.status
	transparency = remote.transparency
	conferenceUrl = remote.conferenceUrl
	externalId = remote.key.externalId
	externalEtag = remote.etag
	syncState = EventSyncState.SYNCED
}

/** [this] event as the provider gets it; [externalId] is its UID (for an override: the UID of its series). */
internal fun CalendarEvent.toRemote(externalId: String): RemoteEvent =
	RemoteEvent(
		key = RemoteEventKey(externalId, recurrenceId),
		etag = externalEtag,
		title = title,
		timing = Timing.of(this),
		description = description,
		location = location,
		rrule = rrule,
		exdates = exdates.toSet(),
		attendees = attendees,
		reminderMinutes = reminderMinutes,
		status = status,
		transparency = transparency,
		conferenceUrl = conferenceUrl,
	)
