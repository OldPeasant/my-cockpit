package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import org.springframework.stereotype.Component

/** The [CalendarProviderAdapter] of each source type, from the adapter beans; at most one per type. */
@Component
class CalendarProviderRegistry(
	adapters: List<CalendarProviderAdapter>,
) {
	private val byType: Map<CalendarSourceType, CalendarProviderAdapter>

	init {
		require(adapters.none { it.sourceType == CalendarSourceType.LOCAL }) { "LOCAL sources are not synced" }
		val duplicates = adapters.groupBy { it.sourceType }.filterValues { it.size > 1 }.keys
		require(duplicates.isEmpty()) { "More than one calendar adapter for $duplicates" }
		byType = adapters.associateBy { it.sourceType }
	}

	/** The adapter of [type]; null for LOCAL and for types without an adapter (yet). */
	fun find(type: CalendarSourceType): CalendarProviderAdapter? = byType[type]

	/** Whether sources of [type] are synced (and their events can be changed through the provider). */
	fun supports(type: CalendarSourceType): Boolean = type in byType
}
