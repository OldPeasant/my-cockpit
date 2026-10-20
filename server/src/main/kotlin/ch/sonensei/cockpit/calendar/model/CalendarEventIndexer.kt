package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Search indexing hooks of events (SPEC.md §4.4). Calendar code calls [index] in the transaction that creates,
 * changes, deletes (Trash) or restores an event, and [unindexCalendar] before it removes a calendar (its events go
 * with it through the foreign key, without [ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent]s).
 *
 * An event is searchable while it and (for an override) its series master are not deleted, and it is not a cancelled
 * override (a cancelled occurrence is gone for the user). Title is the event title; body is its description and location.
 */
@Component
@Transactional
class CalendarEventIndexer(
	private val indexer: SearchIndexer,
	private val events: CalendarEventRepository,
) {
	/** (Re)indexes [event]; for a series master also its overrides, which share the master's Trash state. */
	fun index(event: CalendarEvent) {
		if (event.isOverride) {
			val masterDeleted = events.findActiveById(event.parentEventId!!) == null
			apply(event, masterDeleted)
		} else {
			apply(event, false)
			if (event.isRecurring) events.findByParentEventIdOrderByIdAsc(event.id).forEach { apply(it, event.isDeleted) }
		}
	}

	private fun apply(
		event: CalendarEvent,
		masterDeleted: Boolean,
	) {
		if (masterDeleted || event.isDeleted || (event.isOverride && event.status == EventStatus.CANCELLED)) {
			indexer.delete(ItemType.EVENT, event.id)
		} else {
			indexer.upsert(ItemType.EVENT, event.id, event.title, listOfNotNull(event.description, event.location).joinToString("\n"))
		}
	}

	fun unindexCalendar(calendarId: UUID) {
		events.findIdsByCalendarId(calendarId).forEach { indexer.delete(ItemType.EVENT, it) }
	}
}
