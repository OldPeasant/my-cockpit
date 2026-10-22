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
import tools.jackson.databind.node.ArrayNode
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
	const val UNNAMED = "Unnamed contact"

	private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+$")

	fun resourceName(person: JsonNode): String? = person.text("resourceName")

	fun etag(person: JsonNode): String? = person.text("etag")

	fun isDeleted(person: JsonNode): Boolean = person.path("metadata").path("deleted").asBoolean(false)

	/** Every contact field from [person]; fields Google does not have are cleared. */
	fun toChanges(person: JsonNode): ContactChanges {
		val name = primary(person.path("names"))
		val organization = primary(person.path("organizations"))
		val emails = person.path("emailAddresses").values().mapNotNull(::email)
		val phones = person.path("phoneNumbers").values().mapNotNull(::phone)
		val addresses = person.path("addresses").values().mapNotNull(::address)
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
	 * name from the name parts; a contact without them sends its display name as `unstructuredName`, unless that name
	 * is only the one the cockpit derived from the other fields (organization, email, phone, [UNNAMED]).
	 */
	fun toPerson(
		json: JsonMapper,
		contact: Contact,
		etag: String?,
	): ObjectNode {
		val person = json.createObjectNode()
		etag?.let { person.put("etag", it) }
		val names = person.putArray("names")
		if (contact.givenName != null || contact.familyName != null || contact.displayName != derivedName(contact)) {
			writeName(names.addObject(), contact)
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

	/** An `updateContact` body and its `updatePersonFields`; [fields] is empty when the contact changed nothing Google has. */
	class Update(
		val person: ObjectNode,
		val fields: List<String>,
	)

	/**
	 * The `updateContact` body for [contact], based on [current], the person as Google has it now: only the field
	 * groups where the contact differs from what [toChanges] reads from [current] are sent, so the others stay as they
	 * are at Google. A sent group starts from Google's entries and changes only what the contact models, so parts the
	 * cockpit does not show (a middle name, a year-less birthday, an address' P.O. box, entries it cannot read) stay.
	 * A display name the cockpit derived (phone, organization, [UNNAMED]) is not pushed as a name.
	 */
	fun toUpdate(
		json: JsonMapper,
		contact: Contact,
		current: JsonNode,
	): Update {
		val base = toChanges(current)
		val person = json.createObjectNode()
		etag(current)?.let { person.put("etag", it) }
		val fields = mutableListOf<String>()
		if (namesChanged(contact, base)) {
			mergeNames(person.putArray("names"), contact, current.path("names"))
			fields += "names"
		}
		if (contact.emails != base.emails.given()) {
			merge(person.putArray("emailAddresses"), current.path("emailAddresses"), contact.emails, ::email, ::writeEmail)
			fields += "emailAddresses"
		}
		if (contact.phones != base.phones.given()) {
			merge(person.putArray("phoneNumbers"), current.path("phoneNumbers"), contact.phones, ::phone, ::writePhone)
			fields += "phoneNumbers"
		}
		if (contact.addresses != base.addresses.given()) {
			merge(person.putArray("addresses"), current.path("addresses"), contact.addresses, ::address, ::writeAddress)
			fields += "addresses"
		}
		if (contact.organization != base.organization.given() || contact.title != base.title.given()) {
			mergePrimary(person.putArray("organizations"), current.path("organizations"), contact.organization != null || contact.title != null) {
				it.putOrRemove("name", contact.organization)
				it.putOrRemove("title", contact.title)
			}
			fields += "organizations"
		}
		if (contact.birthday != base.birthday.given()) {
			mergeBirthdays(person.putArray("birthdays"), current.path("birthdays"), contact.birthday)
			fields += "birthdays"
		}
		if (contact.notesMd != base.notesMd.given()) {
			mergePrimary(person.putArray("biographies"), current.path("biographies"), contact.notesMd != null) {
				it.putOrRemove("value", contact.notesMd)
				it.put("contentType", "TEXT_PLAIN")
			}
			fields += "biographies"
		}
		return Update(person, fields)
	}

	/**
	 * Whether the name changed: the given or family name, or the display name of a contact without them. The display
	 * name of a contact with given or family name is Google's (derived there from the parts), so it is not compared.
	 */
	private fun namesChanged(
		contact: Contact,
		base: ContactChanges,
	): Boolean {
		val givenName = base.givenName.given()
		val familyName = base.familyName.given()
		if (contact.givenName != givenName || contact.familyName != familyName) return true
		if (contact.givenName != null || contact.familyName != null) return false
		val displayName =
			base.displayName.given()
				?: base.organization.given()
				?: base.emails
					.given()
					.firstOrNull()
					?.address
		return contact.displayName != displayName
	}

	/** Changes the primary name in place (keeping its other parts and the other names), or drops a derived one. */
	private fun mergeNames(
		target: ArrayNode,
		contact: Contact,
		names: JsonNode,
	) {
		val keep = contact.givenName != null || contact.familyName != null || contact.displayName != derivedName(contact)
		mergePrimary(target, names, keep) { writeName(it, contact) }
	}

	private fun writeName(
		name: ObjectNode,
		contact: Contact,
	) {
		// Output only, or derived by Google from the parts.
		name.remove("displayName")
		name.remove("displayNameLastFirst")
		if (contact.givenName == null && contact.familyName == null) {
			name.remove("givenName")
			name.remove("familyName")
			name.put("unstructuredName", contact.displayName)
		} else {
			name.remove("unstructuredName")
			name.putOrRemove("givenName", contact.givenName)
			name.putOrRemove("familyName", contact.familyName)
		}
	}

	/**
	 * Writes [entries] with the primary (else first) one replaced by [write] on a copy of it (or a new entry), or left
	 * out when not [keep]; the other entries are kept.
	 */
	private fun mergePrimary(
		target: ArrayNode,
		entries: JsonNode,
		keep: Boolean,
		write: (ObjectNode) -> Unit,
	) {
		val primary = primary(entries)
		for (entry in entries) {
			when {
				entry !== primary -> target.add(entry)
				!keep -> Unit
				entry is ObjectNode -> target.add(entry.deepCopy().also(write))
				else -> write(target.addObject())
			}
		}
		if (primary == null && keep) write(target.addObject())
	}

	/**
	 * Writes [values] as entries: each one reuses Google's entry that reads as the same value, else the unused readable
	 * entry at its position, else a new one, and [write] changes in it what differs from the value read from it.
	 * Entries the contact cannot have (e.g. an invalid email address) are kept as they are, after the others.
	 */
	private fun <T : Any> merge(
		target: ArrayNode,
		entries: JsonNode,
		values: List<T>,
		reader: (JsonNode) -> T?,
		write: (ObjectNode, T, T?) -> Unit,
	) {
		val raw = entries.values().toList()
		val read = raw.map(reader)
		val readable = raw.indices.filter { read[it] != null }
		val used = BooleanArray(raw.size)
		val assigned = arrayOfNulls<Int>(values.size)
		values.forEachIndexed { i, value ->
			raw.indices.firstOrNull { !used[it] && read[it] == value }?.let {
				used[it] = true
				assigned[i] = it
			}
		}
		values.indices.filter { assigned[it] == null }.forEach { i ->
			readable.getOrNull(i)?.takeIf { !used[it] }?.let {
				used[it] = true
				assigned[i] = it
			}
		}
		values.forEachIndexed { i, value ->
			val index = assigned[i]
			val copy = index?.let { raw[it] as? ObjectNode }?.deepCopy()
			if (copy != null) target.add(copy)
			write(copy ?: target.addObject(), value, index?.let { read[it] })
		}
		raw.indices.filter { read[it] == null }.forEach { target.add(raw[it]) }
	}

	private fun writeEmail(
		node: ObjectNode,
		email: ContactEmail,
		old: ContactEmail?,
	) {
		if (old?.address != email.address) node.put("value", email.address)
		if (old?.label != email.label) node.setType(email.label)
	}

	private fun writePhone(
		node: ObjectNode,
		phone: ContactPhone,
		old: ContactPhone?,
	) {
		if (old?.number != phone.number) {
			node.put("value", phone.number)
			node.remove("canonicalForm")
		}
		if (old?.label != phone.label) node.setType(phone.label)
	}

	private fun writeAddress(
		node: ObjectNode,
		address: ContactAddress,
		old: ContactAddress?,
	) {
		if (old?.label != address.label) node.setType(address.label)
		val parts =
			listOf(
				"streetAddress" to ContactAddress::street,
				"city" to ContactAddress::city,
				"region" to ContactAddress::region,
				"postalCode" to ContactAddress::postalCode,
				"country" to ContactAddress::country,
			)
		var changed = false
		for ((field, part) in parts) {
			if (old?.let(part) != part(address)) {
				node.putOrRemove(field, part(address))
				changed = true
			}
		}
		if (changed) node.remove("formattedValue")
		if (old?.country != address.country) node.remove("countryCode")
	}

	/**
	 * Replaces the birthday [toChanges] reads (the first full date; else a year-less one, which the cockpit cannot
	 * show) with [birthday], or drops it when [birthday] is null; the other entries (a year-less one, a text) stay.
	 */
	private fun mergeBirthdays(
		target: ArrayNode,
		entries: JsonNode,
		date: LocalDate?,
	) {
		val raw = entries.values().toList()
		val full = raw.indexOfFirst { birthday(it.path("date")) != null }
		val index = if (full >= 0 || date == null) full else raw.indexOfFirst { it.path("date").isObject }
		raw.forEachIndexed { i, entry ->
			when {
				i != index -> target.add(entry)
				date == null -> Unit
				entry is ObjectNode -> target.add(entry.deepCopy().also { writeBirthday(it, date) })
				else -> writeBirthday(target.addObject(), date)
			}
		}
		if (index < 0 && date != null) writeBirthday(target.addObject(), date)
	}

	private fun writeBirthday(
		node: ObjectNode,
		birthday: LocalDate,
	) {
		node.remove("text")
		node
			.putObject("date")
			.put("year", birthday.year)
			.put("month", birthday.monthValue)
			.put("day", birthday.dayOfMonth)
	}

	/** The display name [toChanges] gives a person without a name but with the contact's other fields. */
	private fun derivedName(contact: Contact): String =
		contact.organization
			?: contact.emails.firstOrNull()?.address
			?: contact.phones.firstOrNull()?.number
			?: UNNAMED

	private fun email(node: JsonNode): ContactEmail? =
		node.text("value")?.takeIf { EMAIL.matches(it) && it.length <= ContactService.MAX_FIELD_LENGTH }?.let {
			ContactEmail(it, node.text("type").cut())
		}

	private fun phone(node: JsonNode): ContactPhone? = node.text("value").cut()?.let { ContactPhone(it, node.text("type").cut()) }

	private fun address(node: JsonNode): ContactAddress? =
		ContactAddress(
			label = node.text("type").cut(),
			street = node.text("streetAddress").cut(),
			city = node.text("city").cut(),
			region = node.text("region").cut(),
			postalCode = node.text("postalCode").cut(),
			country = node.text("country").cut(),
		).takeIf { it.parts().isNotEmpty() }

	/** Sets [field] to [value], or removes it when [value] is null. */
	private fun ObjectNode.putOrRemove(
		field: String,
		value: String?,
	) {
		if (value == null) remove(field) else put(field, value)
	}

	/** Google's `type` (the label); `formattedType` is derived from it. */
	private fun ObjectNode.setType(label: String?) {
		putOrRemove("type", label)
		remove("formattedType")
	}

	private fun <T> Change<T>?.given(): T = checkNotNull(this).value

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
