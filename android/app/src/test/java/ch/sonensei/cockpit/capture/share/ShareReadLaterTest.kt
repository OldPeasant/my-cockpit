package ch.sonensei.cockpit.capture.share

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

/** A shared URL can go to the Inbox or the read-later list (SPEC.md §8.4): detection, routing and the sheet's choice. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareReadLaterTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val queue = mockk<CaptureQueue>()
    private val id = UUID.fromString("00000000-0000-7000-9000-000000000001")

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

    private fun viewModel(content: SharedContent) = ShareViewModel(queue, context).apply { load(content) }

    @Test
    fun `a browser share of a page carries its URL`() {
        val content = ShareIntentParser.parse(send("https://example.org/article", subject = "An article"))!!

        assertEquals("https://example.org/article", content.url)
        assertEquals("An article", content.title)
    }

    @Test
    fun `a URL inside text is found without the punctuation after it, keeping parentheses it opened`() {
        assertEquals("https://example.org/a", ShareIntentParser.parse(send("Look at this: https://example.org/a."))!!.url)
        assertEquals(
            "https://en.wikipedia.org/wiki/Bern_(city)",
            ShareIntentParser.parse(send("(see https://en.wikipedia.org/wiki/Bern_(city))"))!!.url,
        )
    }

    @Test
    fun `text without an http URL offers no read-later choice`() {
        assertNull(ShareIntentParser.parse(send("Buy milk"))!!.url)
        assertNull(ShareIntentParser.parse(send("ftp://example.org/file"))!!.url)
        assertNull(ShareIntentParser.parse(send("https://"))!!.url)
    }

    @Test
    fun `the Inbox is the default destination and saves a capture with the notes`() {
        val vm = viewModel(SharedContent("An article", "https://example.org/article", emptyList(), "https://example.org/article"))

        assertEquals(ShareDestination.INBOX, vm.uiState.value.destination)
        vm.save()

        coVerify(exactly = 1) { queue.capture("An article", "https://example.org/article", emptyList()) }
        coVerify(exactly = 0) { queue.readLater(any(), any()) }
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `read later queues the URL with the edited title instead of a capture`() {
        val vm = viewModel(SharedContent("An article", "https://example.org/article", emptyList(), "https://example.org/article"))

        vm.onDestinationChange(ShareDestination.READ_LATER)
        vm.onTextChange("A better title")
        vm.save()

        coVerify(exactly = 1) { queue.readLater("https://example.org/article", "A better title") }
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `read later of a bare link leaves the title to the server, and needs none`() {
        val vm = viewModel(SharedContent("https://example.org/a", null, emptyList(), "https://example.org/a"))
        vm.onDestinationChange(ShareDestination.READ_LATER)
        vm.save()
        coVerify(exactly = 1) { queue.readLater("https://example.org/a", null) }

        val blank = viewModel(SharedContent("", null, emptyList(), "https://example.org/b"))
        blank.onDestinationChange(ShareDestination.READ_LATER)
        assertTrue(blank.uiState.value.canSave)
        blank.save()
        coVerify(exactly = 1) { queue.readLater("https://example.org/b", null) }
    }

    @Test
    fun `a share without a URL cannot switch to read later`() {
        val vm = viewModel(SharedContent("Buy milk", null, emptyList()))

        vm.onDestinationChange(ShareDestination.READ_LATER)
        vm.save()

        assertFalse(vm.uiState.value.readLater)
        coVerify(exactly = 1) { queue.capture("Buy milk", "", emptyList()) }
    }

    @Test
    fun `a failing read-later store reports an error and keeps the sheet open`() {
        coEvery { queue.readLater(any(), any()) } throws IllegalStateException("disk full")
        val vm = viewModel(SharedContent("An article", null, emptyList(), "https://example.org/article"))

        vm.onDestinationChange(ShareDestination.READ_LATER)
        vm.save()

        assertEquals(ShareError.FAILED, vm.uiState.value.error)
        assertFalse(vm.uiState.value.saved)
    }

    @Test
    fun `the sheet offers Inbox and Read later for a URL and switches its fields`() {
        var state by mutableStateOf(
            ShareUiState(text = "An article", notes = "https://example.org/article", url = "https://example.org/article"),
        )
        composeRule.setContent {
            CaptureSheet(
                state = state,
                onTextChange = { state = state.copy(text = it) },
                onNotesChange = { state = state.copy(notes = it) },
                onSave = {},
                onCancel = {},
                onDestinationChange = { state = state.copy(destination = it) },
            )
        }

        composeRule.onNodeWithTag(ShareTags.DESTINATION_INBOX).assertIsSelected()
        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).assertIsNotSelected()
        composeRule.onNodeWithTag(ShareTags.NOTES).assertExists()
        composeRule.onNodeWithText("Save to Inbox").assertExists()

        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).performClick()

        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).assertIsSelected()
        composeRule.onNodeWithTag(ShareTags.NOTES).assertDoesNotExist()
        composeRule.onNodeWithTag(ShareTags.URL).assertTextEquals("https://example.org/article")
        composeRule.onNodeWithText("Save to read later").assertExists()
    }

    @Test
    fun `the sheet shows no choice for a share without a URL`() {
        composeRule.setContent {
            CaptureSheet(state = ShareUiState(text = "Buy milk"), onTextChange = {}, onNotesChange = {}, onSave = {}, onCancel = {})
        }

        composeRule.onNodeWithTag(ShareTags.DESTINATION_INBOX).assertDoesNotExist()
        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).assertDoesNotExist()
    }
}
