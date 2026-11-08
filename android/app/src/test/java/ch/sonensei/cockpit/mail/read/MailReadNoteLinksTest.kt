package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** "Create note" in the toolbar's menu, its notices, and the linked-items slot below the mail. */
@RunWith(AndroidJUnit4::class)
class MailReadNoteLinksTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val message =
        MailMessage(
            id = id(1),
            subject = "Quarterly report",
            from = "Anna Example <anna@example.org>",
            to = listOf("me@example.org"),
            cc = emptyList(),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "<p>Hello</p>",
            text = "Hello",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private val calls = mutableListOf<String>()

    private val noteTag = MailReadTags.gtd(MailGtdAction.NOTE)

    private val fakeLinksTag = "fake-links"

    private val fakeLinks: @Composable () -> Unit = {
        calls += "links panel"
        Text("Linked: Budget plan", Modifier.testTag(fakeLinksTag))
    }

    private fun show(
        state: MailReadUiState,
        linksPanel: @Composable () -> Unit = fakeLinks,
    ) = composeRule.setContent {
        CockpitTheme {
            MailReadContent(
                state,
                MailReadCallbacks(
                    gtd = { calls += "gtd $it" },
                    act = { calls += "act $it" },
                ),
                zone = ZoneId.of("Europe/Zurich"),
                body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
                linksPanel = linksPanel,
            )
        }
    }

    /** Opens the toolbar's menu, where "Create note" is. */
    private fun openMenu() = composeRule.onNodeWithTag(MailReadTags.MORE).performClick()

    @Test
    fun `create note in the menu asks for a note and closes the menu`() {
        show(MailReadUiState(message = message), linksPanel = {})

        openMenu()
        composeRule.onNodeWithTag(noteTag).assertIsEnabled().performClick()

        assertEquals(listOf("gtd NOTE"), calls)
        composeRule.onNodeWithTag(noteTag).assertDoesNotExist()
    }

    @Test
    fun `create note has an English label`() {
        show(MailReadUiState(message = message), linksPanel = {})

        openMenu()
        composeRule.onNodeWithText("Create note").assertIsEnabled()
    }

    @Test
    fun `create note is not in the toolbar but the first item of the menu`() {
        show(MailReadUiState(message = message), linksPanel = {})
        composeRule.onNodeWithTag(noteTag).assertDoesNotExist()

        openMenu()

        fun top(tag: String) =
            composeRule
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue(top(noteTag) < top(MailReadTags.action(MailListAction.UNREAD)))
    }

    @Test
    fun `create note waits while an action runs`() {
        show(MailReadUiState(message = message, busy = true), linksPanel = {})

        composeRule.onNodeWithTag(MailReadTags.MORE).assertIsNotEnabled().performClick()

        composeRule.onNodeWithTag(noteTag).assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `without a mail create note cannot be reached`() {
        show(MailReadUiState(loading = true), linksPanel = {})
        composeRule.onNodeWithTag(MailReadTags.MORE).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(noteTag).assertDoesNotExist()
    }

    @Test
    fun `after a failed load create note cannot be reached`() {
        show(MailReadUiState(error = MailReadError.OFFLINE), linksPanel = {})
        composeRule.onNodeWithTag(MailReadTags.MORE).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(noteTag).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h470dp")
    fun `on a 320dp wide window the menu button is shown at full size`() {
        show(MailReadUiState(message = message), linksPanel = {})

        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val toolbar =
            listOf(
                MailReadTags.BACK,
                MailReadTags.gtd(MailGtdAction.TASK),
                MailReadTags.gtd(MailGtdAction.WAITING),
                MailReadTags.action(MailListAction.ARCHIVE),
                MailReadTags.action(MailListAction.DELETE),
                MailReadTags.MORE,
            )
        toolbar.forEach { tag ->
            val button = composeRule.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            assertTrue(tag, button.getUnclippedBoundsInRoot().right <= root.right)
        }
    }

    @Test
    fun `a created note is announced with its title`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.Created(MailGtdAction.NOTE, "Quarterly report")))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("Note created: Quarterly report")
    }

    @Test
    fun `a failed note is reported`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.GtdFailed(MailGtdAction.NOTE)))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The note could not be created.")
    }

    @Test
    fun `the linked items panel is shown below a mail`() {
        show(MailReadUiState(message = message))

        composeRule.onNodeWithTag(MailReadTags.LINKS).assertExists()
        composeRule.onNodeWithTag(fakeLinksTag).assertTextEquals("Linked: Budget plan")

        fun top(tag: String) =
            composeRule
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue(top(MailReadTags.BODY) < top(MailReadTags.LINKS))
    }

    @Test
    fun `the linked items panel is shown below a text-only mail and while an action runs`() {
        show(MailReadUiState(message = message.copy(html = " ", text = "Plain text only"), busy = true))

        composeRule.onNodeWithTag(MailReadTags.TEXT).assertTextEquals("Plain text only")
        composeRule.onNodeWithTag(fakeLinksTag).assertExists()
    }

    @Test
    fun `without a mail there is no linked items panel`() {
        show(MailReadUiState(loading = true))

        composeRule.onNodeWithTag(MailReadTags.LINKS).assertDoesNotExist()
        composeRule.onNodeWithTag(fakeLinksTag).assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a failed load shows no linked items panel`() {
        var error by mutableStateOf(MailReadError.OFFLINE)
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(error = error),
                    MailReadCallbacks(),
                    zone = ZoneId.of("Europe/Zurich"),
                    linksPanel = fakeLinks,
                )
            }
        }
        listOf(MailReadError.OFFLINE, MailReadError.FAILED, MailReadError.GONE).forEach {
            error = it
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(MailReadTags.ERROR).assertExists()
            composeRule.onNodeWithTag(MailReadTags.LINKS).assertDoesNotExist()
            composeRule.onNodeWithTag(fakeLinksTag).assertDoesNotExist()
            assertEquals("$it", emptyList<String>(), calls)
        }
    }

    @Test
    fun `the route creates a note from the mail and announces it, the mail stays open`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        coEvery { reader.gtd(id(1), MailGtdAction.NOTE) } returns MailResult.Ok("Quarterly report")
        val vm = readViewModel(reader, mockk<MailRepository>())

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
        openMenu()
        composeRule.onNodeWithTag(noteTag).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("Note created: Quarterly report")
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Quarterly report")
        composeRule.onNodeWithTag(MailReadTags.MORE).assertIsEnabled()
        coVerify(exactly = 1) { reader.gtd(id(1), MailGtdAction.NOTE) }
        coVerify(exactly = 0) { reader.gtd(id(1), MailGtdAction.TASK) }
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the route reports a note the server could not create`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        coEvery { reader.gtd(id(1), MailGtdAction.NOTE) } returns MailResult.Offline
        val vm = readViewModel(reader, mockk<MailRepository>())

        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(id = id(1), onClose = { calls += "close" }, viewModel = vm, attachmentsViewModel = attachmentsViewModel())
            }
        }
        composeRule.waitForIdle()
        openMenu()
        composeRule.onNodeWithTag(noteTag).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The note could not be created.")
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Quarterly report")
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the route shows the links area for a loaded mail`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        val vm = readViewModel(reader, mockk<MailRepository>())

        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(id = id(1), onClose = {}, viewModel = vm, attachmentsViewModel = attachmentsViewModel())
            }
        }
        composeRule.waitForIdle()

        // Without Hilt the panel's view model is absent: the area is there, the section inside is not composed.
        composeRule.onNodeWithTag(MailReadTags.LINKS).assertExists()
    }
}
