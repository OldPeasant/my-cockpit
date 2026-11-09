package ch.sonensei.cockpit.files

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/** The preview's linked-items toggle (SPEC.md §4.3), with a fake panel in the `linksPanel` slot. */
@RunWith(AndroidJUnit4::class)
class FilePreviewLinksTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private val notes = DriveEntry.File(id(3), "notes.txt", "text/plain", 12, at)
    private val archive = DriveEntry.File(id(4), "backup.zip", "application/zip", 9000, at)
    private val files = listOf(notes, archive)
    private val calls = mutableListOf<String>()
    private val shownPanels = mutableListOf<String>()

    private val callbacks =
        FilesCallbacks(
            previewStep = { calls += "step $it" },
            closePreview = { calls += "close" },
        )

    private fun preview(
        index: Int,
        content: PreviewContent,
    ) = FilesUiState(entries = files, loaded = true, preview = FilePreview(files, index, content))

    private val fakePanel: @Composable (DriveEntry.File) -> Unit = { file ->
        shownPanels += file.name
        BasicText("Links of ${file.name}", Modifier.testTag(FAKE_PANEL))
    }

    private fun show(
        state: FilesUiState,
        linksPanel: (@Composable (DriveEntry.File) -> Unit)?,
    ): MutableState<FilesUiState> {
        val live = mutableStateOf(state)
        composeRule.setContent {
            CockpitTheme { FilesContent(live.value, callbacks, pdfOpener = { null }, linksPanel = linksPanel) }
        }
        return live
    }

    @Test
    fun `without a links panel the preview has no links toggle`() {
        show(preview(0, PreviewContent.Text("Milk, eggs", truncated = false)), linksPanel = null)
        composeRule.onNodeWithTag(PreviewTags.PREVIEW).assertExists()
        composeRule.onNodeWithTag(PreviewTags.TEXT).assertExists()
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).assertDoesNotExist()
        composeRule.onNodeWithTag(PreviewTags.LINKS).assertDoesNotExist()
    }

    @Test
    fun `the toggle shows the file's linked items instead of its content, and hides them again`() {
        show(preview(0, PreviewContent.Text("Milk, eggs", truncated = false)), fakePanel)
        // Hidden at first: the content shows, and the panel is not composed at all.
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).assertContentDescriptionEquals("Show linked items")
        composeRule.onNodeWithTag(PreviewTags.TEXT).assertExists()
        composeRule.onNodeWithTag(FAKE_PANEL).assertDoesNotExist()
        assertEquals(emptyList<String>(), shownPanels)

        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(PreviewTags.LINKS).assertExists()
        composeRule.onNodeWithTag(FAKE_PANEL, useUnmergedTree = true).assertTextEquals("Links of notes.txt")
        composeRule.onNodeWithTag(PreviewTags.TEXT).assertDoesNotExist()
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).assertContentDescriptionEquals("Hide linked items")
        // The name and the neighbour bar stay.
        composeRule.onNodeWithTag(PreviewTags.NAME).assertTextEquals("notes.txt")
        composeRule.onNodeWithTag(PreviewTags.NEXT).assertExists()

        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(FAKE_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(PreviewTags.LINKS).assertDoesNotExist()
        composeRule.onNodeWithTag(PreviewTags.TEXT).assertExists()
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).assertContentDescriptionEquals("Show linked items")
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `stepping to the next file while the links are shown shows that file's links`() {
        val state = show(preview(0, PreviewContent.Text("Milk, eggs", truncated = false)), fakePanel)
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(FAKE_PANEL, useUnmergedTree = true).assertTextEquals("Links of notes.txt")

        state.value = preview(1, PreviewContent.Unsupported)
        composeRule.onNodeWithTag(PreviewTags.NAME).assertTextEquals("backup.zip")
        composeRule.onNodeWithTag(FAKE_PANEL, useUnmergedTree = true).assertTextEquals("Links of backup.zip")
        composeRule.onNodeWithTag(PreviewTags.UNSUPPORTED).assertDoesNotExist()
        assertEquals("backup.zip", shownPanels.last())
    }

    @Test
    fun `the links panel also replaces a failed preview, and hiding it brings the retry back`() {
        show(preview(1, PreviewContent.Problem(FilesMessage.OFFLINE)), fakePanel)
        composeRule.onNodeWithTag(PreviewTags.RETRY).assertExists()
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(PreviewTags.RETRY).assertDoesNotExist()
        composeRule.onNodeWithTag(FAKE_PANEL, useUnmergedTree = true).assertTextEquals("Links of backup.zip")
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(PreviewTags.RETRY).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German, the links toggle is German`() {
        show(preview(0, PreviewContent.Text("Milch", truncated = false)), fakePanel)
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).assertContentDescriptionEquals("Verknüpfte Elemente anzeigen")
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).assertContentDescriptionEquals("Verknüpfte Elemente ausblenden")
    }

    private companion object {
        const val FAKE_PANEL = "fake-links-panel"
    }
}
