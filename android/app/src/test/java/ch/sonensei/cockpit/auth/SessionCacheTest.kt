package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.files.OpenedFiles
import ch.sonensei.cockpit.files.PreviewFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The cache the auth module contributes: the app's cache directory goes with the logout.
 *
 * The checks name the files and folders each test created instead of requiring an exactly empty cache directory:
 * the app's background work (the capture widget observing the capture queue database) may create a
 * `capture_queue.db.lck` lock file there at any time, also right after the clear, and that file is harmless.
 */
@RunWith(AndroidJUnit4::class)
class SessionCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the cache directory is emptied, including subfolders, but kept itself`() =
        runBlocking {
            val created = createCacheContent()

            AuthModule.cacheDirectory(context).clear()

            assertCleared(created)
        }

    @Test
    fun `the files feature's previewed and opened files go with the logout`() =
        runBlocking {
            val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
            val preview = PreviewFiles.target(context, id).apply { writeText("fictitious") }
            val opened = OpenedFiles.target(context, id, "report-fictitious.pdf").apply { writeText("fictitious") }
            assertTrue(preview.exists() && opened.exists())

            AuthModule.cacheDirectory(context).clear()

            assertFalse(preview.parentFile!!.exists())
            assertFalse(opened.exists())
            assertCleared(listOf(preview.parentFile!!, opened.parentFile!!.parentFile!!))
        }

    @Test
    fun `a lock file the app creates in the cache right after the clear does not fail the checks`() =
        runBlocking {
            val created = createCacheContent()

            AuthModule.cacheDirectory(context).clear()
            // What the capture widget's database open does when it runs late (the CI race).
            val lock = File(context.cacheDir, "capture_queue.db.lck").apply { writeText("") }

            try {
                assertCleared(created)
            } finally {
                lock.delete()
            }
        }

    @Test
    fun `the checks still fail when the clear does nothing`(): Unit =
        runBlocking {
            val created = createCacheContent()

            SessionCache { }.clear()

            try {
                assertThrows(AssertionError::class.java) { assertCleared(created) }
            } finally {
                created.forEach { it.deleteRecursively() }
            }
        }

    @Test
    fun `an empty cache directory is fine`() =
        runBlocking {
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

            AuthModule.cacheDirectory(context).clear()

            assertTrue(context.cacheDir.isDirectory)
        }

    /** A file and a nested folder with a file in the cache directory; returns its top-level entries. */
    private fun createCacheContent(): List<File> {
        val cacheDir = context.cacheDir
        val thumbnail = File(cacheDir, "thumbnail-fictitious.png").apply { writeText("fictitious") }
        val downloads = File(cacheDir, "downloads")
        File(downloads, "nested").mkdirs()
        File(downloads, "nested/report-fictitious.pdf").writeText("fictitious")
        return listOf(thumbnail, downloads)
    }

    /** The cache directory still exists, and none of the [created] files and folders is left in it. */
    private fun assertCleared(created: List<File>) {
        assertTrue(context.cacheDir.isDirectory)
        assertEquals(emptyList<File>(), created.filter { it.exists() })
    }
}
