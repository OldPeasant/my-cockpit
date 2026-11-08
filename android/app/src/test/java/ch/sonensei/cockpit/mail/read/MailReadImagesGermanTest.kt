package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The images banner and its notices in the German locale: the new strings exist there too. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de")
class MailReadImagesGermanTest {
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
            remoteImagesBlocked = 2,
            attachments = emptyList(),
            fromAddress = "anna@example.org",
        )

    private fun show(
        message: MailMessage = this.message,
        notice: MailReadNotice? = null,
    ) = composeRule.setContent {
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
    fun `the banner and its buttons are German`() {
        show()
        composeRule.onNodeWithTag(MailReadTags.IMAGES_BLOCKED).assertTextEquals("2 externe Bilder wurden blockiert.")
        composeRule.onNodeWithTag(MailReadTags.LOAD_IMAGES).assertTextEquals("Bilder laden")
        composeRule.onNodeWithTag(MailReadTags.ALWAYS_IMAGES).assertTextEquals("Immer für diesen Absender")
    }

    @Test
    fun `one blocked image is counted in the German singular`() {
        show(message.copy(remoteImagesBlocked = 1))
        composeRule.onNodeWithTag(MailReadTags.IMAGES_BLOCKED).assertTextEquals("1 externes Bild wurde blockiert.")
    }

    @Test
    fun `failed images are reported in German`() {
        show(notice = MailReadNotice.ImagesFailed)
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("Die Bilder konnten nicht geladen werden.")
    }

    @Test
    fun `an unsaved sender is reported in German`() {
        show(notice = MailReadNotice.SenderNotSaved)
        composeRule
            .onNodeWithTag(MailReadTags.NOTICE)
            .assertTextEquals("Der Absender konnte nicht gespeichert werden; die Bilder werden nur für diese Mail angezeigt.")
    }
}
