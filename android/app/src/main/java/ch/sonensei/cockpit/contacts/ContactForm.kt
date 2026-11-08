package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.core.network.generated.models.ContactAddressDto
import ch.sonensei.cockpit.core.network.generated.models.ContactEmailDto
import ch.sonensei.cockpit.core.network.generated.models.ContactPhoneDto
import ch.sonensei.cockpit.core.network.generated.models.ContactRequest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** A phone number or email address being edited, with its free-text label. */
data class LabeledField(
    val value: String = "",
    val label: String = "",
)

/** An address being edited, field by field. */
data class AddressField(
    val street: String = "",
    val postalCode: String = "",
    val city: String = "",
    val region: String = "",
    val country: String = "",
    val label: String = "",
) {
    internal fun isBlank() = listOf(street, postalCode, city, region, country).all { it.isBlank() }
}

/**
 * The contact form (the web's `contact-form`): every text as typed, repeatable emails, phones and addresses. A
 * contact needs a name, an organization or an email address; the server derives the display name from them.
 */
data class ContactForm(
    val givenName: String = "",
    val familyName: String = "",
    val displayName: String = "",
    val organization: String = "",
    val title: String = "",
    val emails: List<LabeledField> = emptyList(),
    val phones: List<LabeledField> = emptyList(),
    val addresses: List<AddressField> = emptyList(),
    /** ISO date (`1990-05-17`) as typed; blank for none. */
    val birthday: String = "",
    val notes: String = "",
    /** Kept from the edited contact: the form does not change the photo. */
    val photoFileId: UUID? = null,
) {
    /** The birthday as a date; `null` when blank or not a valid ISO date. */
    internal fun birthdayDate(): LocalDate? =
        birthday.trim().takeIf { it.isNotEmpty() }?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    /** What is wrong with the form, or `null` when it can be saved. */
    fun problem(): ContactFormProblem? =
        when {
            listOf(givenName, familyName, displayName, organization).all { it.isBlank() } &&
                emails.all { it.value.isBlank() } -> ContactFormProblem.NAME_REQUIRED
            birthday.isNotBlank() && birthdayDate() == null -> ContactFormProblem.INVALID_BIRTHDAY
            else -> null
        }

    /** The request for create and (as a full replacement: the generated client sends every field) update. */
    fun toRequest(): ContactRequest =
        ContactRequest(
            givenName = givenName.trimToNull(),
            familyName = familyName.trimToNull(),
            displayName = displayName.trimToNull(),
            organization = organization.trimToNull(),
            title = title.trimToNull(),
            emails = emails.filter { it.value.isNotBlank() }.map { ContactEmailDto(it.value.trim(), it.label.trimToNull()) },
            phones = phones.filter { it.value.isNotBlank() }.map { ContactPhoneDto(it.value.trim(), it.label.trimToNull()) },
            addresses =
                addresses.filterNot { it.isBlank() }.map {
                    ContactAddressDto(
                        street = it.street.trimToNull(),
                        postalCode = it.postalCode.trimToNull(),
                        city = it.city.trimToNull(),
                        region = it.region.trimToNull(),
                        country = it.country.trimToNull(),
                        label = it.label.trimToNull(),
                    )
                },
            birthday = birthdayDate(),
            notesMd = notes.trimToNull(),
            photoFileId = photoFileId,
        )

    companion object {
        /** A new contact: one empty email and phone row to start with. */
        fun empty() = ContactForm(emails = listOf(LabeledField()), phones = listOf(LabeledField()))

        /** [contact] as the form edits it; the display name only when it was set by hand (differs from the derived one). */
        fun of(contact: Contact) =
            ContactForm(
                givenName = contact.givenName.orEmpty(),
                familyName = contact.familyName.orEmpty(),
                displayName =
                    contact.displayName
                        .takeIf {
                            val derived = listOfNotNull(contact.givenName, contact.familyName).joinToString(" ")
                            derived.isEmpty() || it != derived
                        }.orEmpty(),
                organization = contact.organization.orEmpty(),
                title = contact.title.orEmpty(),
                emails = contact.emails.map { LabeledField(it.value, it.label.orEmpty()) },
                phones = contact.phones.map { LabeledField(it.value, it.label.orEmpty()) },
                addresses =
                    contact.addressFields.map {
                        AddressField(
                            street = it.street.orEmpty(),
                            postalCode = it.postalCode.orEmpty(),
                            city = it.city.orEmpty(),
                            region = it.region.orEmpty(),
                            country = it.country.orEmpty(),
                            label = it.label.orEmpty(),
                        )
                    },
                birthday = contact.birthday?.toString().orEmpty(),
                notes = contact.notesMd.orEmpty(),
                photoFileId = contact.photoFileId,
            )
    }
}

enum class ContactFormProblem { NAME_REQUIRED, INVALID_BIRTHDAY }

private fun String.trimToNull() = trim().takeIf { it.isNotEmpty() }
