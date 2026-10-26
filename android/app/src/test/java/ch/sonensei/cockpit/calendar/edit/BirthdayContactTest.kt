package ch.sonensei.cockpit.calendar.edit

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.contacts.Contact
import ch.sonensei.cockpit.contacts.ContactDetailViewModel
import ch.sonensei.cockpit.contacts.ContactRelated
import ch.sonensei.cockpit.contacts.ContactsRepository
import ch.sonensei.cockpit.contacts.ContactsResult
import ch.sonensei.cockpit.contacts.ContactsTags
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

/**
 * The real contact screen a tapped birthday opens in the calendar tab: it loads the contact the birthday comes
 * from, and its back button and the system back both return to the calendar.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class BirthdayContactTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val adaId = id(50)
    private val ada =
        Contact(
            id = adaId,
            displayName = "Ada Lovelace",
            organization = "Example Engines Ltd",
            title = null,
            emails = emptyList(),
            phones = emptyList(),
            addresses = emptyList(),
            birthday = LocalDate.of(1815, 12, 10),
            notesMd = null,
            source = "LOCAL",
        )
    private val nothing = ContactRelated(emptyList(), emptyList(), emptyList(), emptyList())

    private val repo = mockk<ContactsRepository>()
    private var backs = 0

    @Before
    fun setUp() {
        coEvery { repo.get(adaId) } returns ContactsResult.Ok(ada)
        coEvery { repo.related(adaId) } returns ContactsResult.Ok(nothing)
    }

    private fun show() {
        val viewModel = ContactDetailViewModel(repo)
        composeRule.setContent {
            CockpitTheme { BirthdayContact(adaId, back = { backs++ }, viewModel = viewModel) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `it loads and shows the contact the birthday comes from`() {
        show()
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        coVerify(exactly = 1) { repo.get(adaId) }
        coVerify(exactly = 1) { repo.related(adaId) }
        assertEquals(0, backs)
    }

    @Test
    fun `its back button returns to the calendar`() {
        show()
        composeRule.onNodeWithTag(ContactsTags.BACK).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `the system back returns to the calendar`() {
        show()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun `a contact that could not be loaded says so, and retry loads it again`() {
        coEvery { repo.get(adaId) } returns ContactsResult.Offline andThen ContactsResult.Ok(ada)
        show()
        composeRule.onNodeWithTag(ContactsTags.MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText("Ada Lovelace").assertDoesNotExist()

        composeRule.onNodeWithTag(ContactsTags.RETRY).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        coVerify(exactly = 2) { repo.get(adaId) }
    }
}
