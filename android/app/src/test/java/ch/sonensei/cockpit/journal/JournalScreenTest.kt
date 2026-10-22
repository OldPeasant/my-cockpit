package ch.sonensei.cockpit.journal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.notes.PhotoSource
import ch.sonensei.cockpit.notes.SaveStatus
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The journal screen alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class JournalScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val day = LocalDate.of(2026, 10, 22)
    private val entry =
        JournalEntry(
            UUID.fromString("00000000-0000-7000-8000-000000000001"),
            day,
            "Walked by the **lake**",
            null,
            emptyList(),
            Instant.EPOCH,
            1,
        )
    private val calls = mutableListOf<String>()

    private val callbacks =
        JournalCallbacks(
            setEditing = { calls += "editing $it" },
            setBody = { calls += "body $it" },
            addPhoto = { source, at -> calls += "photo $source $at" },
            saveNow = { calls += "save" },
            keepMine = { calls += "keep" },
            useTheirs = { calls += "theirs" },
            dismissMessage = { calls += "dismiss" },
            retryLoad = { calls += "retry" },
        )

    private fun show(state: JournalUiState) =
        composeRule.setContent { CockpitTheme { JournalContent(state, callbacks, imageLoader = null) } }

    private fun loaded(
        body: String = entry.bodyMd,
        editing: Boolean = false,
        status: SaveStatus = SaveStatus.SAVED,
    ) = JournalUiState(date = day, entry = entry, body = body, loading = false, unloaded = false, editing = editing, status = status)

    @Test
    fun `today's entry shows the date and the rendered text`() {
        show(loaded())

        composeRule.onNodeWithTag(JournalTags.DATE).assertTextContains("October 22", substring = true)
        composeRule.onNodeWithTag(JournalTags.VIEW).assertIsDisplayed()
        composeRule.onNodeWithText("Walked by the lake", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.STATUS).assertTextContains("Saved")
        composeRule.onNodeWithTag(JournalTags.TOGGLE).performClick()
        assertEquals(listOf("editing true"), calls)
    }

    @Test
    fun `typing in the editor hands every change to the autosave`() {
        var state by mutableStateOf(loaded(body = "", editing = true))
        composeRule.setContent {
            CockpitTheme {
                JournalContent(
                    state,
                    callbacks.copy(setBody = {
                        calls += "body $it"
                        state = state.copy(body = it, status = SaveStatus.UNSAVED)
                    }),
                    imageLoader = null,
                )
            }
        }

        composeRule.onNodeWithTag(JournalTags.BODY).performTextReplacement("Quiet day")

        assertEquals(listOf("body Quiet day"), calls)
        composeRule.onNodeWithTag(JournalTags.STATUS).assertTextContains("Unsaved changes")
    }

    @Test
    fun `photos come from the camera or the gallery, at the cursor`() {
        show(loaded(body = "Lake", editing = true))

        composeRule.onNodeWithContentDescription("Take photo").performClick()
        composeRule.onNodeWithContentDescription("Add photo from gallery").performClick()

        assertEquals(listOf("photo ${PhotoSource.CAMERA} 0", "photo ${PhotoSource.GALLERY} 0"), calls)
    }

    @Test
    fun `a failed save can be retried`() {
        show(loaded(status = SaveStatus.OFFLINE))

        composeRule.onNodeWithTag(JournalTags.STATUS).assertTextContains("Not saved: no connection")
        composeRule.onNodeWithTag(JournalTags.SAVE_RETRY).performClick()
        assertEquals(listOf("save"), calls)
    }

    @Test
    fun `a conflict asks which version to keep and hides the editing tools`() {
        show(loaded().copy(conflict = JournalConflict(entry.copy(bodyMd = "Theirs")), status = SaveStatus.UNSAVED))

        composeRule.onNodeWithTag(JournalTags.CONFLICT).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.CAMERA).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.TOGGLE).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.KEEP_MINE).performClick()
        composeRule.onNodeWithTag(JournalTags.USE_THEIRS).performClick()
        assertEquals(listOf("keep", "theirs"), calls)
    }

    @Test
    fun `a failed load offers a retry and shows no text`() {
        show(JournalUiState(date = day, loading = false, unloaded = true, message = JournalMessage.LOAD_OFFLINE))

        composeRule.onNodeWithText("Can't reach the server", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.VIEW).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.BODY).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.RETRY_LOAD).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `a photo goes in at the cursor, which follows text inserted from elsewhere`() {
        val image = "![lake.jpg](cockpit-file:fictitious)"
        var state by mutableStateOf(loaded(body = "Lake", editing = true))
        composeRule.setContent {
            CockpitTheme {
                JournalContent(
                    state,
                    callbacks.copy(setBody = { state = state.copy(body = it, status = SaveStatus.UNSAVED) }),
                    imageLoader = null,
                )
            }
        }

        composeRule.onNodeWithTag(JournalTags.BODY).performTextReplacement("Lake view")
        composeRule.onNodeWithTag(JournalTags.CAMERA).performClick()
        // The view model inserts the uploaded image at that cursor: the cursor stays behind the inserted text.
        state = state.copy(body = "Lake view\n$image\n")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(JournalTags.GALLERY).performClick()

        assertEquals(listOf("photo ${PhotoSource.CAMERA} 9", "photo ${PhotoSource.GALLERY} ${"Lake view\n$image\n".length}"), calls)
    }

    @Test
    fun `while a photo uploads its buttons are replaced by a progress indicator`() {
        show(loaded(editing = true).copy(uploading = true))

        composeRule.onNodeWithTag(JournalTags.UPLOADING).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Uploading photo…").assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.CAMERA).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.GALLERY).assertDoesNotExist()
    }

    @Test
    fun `a conflict with an entry deleted elsewhere offers to save the text again or discard it`() {
        show(loaded().copy(conflict = JournalConflict(null), status = SaveStatus.UNSAVED))

        composeRule.onNodeWithText("deleted on another device", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.KEEP_MINE).assertTextContains("Save my text again")
        composeRule.onNodeWithTag(JournalTags.USE_THEIRS).assertTextContains("Discard my text")
        // The status still says the text is not saved, but no retry competes with the decision.
        composeRule.onNodeWithTag(JournalTags.SAVE_RETRY).assertDoesNotExist()
    }

    @Test
    fun `a save the server refused says so and can be retried`() {
        show(loaded(status = SaveStatus.FAILED))

        composeRule.onNodeWithTag(JournalTags.STATUS).assertTextContains("the server refused", substring = true)
        composeRule.onNodeWithTag(JournalTags.SAVE_RETRY).performClick()
        assertEquals(listOf("save"), calls)
    }

    @Test
    fun `while today's entry loads nothing can be written`() {
        show(JournalUiState(date = day))

        composeRule.onNodeWithTag(JournalTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.STATUS).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.TOGGLE).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.CAMERA).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.BODY).assertDoesNotExist()
    }

    @Test
    fun `a photo problem can be dismissed`() {
        show(loaded().copy(message = JournalMessage.PHOTO_OFFLINE))

        composeRule.onNodeWithText("Photo not added: no connection.").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun `an empty entry invites to write`() {
        show(loaded(body = ""))

        composeRule.onNodeWithText("Nothing written today yet", substring = true).assertIsDisplayed()
    }
}
