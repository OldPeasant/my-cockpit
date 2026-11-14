package ch.sonensei.cockpit.mail.list

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.links.ItemRef
import ch.sonensei.cockpit.core.links.LinkedItem
import ch.sonensei.cockpit.core.links.LinkedItemsTags
import ch.sonensei.cockpit.core.links.LinksRepository
import ch.sonensei.cockpit.core.links.LinksResult
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.task.TaskDetail
import ch.sonensei.cockpit.gtd.task.TaskDetailRepository
import ch.sonensei.cockpit.gtd.task.TaskDetailTags
import ch.sonensei.cockpit.gtd.task.TaskResult
import ch.sonensei.cockpit.mail.read.MailMessage
import ch.sonensei.cockpit.mail.read.MailReadRepository
import ch.sonensei.cockpit.mail.read.MailReadTags
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
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
 * "Next action & archive" in the Hilt activity with fake repositories, through the real view models and screens: the
 * read view opens the new task's detail, whose links show the mail; back returns to the list without the archived
 * mail; the multi-selection's snackbar opens Next Actions.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MailNextActionInAppTest {
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
    val taskDetail: TaskDetailRepository = mockk()

    @BindValue
    @JvmField
    val links: LinksRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val taskId: UUID = UUID.fromString("00000000-0000-7000-9000-000000000001")

    private val account = MailAccount(id(80), "Work", "me@example.org")
    private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val archive = MailFolder(id(91), account.id, "Archive", "Archive", FolderRole.ARCHIVE, 0)

    private fun summary(n: Int) =
        MailSummary(id(n), account.id, "Anna Example", "Subject $n", "Snippet $n", Instant.EPOCH, true, false, false, inbox.id)

    private fun message(
        n: Int,
        folder: MailFolder = inbox,
    ) = MailMessage(
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
        accountId = account.id,
        folderId = folder.id,
    )

    private val toMail = LinkedItem(id(70), "EMAIL", id(1), "Subject 1", "Anna Example", "/mail/${id(1)}", available = true, manual = false)

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { mail.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, archive))))
        coEvery { mail.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(summary(1), summary(2)), null))
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message(1))
        coEvery { reader.message(id(2)) } returns MailResult.Ok(message(2))
        val task = GtdTask(taskId, "Subject 1", TaskList.NEXT, 1, 1)
        coEvery { taskDetail.cached(any()) } returns null
        coEvery { taskDetail.load(taskId) } returns TaskResult.Ok(TaskDetail(task))
        coEvery { links.list(any()) } returns LinksResult.Ok(emptyList())
        coEvery { links.list(ItemRef("TASK", taskId)) } returns LinksResult.Ok(listOf(toMail))
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

    private fun waitFor(tag: String) =
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitGone(tag: String) =
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
        }

    private fun pressBack() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** Opens mail 1 and runs "Next action & archive", which archives it; the task detail shows. */
    private fun nextActionFromTheReadView() {
        coEvery { mail.toNextActions(listOf(id(1))) } returns
            MailResult.Ok(listOf(MailNextAction(id(1), taskId, "Subject 1", archived = true)))
        openMailTab()
        composeRule.onNodeWithTag(MailListTags.row(id(1))).performClick()
        waitFor(MailReadTags.TEXT)
        composeRule.onNodeWithTag(MailReadTags.NEXT_ACTION).performClick()
        waitFor(TaskDetailTags.TITLE)
    }

    @Test
    fun `the read view opens the new task, whose links show the mail, and back returns to the list without it`() {
        nextActionFromTheReadView()

        composeRule.onNodeWithTag(TaskDetailTags.TITLE).assertTextEquals("Subject 1")
        waitFor(LinkedItemsTags.link(toMail))
        composeRule.onNodeWithTag(MailReadTags.TEXT).assertDoesNotExist()

        pressBack()

        waitFor(MailListTags.row(id(2)))
        waitGone(MailListTags.row(id(1)))
        composeRule.onNodeWithTag(TaskDetailTags.TITLE).assertDoesNotExist()
        coVerify(exactly = 1) { mail.toNextActions(listOf(id(1))) }
        coVerify(exactly = 1) { taskDetail.load(taskId) }
    }

    @Test
    fun `opening the linked mail from the new task's detail shows the archived mail`() {
        // Acceptance criterion 5 (TW-2): the task overlay of the read view opens its linked items; back returns to the task.
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message(1, folder = archive))
        nextActionFromTheReadView()
        waitFor(LinkedItemsTags.link(toMail))

        composeRule.onNodeWithTag(LinkedItemsTags.link(toMail)).performScrollTo().performClick()

        waitFor(MailReadTags.TEXT)
        composeRule.onNodeWithText("Body 1").assertExists()
        composeRule.onNodeWithTag(TaskDetailTags.TITLE).assertDoesNotExist()

        pressBack()

        waitFor(TaskDetailTags.TITLE)
        composeRule.onNodeWithTag(TaskDetailTags.TITLE).assertTextEquals("Subject 1")
        composeRule.onNodeWithTag(MailReadTags.TEXT).assertDoesNotExist()
        coVerify(exactly = 1) { mail.toNextActions(listOf(id(1))) }
    }

    @Test
    fun `back from the task of a mail that was not archived shows the mail with its notice and the new task in its links`() {
        // CR-3: the mail's links panel reloads after a Next action whose archive failed.
        val toTask = LinkedItem(id(71), "TASK", taskId, "Subject 1", null, "/tasks/$taskId", available = true, manual = false)
        coEvery { links.list(ItemRef("EMAIL", id(1))) } returnsMany
            listOf(LinksResult.Ok(emptyList()), LinksResult.Ok(listOf(toTask)))
        coEvery { mail.toNextActions(listOf(id(1))) } returns
            MailResult.Ok(listOf(MailNextAction(id(1), taskId, "Subject 1", archived = false)))
        openMailTab()
        composeRule.onNodeWithTag(MailListTags.row(id(1))).performClick()
        waitFor(MailReadTags.TEXT)
        composeRule.onNodeWithTag(LinkedItemsTags.link(toTask)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.NEXT_ACTION).performClick()
        waitFor(TaskDetailTags.TITLE)

        pressBack()

        waitFor(MailReadTags.TEXT)
        val notice = hasText("Next action created, but “Subject 1” could not be archived.")
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(notice).fetchSemanticsNodes().isNotEmpty() }
        waitFor(LinkedItemsTags.link(toTask))
        composeRule.onNodeWithTag(TaskDetailTags.TITLE).assertDoesNotExist()
        coVerify(exactly = 1) { mail.toNextActions(listOf(id(1))) }
    }

    @Test
    fun `the multi-selection's next actions leave the list and the snackbar opens Next Actions`() {
        coEvery { mail.toNextActions(listOf(id(1), id(2))) } returns
            MailResult.Ok(listOf(MailNextAction(id(1), taskId, "Subject 1", true), MailNextAction(id(2), id(60), "Subject 2", true)))
        openMailTab()

        composeRule.onNodeWithTag(MailListTags.row(id(1))).performTouchInput { longClick() }
        waitFor(MailListTags.SELECTION_BAR)
        composeRule.onNodeWithTag(MailListTags.row(id(2))).performClick()
        composeRule.onNodeWithTag(MailListTags.SELECTION_MORE).performClick()
        composeRule.onNodeWithTag(MailListTags.SELECTION_NEXT_ACTIONS).performClick()

        waitGone(MailListTags.row(id(1)))
        waitGone(MailListTags.row(id(2)))
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Mail)).assertExists()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(hasText("Open Next Actions")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("2 next actions created").assertExists()

        composeRule.onNodeWithText("Open Next Actions").performClick()

        waitFor(screenTag(CockpitDestination.Next))
        coVerify(exactly = 1) { mail.toNextActions(listOf(id(1), id(2))) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
