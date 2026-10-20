package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.model.CalendarEventIndexer
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Deleted events and series in the Trash (overrides share their master's Trash state and are not listed). A restored
 * event is searchable again with its overrides; a purge deletes the row with its EXDATEs and overrides, and the
 * overrides' links and search entries.
 */
@Component
class EventTrashContributor(
	private val events: CalendarEventRepository,
	private val indexer: CalendarEventIndexer,
	private val appEvents: ApplicationEventPublisher,
) : TrashContributor {
	override val type = ItemType.EVENT

	override fun listDeleted(): List<TrashedItem> =
		events.findAllDeleted().filter { !it.isOverride }.map {
			val start =
				if (it.allDay) {
					DATE.format(it.startDate)
				} else {
					DATE.format(it.startAt!!.atZone(ZoneId.of(it.timeZone)))
				}
			TrashedItem(
				id = it.id,
				title = it.title,
				deletedAt = checkNotNull(it.deletedAt),
				subtitle = if (it.isRecurring) "$start (repeats)" else start,
				icon = "event",
			)
		}

	override fun restore(id: UUID): Boolean {
		val event = events.findById(id).orElse(null)?.takeIf { it.isDeleted && !it.isOverride } ?: return false
		event.restore()
		indexer.index(events.saveAndFlush(event))
		return true
	}

	override fun purge(id: UUID): Boolean {
		val event = events.findById(id).orElse(null)?.takeIf { it.isDeleted && !it.isOverride } ?: return false
		val overrides = events.findByParentEventIdOrderByIdAsc(event.id)
		overrides.forEach { appEvents.publishEvent(ItemDeletedEvent(ItemRef(ItemType.EVENT, it.id))) }
		events.deleteAll(overrides)
		events.delete(event)
		events.flush()
		return true
	}

	private companion object {
		val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
	}
}
