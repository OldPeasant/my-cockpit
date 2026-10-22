package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.core.network.generated.models.ContactAddressDto
import ch.sonensei.cockpit.core.network.generated.models.ContactEventDto
import ch.sonensei.cockpit.core.network.generated.models.ContactMailDto
import ch.sonensei.cockpit.core.network.generated.models.ContactRelatedResponse
import ch.sonensei.cockpit.core.network.generated.models.ContactResponse
import ch.sonensei.cockpit.core.network.generated.models.LinkResponse
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A contact as the app shows it (SPEC.md §8.3). */
data class Contact(
    val id: UUID,
    val displayName: String,
    val organization: String?,
    val title: String?,
    val emails: List<LabeledValue>,
    val phones: List<LabeledValue>,
    /** One line each, e.g. `Bahnhofstrasse 1, 8001 Zürich, CH`. */
    val addresses: List<LabeledValue>,
    val birthday: LocalDate?,
    val notesMd: String?,
    /** `LOCAL`, `GOOGLE`, `MICROSOFT` or `CARDDAV`. */
    val source: String,
)

/** A phone number, email or address with its free-text label (`mobile`, `work`, ...). */
data class LabeledValue(
    val value: String,
    val label: String?,
)

/** What the contact detail shows besides the contact itself (SPEC.md §8.3). */
data class ContactRelated(
    val waitingFor: List<RelatedWaiting>,
    val mails: List<RelatedMail>,
    val events: List<RelatedEvent>,
    val links: List<RelatedLink>,
)

/** An open Waiting For task for the contact. */
data class RelatedWaiting(
    val id: UUID,
    val title: String,
    val waitingSince: LocalDate?,
)

/** A recent mail from (`fromContact`) or to the contact. */
data class RelatedMail(
    val id: UUID,
    val subject: String?,
    val fromContact: Boolean,
    val at: Instant?,
)

/** An upcoming event with the contact as attendee: [start] for a timed one, else [startDate]. */
data class RelatedEvent(
    val eventId: UUID,
    val title: String,
    val start: Instant?,
    val startDate: LocalDate?,
)

/** An item linked to the contact by hand or by sync. */
data class RelatedLink(
    val id: UUID,
    /** The item type, e.g. `NOTE` or `TASK`. */
    val type: String,
    val title: String?,
    val subtitle: String?,
    /** `false` when the item was deleted. */
    val available: Boolean,
)

internal fun ContactResponse.toContact() =
    Contact(
        id = id,
        displayName = displayName,
        organization = organization?.takeIf { it.isNotBlank() },
        title = title?.takeIf { it.isNotBlank() },
        emails = emails.filter { it.address.isNotBlank() }.map { LabeledValue(it.address.trim(), it.label) },
        phones = phones.filter { it.number.isNotBlank() }.map { LabeledValue(it.number.trim(), it.label) },
        addresses = addresses.mapNotNull { a -> a.oneLine()?.let { LabeledValue(it, a.label) } },
        birthday = birthday,
        notesMd = notesMd?.takeIf { it.isNotBlank() },
        source = source.value,
    )

private fun ContactAddressDto.oneLine(): String? {
    val town = listOfNotNull(postalCode, city).filter { it.isNotBlank() }.joinToString(" ")
    return listOfNotNull(street, town, region, country)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ")
        .ifEmpty { null }
}

internal fun ContactRelatedResponse.toRelated(links: List<LinkResponse>) =
    ContactRelated(
        waitingFor = waitingFor.map { RelatedWaiting(it.id, it.title, it.waitingSince) },
        mails = mails.map { it.toRelatedMail() },
        events = events.map { it.toRelatedEvent() },
        links = links.map { it.toRelatedLink() },
    )

private fun ContactMailDto.toRelatedMail() =
    RelatedMail(
        id = id,
        subject = subject?.takeIf { it.isNotBlank() },
        fromContact = direction == ContactMailDto.Direction.FROM,
        at = (receivedAt ?: sentAt)?.toInstant(),
    )

private fun ContactEventDto.toRelatedEvent() = RelatedEvent(eventId, title, start?.toInstant(), startDate)

/** The other end of [this] link, seen from the contact. */
private fun LinkResponse.toRelatedLink(): RelatedLink {
    val other = if (direction == LinkResponse.Direction.OUTGOING) target else source
    return RelatedLink(id, other.type.value, other.title, other.subtitle, other.available)
}
