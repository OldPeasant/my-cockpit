package ch.sonensei.cockpit.capture.share

import android.app.Activity
import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sheet alone, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class CaptureSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var saves = 0
    private var cancels = 0
    private lateinit var activity: Activity
    private lateinit var back: OnBackPressedDispatcher

    private fun show(
        initial: ShareUiState,
        autoFocus: Boolean? = null,
    ): (ShareUiState) -> Unit {
        var state by mutableStateOf(initial)
        composeRule.setContent {
            activity = LocalActivity.current!!
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            if (autoFocus == null) {
                // The share target's call: the default.
                CaptureSheet(
                    state = state,
                    onTextChange = { state = state.copy(text = it) },
                    onNotesChange = { state = state.copy(notes = it) },
                    onSave = { saves++ },
                    onCancel = { cancels++ },
                )
            } else {
                CaptureSheet(
                    state = state,
                    onTextChange = { state = state.copy(text = it) },
                    onNotesChange = { state = state.copy(notes = it) },
                    onSave = { saves++ },
                    onCancel = { cancels++ },
                    autoFocus = autoFocus,
                )
            }
        }
        return { state = it }
    }

    /** System Back, as the activity receives it. */
    private fun pressBack() = composeRule.runOnUiThread { back.onBackPressed() }

    /** The top-left corner: the scrim above the bottom-aligned sheet. */
    private fun tapOutside() = composeRule.onRoot().performTouchInput { click(Offset(10f, 10f)) }

    @Test
    fun `the sheet shows the text, notes and file names and edits them`() {
        val file = SharedFile(Uri.parse("content://com.example.gallery/1"), "beach.jpg")
        show(ShareUiState(text = "Holiday", notes = "https://example.org", files = listOf(file)))

        composeRule.onNodeWithTag(ShareTags.TEXT).assertTextEquals("Holiday")
        composeRule.onNodeWithTag(ShareTags.NOTES).assertTextEquals("https://example.org")
        composeRule.onNodeWithText("beach.jpg").assertExists()
        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Holiday @home")
        composeRule.onNodeWithTag(ShareTags.NOTES).performTextReplacement("bring towels")

        composeRule.onNodeWithTag(ShareTags.TEXT).assertTextEquals("Holiday @home")
        composeRule.onNodeWithTag(ShareTags.NOTES).assertTextEquals("bring towels")
    }

    @Test
    fun `save and the keyboard's done action both save`() {
        show(ShareUiState(text = "Buy milk"))

        composeRule.onNodeWithTag(ShareTags.SAVE).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(ShareTags.TEXT).performImeAction()

        assertEquals(2, saves)
        assertEquals(0, cancels)
    }

    @Test
    fun `a tap outside the sheet cancels, a tap on it does not`() {
        show(ShareUiState(text = "Maybe later"))

        composeRule.onNodeWithTag(ShareTags.SHEET).performTouchInput { click(Offset(width / 2f, 4f)) }
        assertEquals(0, cancels)

        tapOutside()
        assertEquals(1, cancels)
        assertEquals(0, saves)
    }

    @Test
    fun `a shared item opens for review without focusing the title by default`() {
        show(ShareUiState(text = "Shared link"))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShareTags.TEXT).assertIsNotFocused()
        composeRule.onNodeWithTag(ShareTags.NOTES).assertIsNotFocused()
    }

    @Test
    fun `autoFocus off keeps the title unfocused`() {
        show(ShareUiState(), autoFocus = false)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShareTags.TEXT).assertIsNotFocused()
    }

    @Test
    fun `autoFocus on focuses the title so typing starts at once`() {
        show(ShareUiState(), autoFocus = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShareTags.TEXT).assertIsFocused()
        composeRule.onNodeWithTag(ShareTags.NOTES).assertIsNotFocused()
    }

    @Test
    fun `cancel cancels`() {
        show(ShareUiState(text = "Maybe later"))

        composeRule.onNodeWithTag(ShareTags.CANCEL).performClick()

        assertEquals(1, cancels)
    }

    @Test
    fun `while saving nothing can be edited or cancelled`() {
        show(ShareUiState(text = "Saving", saving = true))

        composeRule.onNodeWithTag(ShareTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(ShareTags.CANCEL).assertIsNotEnabled()
        composeRule.onNodeWithTag(ShareTags.TEXT).assertIsNotEnabled()
        composeRule.onNodeWithTag(ShareTags.NOTES).assertIsNotEnabled()
        tapOutside()

        assertEquals(0, cancels)
    }

    @Test
    fun `system back while saving is consumed and does not close the sheet`() {
        show(ShareUiState(text = "Saving", saving = true))

        pressBack()
        composeRule.waitForIdle()

        assertFalse(activity.isFinishing)
        assertEquals(0, cancels)
    }

    @Test
    fun `system back closes the sheet again once saving failed`() {
        val update = show(ShareUiState(text = "Retry", saving = true))
        pressBack()
        composeRule.waitForIdle()
        assertFalse(activity.isFinishing)

        update(ShareUiState(text = "Retry", error = ShareError.FAILED))
        composeRule.waitForIdle()
        pressBack()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a tap outside cancels again once saving failed`() {
        val update = show(ShareUiState(text = "Retry", saving = true))
        tapOutside()
        assertEquals(0, cancels)

        update(ShareUiState(text = "Retry", error = ShareError.FAILED))
        composeRule.waitForIdle()
        tapOutside()

        assertEquals(1, cancels)
    }

    @Test
    fun `save waits for the file names`() {
        show(ShareUiState(text = "Photo", loadingFiles = true))

        composeRule.onNodeWithTag(ShareTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(ShareTags.CANCEL).assertIsEnabled()
    }

    @Test
    fun `a blank title cannot be saved`() {
        show(ShareUiState(text = "  "))

        composeRule.onNodeWithTag(ShareTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun `every error has its message`() {
        val messages =
            mapOf(
                ShareError.EMPTY_TEXT to "Type a title to save this to the Inbox.",
                ShareError.TOO_MANY_FILES to "Too many files. Share at most 50 at once.",
                ShareError.TEXT_TOO_LONG to "The text is too long. Shorten it to at most 100,000 characters.",
                ShareError.NOTES_TOO_LONG to "The notes are too long. Shorten them to at most 100,000 characters.",
                ShareError.UNREADABLE_FILE to "A shared file could not be read. Share it again from the other app.",
                ShareError.FAILED to "The capture could not be stored. Try again.",
            )
        // The messages name the limits the code enforces.
        assertEquals(50, CaptureQueue.MAX_ATTACHMENTS)
        assertEquals(100_000, CaptureQueue.MAX_TEXT_LENGTH)
        assertEquals(100_000, ShareViewModel.MAX_NOTES_LENGTH)
        assertEquals(ShareError.entries.toSet(), messages.keys)
        val update = show(ShareUiState(text = "x"))
        composeRule.onAllNodesWithTag(ShareTags.ERROR).assertCountEquals(0)

        for ((error, message) in messages) {
            update(ShareUiState(text = "x", error = error))
            composeRule.onNodeWithTag(ShareTags.ERROR).assertTextEquals(message)
        }
    }
}
