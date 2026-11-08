package ch.sonensei.cockpit.mail.list

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.mail.read.MailActionOutcome
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

/**
 * Previous/next in the read view of the Mail tab (through the mailbox or the search results it was opened from), the
 * next mail after a delete, and the search kept on return (toolbar, system back, rotation).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MailReadNavigationInAppTest {
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
            true,
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
        coEvery { mail.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(summary(1), summary(2), summary(3)), null))
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
        coEvery { mail.search("invoice", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(summary(5), summary(6))))
        (1..6).forEach { n -> coEvery { reader.message(id(n)) } returns MailResult.Ok(message(n)) }
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

    private fun waitForSubject(n: Int) =
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(MailReadTags.SUBJECT) and hasText("Subject $n")).fetchSemanticsNodes().isNotEmpty()
        }

    private fun read(n: Int) {
        composeRule.onNodeWithTag(MailListTags.row(id(n))).performClick()
        waitForSubject(n)
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
    fun `previous and next step through the mailbox in its order and stop at its ends`() {
        openMail()
        read(2)
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("2 of 3")

        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).performClick()
        waitForSubject(1)
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("1 of 3")
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertIsNotEnabled()

        composeRule.onNodeWithTag(MailReadTags.NEXT).performClick()
        waitForSubject(2)
        composeRule.onNodeWithTag(MailReadTags.NEXT).performClick()
        waitForSubject(3)
        composeRule.onNodeWithTag(MailReadTags.NEXT).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertIsEnabled()

        // Back still returns to the list, not through the mails stepped through.
        systemBack()
        waitFor(MailListTags.LIST)
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertDoesNotExist()
    }

    @Test
    fun `previous and next step through the search results, the toolbar back returns to the same search`() {
        openMail()
        searchInvoice()
        read(5)
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("1 of 2")

        composeRule.onNodeWithTag(MailReadTags.NEXT).performClick()
        waitForSubject(6)
        composeRule.onNodeWithTag(MailReadTags.NEXT).assertIsNotEnabled()

        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        waitFor(MailListTags.SEARCH_RESULTS)
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertTextContains("invoice")
        composeRule.onNodeWithTag(MailListTags.row(id(5))).assertExists()
        composeRule.onNodeWithTag(MailListTags.row(id(6))).assertExists()
        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertDoesNotExist()
        coVerify(exactly = 1) { mail.search("invoice", Mailbox.Unified, false) }
    }

    @Test
    fun `deleting a mail shows the next one, the deleted mail leaves the list`() {
        coEvery { reader.act(id(1), MailListAction.DELETE) } returns MailResult.Ok(MailActionOutcome(true))
        openMail()
        read(1)

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.DELETE)).performClick()
        waitForSubject(2)
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("1 of 2")

        systemBack()
        waitFor(MailListTags.row(id(2)))
        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertDoesNotExist()
    }

    @Test
    fun `archiving a search result shows the next result`() {
        coEvery { reader.act(id(5), MailListAction.ARCHIVE) } returns MailResult.Ok(MailActionOutcome(true))
        openMail()
        searchInvoice()
        read(5)

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.ARCHIVE)).performClick()
        waitForSubject(6)

        systemBack()
        waitFor(MailListTags.row(id(6)))
        composeRule.onNodeWithTag(MailListTags.row(id(5))).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertTextContains("invoice")
    }

    @Test
    fun `the open result and the search survive rotation`() {
        openMail()
        searchInvoice()
        read(6)

        scenario!!.recreate()
        waitForSubject(6)
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("2 of 2")

        systemBack()
        waitFor(MailListTags.row(id(5)))
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertTextContains("invoice")

        scenario!!.recreate()
        waitFor(MailListTags.row(id(6)))
        composeRule.onNodeWithTag(MailListTags.SEARCH_FIELD).assertTextContains("invoice")
        coVerify(exactly = 1) { mail.search("invoice", Mailbox.Unified, false) }
    }

    @Test
    fun `the last loaded mail fetches the next page so next can go on`() {
        coEvery { mail.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(summary(1), summary(2)), "page-2"))
        // The list's own load near its end fails (offline), so only the read view's request can bring the next page.
        coEvery { mail.messages(Mailbox.Unified, "page-2") } returnsMany
            listOf(MailResult.Offline, MailResult.Ok(MailPage(listOf(summary(3)), null)))
        openMail()
        waitFor(MailListTags.MESSAGE)
        read(2)

        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(MailReadTags.POSITION) and hasText("2 of 3")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MailReadTags.NEXT).assertIsEnabled().performClick()
        waitForSubject(3)
        coVerify(exactly = 2) { mail.messages(Mailbox.Unified, "page-2") }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
