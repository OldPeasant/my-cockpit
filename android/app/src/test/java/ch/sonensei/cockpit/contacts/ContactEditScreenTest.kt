package ch.sonensei.cockpit.contacts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/**
 * The contact form, the detail's Edit and Delete actions with its source line, and the list's New button, each
 * driven by states the tests control.
 */
@RunWith(AndroidJUnit4::class)
class ContactEditScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val anna =
        Contact(
            id = id(1),
            displayName = "Anna Muster",
            organization = "Beispiel AG",
            title = null,
            emails = listOf(LabeledValue("anna@example.org", "work")),
            phones = emptyList(),
            addresses = emptyList(),
            birthday = LocalDate.of(1990, 5, 17),
            notesMd = null,
            source = "LOCAL",
            version = 3,
            givenName = "Anna",
            familyName = "Muster",
        )
    private val calls = mutableListOf<String>()

    /** The form with its state kept here, as the view model would: every change is shown again. */
    private fun showForm(initial: ContactEditUiState): () -> ContactForm {
        var state by mutableStateOf(initial)
        composeRule.setContent {
            CockpitTheme {
                ContactEditContent(
                    state,
                    ContactEditCallbacks(
                        change = { state = state.copy(form = it) },
                        save = { calls += "save" },
                        cancel = { calls += "cancel" },
                    ),
                )
            }
        }
        return { state.form }
    }

    @Test
    fun `a new contact is typed in with repeatable emails, phones and addresses, then saved`() {
        val form = showForm(ContactEditUiState())
        composeRule.onNodeWithText("New contact").assertExists()

        composeRule.onNodeWithTag(ContactEditTags.GIVEN).performTextInput("Carla")
        composeRule.onNodeWithTag(ContactEditTags.FAMILY).performTextInput("Beispiel")
        composeRule.onNodeWithTag(ContactEditTags.ORGANIZATION).performTextInput("Beispiel AG")
        composeRule.onNodeWithTag(ContactEditTags.email(0)).performScrollTo().performTextInput("carla@example.org")
        composeRule.onNodeWithTag(ContactEditTags.ADD_EMAIL).performScrollTo().performClick()
        composeRule.onNodeWithTag(ContactEditTags.email(1)).performScrollTo().performTextInput("c.b@example.org")
        composeRule.onNodeWithTag(ContactEditTags.phone(0)).performScrollTo().performTextInput("+41 79 000 00 01")
        composeRule.onNodeWithTag(ContactEditTags.ADD_PHONE).performScrollTo().performClick()
        composeRule.onNodeWithTag(ContactEditTags.ADD_ADDRESS).performScrollTo().performClick()
        composeRule.onNodeWithTag(ContactEditTags.street(0)).performScrollTo().performTextInput("Musterweg 1")
        composeRule.onNodeWithTag(ContactEditTags.city(0)).performScrollTo().performTextInput("Zürich")
        composeRule.onNodeWithTag(ContactEditTags.BIRTHDAY).performScrollTo().performTextInput("1990-05-17")
        composeRule.onNodeWithTag(ContactEditTags.NOTES).performScrollTo().performTextInput("Met at the meetup")

        // The empty second phone row is removed again.
        composeRule.onNodeWithTag(ContactEditTags.remove("phone", 1)).performScrollTo().performClick()
        composeRule.onNodeWithTag(ContactEditTags.phone(1)).assertDoesNotExist()

        composeRule.onNodeWithTag(ContactEditTags.SAVE).performClick()
        assertEquals(listOf("save"), calls)
        val typed = form()
        assertEquals("Carla", typed.givenName)
        assertEquals("Beispiel", typed.familyName)
        assertEquals("Beispiel AG", typed.organization)
        assertEquals(listOf("carla@example.org", "c.b@example.org"), typed.emails.map { it.value })
        assertEquals(listOf("+41 79 000 00 01"), typed.phones.map { it.value })
        assertEquals(listOf(AddressField(street = "Musterweg 1", city = "Zürich")), typed.addresses)
        assertEquals("1990-05-17", typed.birthday)
        assertEquals("Met at the meetup", typed.notes)
    }

    @Test
    fun `removing a row removes that row, not the last one`() {
        val form =
            showForm(
                ContactEditUiState(
                    form = ContactForm(givenName = "Carla", emails = listOf(LabeledField("a@example.org"), LabeledField("b@example.org"))),
                ),
            )
        composeRule.onNodeWithTag(ContactEditTags.remove("email", 0)).performScrollTo().performClick()
        assertEquals(listOf(LabeledField("b@example.org")), form().emails)
        composeRule.onNodeWithTag(ContactEditTags.email(0)).assertTextContains("b@example.org")
        composeRule.onNodeWithTag(ContactEditTags.email(1)).assertDoesNotExist()
    }

    @Test
    fun `an address row is removed with its own button`() {
        val form =
            showForm(
                ContactEditUiState(
                    form = ContactForm(givenName = "Carla", addresses = listOf(AddressField(city = "Bern"), AddressField(city = "Zürich"))),
                ),
            )
        composeRule.onNodeWithTag(ContactEditTags.remove("address", 0)).performScrollTo().performClick()
        assertEquals(listOf(AddressField(city = "Zürich")), form().addresses)
    }

    @Test
    fun `editing shows the contact's fields under the edit title, and Cancel closes the form`() {
        val form = showForm(ContactEditUiState(form = ContactForm.of(anna), base = anna))
        composeRule.onNodeWithText("Edit contact").assertExists()
        composeRule.onNodeWithTag(ContactEditTags.GIVEN).assertTextContains("Anna")
        composeRule.onNodeWithTag(ContactEditTags.ORGANIZATION).assertTextContains("Beispiel AG")
        composeRule.onNodeWithTag(ContactEditTags.email(0)).performScrollTo().assertTextContains("anna@example.org")
        composeRule.onNodeWithTag(ContactEditTags.BIRTHDAY).performScrollTo().assertTextContains("1990-05-17")

        composeRule.onNodeWithTag(ContactEditTags.TITLE).performScrollTo().performTextReplacement("CEO")
        assertEquals("CEO", form().title)
        composeRule.onNodeWithTag(ContactEditTags.CANCEL).performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun `while saving, Save and Cancel are disabled`() {
        showForm(ContactEditUiState(form = ContactForm.of(anna), base = anna, saving = true))
        composeRule.onNodeWithTag(ContactEditTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(ContactEditTags.CANCEL).assertIsNotEnabled()
    }

    /** The node tagged [tag] shows exactly [text] (as a child: the tagged banner surface does not merge its text). */
    private fun assertShowsText(
        tag: String,
        text: String,
    ) {
        composeRule.onNodeWithTag(tag).assertExists()
        composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a missing name is shown as the form's problem`() {
        showForm(ContactEditUiState(problem = ContactFormProblem.NAME_REQUIRED))
        assertShowsText(ContactEditTags.PROBLEM, "Enter a name, an organization or an email address.")
        composeRule.onNodeWithTag(ContactEditTags.SAVE).assertIsEnabled()
        composeRule.onNodeWithTag(ContactEditTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun `an invalid birthday is shown as the form's problem`() {
        val form = ContactForm(givenName = "Carla", birthday = "17.5.")
        showForm(ContactEditUiState(form = form, problem = ContactFormProblem.INVALID_BIRTHDAY))
        assertShowsText(ContactEditTags.PROBLEM, "Enter the birthday as YYYY-MM-DD.")
    }

    @Test
    fun `a form without problem or error shows no banner`() {
        showForm(ContactEditUiState())
        composeRule.onNodeWithTag(ContactEditTags.PROBLEM).assertDoesNotExist()
        composeRule.onNodeWithTag(ContactEditTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun `a save without connection, a failed save and a conflict each say so`() {
        val cases =
            listOf(
                ContactSaveError.OFFLINE to "Can't reach the server. Nothing was saved.",
                ContactSaveError.FAILED to "The contact could not be saved.",
                ContactSaveError.CONFLICT to
                    "It was changed elsewhere in the meantime. Your changes are kept: save again to replace that version.",
            )
        var state by mutableStateOf(ContactEditUiState(base = anna))
        composeRule.setContent { CockpitTheme { ContactEditContent(state, ContactEditCallbacks()) } }
        for ((error, text) in cases) {
            state = state.copy(error = error)
            assertShowsText(ContactEditTags.ERROR, text)
        }
    }

    @Test
    fun `a save the server refused as invalid shows what it found wrong, or asks to check the fields`() {
        val refused =
            ContactEditUiState(base = anna, error = ContactSaveError.INVALID, errorDetail = "emails[0].address is not an email address")
        var state by mutableStateOf(refused)
        composeRule.setContent { CockpitTheme { ContactEditContent(state, ContactEditCallbacks()) } }
        assertShowsText(ContactEditTags.ERROR, "The server did not accept the contact: emails[0].address is not an email address")

        state = state.copy(errorDetail = null)
        assertShowsText(ContactEditTags.ERROR, "The server did not accept the contact. Check its fields.")
    }

    @Test
    fun `a change refused by a synced source says where to change the contact`() {
        val google = anna.copy(source = "GOOGLE")
        showForm(ContactEditUiState(form = ContactForm.of(google), base = google, error = ContactSaveError.NOT_ALLOWED))
        assertShowsText(
            ContactEditTags.ERROR,
            "This contact comes from Google, which does not accept changes from the cockpit. Change it there.",
        )
    }

    // The detail's actions and the list's New button.

    private fun showDetail(
        contact: Contact?,
        busy: Boolean = false,
        actionError: ContactSaveError? = null,
        editable: Boolean = true,
    ) = composeRule.setContent {
        CockpitTheme {
            ContactDetailContent(
                ContactDetailUiState(contact = contact, message = ContactsMessage.FAILED.takeIf { contact == null }),
                ContactDetailCallbacks(
                    back = { calls += "back" },
                    edit = { calls += "edit" }.takeIf { editable },
                    delete = { calls += "delete" }.takeIf { editable },
                ),
                busy = busy,
                actionError = actionError,
            )
        }
    }

    @Test
    fun `the detail offers Edit and Delete`() {
        showDetail(anna)
        composeRule.onNodeWithTag(ContactsTags.EDIT).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(ContactsTags.DELETE).assertIsEnabled().performClick()
        assertEquals(listOf("edit", "delete"), calls)
    }

    @Test
    fun `without edit callbacks, or without a loaded contact, there are no edit actions`() {
        var contact by mutableStateOf<Contact?>(anna)
        var editable by mutableStateOf(false)
        composeRule.setContent {
            CockpitTheme {
                ContactDetailContent(
                    ContactDetailUiState(contact = contact, message = ContactsMessage.FAILED.takeIf { contact == null }),
                    ContactDetailCallbacks(
                        edit = { calls += "edit" }.takeIf { editable },
                        delete = { calls += "delete" }.takeIf { editable },
                    ),
                )
            }
        }
        composeRule.onNodeWithTag(ContactsTags.EDIT).assertDoesNotExist()
        composeRule.onNodeWithTag(ContactsTags.DELETE).assertDoesNotExist()

        editable = true
        contact = null
        composeRule.onNodeWithTag(ContactsTags.EDIT).assertDoesNotExist()
        composeRule.onNodeWithTag(ContactsTags.DELETE).assertDoesNotExist()
    }

    @Test
    fun `while a delete runs the edit actions are disabled`() {
        showDetail(anna, busy = true)
        composeRule.onNodeWithTag(ContactsTags.EDIT).assertIsNotEnabled()
        composeRule.onNodeWithTag(ContactsTags.DELETE).assertIsNotEnabled()
    }

    @Test
    fun `a failed delete says the contact could not be deleted`() {
        showDetail(anna, actionError = ContactSaveError.FAILED)
        assertShowsText(ContactsTags.ACTION_ERROR, "The contact could not be deleted.")
    }

    @Test
    fun `a delete refused by a synced source says where to change the contact`() {
        showDetail(anna.copy(source = "MICROSOFT"), actionError = ContactSaveError.NOT_ALLOWED)
        assertShowsText(
            ContactsTags.ACTION_ERROR,
            "This contact comes from Microsoft, which does not accept changes from the cockpit. Change it there.",
        )
    }

    @Test
    fun `a delete that hit a change made elsewhere says the contact was reloaded, not that edits are kept`() {
        showDetail(anna, actionError = ContactSaveError.CONFLICT)
        assertShowsText(
            ContactsTags.ACTION_ERROR,
            "It was changed elsewhere in the meantime. The contact was reloaded: check it and delete again.",
        )
        composeRule.onNodeWithText("Your changes are kept", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a delete without connection says so`() {
        showDetail(anna, actionError = ContactSaveError.OFFLINE)
        assertShowsText(ContactsTags.ACTION_ERROR, "Can't reach the server. Nothing was saved.")
    }

    @Test
    fun `outside a Hilt activity the detail route opens the contact without edit actions instead of failing`() {
        val repo = mockk<ContactsRepository>()
        coEvery { repo.get(anna.id) } returns ContactsResult.Ok(anna)
        coEvery { repo.related(anna.id) } returns ContactsResult.Ok(ContactRelated(emptyList(), emptyList(), emptyList()))
        val viewModel = ContactDetailViewModel(repo)
        composeRule.setContent {
            CockpitTheme { ContactDetailRoute(anna.id, onClose = {}, viewModel = viewModel) }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(ContactsTags.SOURCE).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Anna Muster").assertExists()
        composeRule.onNodeWithTag(ContactsTags.EDIT).assertDoesNotExist()
        composeRule.onNodeWithTag(ContactsTags.DELETE).assertDoesNotExist()
    }

    @Test
    fun `a contact kept in the cockpit says so in its header`() {
        showDetail(anna)
        composeRule.onNodeWithTag(ContactsTags.SOURCE).assertTextEquals("Kept in the cockpit only")
    }

    @Test
    fun `a synced contact names its source in its header`() {
        showDetail(anna.copy(source = "CARDDAV"))
        composeRule.onNodeWithTag(ContactsTags.SOURCE).assertTextEquals("Synced with CardDAV")
    }

    @Test
    fun `source names are spelled like on the web`() {
        assertEquals("Google", sourceName("GOOGLE"))
        assertEquals("Microsoft", sourceName("MICROSOFT"))
        assertEquals("CardDAV", sourceName("CARDDAV"))
        assertEquals("Some source", sourceName("SOME_SOURCE"))
    }

    @Test
    fun `the list's New button opens the form`() {
        val state = ContactsListUiState(contacts = listOf(anna), loaded = true)
        composeRule.setContent { CockpitTheme { ContactsListContent(state, ContactsListCallbacks(create = { calls += "new" })) } }
        assertShowsText(ContactsTags.NEW, "New contact")
        composeRule.onNodeWithTag(ContactsTags.NEW).performClick()
        assertEquals(listOf("new"), calls)
    }

    @Test
    fun `without a create callback the list has no New button`() {
        composeRule.setContent { CockpitTheme { ContactsListContent(ContactsListUiState(loaded = true), ContactsListCallbacks()) } }
        composeRule.onNodeWithTag(ContactsTags.NEW).assertDoesNotExist()
        composeRule.onNodeWithTag(ContactsTags.EMPTY).assertTextContains("Add one with New contact", substring = true)
    }
}
