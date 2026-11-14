package ch.sonensei.cockpit.mail.read

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailNextAction
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * The read route with a real view model: "Next action & archive" replaces the mail with the new task's detail (empty
 * here, without Hilt), and back leaves it for where the mail was opened from, or for the mail when it was not archived.
 */
@RunWith(AndroidJUnit4::class)
class MailReadNextActionRouteTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val taskId: UUID = UUID.fromString("00000000-0000-7000-9000-000000000001")

    private val message =
        MailMessage(
            id = id(1),
            subject = "Quarterly report",
            from = "Anna Example <anna@example.org>",
            to = listOf("me@example.org"),
            cc = emptyList(),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "",
            text = "Hello",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private val reader = mockk<MailReadRepository>()
    private val mail = mockk<MailRepository>()
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
    }

    private fun show() {
        val vm = readViewModel(reader, mail)
        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(
                    id = id(1),
                    onClose = { events += "close" },
                    onRemoved = { events += "removed $it" },
                    viewModel = vm,
                    attachmentsViewModel = attachmentsViewModel(),
                )
            }
        }
        waitFor(MailReadTags.SUBJECT)
    }

    private fun waitFor(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitGone(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun `an archived mail gives way to its task, and back leaves for the list with the mail removed`() {
        coEvery { mail.toNextActions(listOf(id(1))) } returns
            MailResult.Ok(listOf(MailNextAction(id(1), taskId, "Quarterly report", archived = true)))
        show()

        composeRule.onNodeWithTag(MailReadTags.NEXT_ACTION).performClick()
        waitGone(MailReadTags.SUBJECT)
        assertEquals(emptyList<String>(), events)

        pressBack()

        assertEquals(listOf("removed ${id(1)}", "close"), events)
        coVerify(exactly = 1) { mail.toNextActions(listOf(id(1))) }
    }

    @Test
    fun `a mail not archived gives way to its task too, and back returns to the mail with the notice`() {
        coEvery { mail.toNextActions(listOf(id(1))) } returns
            MailResult.Ok(listOf(MailNextAction(id(1), taskId, "Quarterly report", archived = false, archiveErrorCode = "NO_ROLE_FOLDER")))
        show()

        composeRule.onNodeWithTag(MailReadTags.NEXT_ACTION).performClick()
        waitGone(MailReadTags.SUBJECT)

        pressBack()

        waitFor(MailReadTags.SUBJECT)
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("Next action created, but “Quarterly report” could not be archived. This account has no Archive folder.")
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a failed creation keeps the mail shown with the error`() {
        coEvery { mail.toNextActions(listOf(id(1))) } returns MailResult.Failed(404)
        show()

        composeRule.onNodeWithTag(MailReadTags.NEXT_ACTION).performClick()
        waitFor(MailReadTags.NOTICE)

        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Quarterly report")
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("The next action could not be created; the mail stays where it is.")
        assertEquals(emptyList<String>(), events)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
