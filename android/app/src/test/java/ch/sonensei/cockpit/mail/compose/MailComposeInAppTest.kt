package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.test.assertTextEquals
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
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailAccountFolders
import ch.sonensei.cockpit.mail.list.MailListTags
import ch.sonensei.cockpit.mail.list.MailPage
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.MailSummary
import ch.sonensei.cockpit.mail.list.Mailbox
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

/** Compose inside the Mail tab of the Hilt activity: opened from the list and from a mail, closed by back. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MailComposeInAppTest {
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

    @BindValue
    @JvmField
    val compose: MailComposeRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")

    private val message =
        MailMessage(
            id = id(1),
            subject = "Subject 1",
            from = "Anna Example <anna@example.org>",
            to = emptyList(),
            cc = emptyList(),
            date = null,
            seen = true,
            html = "",
            text = "Body 1",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private val replyDraft =
        Draft(
            id = id(7),
            accountId = account.id,
            etag = "\"1\"",
            to = listOf(Recipient("anna@example.org")),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "Re: Subject 1",
            html = "",
            text = "> Body 1",
            attachments = emptyList(),
        )

    private val summary = MailSummary(id(1), account.id, "Anna Example", "Subject 1", "Snippet", Instant.EPOCH, true, false, false)

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { mail.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, emptyList())))
        coEvery { mail.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(summary), null))
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        coEvery { compose.accounts() } returns MailResult.Ok(listOf(account))
        coEvery { compose.start(any()) } returns MailResult.Ok(replyDraft)
        coEvery { compose.discard(any(), any()) } returns MailResult.Ok(Unit)
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

    @Test
    fun `the compose button opens a new mail, the system back returns to the list without storing anything`() {
        openMail()

        composeRule.onNodeWithTag(MailListTags.COMPOSE).performClick()
        waitFor(MailComposeTags.TITLE)
        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("New message")
        composeRule.onNodeWithTag(MailListTags.LIST).assertDoesNotExist()

        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(MailListTags.row(id(1)))
        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertDoesNotExist()
        coVerify(exactly = 0) { compose.create(any(), any()) }
    }

    @Test
    fun `reply in the read view starts a reply of that mail, leaving it untouched discards it and shows the mail again`() {
        openMail()
        composeRule.onNodeWithTag(MailListTags.row(id(1))).performClick()
        waitFor(MailReadTags.REPLY)

        composeRule.onNodeWithTag(MailReadTags.REPLY).performClick()
        waitFor(MailComposeTags.SUBJECT)
        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("Reply")
        coVerify(exactly = 1) { compose.start(ComposeMode.Reply(id(1))) }

        composeRule.onNodeWithTag(MailComposeTags.BACK).performClick()
        waitFor(MailReadTags.SUBJECT)
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Subject 1")
        coVerify(exactly = 1) { compose.discard(id(7), "\"1\"") }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
