package ch.sonensei.cockpit.mail.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.FolderCrumb
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Recipient chips, suggestions and the Files picker on the compose screen, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class MailComposeRecipientsFilesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val reports = DriveEntry.Folder(id(40), "Reports", null)
    private val plan = DriveEntry.File(id(41), "plan.pdf", "application/pdf", 2048, Instant.parse("2026-11-01T08:00:00Z"))

    private val editing =
        MailComposeUiState(
            mode = ComposeMode.New(),
            phase = ComposePhase.EDITING,
            accounts = listOf(work),
            accountId = work.id,
        )

    private val calls = mutableListOf<String>()

    private val callbacks =
        MailComposeCallbacks(
            to = { calls += "to:$it" },
            attach = { calls += "attach" },
            attachFromFiles = { calls += "files" },
        )

    private val pickerCallbacks =
        FilePickerCallbacks(
            open = { calls += "open:${it.name}" },
            up = { calls += "up" },
            pick = { calls += "pick:${it.name}" },
            close = { calls += "close" },
        )

    private fun show(
        state: MailComposeUiState,
        suggestions: RecipientSuggestions = RecipientSuggestions(),
        picker: FilePickerState? = null,
    ) = composeRule.setContent {
        CockpitTheme {
            MailComposeContent(
                state,
                callbacks,
                suggestions = suggestions,
                filePicker = picker,
                pickerCallbacks = pickerCallbacks,
            )
        }
    }

    @Test
    fun `finished recipients show as chips, an invalid one too, typing goes after them`() {
        show(editing.copy(to = "Ann Example <ann@example.org>, nobody, "))

        composeRule.onNodeWithTag(MailComposeTags.recipient(RecipientField.TO, 0)).assertIsDisplayed()
        composeRule.onNodeWithText("Ann Example").assertIsDisplayed()
        composeRule.onNodeWithText("nobody").assertIsDisplayed()
        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.TO)).performTextInput("ben")

        assertEquals("to:Ann Example <ann@example.org>, nobody, ben", calls.last())
    }

    @Test
    fun `the close icon of a chip removes its recipient`() {
        show(editing.copy(to = "ann@example.org, ben@example.org, "))

        composeRule.onNodeWithContentDescription("Remove ann@example.org").performClick()

        assertEquals(listOf("to:ben@example.org, "), calls)
    }

    @Test
    fun `suggestions of the field show contacts and correspondents, choosing one finishes it`() {
        show(
            editing.copy(to = "an"),
            RecipientSuggestions(
                RecipientField.TO,
                listOf(RecipientSuggestion("ann@example.org", "Ann Example", id(30)), RecipientSuggestion("anna@example.net", null)),
            ),
        )

        composeRule.onNodeWithTag(MailComposeTags.suggestions(RecipientField.TO)).assertIsDisplayed()
        composeRule.onNodeWithText("anna@example.net").assertIsDisplayed()
        composeRule.onNodeWithTag(MailComposeTags.suggestion("ann@example.org")).performClick()

        assertEquals(listOf("to:Ann Example <ann@example.org>, "), calls)
    }

    @Test
    fun `attach offers this device and cockpit Files`() {
        show(editing)

        composeRule.onNodeWithTag(MailComposeTags.ATTACH).performClick()
        composeRule.onNodeWithText("From this device").assertIsDisplayed()
        composeRule.onNodeWithTag(MailComposeTags.ATTACH_FILES).performClick()

        assertEquals(listOf("files"), calls)
    }

    @Test
    fun `the Files picker lists the folder, opens folders, picks files and goes up`() {
        show(
            editing,
            picker = FilePickerState(path = listOf(FolderCrumb(id(39), "Projects")), entries = listOf(reports, plan), loading = false),
        )

        composeRule.onNodeWithText("Attach from Files").assertIsDisplayed()
        composeRule.onNodeWithText("Projects").assertIsDisplayed()
        composeRule.onNodeWithTag(MailComposeTags.filesEntry(reports.id)).performClick()
        composeRule.onNodeWithTag(MailComposeTags.filesEntry(plan.id)).performClick()
        composeRule.onNodeWithTag(MailComposeTags.FILES_UP).performClick()
        composeRule.onNodeWithTag(MailComposeTags.FILES_CANCEL).performClick()

        assertEquals(listOf("open:Reports", "pick:plan.pdf", "up", "close"), calls)
    }

    @Test
    fun `an empty folder and a failed one say so`() {
        var picker by mutableStateOf(FilePickerState(loading = false))
        composeRule.setContent {
            CockpitTheme { MailComposeContent(editing, callbacks, filePicker = picker, pickerCallbacks = pickerCallbacks) }
        }
        composeRule.onNodeWithTag(MailComposeTags.FILES_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("Files").assertIsDisplayed()

        picker = FilePickerState(loading = false, problem = FilePickerProblem.FAILED)
        composeRule.onNodeWithText("The folder could not be loaded.").assertIsDisplayed()
    }
}
