package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
                        nextAction = { calls += "next action" },
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
            MailReadTags.NEXT_ACTION,
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

        composeRule.onNodeWithTag(MailAttachmentTags.row("2")).assertTextContains("report.pdf").assertTextContains("1.0 KB")
        composeRule.onNodeWithTag(MailAttachmentTags.row("3")).assertTextContains("notes.txt")
        composeRule.onNodeWithTag(MailReadTags.IMAGES_BLOCKED).assertTextEquals("2 remote images were blocked.")
    }

    @Test
    fun `the toolbar asks for back, the GTD actions, archive, delete, and waiting and spam from its menu`() {
        show(MailReadUiState(message = message))

        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        // The last tag is the menu button: it opens the menu with "To Waiting" and spam.
        actionTags.forEach { composeRule.onNodeWithTag(it).assertIsEnabled().performClick() }
        composeRule.onNodeWithTag(MailReadTags.gtd(MailGtdAction.WAITING)).performClick()
        composeRule.onNodeWithTag(MailReadTags.MORE).performClick()
        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.SPAM)).performClick()

        assertEquals(
            listOf("back", "gtd TASK", "next action", "act ARCHIVE", "act DELETE", "gtd WAITING", "act SPAM"),
            calls,
        )
    }

    @Test
    fun `next action and archive sits right after To task, labelled, and To Waiting is only in the menu`() {
        show(MailReadUiState(message = message))

        fun left(tag: String) =
            composeRule
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue(left(MailReadTags.gtd(MailGtdAction.TASK)) < left(MailReadTags.NEXT_ACTION))
        assertTrue(left(MailReadTags.NEXT_ACTION) < left(MailReadTags.action(MailListAction.ARCHIVE)))
        composeRule.onNodeWithContentDescription("Next action & archive").assertIsEnabled()
        composeRule.onNodeWithTag(MailReadTags.gtd(MailGtdAction.WAITING)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.MORE).performClick()
        composeRule.onNodeWithTag(MailReadTags.gtd(MailGtdAction.WAITING)).assertIsDisplayed()
    }

    @Test
    fun `next action and archive has a 48dp touch target`() {
        show(MailReadUiState(message = message))
        val node = composeRule.onNodeWithTag(MailReadTags.NEXT_ACTION).fetchSemanticsNode()
        val minimum = with(composeRule.density) { 48.dp.toPx() }
        // The touch bounds include the minimum interactive size even when the drawn button is 40dp.
        assertTrue(node.touchBoundsInRoot.width >= minimum - 0.5f)
        assertTrue(node.touchBoundsInRoot.height >= minimum - 0.5f)
    }

    @Test
    fun `a failed next action is reported, the mail stays`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.NextActionFailed))
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("The next action could not be created; the mail stays where it is.")
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Quarterly report")
    }

    @Test
    fun `a mail not archived after its next action is named, with the server's reason`() {
        var notice by mutableStateOf<MailReadNotice>(MailReadNotice.NotArchived("Quarterly report", "NO_ROLE_FOLDER"))
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = message, notice = notice),
                    MailReadCallbacks(),
                    zone = ZoneId.of("Europe/Zurich"),
                    body = { _, modifier -> Box(modifier) },
                )
            }
        }
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("Next action created, but “Quarterly report” could not be archived. This account has no Archive folder.")

        notice = MailReadNotice.NotArchived("Quarterly report", "CONNECTION_FAILED")
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("Next action created, but “Quarterly report” could not be archived. Server error: CONNECTION_FAILED")

        notice = MailReadNotice.NotArchived("Quarterly report")
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("Next action created, but “Quarterly report” could not be archived.")
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
                    attachmentsViewModel = attachmentsViewModel(),
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
                    attachmentsViewModel = attachmentsViewModel(),
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
                MailReadRoute(id = id(1), onClose = { calls += "close" }, viewModel = vm, attachmentsViewModel = attachmentsViewModel())
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailReadTags.LOAD_IMAGES).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailReadTags.IMAGES_BANNER).assertDoesNotExist()
        coVerify(exactly = 1) { reader.message(id(1), true) }
        assertEquals(emptyList<String>(), calls)
    }

    /** A page whose body is a box [bodyHeight] tall (as a long mail's web view would be) and a short links panel. */
    private fun showPage(
        message: MailMessage,
        bodyHeight: Dp = 2000.dp,
        navigation: MailReadNavigation? = null,
    ) = composeRule.setContent {
        CockpitTheme {
            MailReadContent(
                MailReadUiState(message = message),
                MailReadCallbacks(),
                zone = ZoneId.of("Europe/Zurich"),
                navigation = navigation,
                body = { _, modifier -> Box(modifier.height(bodyHeight).testTag(MailReadTags.BODY)) },
                linksPanel = { Text("Linked: Budget plan", Modifier.testTag(LINKED_ITEM)) },
            )
        }
    }

    /** Where [tag] is laid out in the root, in dp, also when it is scrolled out of view. */
    private fun bounds(tag: String) =
        composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let {
            Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
        }

    private fun inPage(tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag(MailReadTags.PAGE))

    private val scrollers = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private val fullMessage =
        message.copy(
            remoteImagesBlocked = 2,
            fromAddress = "anna@example.org",
            attachments = listOf(MailAttachment("2", "report.pdf", 1024)),
        )

    @Test
    fun `headers, attachments, banner, reply buttons, body and links are one page in that order`() {
        showPage(fullMessage)

        val order =
            listOf(
                MailReadTags.HEADERS,
                MailReadTags.ATTACHMENTS,
                MailReadTags.IMAGES_BANNER,
                MailReadTags.REPLY,
                MailReadTags.BODY,
                MailReadTags.LINKS,
            )
        order.forEach { composeRule.onNode(inPage(it)).assertExists() }
        val tops = order.map { bounds(it).top }
        assertEquals("top to bottom: $order -> $tops", tops.sorted(), tops)
        assertEquals(tops.size, tops.distinct().size)
        // Header, body and links have no scroll of their own: the page is the only one outside the attachment list
        // (whose own short scroll box predates this change, see MailAttachments.kt).
        val outsideAttachments = scrollers and !hasAnyAncestor(hasTestTag(MailReadTags.ATTACHMENTS))
        composeRule.onAllNodes(outsideAttachments).assertCountEquals(1)
        composeRule.onNode(outsideAttachments).assert(hasTestTag(MailReadTags.PAGE))
    }

    @Test
    fun `the toolbar and the previous-next row stay outside the page`() {
        showPage(message, navigation = MailReadNavigation(2, 5, id(2), id(3)) {})

        listOf(MailReadTags.BACK, MailReadTags.MORE, MailReadTags.PREVIOUS, MailReadTags.NEXT, MailReadTags.POSITION).forEach {
            composeRule.onNodeWithTag(it).assertExists()
            composeRule.onNode(inPage(it)).assertDoesNotExist()
        }
        assertTrue(bounds(MailReadTags.NEXT).bottom <= bounds(MailReadTags.PAGE).top)
    }

    @Test
    fun `the body gets its full height, and scrolling past the headers leaves it filling the page`() {
        showPage(message, navigation = MailReadNavigation(2, 5, id(2), id(3)) {})
        val back = bounds(MailReadTags.BACK)
        val previous = bounds(MailReadTags.PREVIOUS)
        val page = bounds(MailReadTags.PAGE)

        // Not squeezed into what is left below the headers (the old weight(1f)): the page scrolls instead.
        composeRule.onNodeWithTag(MailReadTags.BODY).assertHeightIsEqualTo(2000.dp)
        assertTrue(page.height < 2000f)
        composeRule.onNodeWithTag(MailReadTags.LINKS).assertIsNotDisplayed()

        composeRule.onNodeWithTag(MailReadTags.BODY).performScrollTo()

        val body = bounds(MailReadTags.BODY)
        assertEquals(page.top, body.top, 1f)
        assertTrue("the mail fills the page down to its bottom", body.bottom >= page.bottom)
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertIsNotDisplayed()
        // The fixed bar did not move.
        assertEquals(back, bounds(MailReadTags.BACK))
        assertEquals(previous, bounds(MailReadTags.PREVIOUS))
        composeRule.onNodeWithTag(MailReadTags.BACK).assertIsDisplayed()
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertIsDisplayed()
    }

    @Test
    fun `the links after a long mail are reached by scrolling to the end of the page`() {
        showPage(message)
        composeRule.onNodeWithTag(LINKED_ITEM).assertIsNotDisplayed()

        composeRule.onNodeWithTag(MailReadTags.PAGE).performTouchInput { repeat(10) { swipeUp() } }

        composeRule.onNodeWithTag(LINKED_ITEM).assertIsDisplayed()
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertIsNotDisplayed()
        assertTrue(bounds(MailReadTags.BODY).bottom <= bounds(MailReadTags.LINKS).top)
    }

    @Test
    fun `a short mail leaves no empty space before the links`() {
        showPage(message, bodyHeight = 40.dp)

        composeRule.onNodeWithTag(MailReadTags.BODY).assertHeightIsEqualTo(40.dp)
        composeRule.onNodeWithTag(LINKED_ITEM).assertIsDisplayed()
        assertEquals(bounds(MailReadTags.BODY).bottom, bounds(MailReadTags.LINKS).top, 1f)
    }

    @Test
    fun `a long plain-text mail scrolls with the page, not in a box of its own`() {
        val text = (1..200).joinToString("\n") { "Line $it of the fictitious minutes" }
        showPage(message.copy(html = "", text = text))

        composeRule.onNode(inPage(MailReadTags.TEXT)).assertExists()
        composeRule.onAllNodes(scrollers).assertCountEquals(1)
        composeRule.onNode(scrollers).assert(hasTestTag(MailReadTags.PAGE))
        // The whole text is laid out: taller than the page, and the links follow it.
        assertTrue(bounds(MailReadTags.TEXT).height > bounds(MailReadTags.PAGE).height)
        assertTrue(bounds(MailReadTags.TEXT).bottom <= bounds(MailReadTags.LINKS).top)

        composeRule.onNodeWithTag(LINKED_ITEM).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertIsNotDisplayed()
    }

    @Test
    fun `the next mail starts at the top of the page, images loading keep the position`() {
        var shown by mutableStateOf(message)
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = shown),
                    MailReadCallbacks(),
                    zone = ZoneId.of("Europe/Zurich"),
                    body = { _, modifier -> Box(modifier.height(2000.dp).testTag(MailReadTags.BODY)) },
                    linksPanel = { Text("Linked: Budget plan", Modifier.testTag(LINKED_ITEM)) },
                )
            }
        }

        fun scrolled() =
            composeRule
                .onNodeWithTag(MailReadTags.PAGE)
                .fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
                .value()

        composeRule.onNodeWithTag(LINKED_ITEM).performScrollTo()
        val end = scrolled()
        assertTrue(end > 0f)

        shown = message.copy(html = "<p>Hello</p><img src=\"/api/v1/mail/image-proxy?url=x\">")
        composeRule.waitForIdle()
        assertEquals(end, scrolled(), 0.5f)

        shown = message.copy(id = id(2), subject = "Next mail")
        composeRule.waitForIdle()
        assertEquals(0f, scrolled(), 0.5f)
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Next mail").assertIsDisplayed()
    }

    @Test
    fun `the route shows the real body inside the page, below the headers`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        val vm = readViewModel(reader, mockk<MailRepository>())

        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(id = id(1), onClose = {}, viewModel = vm, attachmentsViewModel = attachmentsViewModel())
            }
        }
        composeRule.waitForIdle()

        // The real body: a web view inside the page, below the headers.
        composeRule.onNode(inPage(MailReadTags.BODY)).assertExists()
        assertTrue(bounds(MailReadTags.HEADERS).top < bounds(MailReadTags.BODY).top)
    }

    @Test
    fun `the route's back button closes without removing`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Failed(404)
        val vm = readViewModel(reader, mockk<MailRepository>())

        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(id = id(1), onClose = {
                    calls += "close"
                }, onSeen = { calls += "seen" }, viewModel = vm, attachmentsViewModel = attachmentsViewModel())
            }
        }
        composeRule.onNodeWithTag(MailReadTags.ERROR).assertTextContains("no longer", substring = true)
        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()

        assertEquals(listOf("close"), calls)
    }

    private companion object {
        const val LINKED_ITEM = "fake-linked-item"
    }
}
