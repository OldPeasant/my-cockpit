package ch.sonensei.cockpit.contacts.api

import ch.sonensei.cockpit.contacts.domain.Change
import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactAddress
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactEmail
import ch.sonensei.cockpit.contacts.domain.ContactPhone
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.contacts.domain.SkippedVCard
import ch.sonensei.cockpit.contacts.domain.VCardImport
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Schema(description = "A contact. `version` is also sent as the ETag.")
data class ContactResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val displayName: String,
	val givenName: String?,
	val familyName: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val emails: List<ContactEmailDto>,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val phones: List<ContactPhoneDto>,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val addresses: List<ContactAddressDto>,
	val organization: String?,
	val title: String?,
	val birthday: LocalDate?,
	val notesMd: String?,
	@get:Schema(description = "A file of the drive (`GET /files/{id}/content`)") val photoFileId: UUID?,
	@get:Schema(description = "Where the contact lives; only LOCAL ones are created here", requiredMode = RequiredMode.REQUIRED)
	val source: ContactSource,
	@get:Schema(description = "Id of the contact at its source; `null` for LOCAL") val externalId: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(contact: Contact) =
			with(contact) {
				ContactResponse(
					id = id,
					displayName = displayName,
					givenName = givenName,
					familyName = familyName,
					emails = emails.map { ContactEmailDto(it.address, it.label) },
					phones = phones.map { ContactPhoneDto(it.number, it.label) },
					addresses = addresses.map(ContactAddressDto::of),
					organization = organization,
					title = title,
					birthday = birthday,
					notesMd = notesMd,
					photoFileId = photoFileId,
					source = source,
					externalId = externalId,
					createdAt = checkNotNull(createdAt),
					updatedAt = checkNotNull(updatedAt),
					version = version,
				)
			}
	}
}

@Schema(description = "An email address of a contact")
data class ContactEmailDto(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val address: String? = null,
	@get:Schema(description = "Free text, e.g. `work` or `home`") val label: String? = null,
) {
	fun toDomain() = ContactEmail(address.orEmpty(), label)
}

@Schema(description = "A phone number of a contact, as entered")
data class ContactPhoneDto(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val number: String? = null,
	@get:Schema(description = "Free text, e.g. `mobile` or `work`") val label: String? = null,
) {
	fun toDomain() = ContactPhone(number.orEmpty(), label)
}

@Schema(description = "A postal address of a contact; at least one part must be set")
data class ContactAddressDto(
	@get:Schema(description = "Free text, e.g. `home`") val label: String? = null,
	val street: String? = null,
	val city: String? = null,
	val region: String? = null,
	val postalCode: String? = null,
	val country: String? = null,
) {
	fun toDomain() = ContactAddress(label, street, city, region, postalCode, country)

	companion object {
		fun of(address: ContactAddress) =
			ContactAddressDto(address.label, address.street, address.city, address.region, address.postalCode, address.country)
	}
}

@Schema(description = "One page of contacts")
data class ContactPageResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<ContactResponse>,
	@get:Schema(description = "Cursor of the next page; `null` on the last page") val nextCursor: String?,
)

/** Body of create and PATCH: on PATCH only the fields sent change, `null` clears one (lists: `null` or `[]`). */
@Schema(
	description =
		"A contact's editable fields. On create, `displayName` is derived when omitted (given and family name, else " +
			"organization, else the first email address); one of them is required. `source` and `externalId` are read-only.",
)
data class ContactRequest(
	@get:Schema(maxLength = 500) val displayName: String? = null,
	@get:Schema(maxLength = 500) val givenName: String? = null,
	@get:Schema(maxLength = 500) val familyName: String? = null,
	val emails: List<ContactEmailDto>? = null,
	val phones: List<ContactPhoneDto>? = null,
	val addresses: List<ContactAddressDto>? = null,
	@get:Schema(maxLength = 500) val organization: String? = null,
	@get:Schema(maxLength = 500) val title: String? = null,
	val birthday: LocalDate? = null,
	@get:Schema(maxLength = 100_000) val notesMd: String? = null,
	@get:Schema(description = "A file of the drive") val photoFileId: UUID? = null,
) {
	/** The changes of the fields in [sent] (all of them for a create). */
	fun toChanges(sent: Set<String>): ContactChanges {
		fun <V> change(
			name: String,
			value: V,
		): Change<V>? = if (name in sent) Change(value) else null
		return ContactChanges(
			displayName = change("displayName", displayName),
			givenName = change("givenName", givenName),
			familyName = change("familyName", familyName),
			emails = change("emails", emails.orEmpty().map { it.toDomain() }),
			phones = change("phones", phones.orEmpty().map { it.toDomain() }),
			addresses = change("addresses", addresses.orEmpty().map { it.toDomain() }),
			organization = change("organization", organization),
			title = change("title", title),
			birthday = change("birthday", birthday),
			notesMd = change("notesMd", notesMd),
			photoFileId = change("photoFileId", photoFileId),
		)
	}

	companion object {
		val FIELDS =
			setOf(
				"displayName",
				"givenName",
				"familyName",
				"emails",
				"phones",
				"addresses",
				"organization",
				"title",
				"birthday",
				"notesMd",
				"photoFileId",
			)
	}
}

@Schema(description = "Result of a vCard import")
data class VCardImportResponse(
	@get:Schema(description = "The contacts created, in file order", requiredMode = RequiredMode.REQUIRED)
	val imported: List<ContactResponse>,
	@get:Schema(description = "Cards that did not become a contact", requiredMode = RequiredMode.REQUIRED)
	val skipped: List<SkippedVCardDto>,
) {
	companion object {
		fun of(result: VCardImport) = VCardImportResponse(result.imported.map(ContactResponse::of), result.skipped.map(SkippedVCardDto::of))
	}
}

@Schema(description = "A card of an import that was skipped")
data class SkippedVCardDto(
	@get:Schema(description = "Position of the card in the file, from 0", requiredMode = RequiredMode.REQUIRED) val index: Int,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val reason: String,
) {
	companion object {
		fun of(skipped: SkippedVCard) = SkippedVCardDto(skipped.index, skipped.reason)
	}
}
