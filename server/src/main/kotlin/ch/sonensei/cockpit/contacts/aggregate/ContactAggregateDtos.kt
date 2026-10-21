package ch.sonensei.cockpit.contacts.aggregate

import ch.sonensei.cockpit.calendar.recurrence.Occurrence
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailMessage
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Schema(description = "What the cockpit knows about a contact besides its own fields")
data class ContactRelatedResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val contactId: UUID,
	@get:Schema(description = "Newest mails from/to the contact's addresses, newest first", requiredMode = RequiredMode.REQUIRED)
	val mails: List<ContactMailDto>,
	@get:Schema(
		description = "Occurrences in the next 90 days with one of the contact's addresses as attendee, by start",
		requiredMode = RequiredMode.REQUIRED,
	)
	val events: List<ContactEventDto>,
	@get:Schema(description = "Open Waiting For tasks for this contact, in list order", requiredMode = RequiredMode.REQUIRED)
	val waitingFor: List<ContactWaitingForDto>,
) {
	companion object {
		fun of(related: ContactRelated): ContactRelatedResponse {
			val addresses =
				related.contact.emails
					.map { it.address.lowercase() }
					.toSet()
			return ContactRelatedResponse(
				contactId = related.contact.id,
				mails = related.mails.map { ContactMailDto.of(it, addresses) },
				events = related.events.map(ContactEventDto::of),
				waitingFor = related.waitingFor.map(ContactWaitingForDto::of),
			)
		}
	}
}

@Schema(description = "A mailbox: address plus optional display name")
data class ContactMailAddressDto(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val address: String,
	val name: String?,
)

@Schema(description = "Whether the contact sent the mail (FROM) or received it (TO, also Cc)")
enum class MailDirection {
	FROM,
	TO,
}

@Schema(description = "Headers of a mail from/to the contact (`GET /mail/messages/{id}` has the body)")
data class ContactMailDto(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val accountId: UUID,
	val subject: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val from: List<ContactMailAddressDto>,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val to: List<ContactMailAddressDto>,
	@get:Schema(description = "The Date header") val sentAt: Instant?,
	@get:Schema(description = "Arrival in the mailbox") val receivedAt: Instant?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val seen: Boolean,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val direction: MailDirection,
) {
	companion object {
		fun of(
			message: MailMessage,
			contactAddresses: Set<String>,
		) = ContactMailDto(
			id = message.id,
			accountId = message.accountId,
			subject = message.subject,
			from = message.from.map(::address),
			to = message.to.map(::address),
			sentAt = message.sentAt,
			receivedAt = message.receivedAt,
			seen = message.seen,
			direction = if (message.from.any { it.address.lowercase() in contactAddresses }) MailDirection.FROM else MailDirection.TO,
		)

		private fun address(address: MailAddress) = ContactMailAddressDto(address.address, address.name)
	}
}

@Schema(description = "An occurrence of an event with the contact")
data class ContactEventDto(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val eventId: UUID,
	val seriesId: UUID?,
	@get:Schema(description = "Original start of the occurrence: an instant, or a date for all-day series") val occurrence: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val calendarId: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val title: String,
	val location: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val allDay: Boolean,
	val start: Instant?,
	val end: Instant?,
	val startDate: LocalDate?,
	@get:Schema(description = "Exclusive") val endDate: LocalDate?,
) {
	companion object {
		fun of(occurrence: Occurrence) =
			with(occurrence) {
				ContactEventDto(
					eventId = event.id,
					seriesId = seriesId,
					occurrence = occurrenceId?.let { it.at?.toString() ?: it.date.toString() },
					calendarId = event.calendarId,
					title = event.title,
					location = event.location,
					allDay = allDay,
					start = start,
					end = end,
					startDate = startDate,
					endDate = endDate,
				)
			}
	}
}

@Schema(description = "A Waiting For task for the contact")
data class ContactWaitingForDto(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val title: String,
	val waitingSince: LocalDate?,
	val dueDate: LocalDate?,
) {
	companion object {
		fun of(task: Task) = ContactWaitingForDto(task.id, task.title, task.waitingSince, task.dueDate)
	}
}

@Schema(description = "An autocomplete suggestion: a contact (`contactId` set) or a recent correspondent")
data class ContactSuggestionDto(
	@get:Schema(description = "Display name of the contact, or the name last seen with the address") val name: String?,
	@get:Schema(description = "`null` only for a contact without email address") val email: String?,
	@get:Schema(description = "`null` for a correspondent that is not a contact") val contactId: UUID?,
) {
	companion object {
		fun of(entry: AutocompleteEntry) = ContactSuggestionDto(entry.name, entry.email, entry.contact?.id)
	}
}
