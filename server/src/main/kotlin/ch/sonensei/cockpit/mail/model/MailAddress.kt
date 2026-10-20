package ch.sonensei.cockpit.mail.model

import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/** A mailbox of an address header: [address] plus the optional display [name] (decoded, Unicode). */
data class MailAddress(
	val address: String,
	val name: String? = null,
) {
	/** RFC 5322 form with the name quoted where needed, e.g. `"Doe, Ann" <ann@example.com>`. */
	override fun toString(): String = InternetAddress(address, name, Charsets.UTF_8.name()).toUnicodeString()

	companion object {
		/**
		 * Parses an address list header value (lenient, like most mail clients). Groups are flattened to their
		 * members; entries without an address are dropped. A list that cannot be parsed at all gives no addresses.
		 */
		fun parseList(header: String?): List<MailAddress> {
			if (header.isNullOrBlank()) return emptyList()
			val parsed =
				try {
					InternetAddress.parseHeader(header, false)
				} catch (_: AddressException) {
					return emptyList()
				}
			return parsed.flatMap { it.flatten() }.mapNotNull { mailbox ->
				mailbox.address?.takeIf { it.isNotBlank() }?.let { MailAddress(it, mailbox.personal?.takeIf(String::isNotBlank)) }
			}
		}

		fun formatList(addresses: List<MailAddress>): String = addresses.joinToString(", ")

		private fun InternetAddress.flatten(): List<InternetAddress> = if (isGroup) getGroup(false)?.toList().orEmpty() else listOf(this)
	}
}

/** Stores an address list as one RFC 5322 header value (`text`), so it stays readable and searchable in SQL. */
@Converter
class MailAddressListConverter : AttributeConverter<List<MailAddress>, String> {
	override fun convertToDatabaseColumn(attribute: List<MailAddress>?): String? =
		attribute?.takeIf { it.isNotEmpty() }?.let { MailAddress.formatList(it) }

	override fun convertToEntityAttribute(dbData: String?): List<MailAddress> = MailAddress.parseList(dbData)
}
