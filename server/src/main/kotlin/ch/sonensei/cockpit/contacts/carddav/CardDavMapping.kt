package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.VCardService
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.property.Address
import ezvcard.property.Birthday
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.Note
import ezvcard.property.Organization
import ezvcard.property.StructuredName
import ezvcard.property.Telephone
import ezvcard.property.Title
import ezvcard.property.VCardProperty

/**
 * Contacts as vCards for a CardDAV server. A card is read with [VCardService.toChanges] (like a vCard import, without
 * photo). A contact is written into the card the server has: only the field groups that differ from it are replaced,
 * so properties the cockpit does not model (photos, URLs, nicknames, name prefixes of unchanged names, ...) stay.
 */
internal class CardDavMapping(
	private val vcards: VCardService,
) {
	/** The first card of [text]; `null` when there is none. */
	fun parse(text: String): VCard? = Ezvcard.parse(text).first()

	/** The contact fields of [card]. */
	fun changes(card: VCard): ContactChanges = vcards.toChanges(card)

	/** A new card for [contact] (vCard 3.0, `UID` from the contact id). */
	fun newCard(contact: Contact): String = write(vcards.toVCard(contact), VCardVersion.V3_0)

	/**
	 * [server]'s card with the field groups of [contact] that differ from it; `null` when none differs (nothing to
	 * write). The card's `UID` and version stay.
	 */
	fun merged(
		server: VCard,
		contact: Contact,
	): String? {
		val theirs = vcards.toChanges(server)
		val ours = vcards.toVCard(contact)
		var changed = false

		fun replace(
			differs: Boolean,
			vararg types: Class<out VCardProperty>,
		) {
			if (!differs) return
			changed = true
			types.forEach { type ->
				server.removeProperties(type)
				ours.getProperties(type).forEach(server::addProperty)
			}
		}
		replace(theirs.displayName?.value != contact.displayName, FormattedName::class.java)
		if (theirs.givenName?.value != contact.givenName || theirs.familyName?.value != contact.familyName) {
			changed = true
			// Prefixes, suffixes and additional names the cockpit does not model stay.
			val name = server.structuredName ?: StructuredName().also { server.structuredName = it }
			name.given = contact.givenName
			name.family = contact.familyName
		}
		replace(theirs.emails?.value != contact.emails, Email::class.java)
		replace(theirs.phones?.value != contact.phones, Telephone::class.java)
		replace(theirs.addresses?.value != contact.addresses, Address::class.java)
		replace(theirs.organization?.value != contact.organization, Organization::class.java)
		replace(theirs.title?.value != contact.title, Title::class.java)
		replace(theirs.birthday?.value != contact.birthday, Birthday::class.java)
		replace(theirs.notesMd?.value != contact.notesMd, Note::class.java)
		return if (changed) write(server, server.version ?: VCardVersion.V3_0) else null
	}

	private fun write(
		card: VCard,
		version: VCardVersion,
	): String =
		Ezvcard
			.write(card)
			.version(version)
			.prodId(false)
			.go()
}
