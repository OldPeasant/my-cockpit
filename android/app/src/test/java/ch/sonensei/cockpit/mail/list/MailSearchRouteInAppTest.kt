package ch.sonensei.cockpit.mail.list

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.mail.read.MailMessage
import ch.sonensei.cockpit.mail.read.MailReadRepository
import ch.sonensei.cockpit.mail.read.MailReadTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** Search in the Mail tab of the Hilt activity with fake repositories: MailRoute wires the box to the view model. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MailSearchRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val mail: MailRepository = mockk()

    @BindValue
    @JvmField
    val reader: MailReadRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")

    private fun summary(n: Int) =
        MailSummary(id(n), account.id, "Anna Example", "Subject $n", "Snippet $n", Instant.EPOCH, false, false, false)

    private fun message(n: Int) =
        MailMessage(
            id(n),
            "Subject $n",
            "Anna Example <anna@example.org>",
            emptyList(),
            emptyList(),
            null,
            false,
            "",
            "Body $n",
            0,
            emptyList(),
        )

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { mail.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, emptyList())))
        coEvery { mail.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(summary(1), summary(2)), null))
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
        coEvery { mail.search("invoice", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(summary(5))))
        coEvery { reader.message(id(5)) } returns MailResult.Ok(message(5))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun openMail() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("Mail") and isSelectable()).performClick()
        waitFor(MailListTags.row(id(1)))
    }

    private fun waitFor(tag: String) =
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun searchInvoice() {
        composeRule.onNodeWithTag(MailListTags.SEARCH_OPEN).performClick()
        waitFor(MailListTags.SEARCH_FIELD)
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performTextInput("invoice")
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performImeAction()
        waitFor(MailListTags.row(id(5)))
    }

    private fun systemBack() = scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `a result opens the read view, back returns to the results, back again to the mailbox`() {
        openMail()
        searchInvoice()
        composeRule.onNodeWithTag(MailListTags.LIST).assertDoesNotExist()
        coVerify(exactly = 1) { mail.search("invoice", Mailbox.Unified, false) }

        composeRule.onNodeWithTag(MailListTags.row(id(5))).performClick()
        waitFor(MailReadTags.TEXT)
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextContains("Subject 5")
        coVerify(exactly = 1) { mail.markRead(id(5)) }

        systemBack()
        waitFor(MailListTags.row(id(5)))
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertTextContains("invoice")

        systemBack()
        waitFor(MailListTags.row(id(1)))
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertExists()
        composeRule.onNodeWithTag(MailListTags.row(id(2))).assertExists()
    }

    @Test
    fun `search on server asks the servers and names the account that failed, the close button returns`() {
        coEvery { mail.search("invoice", Mailbox.Unified, true) } returns
            MailResult.Ok(MailSearchResult(listOf(summary(6), summary(5)), hasMore = true, failedAccounts = listOf(account.id)))
        openMail()
        searchInvoice()

        composeRule.onNodeWithTag(MailListTags.SEARCH_SERVER).performClick()
        waitFor(MailListTags.row(id(6)))
        waitFor(MailListTags.SEARCH_FAILURES)

        composeRule.onNodeWithTag(MailListTags.SEARCH_FAILURES).assertTextContains("me@example.org", substring = true)
        composeRule.onNodeWithTag(MailListTags.SEARCH_HAS_MORE).assertExists()
        composeRule.onNodeWithTag(MailListTags.SEARCH_SERVER).assertDoesNotExist()
        coVerify(exactly = 1) { mail.search("invoice", Mailbox.Unified, true) }

        composeRule.onNodeWithTag(MailListTags.SEARCH_CLOSE).performClick()
        waitFor(MailListTags.row(id(1)))
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertDoesNotExist()
    }

    @Test
    fun `back from a result returns to the same place in the results, a new search starts at the top`() {
        coEvery { mail.search("report", Mailbox.Unified, false) } returns
            MailResult.Ok(MailSearchResult((100 until 130).map { summary(it) }))
        coEvery { reader.message(id(125)) } returns MailResult.Ok(message(125))
        openMail()
        composeRule.onNodeWithTag(MailListTags.SEARCH_OPEN).performClick()
        waitFor(MailListTags.SEARCH_FIELD)
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performTextInput("report")
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performImeAction()
        waitFor(MailListTags.row(id(100)))

        composeRule.onNodeWithTag(MailListTags.SEARCH_RESULTS).performScrollToNode(hasTestTag(MailListTags.row(id(125))))
        composeRule.onNodeWithTag(MailListTags.row(id(125))).performClick()
        waitFor(MailReadTags.TEXT)
        systemBack()

        waitFor(MailListTags.row(id(125)))
        composeRule.onNodeWithTag(MailListTags.row(id(100))).assertDoesNotExist()

        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performImeAction()
        waitFor(MailListTags.row(id(100)))
        coVerify(exactly = 2) { mail.search("report", Mailbox.Unified, false) }
    }

    @Test
    fun `a failed search on the server keeps the place in the results, the answer to retry starts at the top`() {
        coEvery { mail.search("report", Mailbox.Unified, false) } returns
            MailResult.Ok(MailSearchResult((100 until 130).map { summary(it) }))
        coEvery { mail.search("report", Mailbox.Unified, true) } returnsMany
            listOf(MailResult.Offline, MailResult.Ok(MailSearchResult((99 until 130).map { summary(it) })))
        openMail()
        composeRule.onNodeWithTag(MailListTags.SEARCH_OPEN).performClick()
        waitFor(MailListTags.SEARCH_FIELD)
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performTextInput("report")
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).performImeAction()
        waitFor(MailListTags.row(id(100)))
        composeRule.onNodeWithTag(MailListTags.SEARCH_RESULTS).performScrollToNode(hasTestTag(MailListTags.row(id(125))))

        composeRule.onNodeWithTag(MailListTags.SEARCH_SERVER).performClick()
        waitFor(MailListTags.RETRY)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailListTags.row(id(125))).assertExists()
        composeRule.onNodeWithTag(MailListTags.row(id(100))).assertDoesNotExist()

        composeRule.onNodeWithTag(MailListTags.RETRY).performClick()
        // The newer mail found on the server comes first; the list shows it instead of keeping row 125 by its key.
        waitFor(MailListTags.row(id(99)))
        composeRule.onNodeWithTag(MailListTags.RETRY).assertDoesNotExist()
        coVerify(exactly = 2) { mail.search("report", Mailbox.Unified, true) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
