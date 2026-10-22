package ch.sonensei.cockpit.files

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID

/** Opening drive files in other apps: the cache file, the FileProvider grant and the intent. */
@RunWith(AndroidJUnit4::class)
class DeviceFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    @Before
    fun setUp() = resetFileProviderCache()

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
    fun `a picked file is described by its provider's name, size and type, and read only when opened`() {
        PickedFilesProvider.register()
        val bytes = PickedFilesProvider.file(context, PickedFilesProvider.REISE)
        bytes.writeText("fictitious travel plan")

        val picked = describePicked(context.contentResolver, PickedFilesProvider.uri(PickedFilesProvider.REISE))

        assertEquals("Reise.pdf", picked.name)
        assertEquals("application/pdf", picked.mime)
        assertEquals(2048L, picked.sizeBytes)
        assertEquals("fictitious travel plan", picked.open()!!.use { String(it.readBytes()) })
    }

    @Test
    fun `a picked file without a size has an unknown size`() {
        PickedFilesProvider.register()
        val picked = describePicked(context.contentResolver, PickedFilesProvider.uri(PickedFilesProvider.NO_SIZE))
        assertEquals("notes.txt", picked.name)
        assertEquals("text/plain", picked.mime)
        assertNull(picked.sizeBytes)
    }

    @Test
    fun `a blank, missing or unreadable name falls back to the URI's last segment, an unknown type to octet-stream`() {
        PickedFilesProvider.register()
        for (segment in listOf(PickedFilesProvider.BLANK_NAME, PickedFilesProvider.NO_ROW, PickedFilesProvider.BROKEN)) {
            val picked = describePicked(context.contentResolver, PickedFilesProvider.uri(segment))
            assertEquals("name for $segment", segment, picked.name)
            assertEquals("type for $segment", "application/octet-stream", picked.mime)
        }
        assertNull(describePicked(context.contentResolver, PickedFilesProvider.uri(PickedFilesProvider.NO_ROW)).sizeBytes)
    }

    @Test
    fun `a picked file that is gone or no longer allowed opens as null instead of crashing`() {
        PickedFilesProvider.register()
        val gone = describePicked(context.contentResolver, PickedFilesProvider.uri("gone.txt"))
        val denied = describePicked(context.contentResolver, PickedFilesProvider.uri(PickedFilesProvider.DENIED))
        assertNull(gone.open())
        assertNull(denied.open())
    }

    @Test
    fun `saving writes into the chosen document and replaces what it held`() {
        PickedFilesProvider.register()
        val target = PickedFilesProvider.file(context, "saved.pdf")
        target.writeText("an older and much longer content")

        openForWriting(context.contentResolver, PickedFilesProvider.uri("saved.pdf"))!!.use { it.write("new".toByteArray()) }

        assertEquals("new", target.readText())
    }

    @Test
    fun `a save target that cannot be written gives null instead of crashing`() {
        PickedFilesProvider.register()
        assertNull(openForWriting(context.contentResolver, PickedFilesProvider.uri(PickedFilesProvider.DENIED)))
        // A folder cannot be opened as a file.
        PickedFilesProvider.file(context, "a-folder").mkdirs()
        assertNull(openForWriting(context.contentResolver, PickedFilesProvider.uri("a-folder")))
    }

    @Test
    fun `launching reports whether an app on the phone took the file`() {
        val file = OpenedFiles.target(context, id(5), "Plan.pdf").apply { writeText("pdf") }
        val intent = OpenedFiles.view(context, file, "application/pdf").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val app = shadowOf(context as Application)

        assertTrue(OpenedFiles.launch(context, intent))
        assertEquals(Intent.ACTION_VIEW, app.nextStartedActivity.action)

        app.checkActivities(true)
        assertFalse(OpenedFiles.launch(context, intent))
        assertNull(app.nextStartedActivity)
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
