package ch.sonensei.cockpit.files

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** What the preview does with each type, the URLs it loads, the size a PDF page is rendered at, and zoom and pan limits. */
class FilePreviewModelsTest {
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000042")

    @Test
    fun `the images the server previews are images, other image types are not`() {
        listOf("image/jpeg", "image/png", "image/webp", "image/gif").forEach { assertEquals(it, PreviewKind.IMAGE, previewKind(it)) }
        listOf("image/svg+xml", "image/heic", "image/tiff", "image/bmp").forEach { assertEquals(it, PreviewKind.OTHER, previewKind(it)) }
    }

    @Test
    fun `parameters, case and blanks around the type do not matter`() {
        assertEquals(PreviewKind.IMAGE, previewKind("IMAGE/JPEG"))
        assertEquals(PreviewKind.PDF, previewKind(" Application/PDF ; name=\"2025.pdf\""))
        assertEquals(PreviewKind.TEXT, previewKind("text/plain; charset=utf-8"))
    }

    @Test
    fun `PDFs, text and the text-like application types`() {
        assertEquals(PreviewKind.PDF, previewKind("application/pdf"))
        listOf("text/plain", "text/markdown", "text/csv", "text/html").forEach { assertEquals(it, PreviewKind.TEXT, previewKind(it)) }
        listOf("application/json", "application/xml", "application/yaml", "application/x-yaml", "application/x-sh").forEach {
            assertEquals(it, PreviewKind.TEXT, previewKind(it))
        }
    }

    @Test
    fun `everything else, also an empty or broken type, is only opened in another app`() {
        listOf("application/zip", "application/octet-stream", "video/mp4", "audio/mpeg", "", "pdf", "application/pdfx").forEach {
            assertEquals(it, PreviewKind.OTHER, previewKind(it))
        }
    }

    @Test
    fun `the preview and the small thumbnail are loaded from the file's server URLs`() {
        assertEquals("https://server.invalid/api/v1/files/$id/preview", previewUrl(id))
        assertEquals("https://server.invalid/api/v1/files/$id/thumbnail?size=256", thumbnailUrl(id))
    }

    @Test
    fun `a page keeps its proportions at the width asked for`() {
        // A4 portrait and landscape, in points.
        assertEquals(1000 to 1415, pageSize(1000, 595, 842))
        assertEquals(1000 to 706, pageSize(1000, 842, 595))
        assertEquals(500 to 500, pageSize(500, 72, 72))
    }

    @Test
    fun `a very long page is rendered narrower so it is at most 8192 pixels high`() {
        // Exactly at the limit it keeps the width asked for.
        assertEquals(1000 to 8192, pageSize(1000, 1000, 8192))
        // Just above it, it is narrower.
        assertEquals(999 to 8192, pageSize(1000, 1000, 8193))
        // A receipt roll 100 x 100000 points.
        assertEquals(8 to 8192, pageSize(1000, 100, 100_000))
    }

    @Test
    fun `a page is never rendered zero pixels wide or high`() {
        assertEquals(1 to 8192, pageSize(10, 1, 1_000_000))
        assertEquals(1000 to 1, pageSize(1000, 100_000, 1))
    }

    @Test
    fun `a huge width does not overflow`() {
        assertEquals(4096 to 8192, pageSize(Int.MAX_VALUE, 1, 2))
    }

    @Test
    fun `zoom stays between the fitted view and five times`() {
        assertEquals(2f, zoomed(1f, 2f))
        assertEquals(1f, zoomed(1f, 0.5f))
        assertEquals(1f, zoomed(1.5f, 0.1f))
        assertEquals(MAX_ZOOM, zoomed(4f, 2f))
        assertEquals(MAX_ZOOM, zoomed(MAX_ZOOM, 1.01f))
        assertEquals(3f, zoomed(MAX_ZOOM, 0.6f), 0.0001f)
    }

    @Test
    fun `unzoomed content does not move`() {
        val offset = panned(Offset.Zero, Offset(40f, -30f), 1f, Size(100f, 200f))
        // Compared as numbers: clamping into -0..0 may give -0.0, which is still no movement.
        assertEquals(0f, offset.x, 0f)
        assertEquals(0f, offset.y, 0f)
    }

    @Test
    fun `zoomed content moves with the finger but always covers the view`() {
        val view = Size(100f, 200f)
        // At 2x the content is 200 x 400: it may move 50 sideways and 100 up or down.
        assertEquals(Offset(20f, -30f), panned(Offset.Zero, Offset(20f, -30f), 2f, view))
        assertEquals(Offset(50f, -100f), panned(Offset(40f, -90f), Offset(30f, -30f), 2f, view))
        assertEquals(Offset(-50f, 100f), panned(Offset.Zero, Offset(-500f, 500f), 2f, view))
        // Zooming back out pulls an offset that is now too far back in.
        assertEquals(Offset(25f, 50f), panned(Offset(50f, 100f), Offset.Zero, 1.5f, view))
    }
}
