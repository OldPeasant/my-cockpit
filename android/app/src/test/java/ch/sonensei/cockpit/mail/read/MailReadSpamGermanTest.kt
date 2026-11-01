package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailListAction
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The spam button and its error notices in the German locale: the new strings exist there too. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de")
class MailReadSpamGermanTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val message =
        MailMessage(
            id = UUID.fromString("00000000-0000-7000-8000-000000000001"),
            subject = "Quartalsbericht",
            from = "Anna Beispiel <anna@example.org>",
            to = listOf("ich@example.org"),
            cc = emptyList(),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "<p>Hallo</p>",
            text = "Hallo",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private fun show(notice: MailReadNotice? = null) =
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = message, notice = notice),
                    MailReadCallbacks(),
                    zone = ZoneId.of("Europe/Zurich"),
                    body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
                )
            }
        }

    @Test
    fun `the spam button has a German TalkBack label`() {
        show()
        composeRule.onNodeWithContentDescription("Als Spam verschieben").assertIsEnabled()
    }

    @Test
    fun `a missing Spam folder is explained in German`() {
        show(MailReadNotice.ActionFailed(MailListAction.SPAM, "NO_ROLE_FOLDER"))
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("Die E-Mail konnte nicht in den Spam verschoben werden. Dieses Konto hat keinen Ordner „Spam“.")
    }

    @Test
    fun `another server code is shown with the German prefix`() {
        show(MailReadNotice.ActionFailed(MailListAction.SPAM, "CONNECTION_FAILED"))
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("Die E-Mail konnte nicht in den Spam verschoben werden. Fehler des Servers: CONNECTION_FAILED")
    }
}
