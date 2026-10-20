package ch.sonensei.cockpit.calendar.model

/** Where a [CalendarSource]'s calendars live (SPEC.md §6.1). Stored by name. */
enum class CalendarSourceType {
	/** Stored in the cockpit only. */
	LOCAL,
	GOOGLE,
	MICROSOFT,
	CALDAV,

	/** A read-only ICS subscription URL. */
	ICS_FEED,
}

/** iCalendar `STATUS` of an event. A cancelled override removes its occurrence from the series. */
enum class EventStatus {
	CONFIRMED,
	TENTATIVE,
	CANCELLED,
}

/** iCalendar `TRANSP`: whether the event blocks time (busy) or not (free). */
enum class EventTransparency {
	OPAQUE,
	TRANSPARENT,
}

/** Sync state of the cockpit's copy of an event (SPEC.md §6.2). */
enum class EventSyncState {
	/** Event of a LOCAL calendar: nothing to sync. */
	LOCAL,

	/** Equal to the remote copy at [CalendarEvent.externalEtag]. */
	SYNCED,

	/** Changed locally, not yet confirmed by the provider. */
	PENDING,

	/** The last write to or read from the provider failed. */
	ERROR,
}

/** iCalendar `PARTSTAT` of an attendee. */
enum class AttendeeStatus {
	NEEDS_ACTION,
	ACCEPTED,
	DECLINED,
	TENTATIVE,
	DELEGATED,
}
