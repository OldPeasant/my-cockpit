package ch.sonensei.cockpit.mail.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))
private val account = MailAccount(id(80), "Ann Example", "me@example.org")
private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0)

private fun mail(n: Int) =
    MailSummary(id(n), account.id, "Anna Example", "Subject $n", "", Instant.parse("2026-10-21T07:00:00Z"), true, false, false, inbox.id)

/** The end of a fully loaded folder, where "load older from server" is offered. */
private val folderEnd =
    MailListUiState(
        mailbox = Mailbox.Folder(account.id, inbox.id),
        accounts = listOf(MailAccountFolders(account, listOf(inbox))),
        messages = listOf(mail(1), mail(2)),
        loaded = true,
    )

/** "Load older from server" at the end of a folder: the button, its progress and the non-blocking messages. */
@RunWith(AndroidJUnit4::class)
class MailLoadOlderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: MailListUiState) =
        composeRule.setContent {
            CockpitTheme {
                MailListContent(state, MailListCallbacks(loadOlder = { calls += "older" }, retry = { calls += "retry" }), clock = clock)
            }
        }

    @Test
    fun `the end of a folder offers load older from server`() {
        show(folderEnd)

        composeRule
            .onNodeWithTag(MailListTags.LOAD_OLDER)
            .assertTextEquals("Load older from server")
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf("older"), calls)
        composeRule.onNodeWithTag(MailListTags.LOADING_OLDER).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.OLDER_MESSAGE).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.row(id(2))).assertIsDisplayed()
    }

    @Test
    fun `while it loads the progress shows and the button is disabled`() {
        show(folderEnd.copy(loadingOlder = true))

        composeRule.onNodeWithTag(MailListTags.LOADING_OLDER).assertExists()
        composeRule.onNodeWithText("Loading older messages from the server…").assertExists()
        composeRule.onNodeWithTag(MailListTags.LOAD_OLDER).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `nothing older, offline and refused are said above the button, which tries again`() {
        var state by mutableStateOf(folderEnd.copy(olderMessage = OlderMessage.NOTHING_OLDER))
        composeRule.setContent {
            CockpitTheme { MailListContent(state, MailListCallbacks(loadOlder = { calls += "older" }), clock = clock) }
        }

        composeRule.onNodeWithTag(MailListTags.OLDER_MESSAGE).assertTextEquals("There are no older messages on the server.")
        state = folderEnd.copy(olderMessage = OlderMessage.OFFLINE)
        composeRule.onNodeWithTag(MailListTags.OLDER_MESSAGE).assertTextEquals("No connection: nothing older was loaded.")
        state = folderEnd.copy(olderMessage = OlderMessage.FAILED)
        composeRule.onNodeWithTag(MailListTags.OLDER_MESSAGE).assertTextEquals("The older messages could not be loaded.")

        // Non-blocking: the list stays usable and the list's own error banner is not used.
        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.MESSAGE).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.LOAD_OLDER).assertIsEnabled().performClick()
        assertEquals(listOf("older"), calls)
    }

    @Test
    fun `an empty folder offers it under the empty text`() {
        show(folderEnd.copy(messages = emptyList()))

        composeRule.onNodeWithTag(MailListTags.EMPTY).assertTextEquals("No mail here.")
        composeRule.onNodeWithTag(MailListTags.LOAD_OLDER).performClick()
        assertEquals(listOf("older"), calls)
    }

    @Test
    fun `it is not offered in the unified inbox`() {
        show(folderEnd.copy(mailbox = Mailbox.Unified))
        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.LOAD_OLDER).assertDoesNotExist()
    }

    @Test
    fun `it is not offered while cached pages follow`() {
        show(folderEnd.copy(nextCursor = "c2"))
        composeRule.onNodeWithTag(MailListTags.LOAD_OLDER).assertDoesNotExist()
    }

    @Test
    fun `it is not offered in a search`() {
        show(folderEnd.copy(search = MailSearchState(folderEnd.mailbox, "invoice", results = listOf(mail(1)), loaded = true)))
        composeRule.onNodeWithTag(MailListTags.LOAD_OLDER).assertDoesNotExist()
    }

    @Test
    fun `it is not offered after the folder failed to load`() {
        show(folderEnd.copy(message = MailListMessage.OFFLINE))
        composeRule.onNodeWithTag(MailListTags.LOAD_OLDER).assertDoesNotExist()
    }
}

/** The "load older from server" texts in the German locale. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de")
class MailLoadOlderGermanTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: MailListUiState) =
        composeRule.setContent {
            CockpitTheme { MailListContent(state, MailListCallbacks(), clock = clock) }
        }

    @Test
    fun `the button and its progress are in German`() {
        show(folderEnd.copy(loadingOlder = true))
        composeRule.onNodeWithTag(MailListTags.LOAD_OLDER).assertTextEquals("Ältere vom Server laden")
        composeRule.onNodeWithText("Ältere Nachrichten werden vom Server geladen…").assertExists()
    }

    @Test
    fun `nothing older is said in German`() {
        show(folderEnd.copy(olderMessage = OlderMessage.NOTHING_OLDER))
        composeRule.onNodeWithTag(MailListTags.OLDER_MESSAGE).assertTextEquals("Auf dem Server gibt es keine älteren Nachrichten.")
    }

    @Test
    fun `no connection is said in German`() {
        show(folderEnd.copy(olderMessage = OlderMessage.OFFLINE))
        composeRule.onNodeWithTag(MailListTags.OLDER_MESSAGE).assertTextEquals("Keine Verbindung: Nichts Älteres geladen.")
    }

    @Test
    fun `a refusal is said in German`() {
        show(folderEnd.copy(olderMessage = OlderMessage.FAILED))
        composeRule.onNodeWithTag(MailListTags.OLDER_MESSAGE).assertTextEquals("Die älteren Nachrichten konnten nicht geladen werden.")
    }
}
