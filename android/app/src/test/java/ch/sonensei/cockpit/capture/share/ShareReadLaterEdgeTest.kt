package ch.sonensei.cockpit.capture.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Edge cases of the Inbox / Read later choice: URL detection, routing while saving, the sheet's states. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareReadLaterEdgeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val queue = mockk<CaptureQueue>()
    private val id = UUID.fromString("00000000-0000-7000-9000-000000000002")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { queue.capture(any(), any(), any()) } returns id
        coEvery { queue.readLater(any(), any()) } returns id
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun send(
        text: String?,
        subject: String? = null,
    ) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }

    private fun urlOf(intent: Intent) = ShareIntentParser.parse(intent)!!.url

    private fun viewModel(content: SharedContent) = ShareViewModel(queue, context).apply { load(content) }

    private val article = SharedContent("An article", "Shared from https://example.org/article", emptyList(), "https://example.org/article")

    // --- URL detection ---

    @Test
    fun `a URL only in the subject is still offered for read later`() {
        assertEquals("https://example.org/s", urlOf(send("Great read", subject = "https://example.org/s")))
    }

    @Test
    fun `a share with only a URL subject and no text is offered for read later`() {
        val content = ShareIntentParser.parse(send(null, subject = "https://example.org/only-subject"))!!

        assertEquals("https://example.org/only-subject", content.url)
        assertEquals("https://example.org/only-subject", content.title)
    }

    @Test
    fun `the first URL of several is taken`() {
        assertEquals("https://example.org/one", urlOf(send("https://example.org/one and https://example.org/two")))
    }

    @Test
    fun `quotes, brackets and an unopened parenthesis after a URL are dropped`() {
        assertEquals("https://example.org/q", urlOf(send("\"https://example.org/q\"")))
        assertEquals("https://example.org/p", urlOf(send("(https://example.org/p)")))
        assertEquals("https://example.org/b", urlOf(send("[https://example.org/b]!")))
        assertEquals("https://example.org/x?y=1#z", urlOf(send("https://example.org/x?y=1#z")))
    }

    @Test
    fun `mixed-case schemes are detected`() {
        assertEquals("HTTPS://Example.org/A", urlOf(send("HTTPS://Example.org/A")))
    }

    @Test
    fun `a URL longer than the server accepts offers no read-later choice`() {
        assertNull(urlOf(send("https://example.org/" + "a".repeat(CaptureQueue.MAX_URL_LENGTH))))
    }

    @Test
    fun `a share with a file and a URL goes to the Inbox only`() {
        val intent =
            send("https://example.org/a").apply {
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://ch.sonensei.cockpit.test/photo.jpg"))
            }

        val content = ShareIntentParser.parse(intent)!!

        assertEquals(1, content.uris.size)
        assertNull(content.url)
    }

    // --- Routing ---

    @Test
    fun `read later never sends the notes`() {
        val vm = viewModel(article)
        vm.onDestinationChange(ShareDestination.READ_LATER)
        vm.onNotesChange("Private thoughts")

        vm.save()

        coVerify(exactly = 1) { queue.readLater("https://example.org/article", "An article") }
    }

    @Test
    fun `a whitespace-only title is left to the server`() {
        val vm = viewModel(article)
        vm.onDestinationChange(ShareDestination.READ_LATER)
        vm.onTextChange("   ")

        vm.save()

        coVerify(exactly = 1) { queue.readLater("https://example.org/article", null) }
    }

    @Test
    fun `back to the Inbox a blank title is required again`() {
        val vm = viewModel(SharedContent("", null, emptyList(), "https://example.org/a"))
        vm.onDestinationChange(ShareDestination.READ_LATER)
        assertTrue(vm.uiState.value.canSave)

        vm.onDestinationChange(ShareDestination.INBOX)
        vm.save()

        assertFalse(vm.uiState.value.canSave)
        assertEquals(ShareError.EMPTY_TEXT, vm.uiState.value.error)
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
        coVerify(exactly = 0) { queue.readLater(any(), any()) }
    }

    @Test
    fun `changing the destination clears a previous error`() {
        val vm = viewModel(SharedContent("", null, emptyList(), "https://example.org/a"))
        vm.save()
        assertEquals(ShareError.EMPTY_TEXT, vm.uiState.value.error)

        vm.onDestinationChange(ShareDestination.READ_LATER)

        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `while saving the destination is locked and a second save is ignored`() {
        val stored = CompletableDeferred<UUID>()
        coEvery { queue.readLater(any(), any()) } coAnswers { stored.await() }
        val vm = viewModel(article)
        vm.onDestinationChange(ShareDestination.READ_LATER)

        vm.save()
        assertTrue(vm.uiState.value.saving)
        vm.onDestinationChange(ShareDestination.INBOX)
        vm.save()

        assertEquals(ShareDestination.READ_LATER, vm.uiState.value.destination)
        stored.complete(id)
        assertTrue(vm.uiState.value.saved)
        assertTrue(vm.uiState.value.readLater)
        coVerify(exactly = 1) { queue.readLater(any(), any()) }
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
    }

    @Test
    fun `once saved the destination no longer changes`() {
        val vm = viewModel(article)
        vm.onDestinationChange(ShareDestination.READ_LATER)
        vm.save()
        assertTrue(vm.uiState.value.saved)

        vm.onDestinationChange(ShareDestination.INBOX)
        vm.save()

        assertEquals(ShareDestination.READ_LATER, vm.uiState.value.destination)
        coVerify(exactly = 1) { queue.readLater(any(), any()) }
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
    }

    @Test
    fun `after a failed read-later save a retry can succeed`() {
        var calls = 0
        coEvery { queue.readLater(any(), any()) } answers {
            if (calls++ == 0) throw IllegalStateException("disk full") else id
        }
        val vm = viewModel(article)
        vm.onDestinationChange(ShareDestination.READ_LATER)

        vm.save()
        assertEquals(ShareError.FAILED, vm.uiState.value.error)
        assertFalse(vm.uiState.value.saving)
        vm.save()

        assertTrue(vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
    }

    // --- Sheet ---

    @Test
    fun `read later with an empty title shows that the title is optional`() {
        composeRule.setContent {
            CaptureSheet(
                state = ShareUiState(text = "", url = "https://example.org/a", destination = ShareDestination.READ_LATER),
                onTextChange = {},
                onNotesChange = {},
                onSave = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithText("Title (optional)").assertExists()
        composeRule.onNodeWithTag(ShareTags.SAVE).assertExists()
    }

    @Test
    fun `a failed read-later save shows the error in the sheet`() {
        composeRule.setContent {
            CaptureSheet(
                state =
                    ShareUiState(
                        text = "An article",
                        url = "https://example.org/a",
                        destination = ShareDestination.READ_LATER,
                        error = ShareError.FAILED,
                    ),
                onTextChange = {},
                onNotesChange = {},
                onSave = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithTag(ShareTags.ERROR).assertExists()
        composeRule.onNodeWithTag(ShareTags.URL).assertExists()
    }

    @Test
    fun `the choice cannot be changed while saving`() {
        composeRule.setContent {
            CaptureSheet(
                state = ShareUiState(text = "An article", url = "https://example.org/a", saving = true),
                onTextChange = {},
                onNotesChange = {},
                onSave = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithTag(ShareTags.DESTINATION_INBOX).assertIsNotEnabled()
        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).assertIsNotEnabled()
    }
}
