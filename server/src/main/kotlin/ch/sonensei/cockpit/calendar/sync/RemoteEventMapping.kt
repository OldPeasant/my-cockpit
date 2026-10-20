package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.MAX_ATTENDEES
import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.MAX_DESCRIPTION_LENGTH
import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.MAX_NAME_LENGTH
import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.MAX_REMINDERS
import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.MAX_REMINDER_MINUTES
import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.MAX_TEXT_LENGTH
import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.MAX_TITLE_LENGTH
import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.isEmail
import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.isValidRule
import ch.sonensei.cockpit.calendar.api.CalendarEventService.Companion.isWebUrl
import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.api.setTiming
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.EventSyncState

/** The title of a remote event without one. */
internal const val UNTITLED = "(no title)"

/**
 * [this] remote event within the invariants the event API enforces (`CalendarEventService.validate`), so a pulled event
 * stays editable and its fields hold what clients rely on: overlong text is cut to the API limits, a conference URL
 * that is not http(s), attendees without a valid e-mail address and reminders out of range are dropped. Remote data is
 * untrusted (anyone can send the user an invite).
 *
 * @throws IllegalArgumentException when the event cannot be stored as is: it ends before it starts, or the series has
 *   an invalid RRULE (dropping the rule would turn the series into a single event, which the next local edit would
 *   write back to the provider)
 */
internal fun RemoteEvent.normalized(): RemoteEvent {
	when (val t = timing) {
		is Timing.Timed -> require(!t.end.isBefore(t.start)) { "An event cannot end before it starts" }
		is Timing.AllDay -> require(t.endDate.isAfter(t.startDate)) { "An all-day event ends after its first day" }
	}
	val rule = if (key.recurrenceId == null) normalizedRule(rrule) else rrule
	require(rule == null || key.recurrenceId != null || isValidRule(rule)) { "The series has an invalid RRULE" }
	return copy(
		title = title.ifBlank { UNTITLED }.take(MAX_TITLE_LENGTH),
		description = description?.take(MAX_DESCRIPTION_LENGTH),
		location = location?.take(MAX_TEXT_LENGTH),
		rrule = rule,
		attendees =
			attendees
				.filter { isEmail(it.email) }
				.map { if ((it.name?.length ?: 0) > MAX_NAME_LENGTH) it.copy(name = it.name!!.take(MAX_NAME_LENGTH)) else it }
				.take(MAX_ATTENDEES),
		reminderMinutes = reminderMinutes.filter { it in 0..MAX_REMINDER_MINUTES }.take(MAX_REMINDERS),
		conferenceUrl = conferenceUrl?.takeIf { isWebUrl(it) },
	)
}

/** The rule without an `RRULE:` prefix; blank is none (as the event API stores it). */
private fun normalizedRule(rule: String?): String? =
	rule
		?.trim()
		?.removePrefix("RRULE:")
		?.removePrefix("rrule:")
		?.trim()
		?.ifEmpty { null }

/**
 * Makes [this] the local copy of [remote] ([normalized] first): content, times, rule and EXDATEs (series masters and
 * single events only), external id and etag; the copy is [EventSyncState.SYNCED]. The recurrence id of an override is
 * fixed at creation. An invalid [remote] leaves [this] unchanged.
 *
 * @throws IllegalArgumentException when [remote] cannot be stored (see [normalized])
 */
internal fun CalendarEvent.copyFrom(original: RemoteEvent) {
	val remote = original.normalized()
	title = remote.title
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
