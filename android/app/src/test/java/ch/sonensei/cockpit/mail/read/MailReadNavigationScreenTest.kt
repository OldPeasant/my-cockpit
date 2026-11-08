package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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

/**
 * Previous/next in the read view: the row below the toolbar ("N of M", disabled at the ends and while an action runs,
 * absent without a list), and the route showing the next mail after archive, delete or spam, or closing at the end.
 */
@RunWith(AndroidJUnit4::class)
class MailReadNavigationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun message(n: Int) =
        MailMessage(
            id = id(n),
            subject = "Subject $n",
            from = "Anna Example <anna@example.org>",
            to = listOf("me@example.org"),
            cc = emptyList(),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "",
            text = "Body $n",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private val calls = mutableListOf<String>()

    private val ids = listOf(id(1), id(2), id(3))

    private fun navigation(
        n: Int,
        list: List<UUID> = ids,
    ) = MailReadNavigation.of(list, id(n)) { calls += "open $it" }

    private fun show(
        state: MailReadUiState,
        navigation: MailReadNavigation?,
    ) = composeRule.setContent {
        CockpitTheme {
            MailReadContent(
                state,
                MailReadCallbacks(back = { calls += "back" }),
                zone = ZoneId.of("Europe/Zurich"),
                navigation = navigation,
                body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
            )
        }
    }

    @Test
    fun `a mail in the middle shows its position and opens both neighbours`() {
        show(MailReadUiState(message = message(2)), navigation(2))

        composeRule.onNodeWithTag(MailReadTags.NAVIGATION).assertExists()
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("2 of 3")
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(MailReadTags.NEXT).assertIsEnabled().performClick()

        assertEquals(listOf("open ${id(1)}", "open ${id(3)}"), calls)
    }

    @Test
    fun `previous is disabled on the first mail`() {
        show(MailReadUiState(message = message(1)), navigation(1))

        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("1 of 3")
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(MailReadTags.NEXT).assertIsEnabled()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `next is disabled on the last mail`() {
        show(MailReadUiState(message = message(3)), navigation(3))

        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("3 of 3")
        composeRule.onNodeWithTag(MailReadTags.NEXT).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertIsEnabled()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a single mail shows one of one with both buttons disabled`() {
        show(MailReadUiState(message = message(1)), navigation(1, listOf(id(1))))

        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("1 of 1")
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailReadTags.NEXT).assertIsNotEnabled()
    }

    @Test
    fun `previous and next wait while an action runs, the position stays shown`() {
        show(MailReadUiState(message = message(2), busy = true), navigation(2))

        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("2 of 3")
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(MailReadTags.NEXT).assertIsNotEnabled().performClick()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `without a list there is no previous or next`() {
        show(MailReadUiState(message = message(1)), null)

        composeRule.onNodeWithTag(MailReadTags.NAVIGATION).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertDoesNotExist()
        composeRule.onNodeWithTag(MailReadTags.NEXT).assertDoesNotExist()
    }

    @Test
    fun `previous and next have TalkBack labels`() {
        show(MailReadUiState(message = message(2)), navigation(2))

        composeRule.onNodeWithContentDescription("Previous mail").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Next mail").assertIsEnabled()
    }

    @Test
    fun `the row sits below the toolbar and above the headers`() {
        show(MailReadUiState(message = message(2)), navigation(2))

        fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

        assertTrue(bounds(MailReadTags.BACK).bottom <= bounds(MailReadTags.NAVIGATION).top)
        assertTrue(bounds(MailReadTags.NAVIGATION).bottom <= bounds(MailReadTags.HEADERS).top)
    }

    // The route: a stateful list as the Mail screen keeps it (the removed mail leaves it, open shows another one).

    private fun showRoute(
        start: Int,
        list: List<UUID>,
        reader: MailReadRepository,
    ) {
        val vm = readViewModel(reader, mockk<MailRepository>())
        val mails = list.toMutableStateList()
        composeRule.setContent {
            var current by remember { mutableStateOf(id(start)) }
            CockpitTheme {
                MailReadRoute(
                    id = current,
                    onClose = { calls += "close" },
                    onRemoved = {
                        calls += "removed $it"
                        mails.remove(it)
                    },
                    navigation =
                        MailReadNavigation.of(mails, current) {
                            calls += "open $it"
                            current = it
                        },
                    viewModel = vm,
                    attachmentsViewModel = attachmentsViewModel(),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun readerOf(vararg mails: Int): MailReadRepository {
        val reader = mockk<MailReadRepository>()
        mails.forEach { n -> coEvery { reader.message(id(n)) } returns MailResult.Ok(message(n)) }
        return reader
    }

    @Test
    fun `the route's next loads the next mail without closing`() {
        val reader = readerOf(1, 2, 3)
        showRoute(1, ids, reader)
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Subject 1")

        composeRule.onNodeWithTag(MailReadTags.NEXT).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Subject 2")
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("2 of 3")
        assertEquals(listOf("open ${id(2)}"), calls)
        coVerify(exactly = 1) { reader.message(id(2)) }
    }

    @Test
    fun `a mail deleted in the middle reports it removed and shows the next one`() {
        val reader = readerOf(1, 2, 3)
        coEvery { reader.act(id(2), MailListAction.DELETE) } returns MailResult.Ok(MailActionOutcome(true))
        showRoute(2, ids, reader)

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.DELETE)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Subject 3")
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("2 of 2")
        assertEquals(listOf("removed ${id(2)}", "open ${id(3)}"), calls)
    }

    @Test
    fun `a mail moved to spam shows the next one`() {
        val reader = readerOf(1, 2, 3)
        coEvery { reader.act(id(1), MailListAction.SPAM) } returns MailResult.Ok(MailActionOutcome(true))
        showRoute(1, ids, reader)

        composeRule.onNodeWithTag(MailReadTags.MORE).performClick()
        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.SPAM)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Subject 2")
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("1 of 2")
        composeRule.onNodeWithTag(MailReadTags.PREVIOUS).assertIsNotEnabled()
        assertEquals(listOf("removed ${id(1)}", "open ${id(2)}"), calls)
    }

    @Test
    fun `the last mail archived closes the view instead of showing the previous one`() {
        val reader = readerOf(1, 2, 3)
        coEvery { reader.act(id(3), MailListAction.ARCHIVE) } returns MailResult.Ok(MailActionOutcome(true))
        showRoute(3, ids, reader)

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.ARCHIVE)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("removed ${id(3)}", "close"), calls)
        coVerify(exactly = 0) { reader.message(id(2)) }
    }

    @Test
    fun `the only mail of the list deleted closes the view`() {
        val reader = readerOf(1)
        coEvery { reader.act(id(1), MailListAction.DELETE) } returns MailResult.Ok(MailActionOutcome(true))
        showRoute(1, listOf(id(1)), reader)

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.DELETE)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("removed ${id(1)}", "close"), calls)
    }

    @Test
    fun `a failed delete keeps the mail and its place`() {
        val reader = readerOf(1, 2, 3)
        coEvery { reader.act(id(2), MailListAction.DELETE) } returns MailResult.Offline
        showRoute(2, ids, reader)

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.DELETE)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Subject 2")
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("2 of 3")
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertExists()
        assertEquals(emptyList<String>(), calls)
    }
}
