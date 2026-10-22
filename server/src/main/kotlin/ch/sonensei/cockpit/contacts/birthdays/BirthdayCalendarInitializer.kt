package ch.sonensei.cockpit.contacts.birthdays

import ch.sonensei.cockpit.calendar.api.CalendarService
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Creates the birthdays calendar ([BirthdayCalendarProvider]) once a contact has a birthday: after a contact change
 * commits, and at startup for contacts saved before. Users without birthdays get no empty calendar. Once created, the
 * calendar stays (hide it to not see it).
 */
@Component
class BirthdayCalendarInitializer(
	private val contacts: BirthdayContactRepository,
	private val calendars: CalendarService,
) {
	@EventListener(ApplicationReadyEvent::class)
	@Transactional
	fun onStartup() {
		if (contacts.existsByBirthdayIsNotNullAndDeletedAtIsNull()) ensureCalendar()
	}

	@TransactionalEventListener(fallbackExecution = true)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun onContactChanged(event: ChangeEvent) {
		if (event.type != ItemType.CONTACT.name || event.op == ChangeOp.DELETED) return
		if (contacts.existsByIdAndBirthdayIsNotNullAndDeletedAtIsNull(event.id)) ensureCalendar()
	}

	private fun ensureCalendar() {
		calendars.ensureVirtualCalendar(BirthdayCalendarProvider.KEY, BirthdayCalendarProvider.NAME, BirthdayCalendarProvider.COLOR)
	}
}
