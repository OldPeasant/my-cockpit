package ch.sonensei.cockpit.contacts.aggregate

import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.recurrence.Occurrence
import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.domain.TaskFilter
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.mail.api.MailReadService
import ch.sonensei.cockpit.mail.model.MailMessage
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/** Everything about a contact besides its own fields (SPEC.md §8.3). */
data class ContactRelated(
	val contact: Contact,
	val mails: List<MailMessage>,
	val events: List<Occurrence>,
	val waitingFor: List<Task>,
)

/** One autocomplete suggestion: a contact (with one of its addresses, if any) or a recent correspondent. */
data class AutocompleteEntry(
	val name: String?,
	val email: String?,
	val contact: Contact?,
)

/**
 * Aggregates a contact's mails, events and Waiting For items, and the contact/correspondent autocomplete (SPEC.md
 * §8.3). It reads through the other modules' services; only the address lookup in the mail cache is a query of its
 * own ([CorrespondenceQuery]). Not transactional itself: every service call runs in its own read-only transaction, so
 * a message that vanished meanwhile is just skipped.
 */
@Service
class ContactAggregateService(
	private val contacts: ContactService,
	private val mail: MailReadService,
	private val events: CalendarEventService,
	private val tasks: TaskService,
	private val correspondence: CorrespondenceQuery,
	private val clock: Clock,
) {
	/**
	 * The newest [MAIL_LIMIT] mails from/to the contact's addresses, its occurrences in the next [EVENT_HORIZON] where
	 * one of its addresses is an attendee (at most [EVENT_LIMIT], cancelled ones left out), and the open Waiting For
	 * tasks that name it.
	 *
	 * @throws NotFoundException for an unknown contact or one in the Trash
	 */
	fun related(id: UUID): ContactRelated {
		val contact = contacts.get(id)
		val addresses = contact.emails.map { it.address.lowercase() }.toSet()
		val mails =
			correspondence.messageIdsWith(addresses, MAIL_LIMIT).mapNotNull { messageId ->
				try {
					mail.get(messageId)
				} catch (_: NotFoundException) {
					null
				}
			}
		val now = clock.instant()
		val upcoming =
			if (addresses.isEmpty()) {
				emptyList()
			} else {
				events
					.occurrences(now, now.plus(EVENT_HORIZON), null)
					.filter { occurrence ->
						occurrence.event.status != EventStatus.CANCELLED &&
							occurrence.event.attendees.any { it.email.lowercase() in addresses }
					}.sortedWith(compareBy({ it.start ?: it.startDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant() }, { it.event.id }))
					.take(EVENT_LIMIT)
			}
		return ContactRelated(contact, mails, upcoming, waitingFor(contact))
	}

	/**
	 * Suggestions for [query]: matching contacts first (one entry per email address, or one without address), names
	 * starting with the query before the others (each group by name), then recent correspondents that are not
	 * addresses of a suggested contact, most recently seen first. A correspondent whose address belongs to a contact
	 * carries that contact and its display name. At most [limit] entries; a blank query has none. Looks at no more
	 * than the first [CONTACT_SCAN] matching contacts by name.
	 */
	fun autocomplete(
		query: String,
		limit: Int,
	): List<AutocompleteEntry> {
		val text = query.trim()
		if (text.isEmpty()) return emptyList()
		val needle = text.lowercase()
		val (prefixed, others) =
			matchingContacts(text, needle, limit).partition { it.displayName.lowercase().startsWith(needle) }
		val fromContacts = (prefixed + others).flatMap(::entries)
		if (fromContacts.size >= limit) return fromContacts.take(limit)
		val known = fromContacts.mapNotNull { it.email?.lowercase() }.toSet()
		val fromMail =
			correspondence
				.correspondents(text, CORRESPONDENT_SCAN)
				.filter { it.address.lowercase() !in known }
				.take(limit - fromContacts.size)
				.map { correspondent ->
					val owner = contactWithAddress(correspondent.address)
					AutocompleteEntry(owner?.displayName ?: correspondent.name, correspondent.address, owner)
				}
		return fromContacts + fromMail
	}

	/**
	 * Contacts matching [text] in name order, page by page, up to [CONTACT_SCAN] of them. Stops early once the
	 * contacts whose name starts with [needle] give [limit] entries: everything later ranks behind them.
	 */
	private fun matchingContacts(
		text: String,
		needle: String,
		limit: Int,
	): List<Contact> {
		val result = mutableListOf<Contact>()
		var prefixEntries = 0
		var page = CursorRequest(null, CursorRequest.MAX_LIMIT)
		while (true) {
			val contactPage = contacts.list(text, page)
			for (contact in contactPage.items) {
				result += contact
				if (contact.displayName.lowercase().startsWith(needle)) prefixEntries += maxOf(1, contact.emails.size)
			}
			val next = contactPage.nextCursor
			if (next == null || prefixEntries >= limit || result.size >= CONTACT_SCAN) return result.take(CONTACT_SCAN)
			page = CursorRequest.of(next, CursorRequest.MAX_LIMIT)
		}
	}

	/** One entry per email address of [contact], or one without address. */
	private fun entries(contact: Contact): List<AutocompleteEntry> =
		if (contact.emails.isEmpty()) {
			listOf(AutocompleteEntry(contact.displayName, null, contact))
		} else {
			contact.emails.map { AutocompleteEntry(contact.displayName, it.address, contact) }
		}

	/** The first contact (by name) with exactly [address] (case-insensitive) among its email addresses. */
	private fun contactWithAddress(address: String): Contact? {
		val wanted = address.lowercase()
		var page = CursorRequest(null, CursorRequest.MAX_LIMIT)
		while (true) {
			// list() matches substrings, e.g. "x@example.test" also finds "max@example.test": check exactly.
			val contactPage = contacts.list(address, page)
			val found = contactPage.items.firstOrNull { contact -> contact.emails.any { it.address.lowercase() == wanted } }
			if (found != null) return found
			val next = contactPage.nextCursor ?: return null
			page = CursorRequest.of(next, CursorRequest.MAX_LIMIT)
		}
	}

	/** Open Waiting For tasks whose contact is [contact], in list order. */
	private fun waitingFor(contact: Contact): List<Task> {
		val result = mutableListOf<Task>()
		var page = CursorRequest(null, CursorRequest.MAX_LIMIT)
		while (true) {
			val tasksPage = tasks.list(TaskFilter(list = TaskList.WAITING), page)
			result += tasksPage.items.filter { it.waitingForContactId == contact.id }
			val next = tasksPage.nextCursor ?: return result
			page = CursorRequest.of(next, CursorRequest.MAX_LIMIT)
		}
	}

	companion object {
		const val MAIL_LIMIT = 20
		const val EVENT_LIMIT = 20
		val EVENT_HORIZON: Duration = Duration.ofDays(90)
		const val CONTACT_SCAN = 1000
		const val CORRESPONDENT_SCAN = 500
		const val DEFAULT_AUTOCOMPLETE_LIMIT = 10
		const val MAX_AUTOCOMPLETE_LIMIT = 50
	}
}
