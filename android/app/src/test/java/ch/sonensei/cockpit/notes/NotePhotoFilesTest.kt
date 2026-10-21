package ch.sonensei.cockpit.notes

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Reading picked or taken photos, and the camera's files. */
@RunWith(AndroidJUnit4::class)
class NotePhotoFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun register(
        uri: Uri,
        bytes: ByteArray,
    ) = shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

    @Test
    fun `reads at most the limit, all of a shorter stream`() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(data.copyOf(70_000), NotePhotoFiles.readAtMost(ByteArrayInputStream(data), 70_000))
        assertArrayEquals(data, NotePhotoFiles.readAtMost(ByteArrayInputStream(data), 500_000))
        assertEquals(0, NotePhotoFiles.readAtMost(ByteArrayInputStream(ByteArray(0)), 10).size)
    }

    @Test
    fun `a readable photo is read with an image type and a name`() =
        runBlocking {
            val uri = Uri.parse("content://ch.sonensei.test/photo/1")
            register(uri, byteArrayOf(1, 2, 3))

            val photo = NotePhotoFiles.read(context, uri)!!

            assertArrayEquals(byteArrayOf(1, 2, 3), photo.bytes)
            assertTrue(photo.mime.startsWith("image/"))
            assertTrue(photo.name.isNotBlank())
        }

    @Test
    fun `a photo of exactly 25 MB is accepted, one byte more is refused`() =
        runBlocking {
            val atLimit = Uri.parse("content://ch.sonensei.test/photo/limit")
            register(atLimit, ByteArray(NotePhotoFiles.MAX_BYTES))
            assertEquals(NotePhotoFiles.MAX_BYTES, NotePhotoFiles.read(context, atLimit)!!.bytes.size)

            val over = Uri.parse("content://ch.sonensei.test/photo/over")
            register(over, ByteArray(NotePhotoFiles.MAX_BYTES + 1))
            assertNull(NotePhotoFiles.read(context, over))
        }

    @Test
    fun `a photo whose stream fails while reading is refused, not a crash`() =
        runBlocking {
            val uri = Uri.parse("content://ch.sonensei.test/photo/broken")
            val broken =
                object : InputStream() {
                    override fun read(): Int = throw IOException("fictitious read error")

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int = throw IOException("fictitious read error")
                }
            shadowOf(context.contentResolver).registerInputStream(uri, broken)
            assertNull(NotePhotoFiles.read(context, uri))
        }

    @Test
    fun `an empty photo is refused`() =
        runBlocking {
            val uri = Uri.parse("content://ch.sonensei.test/photo/empty")
            register(uri, ByteArray(0))
            assertNull(NotePhotoFiles.read(context, uri))
        }

    @Test
    fun `camera files are shared through the app's photo provider and cleared`() {
        clearFileProviderCache()
        val (file, uri) = NotePhotoFiles.create(context)

        assertTrue(file.exists())
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.notes.photos", uri.authority)
        assertEquals(File(context.cacheDir, "notes-photos"), file.parentFile)

        NotePhotoFiles.clear(context)
        assertEquals(false, file.exists())
    }
}
