package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.core.network.generated.models.ContactAddressDto
import ch.sonensei.cockpit.core.network.generated.models.ContactEmailDto
import ch.sonensei.cockpit.core.network.generated.models.ContactPhoneDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** The contact form model: what it needs before it can be saved, and the request it becomes. */
class ContactFormTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val photo = id(99)

    private val anna =
        Contact(
            id = id(1),
            displayName = "Anna Muster",
            organization = "Beispiel AG",
            title = "Engineer",
            emails = listOf(LabeledValue("anna@example.org", "work"), LabeledValue("anna.m@example.org", null)),
            phones = listOf(LabeledValue("+41 44 123 45 67", "mobile")),
            addresses = listOf(LabeledValue("Musterweg 1, 8000 Zürich", "home")),
            birthday = LocalDate.of(1990, 5, 17),
            notesMd = "Met at the meetup",
            source = "LOCAL",
            version = 7,
            givenName = "Anna",
            familyName = "Muster",
            addressFields = listOf(ContactAddressDto(street = "Musterweg 1", postalCode = "8000", city = "Zürich", label = "home")),
            photoFileId = photo,
        )

    @Test
    fun `a new form starts with one empty email and phone row and needs a name`() {
        val form = ContactForm.empty()
        assertEquals(listOf(LabeledField()), form.emails)
        assertEquals(listOf(LabeledField()), form.phones)
        assertEquals(emptyList<AddressField>(), form.addresses)
        assertEquals(ContactFormProblem.NAME_REQUIRED, form.problem())
    }

    @Test
    fun `whitespace-only names, organization and emails do not name the contact`() {
        val form =
            ContactForm(
                givenName = " ",
                familyName = "\t",
                displayName = "  ",
                organization = " ",
                emails = listOf(LabeledField("  ", "work")),
                phones = listOf(LabeledField("+41 44 000 00 00")),
            )
        assertEquals(ContactFormProblem.NAME_REQUIRED, form.problem())
    }

    @Test
    fun `a given name, family name, display name, organization or email alone is enough`() {
        assertNull(ContactForm(givenName = "Anna").problem())
        assertNull(ContactForm(familyName = "Muster").problem())
        assertNull(ContactForm(displayName = "Anna M.").problem())
        assertNull(ContactForm(organization = "Beispiel AG").problem())
        assertNull(ContactForm(emails = listOf(LabeledField(""), LabeledField("anna@example.org"))).problem())
    }

    @Test
    fun `the birthday must be an ISO date, and blank means none`() {
        assertNull(ContactForm(givenName = "Anna", birthday = "   ").problem())
        assertNull(ContactForm(givenName = "Anna", birthday = " 1990-05-17 ").problem())
        assertEquals(ContactFormProblem.INVALID_BIRTHDAY, ContactForm(givenName = "Anna", birthday = "17.05.1990").problem())
        assertEquals(ContactFormProblem.INVALID_BIRTHDAY, ContactForm(givenName = "Anna", birthday = "1990-5-17").problem())
        // ISO dates are resolved strictly: there is no 30 February.
        assertEquals(ContactFormProblem.INVALID_BIRTHDAY, ContactForm(givenName = "Anna", birthday = "2026-02-30").problem())
        assertEquals(LocalDate.of(1990, 5, 17), ContactForm(givenName = "Anna", birthday = " 1990-05-17 ").toRequest().birthday)
    }

    @Test
    fun `a missing name is reported before an invalid birthday`() {
        assertEquals(ContactFormProblem.NAME_REQUIRED, ContactForm(birthday = "nope").problem())
    }

    @Test
    fun `the request trims every text, leaves blanks out and drops empty rows`() {
        val form =
            ContactForm(
                givenName = "  Carla ",
                familyName = " ",
                displayName = "",
                organization = " Beispiel AG ",
                title = "  ",
                emails =
                    listOf(
                        LabeledField(" carla@example.org ", " work "),
                        LabeledField("   ", "home"),
                        LabeledField("c@example.org", "  "),
                    ),
                phones = listOf(LabeledField("", "mobile"), LabeledField(" 079 000 00 01 ", "")),
                addresses =
                    listOf(
                        AddressField(street = " ", city = " ", label = "old"),
                        AddressField(city = " Bern ", country = "CH", label = " "),
                        AddressField(street = "Musterweg 1", postalCode = "8000", city = "Zürich", region = "ZH", label = "home"),
                    ),
                birthday = "",
                notes = "  Prefers mail \n",
            )
        val request = form.toRequest()

        assertEquals("Carla", request.givenName)
        assertNull(request.familyName)
        assertNull(request.displayName)
        assertEquals("Beispiel AG", request.organization)
        assertNull(request.title)
        assertEquals(listOf(ContactEmailDto("carla@example.org", "work"), ContactEmailDto("c@example.org", null)), request.emails)
        assertEquals(listOf(ContactPhoneDto("079 000 00 01", null)), request.phones)
        assertEquals(
            listOf(
                ContactAddressDto(city = "Bern", country = "CH"),
                ContactAddressDto(street = "Musterweg 1", postalCode = "8000", city = "Zürich", region = "ZH", label = "home"),
            ),
            request.addresses,
        )
        assertNull(request.birthday)
        assertEquals("Prefers mail", request.notesMd)
        assertNull(request.photoFileId)
    }

    @Test
    fun `an empty form sends empty lists, so removed rows are removed on the server`() {
        val request = ContactForm(givenName = "Anna").toRequest()
        assertEquals(emptyList<ContactEmailDto>(), request.emails)
        assertEquals(emptyList<ContactPhoneDto>(), request.phones)
        assertEquals(emptyList<ContactAddressDto>(), request.addresses)
    }

    @Test
    fun `editing a contact fills every field and keeps its photo`() {
        val form = ContactForm.of(anna)
        assertEquals("Anna", form.givenName)
        assertEquals("Muster", form.familyName)
        assertEquals("Beispiel AG", form.organization)
        assertEquals("Engineer", form.title)
        assertEquals(listOf(LabeledField("anna@example.org", "work"), LabeledField("anna.m@example.org", "")), form.emails)
        assertEquals(listOf(LabeledField("+41 44 123 45 67", "mobile")), form.phones)
        assertEquals(listOf(AddressField(street = "Musterweg 1", postalCode = "8000", city = "Zürich", label = "home")), form.addresses)
        assertEquals("1990-05-17", form.birthday)
        assertEquals("Met at the meetup", form.notes)
        assertEquals(photo, form.photoFileId)
        assertNull(form.problem())
    }

    @Test
    fun `saving an unchanged contact sends what it had, the photo included`() {
        val request = ContactForm.of(anna).toRequest()
        assertEquals("Anna", request.givenName)
        assertEquals("Muster", request.familyName)
        assertNull(request.displayName)
        assertEquals(listOf(ContactEmailDto("anna@example.org", "work"), ContactEmailDto("anna.m@example.org", null)), request.emails)
        assertEquals(anna.addressFields, request.addresses)
        assertEquals(LocalDate.of(1990, 5, 17), request.birthday)
        assertEquals(photo, request.photoFileId)
    }

    @Test
    fun `a display name derived from the name is left empty, one set by hand is kept`() {
        assertEquals("", ContactForm.of(anna).displayName)
        assertEquals("", ContactForm.of(anna.copy(displayName = "Muster", givenName = null)).displayName)
        assertEquals("Dr. Anna Muster", ContactForm.of(anna.copy(displayName = "Dr. Anna Muster")).displayName)
        // Without any name, organization or email the display name can only have been set by hand.
        val bert = anna.copy(displayName = "Bert", givenName = null, familyName = null, organization = null, emails = emptyList())
        assertEquals("Bert", ContactForm.of(bert).displayName)
    }

    @Test
    fun `a display name the server derived from the organization or the first email is left empty, like on the web`() {
        // The server (and the web's derivedName) falls back to the organization, then the first email address.
        val company = anna.copy(displayName = "Beispiel AG", givenName = null, familyName = null)
        assertEquals("", ContactForm.of(company).displayName)
        val mailOnly = anna.copy(displayName = "anna@example.org", givenName = null, familyName = null, organization = null)
        assertEquals("", ContactForm.of(mailOnly).displayName)
    }

    @Test
    fun `a contact without optional fields edits as empty texts`() {
        val bare = Contact(id(2), "Bert", null, null, emptyList(), emptyList(), emptyList(), null, null, "LOCAL")
        assertEquals(ContactForm(displayName = "Bert"), ContactForm.of(bare))
    }
}
