package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.files.OpenedFiles
import ch.sonensei.cockpit.files.PreviewFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The cache the auth module contributes: the app's cache directory goes with the logout. */
@RunWith(AndroidJUnit4::class)
class SessionCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the cache directory is emptied, including subfolders, but kept itself`() =
        runBlocking {
            val cacheDir = context.cacheDir
            File(cacheDir, "thumbnail-fictitious.png").writeText("fictitious")
            File(cacheDir, "downloads/nested").mkdirs()
            File(cacheDir, "downloads/nested/report-fictitious.pdf").writeText("fictitious")

            AuthModule.cacheDirectory(context).clear()

            assertTrue(cacheDir.isDirectory)
            assertEquals(emptyList<String>(), cacheDir.list()!!.toList())
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
            assertEquals(emptyList<String>(), context.cacheDir.list()!!.toList())
        }

    @Test
    fun `an empty cache directory is fine`() =
        runBlocking {
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

            AuthModule.cacheDirectory(context).clear()

            assertTrue(context.cacheDir.isDirectory)
        }
}
