package ch.sonensei.cockpit.mail.model

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
		 * Parses an address list header value leniently, like most mail clients: it never fails, and valid entries
		 * are kept next to malformed ones. Groups are flattened to their members. Entries whose address has no `@`
		 * with text on both sides (e.g. `<`, `@@@`, an empty `<>`) are dropped. Everything else is kept as the
		 * parser read it, without further RFC 5322 validation, so a broken entry such as `Ann <ann@example.com`
		 * (no closing `>`) comes through with that text as its address.
		 */
		fun parseList(header: String?): List<MailAddress> {
			if (header.isNullOrBlank()) return emptyList()
			// Lenient mode (strict = false) does not throw on malformed input; it returns what it could read.
			return InternetAddress.parseHeader(header, false).flatMap { it.flatten() }.mapNotNull { mailbox ->
				mailbox.address?.takeIf(::hasMailboxShape)?.let { MailAddress(it, mailbox.personal?.takeIf(String::isNotBlank)) }
			}
		}

		private fun hasMailboxShape(address: String): Boolean {
			val at = address.lastIndexOf('@')
			return at > 0 && at < address.length - 1
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
