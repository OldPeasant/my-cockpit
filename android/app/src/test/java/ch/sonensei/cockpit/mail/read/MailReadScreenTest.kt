package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertTrue
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
                        loadImages = { calls += "load images" },
                        alwaysLoadImages = { calls += "always images" },
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
            MailReadTags.MORE,
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
    fun `the toolbar asks for back, the GTD actions, archive, delete and spam from its menu`() {
        show(MailReadUiState(message = message))

        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        // The last tag is the menu button: it opens the menu with spam.
        actionTags.forEach { composeRule.onNodeWithTag(it).assertIsEnabled().performClick() }
        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.SPAM)).performClick()

        assertEquals(listOf("back", "gtd TASK", "gtd WAITING", "act ARCHIVE", "act DELETE", "act SPAM"), calls)
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
    fun `the spam menu item is labelled`() {
        show(MailReadUiState(message = message))
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Move to spam").assertIsEnabled()
    }

    @Test
    fun `a failed spam without a Spam folder shows the server's reason`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.SPAM, "NO_ROLE_FOLDER")))
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("The mail could not be moved to spam. This account has no Spam folder.")
    }

    @Test
    fun `another server error code is shown as it came`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.SPAM, "CONNECTION_FAILED")))
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("The mail could not be moved to spam. Server error: CONNECTION_FAILED")
    }

    @Test
    fun `a failed spam without a server code says only that it failed`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.SPAM)))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The mail could not be moved to spam.")
    }

    @Test
    fun `a missing role folder names the folder of the action`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.ARCHIVE, "NO_ROLE_FOLDER")))
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("The mail could not be archived. This account has no Archive folder.")
    }

    @Test
    fun `a delete without a Trash folder names the Trash folder`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.DELETE, "NO_ROLE_FOLDER")))
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextContains("This account has no Trash folder.", substring = true)
    }

    @Test
    fun `the menu button comes after archive and delete`() {
        show(MailReadUiState(message = message))

        fun left(tag: String) =
            composeRule
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue(left(MailReadTags.action(MailListAction.ARCHIVE)) < left(MailReadTags.action(MailListAction.DELETE)))
        assertTrue(left(MailReadTags.action(MailListAction.DELETE)) < left(MailReadTags.MORE))
    }

    @Test
    fun `the menu is disabled while an action runs`() {
        show(MailReadUiState(message = message, busy = true))
        composeRule.onNodeWithContentDescription("More actions").assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.SPAM)).assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the route moves the mail to spam and closes, or stays open with the server's error`() {
        val reader = mockk<MailReadRepository>()
        val mail = mockk<MailRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        coEvery { reader.act(id(1), MailListAction.SPAM) } returns MailResult.Ok(MailActionOutcome(false, "NO_ROLE_FOLDER"))
        val vm = readViewModel(reader, mail)

        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(
                    id = id(1),
                    onClose = { calls += "close" },
                    onRemoved = { calls += "removed $it" },
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailReadTags.MORE).performClick()
        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.SPAM)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextContains("no Spam folder", substring = true)
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Quarterly report")
        assertEquals(emptyList<String>(), calls)

        coEvery { reader.act(id(1), MailListAction.SPAM) } returns MailResult.Ok(MailActionOutcome(true))
        composeRule.onNodeWithTag(MailReadTags.MORE).performClick()
        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.SPAM)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("removed ${id(1)}", "close"), calls)
    }

    @Test
    fun `the route opens the mail, reports it seen, and after a delete reports it removed and closes`() {
        val reader = mockk<MailReadRepository>()
        val mail = mockk<MailRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message.copy(seen = false))
        coEvery { mail.markRead(id(1)) } returns MailResult.Ok(true)
        coEvery { reader.act(id(1), MailListAction.DELETE) } returns MailResult.Ok(MailActionOutcome(true))
        val vm = readViewModel(reader, mail)

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
    fun `blocked remote images show a banner with load images and always for this sender`() {
        show(MailReadUiState(message = message.copy(remoteImagesBlocked = 2, fromAddress = "anna@example.org")))

        composeRule.onNodeWithTag(MailReadTags.IMAGES_BANNER).assertIsDisplayed()
        composeRule.onNodeWithTag(MailReadTags.IMAGES_BLOCKED).assertTextEquals("2 remote images were blocked.")
        composeRule
            .onNodeWithTag(MailReadTags.LOAD_IMAGES)
            .assertTextEquals("Load images")
            .assertIsEnabled()
            .performClick()
        composeRule
            .onNodeWithTag(MailReadTags.ALWAYS_IMAGES)
            .assertTextEquals("Always for this sender")
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf("load images", "always images"), calls)
    }

    @Test
    fun `one blocked image is counted in the singular`() {
        show(MailReadUiState(message = message.copy(remoteImagesBlocked = 1, fromAddress = "anna@example.org")))
        composeRule.onNodeWithTag(MailReadTags.IMAGES_BLOCKED).assertTextEquals("1 remote image was blocked.")
    }

    @Test
    fun `without a sender address the banner offers only load images`() {
        show(MailReadUiState(message = message.copy(remoteImagesBlocked = 3, fromAddress = null)))

        composeRule.onNodeWithTag(MailReadTags.LOAD_IMAGES).assertIsEnabled()
        composeRule.onNodeWithTag(MailReadTags.ALWAYS_IMAGES).assertDoesNotExist()
    }

    @Test
    fun `a mail without blocked images has no banner`() {
        show(MailReadUiState(message = message.copy(remoteImagesBlocked = 0, fromAddress = "anna@example.org")))

        composeRule.onNodeWithTag(MailReadTags.IMAGES_BANNER).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.LOAD_IMAGES).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.ALWAYS_IMAGES).assertDoesNotExist()
    }

    @Test
    fun `the banner buttons wait while an action runs`() {
        show(MailReadUiState(message = message.copy(remoteImagesBlocked = 2, fromAddress = "anna@example.org"), busy = true))

        composeRule.onNodeWithTag(MailReadTags.LOAD_IMAGES).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(MailReadTags.ALWAYS_IMAGES).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `failed images are reported`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ImagesFailed))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The images could not be loaded.")
    }

    @Test
    fun `an unsaved sender is reported`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.SenderNotSaved))
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("The sender could not be saved; images are shown for this mail only.")
    }

    @Test
    fun `the route's load images shows the mail with proxied images and the banner goes away`() {
        val reader = mockk<MailReadRepository>()
        val blocked = message.copy(remoteImagesBlocked = 2, fromAddress = "anna@example.org")
        val proxied = blocked.copy(html = """<p>Hello</p><img src="/api/v1/mail/image-proxy?url=x">""", remoteImagesBlocked = 0)
        coEvery { reader.message(id(1)) } returns MailResult.Ok(blocked)
        coEvery { reader.message(id(1), true) } returns MailResult.Ok(proxied)
        val vm = readViewModel(reader, mockk<MailRepository>())

        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(id = id(1), onClose = { calls += "close" }, viewModel = vm)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailReadTags.LOAD_IMAGES).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailReadTags.IMAGES_BANNER).assertDoesNotExist()
        coVerify(exactly = 1) { reader.message(id(1), true) }
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the route's back button closes without removing`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Failed(404)
        val vm = readViewModel(reader, mockk<MailRepository>())

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
