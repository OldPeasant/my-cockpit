package ch.sonensei.cockpit.mail.list

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.read.MailMessage
import ch.sonensei.cockpit.mail.read.MailReadCallbacks
import ch.sonensei.cockpit.mail.read.MailReadContent
import ch.sonensei.cockpit.mail.read.MailReadUiState
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The labels of the selection bar, the undo snackbar and the read view's menu in the German locale. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de")
class MailActionsGermanTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val account = MailAccount(id(80), "Anna Beispiel", "anna@example.org")
    private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val archive = MailFolder(id(91), account.id, "Archives", "Archives", FolderRole.ARCHIVE, 0)

    private fun mail(n: Int) =
        MailSummary(
            id(n),
            account.id,
            "Anna Beispiel",
            "Betreff $n",
            "",
            Instant.parse("2026-10-21T07:00:00Z"),
            false,
            false,
            false,
            inbox.id,
        )

    private fun showList(state: MailListUiState) =
        composeRule.setContent { CockpitTheme { MailListContent(state, MailListCallbacks(), clock = clock) } }

    private val selecting =
        MailListUiState(
            accounts = listOf(MailAccountFolders(account, listOf(inbox, archive))),
            messages = listOf(mail(1), mail(2), mail(3)),
            loaded = true,
            selected = setOf(id(1), id(2)),
        )

    @Test
    fun `the selection bar is labelled in German`() {
        showList(selecting)

        composeRule.onNodeWithTag(MailListTags.SELECTION_COUNT).assertTextEquals("2 ausgewählt")
        composeRule.onNodeWithContentDescription("Auswahl beenden").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Als gelesen markieren").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Markieren").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Weitere Aktionen").performClick()
        composeRule.onNodeWithTag(MailListTags.selectionAction(MailListAction.MOVE)).assertTextContains("In Ordner verschieben")
        composeRule.onNodeWithTag(MailListTags.selectionAction(MailListAction.SPAM)).assertTextContains("Als Spam verschieben")
    }

    @Test
    fun `the move picker names special folders in German`() {
        showList(selecting)

        composeRule.onNodeWithTag(MailListTags.SELECTION_MORE).performClick()
        composeRule.onNodeWithTag(MailListTags.selectionAction(MailListAction.MOVE)).performClick()

        composeRule.onNodeWithTag(MailMoveTags.folder(archive.id)).assertTextEquals("Archiv")
        composeRule.onNodeWithTag(MailMoveTags.CANCEL).assertTextEquals("Abbrechen")
    }

    @Test
    fun `the undo snackbar is in German`() {
        showList(selecting.copy(selected = emptySet(), undo = MailUndo(MailListAction.ARCHIVE, listOf(mail(4), mail(5)), Mailbox.Unified)))

        composeRule.onNodeWithText("2 E-Mails archiviert").assertIsDisplayed()
        composeRule.onNodeWithText("Rückgängig").assertIsDisplayed()
    }

    @Test
    fun `a failed undo is reported in German`() {
        showList(selecting.copy(selected = emptySet(), message = MailListMessage.UNDO_FAILED))
        composeRule.onNodeWithTag(MailListTags.MESSAGE).assertTextEquals("Die Aktion konnte nicht rückgängig gemacht werden.")
    }

    @Test
    fun `the read view's menu is in German`() {
        val message =
            MailMessage(
                id = id(1),
                subject = "Quartalsbericht",
                from = "Anna Beispiel <anna@example.org>",
                to = emptyList(),
                cc = emptyList(),
                date = Instant.parse("2026-10-21T06:00:00Z"),
                seen = true,
                html = "<p>Hallo</p>",
                text = "Hallo",
                remoteImagesBlocked = 0,
                attachments = emptyList(),
                flagged = true,
            )
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = message),
                    MailReadCallbacks(),
                    zone = ZoneId.of("Europe/Zurich"),
                    body = { _, modifier -> Box(modifier.testTag("body")) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Weitere Aktionen").performClick()
        composeRule.onNodeWithText("Als ungelesen markieren").assertIsDisplayed()
        composeRule.onNodeWithText("Markierung entfernen").assertIsDisplayed()
        composeRule.onNodeWithText("In Ordner verschieben").assertIsDisplayed()
    }
}
