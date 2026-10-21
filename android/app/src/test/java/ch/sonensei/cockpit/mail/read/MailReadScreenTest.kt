package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailListAction
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The read view alone, driven by states the tests control; the WebView is replaced by a marker that records its html. */
@RunWith(AndroidJUnit4::class)
class MailReadScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val message =
        MailMessage(
            id = id(1),
            subject = "Quarterly report",
            from = "Anna Example <anna@example.org>",
            to = listOf("me@example.org", "Ben Example <ben@example.org>"),
            cc = listOf("Carla Example"),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "<p>Hello</p>",
            text = "Hello",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private fun show(state: MailReadUiState) =
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    state,
                    MailReadCallbacks(
                        back = { calls += "back" },
                        retry = { calls += "retry" },
                        gtd = { calls += "gtd $it" },
                        act = { calls += "act $it" },
                    ),
                    zone = ZoneId.of("Europe/Zurich"),
                    body = { html, modifier ->
                        bodies += html
                        Box(modifier.testTag(MailReadTags.BODY))
                    },
                )
            }
        }

    private val actionTags =
        listOf(
            MailReadTags.gtd(MailGtdAction.TASK),
            MailReadTags.gtd(MailGtdAction.WAITING),
            MailReadTags.action(MailListAction.ARCHIVE),
            MailReadTags.action(MailListAction.DELETE),
        )

    @Test
    fun `the headers are shown above the html body`() {
        show(MailReadUiState(message = message))

        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Quarterly report")
        composeRule.onNodeWithText("From Anna Example <anna@example.org>").assertExists()
        composeRule.onNodeWithText("To me@example.org, Ben Example <ben@example.org>").assertExists()
        composeRule.onNodeWithText("Cc Carla Example").assertExists()
        composeRule.onNodeWithTag(MailReadTags.BODY).assertExists()
        composeRule.onNodeWithTag(MailReadTags.TEXT).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.ATTACHMENTS).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.IMAGES_BLOCKED).assertDoesNotExist()
        assertEquals("<p>Hello</p>", bodies.last())
    }

    @Test
    fun `a text-only mail is shown as text, without the web view`() {
        show(
            MailReadUiState(
                message = message.copy(subject = "", html = "  ", text = "Plain text only", to = emptyList(), cc = emptyList()),
            ),
        )

        composeRule.onNodeWithTag(MailReadTags.TEXT).assertTextEquals("Plain text only")
        composeRule.onNodeWithTag(MailReadTags.BODY).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("(no subject)")
        composeRule.onNodeWithText("To ", substring = true).assertDoesNotExist()
        assertEquals(emptyList<String>(), bodies)
    }

    @Test
    fun `attachments are listed and blocked remote images counted`() {
        show(
            MailReadUiState(
                message =
                    message.copy(
                        remoteImagesBlocked = 2,
                        attachments = listOf(MailAttachment("2", "report.pdf", 1024), MailAttachment("3", "notes.txt", null)),
                    ),
            ),
        )

        composeRule.onNodeWithTag(MailReadTags.ATTACHMENTS).assertTextEquals("Attachments: report.pdf, notes.txt")
        composeRule.onNodeWithTag(MailReadTags.IMAGES_BLOCKED).assertTextEquals("2 remote images were blocked.")
    }

    @Test
    fun `the toolbar asks for back, the GTD actions, archive and delete`() {
        show(MailReadUiState(message = message))

        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        actionTags.forEach { composeRule.onNodeWithTag(it).assertIsEnabled().performClick() }

        assertEquals(listOf("back", "gtd TASK", "gtd WAITING", "act ARCHIVE", "act DELETE"), calls)
    }

    @Test
    fun `the actions wait while one runs, back still works`() {
        show(MailReadUiState(message = message, busy = true))

        actionTags.forEach { composeRule.onNodeWithTag(it).assertIsNotEnabled() }
        composeRule.onNodeWithTag(MailReadTags.BACK).assertIsEnabled()
    }

    @Test
    fun `without a mail the actions are disabled`() {
        show(MailReadUiState(loading = true))

        actionTags.forEach { composeRule.onNodeWithTag(it).assertIsNotEnabled() }
        composeRule.onNodeWithTag(MailReadTags.HEADERS).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun `an offline or failed load offers a retry`() {
        show(MailReadUiState(error = MailReadError.OFFLINE))

        composeRule.onNodeWithTag(MailReadTags.ERROR).assertTextEquals("Can't reach the server.")
        composeRule.onNodeWithTag(MailReadTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `a failed load says so`() {
        show(MailReadUiState(error = MailReadError.FAILED))
        composeRule.onNodeWithTag(MailReadTags.ERROR).assertTextEquals("The mail could not be loaded.")
        composeRule.onNodeWithTag(MailReadTags.RETRY).assertIsDisplayed()
    }

    @Test
    fun `a mail that is gone offers no retry`() {
        show(MailReadUiState(error = MailReadError.GONE))

        composeRule.onNodeWithTag(MailReadTags.ERROR).assertTextEquals("This mail is no longer on the mail server.")
        composeRule.onNodeWithTag(MailReadTags.RETRY).assertDoesNotExist()
    }

    @Test
    fun `a created task is announced with its title`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.Created(MailGtdAction.TASK, "Quarterly report")))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("Added to the Inbox: Quarterly report")
    }

    @Test
    fun `a created waiting item is announced with its title`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.Created(MailGtdAction.WAITING, "Quarterly report")))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("Added to Waiting For: Quarterly report")
    }

    @Test
    fun `a failed waiting item is reported`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.GtdFailed(MailGtdAction.WAITING)))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The Waiting For item could not be created.")
    }

    @Test
    fun `a failed task is reported`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.GtdFailed(MailGtdAction.TASK)))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The task could not be created.")
    }

    @Test
    fun `a failed archive is reported`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.ARCHIVE)))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The mail could not be archived.")
    }

    @Test
    fun `a failed delete is reported`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.DELETE)))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextContains("could not be deleted", substring = true)
    }

    @Test
    fun `the route opens the mail, reports it seen, and after a delete reports it removed and closes`() {
        val reader = mockk<MailReadRepository>()
        val mail = mockk<MailRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message.copy(seen = false))
        coEvery { mail.markRead(id(1)) } returns MailResult.Ok(true)
        coEvery { mail.act(id(1), MailListAction.DELETE) } returns MailResult.Ok(true)
        val vm = MailReadViewModel(reader, mail)

        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(
                    id = id(1),
                    onClose = { calls += "close" },
                    onSeen = { calls += "seen $it" },
                    onRemoved = { calls += "removed $it" },
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Quarterly report")
        assertEquals(listOf("seen ${id(1)}"), calls)
        coVerify(exactly = 1) { mail.markRead(id(1)) }

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.DELETE)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("seen ${id(1)}", "removed ${id(1)}", "close"), calls)
    }

    @Test
    fun `the route's back button closes without removing`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Failed(404)
        val vm = MailReadViewModel(reader, mockk<MailRepository>())

        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(id = id(1), onClose = { calls += "close" }, onSeen = { calls += "seen" }, viewModel = vm)
            }
        }
        composeRule.onNodeWithTag(MailReadTags.ERROR).assertTextContains("no longer", substring = true)
        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()

        assertEquals(listOf("close"), calls)
    }
}
