package ch.sonensei.cockpit.files

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** The in-app preview over the Files list, driven by states the tests control; PDFs come from a fake renderer. */
@RunWith(AndroidJUnit4::class)
class FilePreviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private val photo = DriveEntry.File(id(1), "Ferien.jpg", "image/jpeg", 4096, at)
    private val pdf = DriveEntry.File(id(2), "2025.pdf", "application/pdf", 1536, at)
    private val notes = DriveEntry.File(id(3), "notes.txt", "text/plain", 12, at)
    private val archive = DriveEntry.File(id(4), "backup.zip", "application/zip", 9000, at)
    private val files = listOf(photo, pdf, notes, archive)
    private val calls = mutableListOf<String>()

    private val callbacks =
        FilesCallbacks(
            openFile = { calls += "open ${it.name}" },
            preview = { calls += "preview ${it.name}" },
            previewStep = { calls += "step $it" },
            closePreview = { calls += "close" },
            retryPreview = { calls += "retry" },
            saveFile = { calls += "save ${it.name}" },
        )

    /** A PDF of [pageCount] pages that records what it renders and whether it was closed. */
    private class FakePdf(
        override val pageCount: Int,
    ) : PdfPages {
        val rendered = mutableListOf<Int>()

        @Volatile var closed = false

        override fun render(
            index: Int,
            width: Int,
        ): Bitmap? {
            synchronized(rendered) { rendered += index }
            return if (closed) null else Bitmap.createBitmap(width.coerceAtMost(64), 90, Bitmap.Config.ARGB_8888)
        }

        override fun close() {
            closed = true
        }
    }

    private fun preview(
        index: Int,
        content: PreviewContent,
        list: List<DriveEntry.File> = files,
    ) = FilesUiState(entries = list, loaded = true, preview = FilePreview(list, index, content))

    private fun show(
        state: FilesUiState,
        imageLoader: ImageLoader? = null,
        pdfOpener: (File) -> PdfPages? = { null },
    ): MutableState<FilesUiState> {
        val live = mutableStateOf(state)
        composeRule.setContent {
            CockpitTheme { FilesContent(live.value, callbacks, imageLoader = imageLoader, pdfOpener = pdfOpener) }
        }
        return live
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** An image loader that answers every request from memory, failing the first [failures] times; it never goes online. */
    private fun images(failures: Int = 0): Pair<ImageLoader, () -> Int> {
        var requests = 0
        val factory =
            Fetcher.Factory<Uri> { _, _, _ ->
                Fetcher {
                    if (++requests <= failures) throw IOException("fictitious failure")
                    ImageFetchResult(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImage(), false, DataSource.MEMORY)
                }
            }
        val loader =
            ImageLoader
                .Builder(ApplicationProvider.getApplicationContext<Context>())
                .memoryCache(null)
                .components { add(factory) }
                .build()
        return loader to { requests }
    }

    @Test
    fun `the preview names the file, its place in the folder and its size, and closes`() {
        show(preview(1, PreviewContent.Loading))
        composeRule.onNodeWithTag(PreviewTags.PREVIEW).assertExists()
        composeRule.onNodeWithTag(PreviewTags.NAME).assertTextEquals("2025.pdf")
        composeRule.onNodeWithTag(PreviewTags.POSITION).assertTextEquals("2 of 4, 1.5 KB")
        composeRule.onNodeWithTag(PreviewTags.LOADING).assertExists()

        composeRule.onNodeWithContentDescription("Close preview").performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `previous and next name the neighbouring files and step to them`() {
        show(preview(1, PreviewContent.Loading))
        composeRule.onNodeWithTag(PreviewTags.PREVIOUS).assertIsEnabled().assertTextContains("Ferien.jpg", substring = true)
        composeRule.onNodeWithTag(PreviewTags.NEXT).assertIsEnabled().assertTextContains("notes.txt", substring = true)
        composeRule.onNodeWithContentDescription("Previous file", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Next file", useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag(PreviewTags.NEXT).performClick()
        composeRule.onNodeWithTag(PreviewTags.PREVIOUS).performClick()
        assertEquals(listOf("step 1", "step -1"), calls)
    }

    @Test
    fun `there is no previous file before the first, and no next one after the last`() {
        val state = show(preview(0, PreviewContent.Image))
        composeRule.onNodeWithTag(PreviewTags.PREVIOUS).assertIsNotEnabled()
        composeRule.onNodeWithTag(PreviewTags.NEXT).assertIsEnabled()

        state.value = preview(3, PreviewContent.Unsupported)
        composeRule.onNodeWithTag(PreviewTags.PREVIOUS).assertIsEnabled()
        composeRule.onNodeWithTag(PreviewTags.NEXT).assertIsNotEnabled()

        state.value = preview(0, PreviewContent.Unsupported, list = listOf(archive))
        composeRule.onNodeWithTag(PreviewTags.PREVIOUS).assertIsNotEnabled()
        composeRule.onNodeWithTag(PreviewTags.NEXT).assertIsNotEnabled()
        composeRule.onNodeWithTag(PreviewTags.POSITION).assertTextEquals("1 of 1, 8.8 KB")
    }

    @Test
    fun `the top bar saves the file or opens it with another app, and shows a running download instead`() {
        val state = show(preview(1, PreviewContent.Loading))
        composeRule.onNodeWithTag(PreviewTags.SAVE).performClick()
        composeRule.onNodeWithTag(PreviewTags.OPEN_WITH).performClick()
        assertEquals(listOf("save 2025.pdf", "open 2025.pdf"), calls)

        state.value = state.value.copy(downloading = pdf.id)
        composeRule.onNodeWithTag(PreviewTags.DOWNLOADING).assertExists()
        composeRule.onNodeWithTag(PreviewTags.SAVE).assertDoesNotExist()
        composeRule.onNodeWithTag(PreviewTags.OPEN_WITH).assertDoesNotExist()

        state.value = state.value.copy(downloading = null, downloadMessage = FilesMessage.OFFLINE)
        // Over the list's own banner, the preview says it too.
        composeRule
            .onNode(hasText("Offline. The file could not be downloaded.") and hasParentTag(PreviewTags.PREVIEW))
            .assertExists()

        state.value = state.value.copy(downloadMessage = FilesMessage.FAILED)
        composeRule
            .onNode(hasText("The file could not be downloaded.") and hasParentTag(PreviewTags.PREVIEW))
            .assertExists()
    }

    @Test
    fun `a text file shows its text, and a notice only when it is cut`() {
        val state = show(preview(2, PreviewContent.Text("Milch\nBrot", truncated = false)))
        composeRule.onNodeWithTag(PreviewTags.TEXT).assertTextEquals("Milch\nBrot")
        composeRule.onNodeWithTag(PreviewTags.TRUNCATED).assertDoesNotExist()

        state.value = preview(2, PreviewContent.Text("Milch", truncated = true))
        composeRule
            .onNodeWithTag(PreviewTags.TRUNCATED)
            .assertTextEquals("Only the start of this file is shown. Open it in another app to see all of it.")
        composeRule.onNodeWithTag(PreviewTags.TEXT).assertTextEquals("Milch")
    }

    @Test
    fun `a type without a preview says so and offers Open with`() {
        show(preview(3, PreviewContent.Unsupported))
        composeRule.onNodeWithTag(PreviewTags.UNSUPPORTED).assertExists()
        composeRule.onNodeWithText("No preview for this type of file. Open it in another app.").assertExists()
        composeRule.onNode(hasText("Open with…") and hasParentTag(PreviewTags.UNSUPPORTED)).performClick()
        assertEquals(listOf("open backup.zip"), calls)
    }

    @Test
    fun `while a file downloads, the Open with of an unsupported type is disabled`() {
        show(preview(3, PreviewContent.Unsupported).copy(downloading = archive.id))
        composeRule.onNode(hasText("Open with…") and hasParentTag(PreviewTags.UNSUPPORTED)).assertIsNotEnabled()
    }

    @Test
    fun `a preview that could not load says why and can be tried again`() {
        val state = show(preview(2, PreviewContent.Problem(FilesMessage.OFFLINE)))
        composeRule.onNodeWithTag(PreviewTags.PROBLEM).assertExists()
        composeRule.onNodeWithText("Offline. The preview could not be loaded.").assertExists()
        composeRule.onNodeWithTag(PreviewTags.RETRY).performClick()

        state.value = preview(2, PreviewContent.Problem(FilesMessage.FAILED))
        composeRule.onNodeWithText("The preview could not be loaded.").assertExists()
        composeRule.onNodeWithTag(PreviewTags.RETRY).performClick()
        assertEquals(listOf("retry", "retry"), calls)
    }

    @Test
    fun `an image without a loader shows its type, zoomable, and nothing is loaded`() {
        show(preview(0, PreviewContent.Image))
        composeRule.onNodeWithTag(PreviewTags.IMAGE).assertExists()
        composeRule.onNodeWithTag(PreviewTags.PROBLEM).assertDoesNotExist()
    }

    @Test
    fun `an image is loaded with the loader and named for screen readers`() {
        val (loader, requests) = images()
        show(preview(0, PreviewContent.Image), imageLoader = loader)
        composeRule.waitUntil(WAIT_MS) { requests() > 0 }
        composeRule.onNodeWithTag(PreviewTags.IMAGE).assertExists()
        composeRule.onAllNodesWithContentDescription("Ferien.jpg").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        composeRule.onNodeWithTag(PreviewTags.PROBLEM).assertDoesNotExist()
    }

    /**
     * The view model's retry gives an image the same state it had ([PreviewContent.Image]), so the screen itself must
     * load it again: a retry that only calls back would leave the failure on screen for good.
     */
    @Test
    fun `an image that failed to load says so, and retry loads it again`() {
        val (loader, requests) = images(failures = 1)
        // No list rows behind it, so the only image loaded is the preview's.
        val state = show(preview(0, PreviewContent.Image).copy(entries = emptyList()), imageLoader = loader)
        waitForTag(PreviewTags.PROBLEM)
        composeRule.onNodeWithText("The image could not be loaded.").assertExists()
        assertEquals(1, requests())

        composeRule.onNodeWithTag(PreviewTags.RETRY).performClick()
        // What FilesViewModel.retryPreview does for an image: the same preview again.
        state.value = preview(0, PreviewContent.Image).copy(entries = emptyList())

        assertEquals(listOf("retry"), calls)
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(PreviewTags.PROBLEM).fetchSemanticsNodes().isEmpty() }
        assertEquals(2, requests())
    }

    @Test
    fun `an image that failed to load does not keep the next image from loading`() {
        val (loader, requests) = images(failures = 1)
        val beach = DriveEntry.File(id(6), "Strand.png", "image/png", 2048, at)
        val list = listOf(photo, beach)
        val state = show(preview(0, PreviewContent.Image, list).copy(entries = emptyList()), imageLoader = loader)
        waitForTag(PreviewTags.PROBLEM)

        state.value = preview(1, PreviewContent.Image, list).copy(entries = emptyList())
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(PreviewTags.PROBLEM).fetchSemanticsNodes().isEmpty() }
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithContentDescription("Strand.png").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, requests())
    }

    /**
     * How far the content inside the zoom frame tagged [frameTag] is scaled: the frame scales around its center, so the
     * content's left edge moves out from the center by the scale.
     */
    private fun zoomOf(
        content: SemanticsNodeInteraction,
        frameTag: String,
    ): Float {
        val frame = composeRule.onNodeWithTag(frameTag).fetchSemanticsNode()
        val left = frame.positionInRoot.x
        val center = left + frame.size.width / 2f
        return (center - content.fetchSemanticsNode().positionInRoot.x) / (center - left)
    }

    private fun showZoomableImage(list: List<DriveEntry.File> = listOf(photo)): MutableState<FilesUiState> {
        val (loader, _) = images()
        val state = show(preview(0, PreviewContent.Image, list).copy(entries = emptyList()), imageLoader = loader)
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithContentDescription(list[0].name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return state
    }

    @Test
    fun `a double tap zooms an image in, and another one back out`() {
        showZoomableImage()
        val image = composeRule.onNodeWithContentDescription("Ferien.jpg", useUnmergedTree = true)
        assertEquals(1f, zoomOf(image, PreviewTags.IMAGE), 0.01f)

        composeRule.onNodeWithTag(PreviewTags.IMAGE).performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertEquals(2.5f, zoomOf(image, PreviewTags.IMAGE), 0.01f)

        composeRule.onNodeWithTag(PreviewTags.IMAGE).performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertEquals(1f, zoomOf(image, PreviewTags.IMAGE), 0.01f)
    }

    @Test
    fun `pinching an image zooms it, at most five times, and never below the fitted view`() {
        showZoomableImage()
        val image = composeRule.onNodeWithContentDescription("Ferien.jpg", useUnmergedTree = true)

        // Fingers spread far apart: far more than 5x, capped.
        composeRule.onNodeWithTag(PreviewTags.IMAGE).performTouchInput {
            pinch(center - Offset(10f, 0f), center - Offset(150f, 0f), center + Offset(10f, 0f), center + Offset(150f, 0f))
        }
        composeRule.waitForIdle()
        assertEquals(MAX_ZOOM, zoomOf(image, PreviewTags.IMAGE), 0.05f)

        // Fingers pinched together: back to the fitted view, not smaller.
        composeRule.onNodeWithTag(PreviewTags.IMAGE).performTouchInput {
            pinch(center - Offset(150f, 0f), center - Offset(5f, 0f), center + Offset(150f, 0f), center + Offset(5f, 0f))
        }
        composeRule.waitForIdle()
        assertEquals(1f, zoomOf(image, PreviewTags.IMAGE), 0.01f)
    }

    @Test
    fun `the next image starts unzoomed`() {
        val beach = DriveEntry.File(id(6), "Strand.png", "image/png", 2048, at)
        val list = listOf(photo, beach)
        val state = showZoomableImage(list)
        composeRule.onNodeWithTag(PreviewTags.IMAGE).performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertEquals(2.5f, zoomOf(composeRule.onNodeWithContentDescription("Ferien.jpg", useUnmergedTree = true), PreviewTags.IMAGE), 0.01f)

        state.value = preview(1, PreviewContent.Image, list).copy(entries = emptyList())
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithContentDescription("Strand.png", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1f, zoomOf(composeRule.onNodeWithContentDescription("Strand.png", useUnmergedTree = true), PreviewTags.IMAGE), 0.01f)
    }

    @Test
    fun `a double tap zooms a PDF page in and back out`() {
        show(preview(1, PreviewContent.Pdf(File("fictitious/2025.pdf")))) { FakePdf(pageCount = 2) }
        waitForTag(PreviewTags.PDF_PAGE)
        val page = composeRule.onNodeWithTag(PreviewTags.PDF_PAGE)
        val fitted = page.fetchSemanticsNode().positionInRoot

        page.performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        val zoomed = page.fetchSemanticsNode().positionInRoot
        assertTrue("the page did not grow out from the center: $fitted -> $zoomed", zoomed.x < fitted.x - 1f && zoomed.y < fitted.y - 1f)

        page.performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertEquals(fitted.x, page.fetchSemanticsNode().positionInRoot.x, 0.5f)
        assertEquals(fitted.y, page.fetchSemanticsNode().positionInRoot.y, 0.5f)
    }

    @Test
    fun `a PDF is shown page by page, and the bar turns the pages`() {
        val fake = FakePdf(pageCount = 3)
        val cache = File("fictitious/2025.pdf")
        var opened: File? = null
        show(preview(1, PreviewContent.Pdf(cache))) {
            opened = it
            fake
        }
        waitForTag(PreviewTags.PDF_PAGE)
        assertEquals(cache, opened)
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE_NUMBER).assertTextEquals("Page 1 of 3")
        composeRule.onNodeWithTag(PreviewTags.PDF_PREVIOUS_PAGE).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Page 1 of 3").assertExists()

        composeRule.onNodeWithTag(PreviewTags.PDF_NEXT_PAGE).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE_NUMBER).assertTextEquals("Page 2 of 3")
        composeRule.onNodeWithTag(PreviewTags.PDF_NEXT_PAGE).performClick()
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE_NUMBER).assertTextEquals("Page 3 of 3")
        composeRule.onNodeWithTag(PreviewTags.PDF_NEXT_PAGE).assertIsNotEnabled()
        composeRule.waitUntil(WAIT_MS) { synchronized(fake.rendered) { 2 in fake.rendered } }
        composeRule.onNodeWithContentDescription("Page 3 of 3").assertExists()

        composeRule.onNodeWithTag(PreviewTags.PDF_PREVIOUS_PAGE).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE_NUMBER).assertTextEquals("Page 2 of 3")
        assertEquals(listOf(0, 1, 2), synchronized(fake.rendered) { fake.rendered.distinct().sorted() })
    }

    @Test
    fun `a single-page PDF cannot be turned`() {
        show(preview(1, PreviewContent.Pdf(File("fictitious/2025.pdf")))) { FakePdf(pageCount = 1) }
        waitForTag(PreviewTags.PDF_PAGE)
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE_NUMBER).assertTextEquals("Page 1 of 1")
        composeRule.onNodeWithTag(PreviewTags.PDF_PREVIOUS_PAGE).assertIsNotEnabled()
        composeRule.onNodeWithTag(PreviewTags.PDF_NEXT_PAGE).assertIsNotEnabled()
    }

    @Test
    fun `the open PDF is closed when the preview moves on to another file`() {
        val fake = FakePdf(pageCount = 2)
        val state = show(preview(1, PreviewContent.Pdf(File("fictitious/2025.pdf")))) { fake }
        waitForTag(PreviewTags.PDF_PAGE)
        assertFalse(fake.closed)

        state.value = preview(2, PreviewContent.Text("Milch", false))
        composeRule.waitForIdle()
        assertTrue("the PDF was left open", fake.closed)
        composeRule.onNodeWithTag(PreviewTags.TEXT).assertTextEquals("Milch")
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE).assertDoesNotExist()
    }

    @Test
    fun `the open PDF is closed when the preview closes, and the list shows again`() {
        val fake = FakePdf(pageCount = 2)
        val state = show(preview(1, PreviewContent.Pdf(File("fictitious/2025.pdf")))) { fake }
        waitForTag(PreviewTags.PDF_PAGE)

        state.value = state.value.copy(preview = null)
        composeRule.waitForIdle()
        assertTrue("the PDF was left open", fake.closed)
        composeRule.onNodeWithTag(PreviewTags.PREVIEW).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).assertExists()
    }

    @Test
    fun `a new file of the folder starts on its first page`() {
        val state = show(preview(1, PreviewContent.Pdf(File("fictitious/2025.pdf")))) { FakePdf(pageCount = 3) }
        waitForTag(PreviewTags.PDF_PAGE)
        composeRule.onNodeWithTag(PreviewTags.PDF_NEXT_PAGE).performClick()
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE_NUMBER).assertTextEquals("Page 2 of 3")

        val other = DriveEntry.File(id(5), "2026.pdf", "application/pdf", 1536, at)
        val list = listOf(photo, pdf, other)
        state.value = preview(2, PreviewContent.Pdf(File("fictitious/2026.pdf")), list)
        waitForTag(PreviewTags.PDF_PAGE)
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE_NUMBER).assertTextEquals("Page 1 of 3")
    }

    @Test
    fun `a PDF that cannot be opened offers Open with`() {
        show(preview(1, PreviewContent.Pdf(File("fictitious/broken.pdf")))) { null }
        waitForTag(PreviewTags.PDF_FAILED)
        composeRule.onNodeWithText("This PDF cannot be shown here. Open it in another app.").assertExists()
        composeRule.onNode(hasText("Open with…") and hasParentTag(PreviewTags.PDF_FAILED)).performClick()
        assertEquals(listOf("open 2025.pdf"), calls)
    }

    @Test
    fun `a PDF without pages cannot be shown, and is closed with the preview`() {
        val empty = FakePdf(pageCount = 0)
        val state = show(preview(1, PreviewContent.Pdf(File("fictitious/empty.pdf")))) { empty }
        waitForTag(PreviewTags.PDF_FAILED)
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE).assertDoesNotExist()

        state.value = state.value.copy(preview = null)
        composeRule.waitForIdle()
        assertTrue("the PDF was left open", empty.closed)
    }

    @Test
    fun `image rows show the server's thumbnail when there is a loader, other rows only their type`() {
        val (loader, _) = images()
        show(FilesUiState(entries = files, loaded = true), imageLoader = loader)
        composeRule.onNodeWithTag(FilesTags.thumbnail(photo.id), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(FilesTags.thumbnail(pdf.id), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.thumbnail(notes.id), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.thumbnail(archive.id), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `without a loader no row loads a thumbnail`() {
        show(FilesUiState(entries = files, loaded = true))
        composeRule.onNodeWithTag(FilesTags.thumbnail(photo.id), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a selected image row shows the check, not its thumbnail`() {
        val (loader, _) = images()
        show(FilesUiState(entries = files, loaded = true, selected = setOf(photo.id)), imageLoader = loader)
        composeRule.onNodeWithTag(FilesTags.thumbnail(photo.id), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a tap on an image row previews it`() {
        val (loader, _) = images()
        show(FilesUiState(entries = files, loaded = true), imageLoader = loader)
        composeRule.onNodeWithTag(FilesTags.entry(photo.id)).performClick()
        assertEquals(listOf("preview Ferien.jpg"), calls)
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German, the preview is German`() {
        val state = show(preview(1, PreviewContent.Text("Milch", truncated = true)))
        composeRule.onNodeWithTag(PreviewTags.POSITION).assertTextEquals("2 von 4, 1.5 KB")
        composeRule.onNodeWithContentDescription("Vorschau schließen").assertExists()
        composeRule.onNodeWithContentDescription("Vorherige Datei", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Nächste Datei", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Öffnen mit…").assertExists()
        composeRule
            .onNodeWithTag(PreviewTags.TRUNCATED)
            .assertTextEquals("Nur der Anfang dieser Datei wird angezeigt. Öffne sie in einer anderen App, um alles zu sehen.")

        state.value = preview(3, PreviewContent.Unsupported)
        composeRule.onNodeWithText("Keine Vorschau für diese Art von Datei. Öffne sie in einer anderen App.").assertExists()
        state.value = preview(2, PreviewContent.Problem(FilesMessage.OFFLINE))
        composeRule.onNodeWithText("Offline. Die Vorschau konnte nicht geladen werden.").assertExists()
        composeRule.onNodeWithTag(PreviewTags.RETRY).assertTextEquals("Erneut versuchen")
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German, the PDF pages are German`() {
        show(preview(1, PreviewContent.Pdf(File("fictitious/2025.pdf")))) { FakePdf(pageCount = 2) }
        waitForTag(PreviewTags.PDF_PAGE)
        composeRule.onNodeWithTag(PreviewTags.PDF_PAGE_NUMBER).assertTextEquals("Seite 1 von 2")
        composeRule.onNodeWithContentDescription("Vorherige Seite").assertExists()
        composeRule.onNodeWithContentDescription("Nächste Seite").assertExists()
    }

    @Test
    fun `every new preview string has its own German translation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        fun inLocale(tag: String) =
            context
                .createConfigurationContext(
                    android.content.res.Configuration(context.resources.configuration).apply {
                        setLocale(java.util.Locale.forLanguageTag(tag))
                    },
                ).resources
        val en = inLocale("en")
        val de = inLocale("de")
        val keys =
            listOf(
                R.string.files_open,
                R.string.files_preview_close,
                R.string.files_preview_previous,
                R.string.files_preview_next,
                R.string.files_preview_position,
                R.string.files_preview_page,
                R.string.files_preview_previous_page,
                R.string.files_preview_next_page,
                R.string.files_preview_unsupported,
                R.string.files_preview_offline,
                R.string.files_preview_failed,
                R.string.files_preview_truncated,
                R.string.files_preview_pdf_failed,
                R.string.files_preview_image_failed,
            )
        keys.forEach { key ->
            val name = en.getResourceEntryName(key)
            assertTrue("$name is empty in English", en.getString(key).isNotBlank())
            assertTrue("$name is empty in German", de.getString(key).isNotBlank())
            assertNotEquals("$name is not translated", en.getString(key), de.getString(key))
        }
        assertEquals("2 of 4", en.getString(R.string.files_preview_position, 2, 4))
        assertEquals("2 von 4", de.getString(R.string.files_preview_position, 2, 4))
        assertEquals("Seite 3 von 7", de.getString(R.string.files_preview_page, 3, 7))
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

/** A node inside the one carrying [tag]. */
private fun hasParentTag(tag: String) = hasAnyAncestor(hasTestTag(tag))
