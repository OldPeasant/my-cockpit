package ch.sonensei.cockpit.mail.list

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** The folder actions and dialogs in the German locale: TalkBack labels, the delete warning and the errors. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de")
class MailFolderManageGermanTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Anna Beispiel", "ich@example.org")
    private val projects = MailFolder(id(92), account.id, "Projekte", "Projekte", null, 0, 3, "/")
    private val base = MailListUiState(accounts = listOf(MailAccountFolders(account, listOf(projects))), loaded = true)

    private fun show(state: MailListUiState) =
        composeRule.setContent {
            CockpitTheme { MailListContent(state, MailListCallbacks()) }
        }

    @Test
    fun `the new folder and folder action buttons have German TalkBack labels`() {
        show(base)
        composeRule.onNodeWithTag(MailListTags.MAILBOX).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Neuer Ordner in ich@example.org").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ordneraktionen: Projekte").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Neuer Unterordner").assertIsDisplayed()
        composeRule.onNodeWithText("Umbenennen").assertIsDisplayed()
        composeRule.onNodeWithText("Löschen").assertIsDisplayed()
    }

    @Test
    fun `the delete dialog warns in German that messages and subfolders go on the mail server too`() {
        show(base.copy(folderEdit = FolderEdit(FolderEditKind.DELETE, account.id, projects)))

        composeRule.onNodeWithText("Projekte löschen?").assertIsDisplayed()
        composeRule
            .onNodeWithText("seine Unterordner und alle ihre Nachrichten werden auch auf dem Mailserver gelöscht", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_CANCEL).assertTextEquals("Abbrechen")
    }

    @Test
    fun `a taken name is explained in German`() {
        show(base.copy(folderEdit = FolderEdit(FolderEditKind.RENAME, account.id, projects, error = FolderEditError.NAME_TAKEN)))

        composeRule.onNodeWithText("Projekte umbenennen").assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_ERROR).assertTextEquals("Hier gibt es schon einen Ordner mit diesem Namen.")
    }

    @Test
    fun `any other failure asks in German to try again`() {
        show(base.copy(folderEdit = FolderEdit(FolderEditKind.CREATE, account.id, null, error = FolderEditError.FAILED)))

        composeRule.onNodeWithText("Neuer Ordner").assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_ERROR).assertTextEquals("Das hat nicht geklappt. Versuche es noch einmal.")
    }

    @Test
    fun `a changed folder is reported in German`() {
        show(base.copy(message = MailListMessage.FOLDER_CHANGED))

        composeRule
            .onNodeWithTag(MailListTags.MESSAGE)
            .assertTextEquals("Der Ordner wurde anderswo geändert. Die Ordner wurden neu geladen.")
    }
}
