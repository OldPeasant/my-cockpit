package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.files.FolderCrumb
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The attachment list, its notices and the folder picker in the German locale: the new strings exist there too. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de")
class MailAttachmentsGermanTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pdf = MailAttachment("2", "bericht.pdf", 1024, "application/pdf")

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
            attachments = listOf(pdf),
        )

    private fun show(state: MailAttachmentsUiState = MailAttachmentsUiState()) =
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = message),
                    MailReadCallbacks(),
                    attachments = state,
                    zone = ZoneId.of("Europe/Zurich"),
                    body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
                )
            }
        }

    private fun notice(
        notice: AttachmentNotice,
        text: String,
    ) {
        show(MailAttachmentsUiState(notice = notice))
        composeRule.onNodeWithTag(MailAttachmentTags.NOTICE).assertTextEquals(text)
    }

    @Test
    fun `the list, its preview button and its menu are German`() {
        show()

        composeRule.onNodeWithText("Anhänge").assertExists()
        composeRule.onNodeWithContentDescription("Vorschau").assertExists()
        composeRule.onNodeWithContentDescription("Mehr für bericht.pdf").performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveDevice("2")).assertTextEquals("Auf dem Gerät speichern")
        composeRule.onNodeWithTag(MailAttachmentTags.saveFiles("2")).assertTextEquals("In Dateien speichern")
    }

    @Test
    fun `a save to the device is confirmed in German`() =
        notice(AttachmentNotice.SavedToDevice("bericht.pdf"), "bericht.pdf wurde gespeichert.")

    @Test
    fun `a save to Files is confirmed in German`() =
        notice(AttachmentNotice.SavedToFiles("bericht.pdf", "/Projekte"), "bericht.pdf wurde in Dateien unter /Projekte gespeichert.")

    @Test
    fun `a failed move is reported in German`() =
        notice(
            AttachmentNotice.SavedToFiles("bericht.pdf", notMoved = "/Projekte"),
            "bericht.pdf wurde in Dateien unter /Attachments/Mail gespeichert; es konnte nicht nach /Projekte verschoben werden.",
        )

    @Test
    fun `an offline open is reported in German`() =
        notice(
            AttachmentNotice.Failed(AttachmentAction.OPEN, offline = true),
            "Der Anhang konnte nicht geöffnet werden. Der Server ist nicht erreichbar.",
        )

    @Test
    fun `a too large attachment is reported in German`() =
        notice(
            AttachmentNotice.Failed(AttachmentAction.FILES, tooLarge = true),
            "Der Anhang konnte nicht in Dateien gespeichert werden. Er ist größer, als Dateien erlaubt.",
        )

    @Test
    fun `a failed preview is reported in German`() =
        notice(AttachmentNotice.Failed(AttachmentAction.PREVIEW), "Die Vorschau konnte nicht geladen werden.")

    @Test
    fun `a failed save is reported in German`() =
        notice(AttachmentNotice.Failed(AttachmentAction.SAVE), "Der Anhang konnte nicht gespeichert werden.")

    @Test
    fun `no app is reported in German`() = notice(AttachmentNotice.NoApp, "Keine App auf diesem Gerät öffnet diesen Anhang.")

    @Test
    fun `the folder picker is German at the root`() {
        show(MailAttachmentsUiState(picker = AttachmentFolderPicker(pdf)))

        composeRule.onNodeWithText("bericht.pdf in Dateien speichern").assertExists()
        composeRule.onNodeWithTag(MailAttachmentTags.crumb(0)).assertTextEquals("Dateien")
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_SAVE).assertTextEquals("In /Attachments/Mail speichern")
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_EMPTY).assertTextEquals("Hier gibt es keine Ordner.")
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_CANCEL).assertTextEquals("Abbrechen")
    }

    @Test
    fun `the folder picker is German inside a folder and when listing fails`() {
        show(
            MailAttachmentsUiState(
                picker =
                    AttachmentFolderPicker(
                        pdf,
                        path = listOf(FolderCrumb(UUID.fromString("00000000-0000-7000-8000-000000000020"), "Projekte")),
                        failed = true,
                    ),
            ),
        )

        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_SAVE).assertTextEquals("Hier speichern")
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_FAILED).assertTextEquals("Die Ordner konnten nicht geladen werden.")
    }

    @Test
    fun `a notice is dismissed with a German label`() {
        show(MailAttachmentsUiState(notice = AttachmentNotice.NoApp))

        composeRule.onNodeWithContentDescription("Schließen").assertExists()
    }
}
