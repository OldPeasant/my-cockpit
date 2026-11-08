package ch.sonensei.cockpit.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The cache PDFs are downloaded into for the in-app preview, and opening files that are no readable PDF. */
@RunWith(AndroidJUnit4::class)
class PreviewFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    @Test
    fun `a previewed PDF goes into its own cache folder, named by its id`() {
        val target = PreviewFiles.target(context, id(1))
        assertEquals(File(context.cacheDir, "files-preview"), target.parentFile)
        assertTrue(target.name, target.name.startsWith("${id(1)}-") && target.name.endsWith(".pdf"))
        assertTrue("the folder is created", target.parentFile!!.isDirectory)
    }

    @Test
    fun `only the PDF previewed last stays in the cache`() {
        val first = PreviewFiles.target(context, id(1)).apply { writeText("%PDF-fictitious-1") }
        val stray = File(first.parentFile, "left-over").apply { mkdirs() }
        File(stray, "inside.tmp").writeText("x")

        val second = PreviewFiles.target(context, id(2)).apply { writeText("%PDF-fictitious-2") }
        assertFalse(first.exists())
        assertFalse("other leftovers go too, folders included", stray.exists())
        assertTrue(second.exists())
    }

    @Test
    fun `every load of the same PDF gets a file of its own, so a late cleanup of an earlier load cannot hit it`() {
        val first = PreviewFiles.target(context, id(3)).apply { writeText("%PDF-fictitious-1") }
        val again = PreviewFiles.target(context, id(3)).apply { writeText("%PDF-fictitious-2") }
        assertNotEquals(first, again)
        assertFalse("the earlier load's file goes", first.exists())

        // The earlier, cancelled load deletes its own file when it ends: the newer one stays.
        first.delete()
        assertEquals("%PDF-fictitious-2", again.readText())
    }

    @Test
    fun `the preview cache is separate from the files opened in other apps`() {
        val opened = OpenedFiles.target(context, id(4), "Steuern 2025.pdf").apply { writeText("pdf") }
        PreviewFiles.target(context, id(5))
        assertTrue("previewing must not delete a file another app is reading", opened.exists())
    }

    /** Robolectric cannot construct a `PdfRenderer`, so rendering real PDFs is left to a device; this fails before it. */
    @Test
    fun `a missing file is no PDF`() {
        assertNull(openPdf(File(context.cacheDir, "fictitious/missing.pdf")))
    }
}
