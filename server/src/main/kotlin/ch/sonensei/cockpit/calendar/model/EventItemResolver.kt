package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Events in generic views (SPEC.md §4.3): subtitle is the start ("Tue 20 Oct 2026, 14:00" in the event's zone,
 * "Tue 20 Oct 2026, all day"), with "(repeats)" for a series; deep link `/calendar/events/{id}`. An override of a
 * series in the Trash and a cancelled override (a removed occurrence) do not resolve, as in [CalendarEventIndexer].
 */
@Component
class EventItemResolver(
	private val events: CalendarEventRepository,
) : ItemResolver {
	override val type = ItemType.EVENT

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		events
			.findActiveById(id)
			?.takeIf {
				!it.isOverride || (it.status != EventStatus.CANCELLED && events.findActiveById(it.parentEventId!!) != null)
			}?.let {
				ResolvedItem(
					title = it.title,
					subtitle = it.startLabel() + if (it.isRecurring) " (repeats)" else "",
					icon = "event",
					deepLink = "/calendar/events/${it.id}",
				)
			}

	private fun CalendarEvent.startLabel(): String =
		if (allDay) {
			"${DATE.format(startDate)}, all day"
		} else {
			val zone = ZoneId.of(timeZone)
			"${DATE.format(startAt!!.atZone(zone))}, ${TIME.format(startAt!!.atZone(zone))}"
		}

	private companion object {
		val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
		val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
	}
}
