package ch.sonensei.cockpit.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** The list and the detail alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class ContactsScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val anna =
        Contact(
            id = id(1),
            displayName = "Anna Muster",
            organization = "Beispiel AG",
            title = "Engineer",
            emails = listOf(LabeledValue("anna@example.org", "work")),
            phones = listOf(LabeledValue("+41 44 123 45 67", "mobile"), LabeledValue("044 765 43 21", null)),
            addresses = listOf(LabeledValue("Musterweg 1, 8000 Zürich", "home")),
            birthday = LocalDate.of(1990, 5, 17),
            notesMd = "Met at the meetup",
            source = "LOCAL",
        )
    private val bert = anna.copy(id = id(2), displayName = "Bert", organization = null, emails = emptyList(), phones = emptyList())
    private val calls = mutableListOf<String>()

    private fun showList(state: ContactsListUiState) =
        composeRule.setContent {
            CockpitTheme {
                ContactsListContent(
                    state,
                    ContactsListCallbacks(open = { calls += "open $it" }, search = { calls += "search $it" }, retry = { calls += "retry" }),
                )
            }
        }

    private fun showDetail(state: ContactDetailUiState) =
        composeRule.setContent {
            CockpitTheme {
                ContactDetailContent(
                    state,
                    ContactDetailCallbacks(
                        back = { calls += "back" },
                        call = { calls += "call $it" },
                        mail = { calls += "mail $it" },
                        retry = { calls += "retry" },
                    ),
                )
            }
        }

    @Test
    fun `the list shows contacts in order and opens one`() {
        showList(ContactsListUiState(contacts = listOf(anna, bert), loaded = true))

        val list = composeRule.onNodeWithTag(ContactsTags.LIST)
        list.onChildAt(0).assertTextContains("Anna Muster", substring = true)
        list.onChildAt(0).assertTextContains("Beispiel AG, anna@example.org", substring = true)
        list.onChildAt(1).assertTextContains("Bert", substring = true)
        composeRule.onNodeWithText("AM").assertExists()

        composeRule.onNodeWithTag(ContactsTags.contact(bert.id)).performClick()
        assertEquals(listOf("open ${bert.id}"), calls)
    }

    @Test
    fun `typing searches, and clearing searches for everything again`() {
        showList(ContactsListUiState(contacts = emptyList(), query = "zz", loaded = true))
        composeRule.onNodeWithTag(ContactsTags.EMPTY).assertTextContains("No contacts match \"zz\".")
        composeRule.onNodeWithTag(ContactsTags.SEARCH).performTextInput("a")
        composeRule.onNodeWithTag(ContactsTags.CLEAR).performClick()
        assertEquals(listOf("search azz", "search "), calls)
    }

    @Test
    fun `an empty list and a failed load say so, with retry`() {
        showList(ContactsListUiState(loaded = true, message = ContactsMessage.OFFLINE))
        composeRule.onNodeWithTag(ContactsTags.EMPTY).assertTextContains("No contacts yet", substring = true)
        composeRule.onNodeWithTag(ContactsTags.MESSAGE).assertExists()
        composeRule.onNodeWithText("Can't reach the server", substring = true).assertExists()
        composeRule.onNodeWithTag(ContactsTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `the detail calls and mails from the header and from each number and address`() {
        showDetail(ContactDetailUiState(contact = anna, related = ContactRelated(emptyList(), emptyList(), emptyList(), emptyList())))

        composeRule.onNodeWithText("Engineer, Beispiel AG").assertIsDisplayed()
        composeRule.onNodeWithTag(ContactsTags.CALL).performClick()
        composeRule.onNodeWithTag(ContactsTags.MAIL).performClick()
        composeRule.onNodeWithTag(ContactsTags.phone("044 765 43 21")).performClick()
        composeRule.onNodeWithTag(ContactsTags.DETAIL).performScrollToNode(hasTestTag(ContactsTags.email("anna@example.org")))
        composeRule.onNodeWithTag(ContactsTags.email("anna@example.org")).performClick()
        composeRule.onNodeWithTag(ContactsTags.BACK).performClick()
        assertEquals(
            listOf("call +41 44 123 45 67", "mail anna@example.org", "call 044 765 43 21", "mail anna@example.org", "back"),
            calls,
        )
        composeRule.onNodeWithTag(ContactsTags.DETAIL).performScrollToNode(hasTestTag(ContactsTags.NOTHING_RELATED))
        composeRule.onNodeWithText("Musterweg 1, 8000 Zürich").assertExists()
        composeRule.onNodeWithText("Met at the meetup").assertExists()
    }

    @Test
    fun `a contact without phone or email has no call or mail button`() {
        showDetail(ContactDetailUiState(contact = bert))
        composeRule.onNodeWithTag(ContactsTags.CALL).assertDoesNotExist()
        composeRule.onNodeWithTag(ContactsTags.MAIL).assertDoesNotExist()
    }

    @Test
    fun `the detail shows waiting for items, mails, events and links`() {
        val related =
            ContactRelated(
                waitingFor = listOf(RelatedWaiting(id(10), "Send the offer", LocalDate.of(2026, 10, 1))),
                mails = listOf(RelatedMail(id(11), null, fromContact = true, at = null)),
                events = listOf(RelatedEvent(id(12), "Lunch", null, LocalDate.of(2026, 11, 2))),
                links = listOf(RelatedLink(id(13), "NOTE", "Meeting notes", null, true), RelatedLink(id(14), "TASK", null, null, false)),
            )
        showDetail(ContactDetailUiState(contact = bert, related = related))

        val detail = composeRule.onNodeWithTag(ContactsTags.DETAIL)
        detail.performScrollToNode(hasTestTag(ContactsTags.related(id(10))))
        composeRule.onNodeWithTag(ContactsTags.related(id(10))).assertTextContains("Send the offer", substring = true)
        detail.performScrollToNode(hasTestTag(ContactsTags.related(id(11))))
        composeRule.onNodeWithTag(ContactsTags.related(id(11))).assertTextContains("(no subject)", substring = true)
        composeRule.onNodeWithTag(ContactsTags.related(id(11))).assertTextContains("From them", substring = true)
        detail.performScrollToNode(hasTestTag(ContactsTags.related(id(12))))
        composeRule.onNodeWithTag(ContactsTags.related(id(12))).assertTextContains("Lunch", substring = true)
        detail.performScrollToNode(hasTestTag(ContactsTags.related(id(14))))
        composeRule.onNodeWithTag(ContactsTags.related(id(13))).assertTextContains("Note", substring = true)
        composeRule.onNodeWithTag(ContactsTags.related(id(14))).assertTextContains("Deleted", substring = true)
    }

    @Test
    fun `related items that failed to load show a banner, the contact stays`() {
        showDetail(ContactDetailUiState(contact = anna, relatedMessage = ContactsMessage.FAILED))
        composeRule.onNodeWithText("Anna Muster").assertExists()
        composeRule.onNodeWithTag(ContactsTags.DETAIL).performScrollToNode(hasTestTag(ContactsTags.RELATED_MESSAGE))
        composeRule.onNodeWithText("Mail, events and Waiting For items could not be loaded.").assertExists()
    }

    @Test
    fun `a contact that could not be loaded says so`() {
        showDetail(ContactDetailUiState(message = ContactsMessage.FAILED))
        composeRule.onNodeWithTag(ContactsTags.MESSAGE).assertExists()
        composeRule.onNodeWithText("This contact could not be loaded.").assertExists()
        composeRule.onNodeWithTag(ContactsTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `initials and type names`() {
        assertEquals("AM", initials("Anna Muster"))
        assertEquals("AM", initials("Anna Maria Muster"))
        assertEquals("B", initials("Bert"))
        assertEquals("?", initials("  "))
        assertEquals("Journal entry", typeName("JOURNAL_ENTRY"))
    }
}
