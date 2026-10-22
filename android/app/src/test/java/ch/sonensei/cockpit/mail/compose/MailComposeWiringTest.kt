package ch.sonensei.cockpit.mail.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailListCallbacks
import ch.sonensei.cockpit.mail.list.MailListContent
import ch.sonensei.cockpit.mail.list.MailListTags
import ch.sonensei.cockpit.mail.list.MailListUiState
import ch.sonensei.cockpit.mail.read.MailMessage
import ch.sonensei.cockpit.mail.read.MailReadCallbacks
import ch.sonensei.cockpit.mail.read.MailReadContent
import ch.sonensei.cockpit.mail.read.MailReadTags
import ch.sonensei.cockpit.mail.read.MailReadUiState
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** How compose is reached: the list's compose button and the read view's reply, reply all and forward buttons. */
@RunWith(AndroidJUnit4::class)
class MailComposeWiringTest {
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
            html = "",
            text = "Hello",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private val modes = mutableListOf<ComposeMode>()
    private var composeClicks = 0

    private val replyTags = listOf(MailReadTags.REPLY, MailReadTags.REPLY_ALL, MailReadTags.FORWARD)

    private fun showRead(state: MailReadUiState) =
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    state,
                    MailReadCallbacks(compose = { modes += it }),
                    zone = ZoneId.of("Europe/Zurich"),
                    body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
                )
            }
        }

    @Test
    fun `the list's compose button asks for a new mail, also when the mailbox is empty`() {
        composeRule.setContent {
            CockpitTheme {
                MailListContent(MailListUiState(loaded = true), MailListCallbacks(compose = { composeClicks++ }))
            }
        }

        composeRule.onNodeWithTag(MailListTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.COMPOSE).assertIsDisplayed().performClick()

        assertEquals(1, composeClicks)
    }

    @Test
    fun `reply, reply all and forward ask for a draft of the open mail`() {
        showRead(MailReadUiState(message = message))

        replyTags.forEach { composeRule.onNodeWithTag(it).assertIsEnabled().performClick() }

        assertEquals(listOf(ComposeMode.Reply(id(1)), ComposeMode.ReplyAll(id(1)), ComposeMode.Forward(id(1))), modes)
    }

    @Test
    fun `the reply buttons wait while an action runs`() {
        showRead(MailReadUiState(message = message, busy = true))

        replyTags.forEach { composeRule.onNodeWithTag(it).assertIsNotEnabled().performClick() }

        assertEquals(emptyList<ComposeMode>(), modes)
    }

    @Test
    fun `without a mail there is nothing to reply to`() {
        showRead(MailReadUiState(loading = true))

        replyTags.forEach { composeRule.onNodeWithTag(it).assertDoesNotExist() }
    }
}
