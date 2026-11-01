package ch.sonensei.cockpit.mail.list

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Edge cases of the search box: the mailbox without search, Back, rows without swipe actions, scope labels. */
@RunWith(AndroidJUnit4::class)
class MailSearchScreenEdgeCasesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val work = MailAccount(id(80), "Ann Example", "me@example.org")
    private val home = MailAccount(id(81), "Ann Example", "me@example.net")
    private val workInbox = MailFolder(id(90), work.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val accounts = listOf(MailAccountFolders(work, listOf(workInbox)), MailAccountFolders(home, emptyList()))
    private val inbox = Mailbox.Folder(work.id, workInbox.id)

    private fun mail(n: Int) =
        MailSummary(id(n), work.id, "Anna Example", "Subject $n", "Snippet $n", Instant.parse("2026-10-21T07:00:00Z"), true, false, false)

    private val calls = mutableListOf<String>()

    private val callbacks =
        MailListCallbacks(
            open = { calls += "open $it" },
            select = { calls += "select $it" },
            act = { id, action -> calls += "$action $id" },
            search =
                SearchCallbacks(
                    open = { calls += "open-search" },
                    submit = { calls += "search $it" },
                    server = { calls += "server $it" },
                    retry = { calls += "retry" },
                    close = { calls += "close" },
                ),
        )

    private fun show(state: MailListUiState) =
        composeRule.setContent {
            CockpitTheme { MailListContent(state, callbacks, clock = clock) }
        }

    private fun searched(
        search: MailSearchState,
        mailbox: Mailbox = Mailbox.Unified,
    ) = MailListUiState(mailbox = mailbox, accounts = accounts, messages = listOf(mail(1)), loaded = true, search = search)

    @Test
    fun `with search closed the mailbox keeps its picker, rows and search action, and shows no search box`() {
        show(MailListUiState(accounts = accounts, messages = listOf(mail(1)), loaded = true))

        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertTextContains("All inboxes", substring = true)
        composeRule.onNodeWithTag(MailListTags.LIST).assertExists()
        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertExists()
        composeRule.onNodeWithTag(MailListTags.SEARCH_OPEN).assertExists()
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.SEARCH_RESULTS).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.SEARCH_SERVER).assertDoesNotExist()
    }

    @Test
    fun `while searching the box replaces the mailbox picker`() {
        show(searched(MailSearchState(Mailbox.Unified)))

        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertExists()
        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.SEARCH_OPEN).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertDoesNotExist()
    }

    @Test
    fun `the system back closes an open search`() {
        var dispatcher: OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CockpitTheme {
                MailListContent(
                    searched(MailSearchState(Mailbox.Unified, query = "x", results = listOf(mail(5)), loaded = true)),
                    callbacks,
                    clock = clock,
                )
            }
        }

        composeRule.runOnIdle { dispatcher!!.onBackPressed() }

        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `search results have no swipe actions`() {
        show(searched(MailSearchState(Mailbox.Unified, query = "x", results = listOf(mail(5)), loaded = true)))

        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.row(id(5))).performTouchInput { swipeRight() }
        composeRule.onNodeWithTag(MailListTags.row(id(5))).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // A row opens on a tap, and Robolectric may count a swipe as one; only archiving or deleting must not happen.
        assertEquals(emptyList<String>(), calls.filterNot { it.startsWith("open ") })
        composeRule.onNodeWithTag(MailListTags.row(id(5))).assertExists()
    }

    @Test
    fun `results of a folder search do not name their account`() {
        show(searched(MailSearchState(inbox, query = "x", results = listOf(mail(5)), loaded = true), mailbox = inbox))

        composeRule.onNodeWithTag(MailListTags.row(id(5))).assertTextContains("Subject 5", substring = true)
        composeRule.onNodeWithTag(MailListTags.row(id(5))).assert(!hasText("me@example.org", substring = true))
    }

    @Test
    fun `the box of the unified inbox says it searches all folders`() {
        show(searched(MailSearchState(Mailbox.Unified)))

        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertTextContains("Search all folders")
    }

    @Test
    fun `a search shown again keeps its query in the box`() {
        show(searched(MailSearchState(Mailbox.Unified, query = "invoice", results = listOf(mail(5)), loaded = true)))

        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertTextContains("invoice")
        composeRule.onNodeWithTag(MailListTags.SEARCH_SUBMIT).performClick()

        assertEquals(listOf("search invoice"), calls)
    }

    @Test
    fun `the box takes no more text than the server accepts`() {
        show(searched(MailSearchState(Mailbox.Unified)))

        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performTextInput("a".repeat(MAX_QUERY + 50))
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performImeAction()

        assertEquals(listOf("search " + "a".repeat(MAX_QUERY)), calls)
    }

    @Test
    fun `while a search on the server runs the cached results stay and the hasMore hint waits for its answer`() {
        show(
            searched(
                MailSearchState(
                    Mailbox.Unified,
                    query = "x",
                    server = true,
                    results = listOf(mail(5)),
                    loading = true,
                    loaded = true,
                    hasMore = true,
                ),
            ),
        )

        composeRule.onNodeWithTag(MailListTags.row(id(5))).assertExists()
        composeRule.onNodeWithTag(MailListTags.SEARCH_HAS_MORE).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.SEARCH_SERVER).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.SEARCH_EMPTY).assertDoesNotExist()
    }
}
