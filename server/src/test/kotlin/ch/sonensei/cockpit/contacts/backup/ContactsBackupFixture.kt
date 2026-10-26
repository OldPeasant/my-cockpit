package ch.sonensei.cockpit.contacts.backup

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.contacts.carddav.CardDavAccount
import ch.sonensei.cockpit.contacts.carddav.CardDavAccountRepository
import ch.sonensei.cockpit.contacts.carddav.CardDavLink
import ch.sonensei.cockpit.contacts.carddav.CardDavLinkRepository
import ch.sonensei.cockpit.contacts.domain.Change
import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactAddress
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactEmail
import ch.sonensei.cockpit.contacts.domain.ContactPhone
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.contacts.infra.ContactRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.files.domain.FilesService
import java.time.LocalDate

/**
 * Contacts with photos (drive files, one with spaces and brackets in its name), with every field set (several
 * labelled emails, phones and addresses, unicode, multi-line notes, a birthday), a minimal one, one in the Trash and
 * one of an external source with its external id; two CardDAV accounts (with and without password), one with a linked
 * contact and the tombstone of a purged one.
 */
class ContactsBackupFixture(
	private val contacts: ContactService,
	private val files: FilesService,
	private val rows: ContactRepository,
	private val carddavAccounts: CardDavAccountRepository,
	private val carddavLinks: CardDavLinkRepository,
) : BackupFixture {
	override val contributor = ContactsBackupContributor.ID

	override fun seed() {
		// Not in the special Attachments folder, as the vCard import would put them: the files fixture creates that one.
		val people = files.createFolder("People photos", null)
		val photo = files.storeFile(people.id, "Zoë portrait (1).png", "image/png", PNG.inputStream())
		val small = files.storeFile(people.id, "alex.jpg", "image/jpeg", JPEG.inputStream())
		contacts.create(
			ContactChanges(
				givenName = Change("Zoë"),
				familyName = Change("Müller-Exemple"),
				emails = Change(listOf(ContactEmail("zoe@example.test", "work"), ContactEmail("zoe.home@example.test", "home, pref"))),
				phones = Change(listOf(ContactPhone("+41 44 000 00 00", "mobile"), ContactPhone("044 000 00 01"))),
				addresses =
					Change(
						listOf(
							ContactAddress("home", "Rue du Lac 1", "Lausanne", "VD", "1000", "Switzerland"),
							ContactAddress(city = "Zürich"),
						),
					),
				organization = Change("Example «Labs» AG"),
				title = Change("Engineer; \"lead\""),
				birthday = Change(LocalDate.parse("1990-02-28")),
				notesMd = Change("Met at the *meetup*\n\n- likes tea ☕"),
				photoFileId = Change(photo.id),
			),
		)
		contacts.create(
			ContactChanges(
				givenName = Change("Alex"),
				familyName = Change("Example"),
				emails = Change(listOf(ContactEmail("alex@example.test"))),
				photoFileId = Change(small.id),
			),
		)
		contacts.create(ContactChanges(organization = Change("Acme Corp")))
		val gone =
			contacts.create(
				ContactChanges(displayName = Change("Former colleague"), emails = Change(listOf(ContactEmail("old@example.test")))),
			)
		contacts.delete(gone.id) {}
		rows.saveAndFlush(
			Contact("Sam Synced", ContactSource.GOOGLE).apply {
				emails = listOf(ContactEmail("sam@example.test"))
				externalId = "people/c0000000000000000001"
			},
		)
		val account =
			carddavAccounts.saveAndFlush(
				CardDavAccount("Home server", "https://dav.example.test/dav/", "alice", EncryptedSecret("fictitious-carddav-pw")).apply {
					addressbookUrl = "https://dav.example.test/dav/alice/contacts/"
					syncToken = "https://dav.example.test/sync/1"
				},
			)
		val href = "/dav/alice/contacts/0192f000-card-0001.vcf"
		val card =
			rows.saveAndFlush(
				Contact("Robin Carddav", ContactSource.CARDDAV).apply {
					emails = listOf(ContactEmail("robin@example.test"))
					externalId = "https://dav.example.test$href"
				},
			)
		carddavLinks.saveAndFlush(CardDavLink(card.id, account.id, href, "\"etag-1\"", card.version))
		// The tombstone of a purged contact whose card the next push deletes.
		carddavLinks.saveAndFlush(CardDavLink(null, account.id, "/dav/alice/contacts/0192f000-card-0002.vcf", "\"etag-2\"", 3))
		// Without a password.
		carddavAccounts.saveAndFlush(CardDavAccount("Open server", "http://localhost:5232/", null, null))
	}

	companion object {
		/** The smallest PNG header; the content is never decoded. */
		private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
		private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
	}
}
