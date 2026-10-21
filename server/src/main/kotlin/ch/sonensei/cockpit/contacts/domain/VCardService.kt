package ch.sonensei.cockpit.contacts.domain

import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Address
import ezvcard.property.Birthday
import ezvcard.property.StructuredName
import ezvcard.property.Uid
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalQueries
import java.util.UUID

/** Result of a vCard import: the created contacts, and the cards that were skipped with the reason. */
data class VCardImport(
	val imported: List<Contact>,
	val skipped: List<SkippedVCard>,
)

/** A card of an import that did not become a contact; [index] counts the cards of the file from 0. */
data class SkippedVCard(
	val index: Int,
	val reason: String,
)

/**
 * vCard import and export of contacts (SPEC.md §8.3), with ez-vcard.
 *
 * Import reads every card of a file (vCard 2.1, 3.0 or 4.0) as a new LOCAL contact. Each card is created in a
 * transaction of its own: an invalid card (e.g. no name, organization or email address) is skipped and reported, the
 * others are still imported. An embedded photo is stored in the drive's `/Attachments` folder and becomes the
 * contact's photo; photos only referenced by URL are ignored. Birthdays without a year are ignored too.
 *
 * Export writes vCard 3.0 (the widest supported version), with the contact id as `UID`; photos are not embedded.
 */
@Service
class VCardService(
	private val contacts: ContactService,
	private val files: FilesService,
	private val transactions: TransactionTemplate,
) {
	/**
	 * Imports every card of [text].
	 *
	 * @throws InvalidRequestException (400) when [text] contains no vCard
	 */
	fun import(text: String): VCardImport {
		val cards = Ezvcard.parse(text).all()
		if (cards.isEmpty()) throw InvalidRequestException("file", "contains no vCard")
		if (cards.size > MAX_CARDS) throw InvalidRequestException("file", "contains more than $MAX_CARDS vCards")
		val imported = mutableListOf<Contact>()
		val skipped = mutableListOf<SkippedVCard>()
		cards.forEachIndexed { index, card ->
			try {
				imported += checkNotNull(transactions.execute { importCard(card) })
			} catch (e: InvalidRequestException) {
				skipped += SkippedVCard(index, e.fieldErrors.joinToString("; ") { "${it.field} ${it.message}" })
			}
		}
		log.info("vCard import: {} contacts imported, {} cards skipped", imported.size, skipped.size)
		return VCardImport(imported, skipped)
	}

	/** The contacts as one vCard 3.0 file. */
	fun export(list: List<Contact>): String =
		Ezvcard
			.write(list.map(::toVCard))
			.version(VCardVersion.V3_0)
			.prodId(false)
			.go()

	private fun importCard(card: VCard): Contact {
		val fields = toChanges(card)
		val photo = photoOf(card, fields)
		return contacts.create(if (photo == null) fields else fields.copy(photoFileId = Change(photo)))
	}

	private fun toChanges(card: VCard): ContactChanges {
		val name: StructuredName? = card.structuredName
		return ContactChanges(
			displayName = Change(card.formattedName?.value),
			givenName = Change(name?.given),
			familyName = Change(name?.family),
			emails = Change(card.emails.filter { !it.value.isNullOrBlank() }.map { ContactEmail(it.value, label(it.types.map { t -> t.value })) }),
			phones =
				Change(
					card.telephoneNumbers
						.mapNotNull { tel -> (tel.text ?: tel.uri?.number)?.let { ContactPhone(it, label(tel.types.map { t -> t.value })) } }
						.filter { it.number.isNotBlank() },
				),
			addresses =
				Change(
					card.addresses
						.map {
							ContactAddress(
								label = label(it.types.map { t -> t.value }),
								street = it.streetAddress,
								city = it.locality,
								region = it.region,
								postalCode = it.postalCode,
								country = it.country,
							)
						}.filter { it.parts().isNotEmpty() },
				),
			organization =
				Change(
					card.organization
						?.values
						?.filter { it.isNotBlank() }
						?.joinToString(", ")
						?.ifBlank { null },
				),
			title = Change(card.titles.firstOrNull()?.value),
			birthday = Change(card.birthday?.date?.let(::fullDate)),
			notesMd =
				Change(
					card.notes
						.mapNotNull { it.value }
						.joinToString("\n\n")
						.ifBlank { null },
				),
		)
	}

	/** Stores the first embedded photo in `/Attachments`; `null` when there is none or it is too large. */
	private fun photoOf(
		card: VCard,
		fields: ContactChanges,
	): UUID? {
		val photo = card.photos.firstOrNull { it.data != null && it.data.isNotEmpty() } ?: return null
		if (photo.data.size > MAX_PHOTO_BYTES) return null
		val type = photo.contentType
		// The rule of the drive's file names (FileNames): no separators and no control characters.
		val name =
			(fields.displayName?.value ?: fields.familyName?.value ?: "Contact")
				.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
				.joinToString("")
				.trim()
				.take(100)
		val extension = type?.extension?.let { ".$it" } ?: ".jpg"
		val folder = files.specialFolder(SpecialFolder.ATTACHMENTS)
		return files.storeFile(folder.id, "Contact photo $name$extension", type?.mediaType ?: "image/jpeg", photo.data.inputStream()).id
	}

	private fun toVCard(contact: Contact): VCard =
		VCard().apply {
			uid = Uid("urn:uuid:${contact.id}")
			setFormattedName(contact.displayName)
			structuredName =
				StructuredName().apply {
					given = contact.givenName
					family = contact.familyName
				}
			contact.emails.forEach { email -> addEmail(email.address, *labelTypes(email.label).map(EmailType::get).toTypedArray()) }
			contact.phones.forEach { phone -> addTelephoneNumber(phone.number, *labelTypes(phone.label).map(TelephoneType::get).toTypedArray()) }
			contact.addresses.forEach { address ->
				addAddress(
					Address().apply {
						streetAddress = address.street
						locality = address.city
						region = address.region
						postalCode = address.postalCode
						country = address.country
						labelTypes(address.label).forEach { types.add(AddressType.get(it)) }
					},
				)
			}
			contact.organization?.let { setOrganization(it) }
			contact.title?.let { addTitle(it) }
			contact.birthday?.let { birthday = Birthday(it) }
			contact.notesMd?.let { addNote(it) }
		}

	/** The vCard types as one label (`work`, `home, voice`); the `pref` and `internet` markers are left out. */
	private fun label(types: List<String?>): String? =
		types
			.filterNotNull()
			.map { it.lowercase() }
			.filter { it !in IGNORED_TYPES }
			.joinToString(", ")
			.ifBlank { null }

	/**
	 * A label as vCard types: `home, voice` → `home`, `voice`. A parameter value cannot hold `"`, CR or LF, so these
	 * become `'` and spaces.
	 */
	private fun labelTypes(label: String?): List<String> =
		label
			?.split(',')
			?.map {
				it
					.replace('"', '\'')
					.replace('\r', ' ')
					.replace('\n', ' ')
					.trim()
			}?.filter { it.isNotEmpty() }
			.orEmpty()

	private fun fullDate(date: TemporalAccessor): LocalDate? = date.query(TemporalQueries.localDate())

	companion object {
		const val MAX_CARDS = 10_000
		const val MAX_PHOTO_BYTES = 5 * 1024 * 1024
		private val IGNORED_TYPES = setOf("pref", "internet", "x400")
		private val log = LoggerFactory.getLogger(VCardService::class.java)
	}
}
