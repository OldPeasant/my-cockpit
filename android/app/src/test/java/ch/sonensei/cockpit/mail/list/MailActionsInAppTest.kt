package ch.sonensei.cockpit.mail.list

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import org.junit.Assert.assertEquals
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
 * The new mail actions in the Hilt activity with fake repositories, through the real view models: what the read view
 * marks shows in the list, its move picker gets the list's folders, and a multi-selection's bulk action can be undone.
 * The reloads answer with the old state, so the list can only show a change because the action reached it.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MailActionsInAppTest {
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
    private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val projects = MailFolder(id(91), account.id, "Projects", "Projects", null, 0)

    private fun summary(
        n: Int,
        seen: Boolean = false,
    ) = MailSummary(id(n), account.id, "Anna Example", "Subject $n", "Snippet $n", Instant.EPOCH, seen, false, false, inbox.id)

    private fun message(
        n: Int,
        seen: Boolean = false,
    ) = MailMessage(
        id(n),
        "Subject $n",
        "Anna Example <anna@example.org>",
        emptyList(),
        emptyList(),
        null,
        seen,
        "",
        "Body $n",
        0,
        emptyList(),
        accountId = account.id,
        folderId = inbox.id,
    )

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { mail.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, projects))))
        // Mail 1 is read already, so opening it marks nothing and only "mark unread" can make it unread.
        coEvery { mail.messages(Mailbox.Unified, null) } returns
            MailResult.Ok(MailPage(listOf(summary(1, seen = true), summary(2)), null))
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message(1, seen = true))
        coEvery { reader.message(id(2)) } returns MailResult.Ok(message(2))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun openMailTab() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("Mail") and isSelectable()).performClick()
        waitFor(MailListTags.row(id(1)))
    }

    private fun openMail1() {
        composeRule.onNodeWithTag(MailListTags.row(id(1))).performClick()
        waitFor(MailReadTags.TEXT)
    }

    private fun waitFor(tag: String) =
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitGone(tag: String) =
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
        }

    private fun described(text: String) =
        composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text)))
            .fetchSemanticsNodes()
            .size

    @Test
    fun `mark unread in the read view returns to the list, which shows the mail unread again`() {
        coEvery { reader.act(id(1), MailListAction.UNREAD, null) } returns MailResult.Ok(MailActionOutcome(true))
        openMailTab()
        composeRule.waitUntil(WAIT_MS) { described("Unread") == 1 }
        openMail1()

        composeRule.onNodeWithTag(MailReadTags.MORE).performClick()
        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.UNREAD)).performClick()

        // Back in the list without pressing back.
        waitFor(MailListTags.row(id(1)))
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertDoesNotExist()
        composeRule.waitUntil(WAIT_MS) { described("Unread") == 2 }
        coVerify(exactly = 1) { reader.act(id(1), MailListAction.UNREAD, null) }
        coVerify(exactly = 0) { mail.markRead(id(1)) }
    }

    @Test
    fun `a flag set in the read view shows on the list row after going back`() {
        coEvery { reader.act(id(1), MailListAction.FLAG, null) } returns MailResult.Ok(MailActionOutcome(true))
        openMailTab()
        assertEquals(0, described("Flagged"))
        openMail1()

        composeRule.onNodeWithTag(MailReadTags.MORE).performClick()
        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.FLAG)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()

        waitFor(MailListTags.row(id(1)))
        composeRule.waitUntil(WAIT_MS) { described("Flagged") == 1 }
    }

    @Test
    fun `the read view's move picker offers the other folders of the list's account, and the moved mail leaves the list`() {
        coEvery { reader.act(id(1), MailListAction.MOVE, projects.id) } returns MailResult.Ok(MailActionOutcome(true))
        openMailTab()
        openMail1()

        composeRule.onNodeWithTag(MailReadTags.MORE).performClick()
        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.MOVE)).performClick()
        waitFor(MailMoveTags.DIALOG)
        composeRule.onNodeWithTag(MailMoveTags.folder(inbox.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailMoveTags.folder(projects.id)).performClick()

        // Like archive and delete, a move goes on to the next mail of the list; back then shows the list without it.
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(hasText("Subject 2")).fetchSemanticsNodes().isNotEmpty() }
        coVerify(exactly = 1) { reader.act(id(1), MailListAction.MOVE, projects.id) }
        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        waitFor(MailListTags.row(id(2)))
        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertDoesNotExist()
    }

    @Test
    fun `a long press selects rows, bulk archive takes them out, and Undo moves them back to their folder`() {
        coEvery { mail.actAll(listOf(id(1), id(2)), MailListAction.ARCHIVE, null) } returns MailResult.Ok(setOf(id(1), id(2)))
        coEvery { mail.actAll(listOf(id(1), id(2)), MailListAction.MOVE, inbox.id) } returns MailResult.Ok(setOf(id(1), id(2)))
        openMailTab()

        composeRule.onNodeWithTag(MailListTags.row(id(1))).performTouchInput { longClick() }
        waitFor(MailListTags.SELECTION_BAR)
        // While selecting, a tap adds a row instead of opening it.
        composeRule.onNodeWithTag(MailListTags.row(id(2))).performClick()
        composeRule.onNodeWithText("2 selected").assertExists()
        composeRule.onNodeWithTag(MailListTags.selectionAction(MailListAction.ARCHIVE)).performClick()

        waitGone(MailListTags.row(id(1)))
        waitGone(MailListTags.row(id(2)))
        composeRule.onNodeWithTag(MailListTags.SELECTION_BAR).assertDoesNotExist()
        coVerify(exactly = 1) { mail.actAll(listOf(id(1), id(2)), MailListAction.ARCHIVE, null) }

        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("2 mails archived").assertExists()
        composeRule.onNodeWithText("Undo").performClick()

        waitFor(MailListTags.row(id(1)))
        waitFor(MailListTags.row(id(2)))
        coVerify(exactly = 1) { mail.actAll(listOf(id(1), id(2)), MailListAction.MOVE, inbox.id) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
