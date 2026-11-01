package ch.sonensei.cockpit.mail.list

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
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

/** The search box and its results, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class MailSearchScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val work = MailAccount(id(80), "Ann Example", "me@example.org")
    private val home = MailAccount(id(81), "Ann Example", "me@example.net")
    private val workInbox = MailFolder(id(90), work.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val accounts = listOf(MailAccountFolders(work, listOf(workInbox)), MailAccountFolders(home, emptyList()))

    private fun mail(n: Int) =
        MailSummary(id(n), work.id, "Anna Example", "Subject $n", "Snippet $n", Instant.parse("2026-10-21T07:00:00Z"), true, false, false)

    private val calls = mutableListOf<String>()

    private fun show(state: MailListUiState) =
        composeRule.setContent {
            CockpitTheme {
                MailListContent(
                    state,
                    MailListCallbacks(
                        open = { calls += "open $it" },
                        search =
                            SearchCallbacks(
                                open = { calls += "open-search" },
                                submit = { calls += "search $it" },
                                server = { calls += "server" },
                                retry = { calls += "retry" },
                                close = { calls += "close" },
                            ),
                    ),
                    clock = clock,
                )
            }
        }

    private fun searched(
        search: MailSearchState,
        mailbox: Mailbox = Mailbox.Unified,
    ) = MailListUiState(mailbox = mailbox, accounts = accounts, messages = listOf(mail(1)), loaded = true, search = search)

    @Test
    fun `the mail list has a search action`() {
        show(MailListUiState(accounts = accounts, messages = listOf(mail(1)), loaded = true))

        composeRule.onNodeWithTag(MailListTags.SEARCH_OPEN).performClick()

        assertEquals(listOf("open-search"), calls)
    }

    @Test
    fun `submitting a query searches, a blank one is not submitted`() {
        show(searched(MailSearchState(Mailbox.Unified)))
        composeRule.onNodeWithTag(MailListTags.SEARCH_SUBMIT).assertIsNotEnabled()

        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performTextInput("invoice")
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performImeAction()
        composeRule.onNodeWithTag(MailListTags.SEARCH_SUBMIT).performClick()

        assertEquals(listOf("search invoice", "search invoice"), calls)
    }

    @Test
    fun `the box says what is searched`() {
        show(searched(MailSearchState(Mailbox.Folder(work.id, workInbox.id)), Mailbox.Folder(work.id, workInbox.id)))

        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertTextContains("Search in Inbox")
    }

    @Test
    fun `results show in mail rows that open the read view, the mailbox list is hidden, close returns`() {
        show(searched(MailSearchState(Mailbox.Unified, query = "x", results = listOf(mail(5)), loaded = true)))

        composeRule.onNodeWithTag(MailListTags.LIST).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.row(id(5))).assertTextContains("Subject 5", substring = true)
        // From all folders with two accounts, each result names its account.
        composeRule.onNodeWithTag(MailListTags.row(id(5))).assertTextContains("me@example.org", substring = true)
        composeRule.onNodeWithTag(MailListTags.row(id(5))).performClick()
        composeRule.onNodeWithTag(MailListTags.SEARCH_CLOSE).performClick()

        assertEquals(listOf("open ${id(5)}", "close"), calls)
    }

    @Test
    fun `search on server is offered after a cached search and repeats it`() {
        show(searched(MailSearchState(Mailbox.Unified, query = "x", results = listOf(mail(5)), loaded = true)))

        composeRule.onNodeWithTag(MailListTags.SEARCH_SERVER).assertTextContains("Search on server").performClick()

        assertEquals(listOf("server"), calls)
    }

    @Test
    fun `failed accounts are named and hasMore shows a hint, without hiding the results`() {
        show(
            searched(
                MailSearchState(
                    Mailbox.Unified,
                    query = "x",
                    server = true,
                    results = listOf(mail(5)),
                    loaded = true,
                    hasMore = true,
                    failedAccounts = listOf(home.id, id(99)),
                ),
            ),
        )

        composeRule.onNodeWithTag(MailListTags.SEARCH_SERVER).assertDoesNotExist()
        composeRule
            .onNodeWithTag(MailListTags.SEARCH_FAILURES)
            .assertTextContains("The server of me@example.net, an account could not be searched", substring = true)
        composeRule.onNodeWithTag(MailListTags.SEARCH_HAS_MORE).assertTextContains("Only the newest results are listed", substring = true)
        composeRule.onNodeWithTag(MailListTags.row(id(5))).assertExists()
    }

    @Test
    fun `an empty result says so, an error offers retry like the list`() {
        show(searched(MailSearchState(Mailbox.Unified, query = "x", loaded = true, message = MailListMessage.OFFLINE)))

        composeRule.onNodeWithTag(MailListTags.SEARCH_EMPTY).assertTextContains("No mail found.")
        composeRule.onNodeWithTag(MailListTags.MESSAGE).assertTextContains("Can't reach the server", substring = true)
        composeRule.onNodeWithTag(MailListTags.RETRY).performClick()

        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `while loading no empty text and no server option show`() {
        show(searched(MailSearchState(Mailbox.Unified, query = "x", loading = true)))

        composeRule.onNodeWithTag(MailListTags.SEARCH_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.SEARCH_SERVER).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the search speaks German`() {
        show(
            searched(
                MailSearchState(Mailbox.Unified, query = "x", loaded = true, hasMore = true, failedAccounts = listOf(home.id)),
            ),
        )

        composeRule.onNodeWithTag(MailListTags.SEARCH_SERVER).assertTextContains("Auf dem Server suchen")
        composeRule.onNodeWithTag(MailListTags.SEARCH_EMPTY).assertTextContains("Keine E-Mails gefunden.")
        composeRule.onNodeWithTag(MailListTags.SEARCH_HAS_MORE).assertTextContains("Nur die neuesten Ergebnisse", substring = true)
        composeRule
            .onNodeWithTag(MailListTags.SEARCH_FAILURES)
            .assertTextContains("Der Server von me@example.net konnte nicht durchsucht werden", substring = true)
    }
}
