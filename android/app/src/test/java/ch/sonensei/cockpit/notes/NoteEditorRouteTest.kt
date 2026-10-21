package ch.sonensei.cockpit.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.UUID

/** The editor route wired to a real view model (fake repository): open, leave, conflicts, and photos from the pickers. */
@RunWith(AndroidJUnit4::class)
class NoteEditorRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val loaded = Note(id, null, "Shopping", "Shopping", false, emptyList(), Instant.EPOCH, 3)
    private val repo = mockk<NotesRepository>()
    private val images = NoteImages(ApplicationProvider.getApplicationContext<Context>(), OkHttpClient())
    private lateinit var activity: Activity
    private var closed = 0

    @Before
    fun setUp() {
        clearFileProviderCache()
        coEvery { repo.get(id) } returns NotesResult.Ok(loaded)
    }

    private fun show(noteId: UUID? = id): NoteEditorViewModel {
        val vm = NoteEditorViewModel(repo, images)
        composeRule.setContent {
            activity = LocalActivity.current!!
            CockpitTheme { NoteEditorRoute(id = noteId, key = "test", onClose = { closed++ }, viewModel = vm) }
        }
        composeRule.waitForIdle()
        return vm
    }

    @Test
    fun `opens the note and shows its text`() {
        show()
        composeRule.onNodeWithTag(NoteEditorTags.VIEW).assertExists()
        composeRule.onNodeWithText("Shopping").assertExists()
        coVerify(exactly = 1) { repo.get(id) }
    }

    @Test
    fun `leaving saves what was typed and closes`() {
        coEvery { repo.save(loaded, NoteDraft("", "Milk")) } returns SaveResult.Saved(loaded.copy(bodyMd = "Milk", version = 4))
        show()
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement("Milk")

        composeRule.onNodeWithContentDescription("Back to notes").performClick()
        composeRule.waitForIdle()

        assertEquals(1, closed)
        composeRule.waitUntil(WAIT_MS) { runCatching { coVerify { repo.save(loaded, NoteDraft("", "Milk")) } }.isSuccess }
    }

    @Test
    fun `a note deleted elsewhere closes the editor when the user discards the text`() {
        coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(null)
        show()
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement("Mine")
        // Done leaves the editor and saves at once: the save is refused.
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag(NoteEditorTags.CONFLICT).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("This note was deleted on another device while you edited it.").assertExists()
        assertEquals(0, closed)

        composeRule.onNodeWithTag(NoteEditorTags.USE_THEIRS).performClick()
        composeRule.waitUntil(WAIT_MS) { closed == 1 }
    }

    @Test
    fun `a photo taken with the camera is uploaded and inserted`() {
        var uploaded: NotePhoto? = null
        coEvery { repo.uploadImage(id, any()) } answers {
            uploaded = secondArg()
            NotesResult.Ok("![](cockpit-file:${UUID(0, 9)})")
        }
        coEvery { repo.save(loaded, any()) } answers { SaveResult.Saved(loaded.copy(bodyMd = secondArg<NoteDraft>().bodyMd, version = 4)) }
        val vm = show()

        composeRule.onNodeWithTag(NoteEditorTags.CAMERA).performClick()
        composeRule.waitForIdle()

        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("the camera was not started", request)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.intent.action)
        val output = IntentCompat.getParcelableExtra(request.intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
        assertEquals("${activity.packageName}.notes.photos", output.authority)
        // The camera writes the photo into the shared file.
        val file = File(activity.cacheDir, "notes-photos").listFiles()!!.single()
        file.writeBytes(byteArrayOf(7, 8, 9))

        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, null)

        composeRule.waitUntil(WAIT_MS) { "cockpit-file:" in vm.uiState.value.body }
        assertArrayEquals(byteArrayOf(7, 8, 9), uploaded!!.bytes)
        assertEquals("image/jpeg", uploaded!!.mime)
    }

    @Test
    fun `a photo picked from the gallery is uploaded and inserted`() {
        val picked = Uri.parse("content://ch.sonensei.test.gallery/images/5")
        var uploaded: NotePhoto? = null
        coEvery { repo.uploadImage(id, any()) } answers {
            uploaded = secondArg()
            NotesResult.Ok("![](cockpit-file:${UUID(0, 5)})")
        }
        coEvery { repo.save(loaded, any()) } answers { SaveResult.Saved(loaded.copy(bodyMd = secondArg<NoteDraft>().bodyMd, version = 4)) }
        val vm = show()
        shadowOf(activity.contentResolver).registerInputStream(picked, ByteArrayInputStream(byteArrayOf(4, 5)))

        composeRule.onNodeWithTag(NoteEditorTags.GALLERY).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("the photo picker was not started", request)

        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(picked))

        composeRule.waitUntil(WAIT_MS) { "cockpit-file:" in vm.uiState.value.body }
        assertArrayEquals(byteArrayOf(4, 5), uploaded!!.bytes)
    }

    @Test
    fun `a cancelled camera uploads nothing`() {
        show()
        composeRule.onNodeWithTag(NoteEditorTags.CAMERA).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity).nextStartedActivityForResult

        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        composeRule.waitForIdle()

        coVerify(exactly = 0) { repo.uploadImage(any(), any()) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
