package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactAddress
import ch.sonensei.cockpit.contacts.domain.ContactEmail
import ch.sonensei.cockpit.contacts.domain.ContactPhone
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.contacts.domain.VCardService
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * [CardDavMapping] without Spring: a contact is written into the server's card so that only the field groups that
 * differ are replaced and properties the cockpit does not model (URL, NICKNAME, name prefixes, UID, version) stay;
 * nothing is written when nothing differs. All names and addresses are fictitious.
 */
class CardDavMappingTests {
	private val mapping = CardDavMapping(VCardService(mockk(), mockk(), mockk()))

	private fun serverCard(
		version: String = "3.0",
		extra: String = "",
	): VCard =
		mapping
			.parse(
				"BEGIN:VCARD\r\nVERSION:$version\r\nUID:ada-uid-1\r\nFN:Ada Example\r\nN:Example;Ada;;Dr.;\r\n" +
					"EMAIL;TYPE=work:ada@example.test\r\nURL:https://ada.example.test\r\nNICKNAME:Addy\r\n" +
					extra + "END:VCARD\r\n",
			).shouldNotBeNull()

	/** The contact as the server's card has it (see [serverCard]). */
	private fun contact(configure: Contact.() -> Unit = {}): Contact =
		Contact(displayName = "Ada Example", source = ContactSource.CARDDAV)
			.apply {
				givenName = "Ada"
				familyName = "Example"
				emails = listOf(ContactEmail("ada@example.test", "work"))
			}.apply(configure)

	private fun read(text: String): VCard = Ezvcard.parse(text).first().shouldNotBeNull()

	@Test
	fun `nothing is written when no field group differs from the server's card`() {
		mapping.merged(serverCard(), contact()).shouldBeNull()
	}

	@Test
	fun `a changed email replaces only the emails, unknown properties and the UID stay`() {
		val merged = mapping.merged(serverCard(), contact { emails = listOf(ContactEmail("ada.new@example.test", "home")) })

		val card = read(merged.shouldNotBeNull())
		card.emails.map { it.value } shouldContainExactly listOf("ada.new@example.test")
		card.emails
			.single()
			.types
			.map { it.value.lowercase() } shouldContainExactly listOf("home")
		card.uid.value shouldBe "ada-uid-1"
		card.urls.map { it.value } shouldContainExactly listOf("https://ada.example.test")
		card.nickname.values shouldContainExactly listOf("Addy")
		card.formattedName.value shouldBe "Ada Example"
		card.structuredName.prefixes shouldContainExactly listOf("Dr.")
	}

	@Test
	fun `a changed family name keeps the name prefix the cockpit does not model`() {
		val merged = mapping.merged(serverCard(), contact { familyName = "Sample" })

		val name = read(merged.shouldNotBeNull()).structuredName
		name.given shouldBe "Ada"
		name.family shouldBe "Sample"
		name.prefixes shouldContainExactly listOf("Dr.")
	}

	@Test
	fun `a card without N gets one when the contact has a name`() {
		val server =
			mapping
				.parse("BEGIN:VCARD\r\nVERSION:3.0\r\nUID:bob-uid\r\nFN:Bob Example\r\nEND:VCARD\r\n")
				.shouldNotBeNull()
		val contact =
			Contact(displayName = "Bob Example", source = ContactSource.CARDDAV).apply {
				givenName = "Bob"
				familyName = "Example"
			}

		val name = read(mapping.merged(server, contact).shouldNotBeNull()).structuredName.shouldNotBeNull()
		name.given shouldBe "Bob"
		name.family shouldBe "Example"
	}

	@Test
	fun `a field cleared in the cockpit is removed from the card, the others stay`() {
		val server = serverCard(extra = "BDAY:1980-04-01\r\nNOTE:Met at a fictitious fair\r\nTITLE:Engineer\r\n")
		val contact =
			contact {
				birthday = null
				notesMd = "Met at a fictitious fair"
				title = "Engineer"
			}

		val card = read(mapping.merged(server, contact).shouldNotBeNull())
		card.birthday.shouldBeNull()
		card.notes.map { it.value } shouldContainExactly listOf("Met at a fictitious fair")
		card.titles.map { it.value } shouldContainExactly listOf("Engineer")
	}

	@Test
	fun `organization, title, phone, address and birthday changes are written`() {
		val contact =
			contact {
				organization = "Fictitious AG"
				title = "CTO"
				birthday = LocalDate.of(1990, 2, 3)
				phones = listOf(ContactPhone("+41 79 000 00 01", "cell"))
				addresses = listOf(ContactAddress(label = "home", street = "Musterweg 1", city = "Exampletown", country = "CH"))
			}

		val card = read(mapping.merged(serverCard(), contact).shouldNotBeNull())

		card.organization.values shouldContainExactly listOf("Fictitious AG")
		card.titles.map { it.value } shouldContainExactly listOf("CTO")
		card.birthday.date shouldBe LocalDate.of(1990, 2, 3)
		card.telephoneNumbers.map { it.text } shouldContainExactly listOf("+41 79 000 00 01")
		card.addresses.map { it.locality } shouldContainExactly listOf("Exampletown")
		card.urls.map { it.value } shouldContainExactly listOf("https://ada.example.test")
	}

	@Test
	fun `the server's vCard version is kept when writing into its card`() {
		val merged = mapping.merged(serverCard(version = "4.0"), contact { title = "Engineer" }).shouldNotBeNull()

		merged shouldContain "VERSION:4.0"
		read(merged).version shouldBe VCardVersion.V4_0
	}

	@Test
	fun `a new card is vCard 3_0 with the contact id as UID and no PRODID`() {
		val contact = contact()

		val text = mapping.newCard(contact)

		text shouldContain "VERSION:3.0"
		text shouldNotContain "PRODID"
		val card = read(text)
		card.uid.value shouldBe "urn:uuid:${contact.id}"
		card.formattedName.value shouldBe "Ada Example"
		card.emails.map { it.value } shouldContainExactly listOf("ada@example.test")
	}

	@Test
	fun `text without a vCard parses to null`() {
		mapping.parse("").shouldBeNull()
		mapping.parse("not a card at all").shouldBeNull()
	}

	@Test
	fun `changes read the contact fields of a card`() {
		val changes = mapping.changes(serverCard(extra = "TEL;TYPE=cell:+41 79 000 00 02\r\n"))

		changes.displayName?.value shouldBe "Ada Example"
		changes.givenName?.value shouldBe "Ada"
		changes.familyName?.value shouldBe "Example"
		changes.emails?.value.shouldNotBeNull() shouldContainExactly listOf(ContactEmail("ada@example.test", "work"))
		changes.phones
			?.value
			.shouldNotBeNull()
			.map { it.number } shouldContainExactly listOf("+41 79 000 00 02")
		val addresses = changes.addresses?.value.shouldNotBeNull()
		addresses.shouldBeEmpty()
		changes.photoFileId.shouldBeNull()
	}
}
