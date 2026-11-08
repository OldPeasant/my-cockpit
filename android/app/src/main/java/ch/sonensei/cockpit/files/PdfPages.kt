package ch.sonensei.cockpit.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.io.File
import java.io.IOException

/** The pages of an open PDF, rendered one at a time (SPEC.md §8.2: Android `PdfRenderer`). */
interface PdfPages : Closeable {
    val pageCount: Int

    /** Page [index] rendered [width] pixels wide on white; `null` once closed, or when it cannot be rendered. */
    fun render(
        index: Int,
        width: Int,
    ): Bitmap?
}

/** Opens [file] with `PdfRenderer`; `null` when it cannot be read or is not a PDF it understands (or is encrypted). */
fun openPdf(file: File): PdfPages? =
    try {
        RendererPdfPages(file)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

/** `PdfRenderer` renders one page at a time and must not be closed while it does: every call holds the lock. */
private class RendererPdfPages(
    file: File,
) : PdfPages {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer =
        try {
            PdfRenderer(descriptor)
        } catch (e: IOException) {
            descriptor.close()
            throw e
        } catch (e: SecurityException) {
            descriptor.close()
            throw e
        }
    private var closed = false

    override val pageCount: Int = renderer.pageCount

    @Synchronized
    override fun render(
        index: Int,
        width: Int,
    ): Bitmap? {
        if (closed || index !in 0 until pageCount || width <= 0) return null
        val page = renderer.openPage(index)
        return try {
            if (page.width <= 0 || page.height <= 0) return null
            val (w, h) = pageSize(width, page.width, page.height)
            createBitmap(w, h).also {
                // Pages are transparent where nothing is drawn: paper is white, also in the dark theme.
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } finally {
            page.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
        descriptor.close()
    }
}

/**
 * The bitmap size of a page of [pageWidth] x [pageHeight] points rendered [width] pixels wide, keeping its proportions;
 * a very long page (a receipt roll) is rendered narrower so its height stays at most [MAX_PAGE_SIDE].
 */
internal fun pageSize(
    width: Int,
    pageWidth: Int,
    pageHeight: Int,
): Pair<Int, Int> {
    val height = width.toLong() * pageHeight / pageWidth
    return if (height <= MAX_PAGE_SIDE) {
        width to height.coerceAtLeast(1).toInt()
    } else {
        (width.toLong() * MAX_PAGE_SIDE / height).coerceAtLeast(1).toInt() to MAX_PAGE_SIDE
    }
}

private const val MAX_PAGE_SIDE = 8192
