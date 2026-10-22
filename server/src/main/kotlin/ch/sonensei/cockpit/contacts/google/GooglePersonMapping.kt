package ch.sonensei.cockpit.contacts.google

import ch.sonensei.cockpit.contacts.domain.Change
import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactAddress
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactEmail
import ch.sonensei.cockpit.contacts.domain.ContactPhone
import ch.sonensei.cockpit.contacts.domain.ContactService
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Maps People API `Person` resources to contact fields and back. Only the fields a contact has are read and written:
 * the primary (else first) name, organization and biography, all email addresses, phone numbers and postal addresses,
 * and a birthday with a year. Google's `type` (`work`, `home`, a custom label) is the label.
 *
 * Google accepts data the cockpit does not (very long values, malformed email addresses, a person with only a phone
 * number), so reading cuts values to the contact limits, drops invalid entries and names a contact without name,
 * organization or email by its first phone number (else [UNNAMED]).
 */
object GooglePersonMapping {
	/** What is read and written; `metadata` tells deleted persons in incremental listings. */
	const val PERSON_FIELDS = "names,emailAddresses,phoneNumbers,addresses,organizations,birthdays,biographies,metadata"
	const val UPDATE_FIELDS = "names,emailAddresses,phoneNumbers,addresses,organizations,birthdays,biographies"
	const val UNNAMED = "Unnamed contact"

	private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+$")

	fun resourceName(person: JsonNode): String? = person.text("resourceName")

	fun etag(person: JsonNode): String? = person.text("etag")

	fun isDeleted(person: JsonNode): Boolean = person.path("metadata").path("deleted").asBoolean(false)

	/** Every contact field from [person]; fields Google does not have are cleared. */
	fun toChanges(person: JsonNode): ContactChanges {
		val name = primary(person.path("names"))
		val organization = primary(person.path("organizations"))
		val emails =
			person.path("emailAddresses").values().mapNotNull { node ->
				node.text("value")?.takeIf { EMAIL.matches(it) && it.length <= ContactService.MAX_FIELD_LENGTH }?.let {
					ContactEmail(it, node.text("type").cut())
				}
			}
		val phones =
			person.path("phoneNumbers").values().mapNotNull { node ->
				node.text("value").cut()?.let { ContactPhone(it, node.text("type").cut()) }
			}
		val addresses: List<ContactAddress> =
			person
				.path("addresses")
				.values()
				.map { node: JsonNode ->
					ContactAddress(
						label = node.text("type").cut(),
						street = node.text("streetAddress").cut(),
						city = node.text("city").cut(),
						region = node.text("region").cut(),
						postalCode = node.text("postalCode").cut(),
						country = node.text("country").cut(),
					)
				}.filter { address: ContactAddress -> address.parts().isNotEmpty() }
		val limited = limit(emails, phones, addresses)
		val givenName = name?.text("givenName").cut()
		val familyName = name?.text("familyName").cut()
		val organizationName = organization?.text("name").cut()
		val displayName =
			(name?.text("displayName") ?: name?.text("unstructuredName")).cut()
				?: if (givenName == null && familyName == null && organizationName == null && limited.first.isEmpty()) {
					limited.second.firstOrNull()?.number ?: UNNAMED
				} else {
					null
				}
		return ContactChanges(
			displayName = Change(displayName),
			givenName = Change(givenName),
			familyName = Change(familyName),
			emails = Change(limited.first),
			phones = Change(limited.second),
			addresses = Change(limited.third),
			organization = Change(organizationName),
			title = Change(organization?.text("title").cut()),
			birthday = Change(person.path("birthdays").firstNotNullOfOrNull { birthday(it.path("date")) }),
			notesMd = Change(primary(person.path("biographies"))?.text("value").cut(ContactService.MAX_NOTES_LENGTH)),
		)
	}

	/**
	 * The `Person` body of a create or update ([etag]: the person's etag for an update). Google derives the display
	 * name from the name parts; a contact without them sends its display name as `unstructuredName`.
	 */
	fun toPerson(
		json: JsonMapper,
		contact: Contact,
		etag: String?,
	): ObjectNode {
		val person = json.createObjectNode()
		etag?.let { person.put("etag", it) }
		val name = person.putArray("names").addObject()
		if (contact.givenName == null && contact.familyName == null) {
			name.put("unstructuredName", contact.displayName)
		} else {
			contact.givenName?.let { name.put("givenName", it) }
			contact.familyName?.let { name.put("familyName", it) }
		}
		val emails = person.putArray("emailAddresses")
		contact.emails.forEach { email -> emails.addObject().put("value", email.address).apply { email.label?.let { put("type", it) } } }
		val phones = person.putArray("phoneNumbers")
		contact.phones.forEach { phone -> phones.addObject().put("value", phone.number).apply { phone.label?.let { put("type", it) } } }
		val addresses = person.putArray("addresses")
		contact.addresses.forEach { address ->
			val node = addresses.addObject()
			address.label?.let { node.put("type", it) }
			address.street?.let { node.put("streetAddress", it) }
			address.city?.let { node.put("city", it) }
			address.region?.let { node.put("region", it) }
			address.postalCode?.let { node.put("postalCode", it) }
			address.country?.let { node.put("country", it) }
		}
		val organizations = person.putArray("organizations")
		if (contact.organization != null || contact.title != null) {
			organizations.addObject().apply {
				contact.organization?.let { put("name", it) }
				contact.title?.let { put("title", it) }
			}
		}
		val birthdays = person.putArray("birthdays")
		contact.birthday?.let { date ->
			birthdays
				.addObject()
				.putObject("date")
				.put("year", date.year)
				.put("month", date.monthValue)
				.put("day", date.dayOfMonth)
		}
		val biographies = person.putArray("biographies")
		contact.notesMd?.let { biographies.addObject().put("value", it).put("contentType", "TEXT_PLAIN") }
		return person
	}

	/** The entry Google marks `metadata.primary`, else the first. */
	private fun primary(list: JsonNode): JsonNode? =
		list.firstOrNull { it.path("metadata").path("primary").asBoolean(false) } ?: list.firstOrNull()

	/** A full date; Google's year-less birthdays (and invalid ones) are none. */
	private fun birthday(date: JsonNode): LocalDate? {
		val year = date.path("year").asInt(0)
		val month = date.path("month").asInt(0)
		val day = date.path("day").asInt(0)
		if (year == 0 || month == 0 || day == 0) return null
		return try {
			LocalDate.of(year, month, day)
		} catch (e: DateTimeException) {
			null
		}
	}

	/** At most [ContactService.MAX_ENTRIES] entries in total, emails first, then phones, then addresses. */
	private fun limit(
		emails: List<ContactEmail>,
		phones: List<ContactPhone>,
		addresses: List<ContactAddress>,
	): Triple<List<ContactEmail>, List<ContactPhone>, List<ContactAddress>> {
		val max = ContactService.MAX_ENTRIES
		val e = emails.take(max)
		val p = phones.take(max - e.size)
		return Triple(e, p, addresses.take(max - e.size - p.size))
	}

	private fun String?.cut(max: Int = ContactService.MAX_FIELD_LENGTH): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

	private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isString }?.asString()
}
