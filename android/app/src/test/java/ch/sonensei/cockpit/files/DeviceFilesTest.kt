package ch.sonensei.cockpit.files

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opening drive files in other apps: the cache file, the FileProvider grant and the intent. */
@RunWith(AndroidJUnit4::class)
class DeviceFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    @Test
    fun `a file opens through this app's provider, readable by the other app`() {
        val file = OpenedFiles.target(context, id(1), "Steuern 2025.pdf").apply { writeText("pdf") }
        val intent = OpenedFiles.view(context, file, "application/pdf")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.files", intent.data!!.authority)
        assertTrue(intent.data!!.path!!.endsWith("/Steuern 2025.pdf"))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("pdf", context.contentResolver.openInputStream(intent.data!!)!!.use { String(it.readBytes()) })
    }

    @Test
    fun `only the file opened last stays in the cache`() {
        val first = OpenedFiles.target(context, id(1), "a.txt").apply { writeText("a") }
        val second = OpenedFiles.target(context, id(2), "b.txt").apply { writeText("b") }
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertEquals(File(context.cacheDir, "files-open/${id(2)}/b.txt"), second)
    }

    @Test
    fun `names never escape the cache folder`() {
        assertEquals("_.._etc_passwd", OpenedFiles.safeName("/../etc/passwd"))
        assertEquals("file", OpenedFiles.safeName(".."))
        assertEquals("file", OpenedFiles.safeName("  "))
        assertEquals("a_b.txt", OpenedFiles.safeName("a\u0000b.txt"))
        assertEquals(120, OpenedFiles.safeName("x".repeat(300)).length)
    }

    @Test
    fun `the view type drops parameters and falls back for odd types`() {
        assertEquals("text/plain", OpenedFiles.viewType("Text/Plain; charset=utf-8"))
        assertEquals("image/svg+xml", OpenedFiles.viewType("image/svg+xml"))
        assertEquals("*/*", OpenedFiles.viewType("nonsense"))
        assertEquals("*/*", OpenedFiles.viewType(""))
    }

    @Test
    fun `sizes read like a file manager's`() {
        assertEquals("532 B", formatSize(532))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("12 MB", formatSize(12L * 1024 * 1024))
        assertEquals("3.1 GB", formatSize((3.1 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `mime types pick the row's tile`() {
        assertEquals(FileKind.IMAGE, fileKind("image/png"))
        assertEquals(FileKind.PDF, fileKind("application/pdf"))
        assertEquals(FileKind.TEXT, fileKind("application/json; charset=utf-8"))
        assertEquals(FileKind.VIDEO, fileKind("video/mp4"))
        assertEquals(FileKind.AUDIO, fileKind("audio/mpeg"))
        assertEquals(FileKind.OTHER, fileKind("application/zip"))
    }
}
