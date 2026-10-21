package ch.sonensei.cockpit.mail.list

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

/** The Mail tab in the Hilt activity with fake repositories: a mail opens from the list, back returns, actions reach the list. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MailRouteInAppTest {
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
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message(1))
        coEvery { reader.message(id(2)) } returns MailResult.Ok(message(2))
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

    private val unreadDots get() =
        composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Unread")))
            .fetchSemanticsNodes()
            .size

    @Test
    fun `a mail opened from the list is shown, marked read, and back returns to the list`() {
        openMail()
        composeRule.waitUntil(WAIT_MS) { unreadDots == 2 }

        composeRule.onNodeWithTag(MailListTags.row(id(1))).performClick()
        waitFor(MailReadTags.TEXT)
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertExists()
        composeRule.onNodeWithTag(MailListTags.LIST).assertDoesNotExist()
        coVerify(exactly = 1) { mail.markRead(id(1)) }

        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        waitFor(MailListTags.row(id(1)))
        // The opened mail is no longer shown as unread.
        composeRule.waitUntil(WAIT_MS) { unreadDots == 1 }
    }

    @Test
    fun `the system back closes the mail, not the Mail tab`() {
        openMail()
        composeRule.onNodeWithTag(MailListTags.row(id(2))).performClick()
        waitFor(MailReadTags.TEXT)

        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(MailListTags.row(id(2)))
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertDoesNotExist()
    }

    @Test
    fun `a mail deleted in the read view leaves the list`() {
        coEvery { mail.act(id(2), MailListAction.DELETE) } returns MailResult.Ok(true)
        openMail()
        composeRule.onNodeWithTag(MailListTags.row(id(2))).performClick()
        waitFor(MailReadTags.action(MailListAction.DELETE))
        waitFor(MailReadTags.TEXT)

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.DELETE)).performClick()
        waitFor(MailListTags.row(id(1)))

        composeRule.onNodeWithTag(MailListTags.row(id(2))).assertDoesNotExist()
        coVerify(exactly = 1) { mail.act(id(2), MailListAction.DELETE) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
