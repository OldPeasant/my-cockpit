package ch.sonensei.cockpit.capture.share

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate

/** The sheet's syntax chips, "save and add another", the attach button and removable attachments. */
@RunWith(AndroidJUnit4::class)
class CaptureSheetCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 3, 1)
    private val garden = SyntaxProject("00000000-0000-7000-8000-000000000002", "Garden")
    private val home = SyntaxContext("00000000-0000-7000-8000-000000000011", "@home")
    private val phone = SyntaxContext("00000000-0000-7000-8000-000000000012", "Phone")
    private val vocabulary = CaptureVocabulary(listOf(garden), listOf(home, phone))
    private val beach = SharedFile(Uri.parse("content://com.example.gallery/1"), "beach.jpg")
    private val dunes = SharedFile(Uri.parse("content://com.example.gallery/2"), "dunes.png")

    private var saves = 0
    private var savesAnother = 0
    private val added = mutableListOf<List<Uri>>()
    private val removed = mutableListOf<Uri>()
    private val launched = mutableListOf<Any?>()

    /** A system picker stand-in: records what was asked for and answers [answer]. */
    private fun picker(answer: List<Uri>) =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                launched += input
                dispatchResult(requestCode, answer)
            }
        }

    /** The sheet as the activities wire it; the text field re-parses like the view model does. */
    private fun show(
        initial: ShareUiState,
        withAddAnother: Boolean = true,
        withAttach: Boolean = true,
        withRemove: Boolean = true,
        registry: ActivityResultRegistry = picker(emptyList()),
    ): (ShareUiState) -> Unit {
        var state by mutableStateOf(initial)
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = registry
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                CaptureSheet(
                    state = state,
                    onTextChange = {
                        state = state.copy(text = it, preview = if (it.isBlank()) null else CaptureSyntax.parse(it, today, vocabulary))
                    },
                    onNotesChange = { state = state.copy(notes = it) },
                    onSave = { saves++ },
                    onCancel = {},
                    onSaveAndAddAnother = if (withAddAnother) ({ savesAnother++ }) else null,
                    onAddFiles = if (withAttach) ({ added += it }) else null,
                    onRemoveFile = if (withRemove) ({ removed += it }) else null,
                )
            }
        }
        return { state = it }
    }

    private fun typed(text: String) = ShareUiState(text = text, preview = CaptureSyntax.parse(text, today, vocabulary))

    // Syntax chips

    @Test
    fun `the recognized project, contexts, due date and waiting show as chips`() {
        show(typed("Plant tulips #garden @home @phone !2026-04-01 ^Anna"))

        composeRule.onNodeWithTag(ShareTags.PREVIEW).assertExists()
        composeRule.onNodeWithTag(ShareTags.PREVIEW_PROJECT).assert(hasAnyDescendant(hasText("Garden")))
        composeRule.onAllNodesWithTag(ShareTags.PREVIEW_CONTEXT).assertCountEquals(2)
        composeRule.onAllNodesWithTag(ShareTags.PREVIEW_CONTEXT)[0].assert(hasAnyDescendant(hasText("home")))
        composeRule.onAllNodesWithTag(ShareTags.PREVIEW_CONTEXT)[1].assert(hasAnyDescendant(hasText("Phone")))
        composeRule.onNodeWithTag(ShareTags.PREVIEW_DUE).assert(hasAnyDescendant(hasText("Due ", substring = true)))
        composeRule.onNodeWithTag(ShareTags.PREVIEW_DUE).assert(hasAnyDescendant(hasText("Apr", substring = true)))
        composeRule.onNodeWithTag(ShareTags.PREVIEW_WAITING).assert(hasAnyDescendant(hasText("Waiting for Anna")))
    }

    @Test
    fun `waiting without a name shows a plain waiting chip`() {
        show(typed("Quote for the roof ^waiting"))

        composeRule.onNodeWithTag(ShareTags.PREVIEW_WAITING).assert(hasAnyDescendant(hasText("Waiting")))
        composeRule.onAllNodesWithTag(ShareTags.PREVIEW_PROJECT).assertCountEquals(0)
        composeRule.onAllNodesWithTag(ShareTags.PREVIEW_DUE).assertCountEquals(0)
    }

    @Test
    fun `the preview is announced under its label`() {
        show(typed("Plant #garden"))

        composeRule.onNodeWithContentDescription("Recognized").assertExists()
    }

    @Test
    fun `the chips follow the typing`() {
        show(ShareUiState())
        composeRule.onAllNodesWithTag(ShareTags.PREVIEW).assertCountEquals(0)

        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Weed #garden")
        composeRule.onNodeWithTag(ShareTags.PREVIEW_PROJECT).assertExists()

        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Weed #gard")
        composeRule.onAllNodesWithTag(ShareTags.PREVIEW).assertCountEquals(0)
    }

    @Test
    fun `text without tokens or unknown names shows no chips`() {
        show(typed("Read #book @office"))

        composeRule.onAllNodesWithTag(ShareTags.PREVIEW).assertCountEquals(0)
    }

    @Test
    fun `a read-later bookmark shows no chips`() {
        show(typed("Tulips #garden").copy(url = "https://example.org/tulips", destination = ShareDestination.READ_LATER))

        composeRule.onAllNodesWithTag(ShareTags.PREVIEW).assertCountEquals(0)
    }

    // Save and add another

    @Test
    fun `save and add another saves through its own action`() {
        show(ShareUiState(text = "First of many"))

        composeRule
            .onNodeWithTag(ShareTags.SAVE_ANOTHER)
            .assertTextEquals("Save and add another")
            .assertIsEnabled()
            .performClick()

        assertEquals(1, savesAnother)
        assertEquals(0, saves)
    }

    @Test
    fun `save and add another is disabled like save`() {
        val update = show(ShareUiState(text = "  "))
        composeRule.onNodeWithTag(ShareTags.SAVE_ANOTHER).assertIsNotEnabled()

        update(ShareUiState(text = "Saving", saving = true))
        composeRule.onNodeWithTag(ShareTags.SAVE_ANOTHER).assertIsNotEnabled()

        update(ShareUiState(text = "Photo", loadingFiles = true))
        composeRule.onNodeWithTag(ShareTags.SAVE_ANOTHER).assertIsNotEnabled()
    }

    @Test
    fun `without an add-another action there is no such button`() {
        show(ShareUiState(text = "One only"), withAddAnother = false)

        composeRule.onAllNodesWithTag(ShareTags.SAVE_ANOTHER).assertCountEquals(0)
    }

    @Test
    fun `after save and add another the cleared title takes the focus`() {
        val update = show(ShareUiState(text = "First"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ShareTags.TEXT).assertIsNotFocused()

        update(ShareUiState(savedAndCleared = 1))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShareTags.TEXT).assertIsFocused().assertTextEquals("")
    }

    // Attachments

    @Test
    fun `the attach button opens the system picker for any file and hands over the picked ones`() {
        val picked = listOf(Uri.parse("content://com.example.docs/7"), Uri.parse("content://com.example.photos/8"))
        show(ShareUiState(text = "Tax return"), registry = picker(picked))

        composeRule.onNodeWithContentDescription("Attach files or photos").assertIsEnabled()
        composeRule.onNodeWithTag(ShareTags.ATTACH).performClick()

        assertEquals(1, launched.size)
        assertTrue((launched.single() as Array<*>).contentEquals(arrayOf("*/*")))
        assertEquals(listOf(picked), added)
    }

    @Test
    fun `a cancelled picker attaches nothing`() {
        show(ShareUiState(text = "Tax return"), registry = picker(emptyList()))

        composeRule.onNodeWithTag(ShareTags.ATTACH).performClick()

        assertEquals(1, launched.size)
        assertTrue(added.isEmpty())
    }

    @Test
    fun `the attach button is disabled while saving`() {
        show(ShareUiState(text = "Saving", saving = true))

        composeRule.onNodeWithTag(ShareTags.ATTACH).assertIsNotEnabled()
    }

    @Test
    fun `there is no attach button without an attach action or for a read-later bookmark`() {
        val update = show(ShareUiState(text = "Plain"), withAttach = false)
        composeRule.onAllNodesWithTag(ShareTags.ATTACH).assertCountEquals(0)

        update(ShareUiState(url = "https://example.org/a", destination = ShareDestination.READ_LATER))
        composeRule.onAllNodesWithTag(ShareTags.ATTACH).assertCountEquals(0)
    }

    @Test
    fun `a shared page in the Inbox can get attachments`() {
        show(ShareUiState(text = "Article", url = "https://example.org/a"))

        composeRule.onNodeWithTag(ShareTags.ATTACH).assertIsEnabled()
    }

    @Test
    fun `with attachments the read-later choice is hidden`() {
        val update = show(ShareUiState(text = "Article", url = "https://example.org/a"))
        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).assertExists()

        update(ShareUiState(text = "Article", url = "https://example.org/a", files = listOf(beach)))

        composeRule.onAllNodesWithTag(ShareTags.DESTINATION_READ_LATER).assertCountEquals(0)
        composeRule.onNodeWithText("Inbox").assertExists()
    }

    @Test
    fun `each attachment can be removed by its close icon`() {
        show(ShareUiState(text = "Holiday", files = listOf(beach, dunes)))

        composeRule.onAllNodesWithTag(ShareTags.FILE).assertCountEquals(2)
        composeRule.onNodeWithContentDescription("Remove dunes.png").assertExists()
        composeRule.onAllNodesWithTag(ShareTags.REMOVE_FILE, useUnmergedTree = true)[1].performClick()

        assertEquals(listOf(dunes.uri), removed)
    }

    @Test
    fun `a tap on the attachment chip removes it too`() {
        show(ShareUiState(text = "Holiday", files = listOf(beach)))

        composeRule.onNodeWithTag(ShareTags.FILE).assertIsEnabled().performClick()

        assertEquals(listOf(beach.uri), removed)
    }

    @Test
    fun `attachments cannot be removed while saving`() {
        show(ShareUiState(text = "Holiday", files = listOf(beach), saving = true))

        composeRule.onNodeWithTag(ShareTags.FILE).assertIsNotEnabled().performClick()

        assertTrue(removed.isEmpty())
    }

    @Test
    fun `without a remove action attachments are shown without a close icon`() {
        show(ShareUiState(text = "Holiday", files = listOf(beach)), withRemove = false)

        composeRule.onNodeWithText("beach.jpg").assertExists()
        composeRule.onAllNodesWithTag(ShareTags.REMOVE_FILE, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag(ShareTags.FILE).assertIsNotEnabled()
    }

    // The confirmation toast of "save and add another"

    @Test
    fun `each capture saved with add another is confirmed with where it went`() {
        var state by mutableStateOf(ShareUiState())
        composeRule.setContent { SavedAndClearedToast(state) }
        composeRule.waitForIdle()
        assertEquals(0, ShadowToast.shownToastCount())

        state = ShareUiState(savedAndCleared = 1)
        composeRule.waitForIdle()
        assertEquals("Saved to Inbox", ShadowToast.getTextOfLatestToast())

        state = ShareUiState(savedAndCleared = 2, lastSavedReadLater = true)
        composeRule.waitForIdle()
        assertEquals("Saved to read later", ShadowToast.getTextOfLatestToast())
        assertEquals(2, ShadowToast.shownToastCount())
    }

    @Test
    fun `a sheet shown again with an earlier count does not repeat the confirmation`() {
        composeRule.setContent { SavedAndClearedToast(ShareUiState(savedAndCleared = 3)) }
        composeRule.waitForIdle()

        assertEquals(0, ShadowToast.shownToastCount())
    }
}
