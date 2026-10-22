package ch.sonensei.cockpit.mail.compose

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.function.Supplier

/** Name, type and size of a picked file as its provider tells them, and reading it only when uploaded. */
@RunWith(AndroidJUnit4::class)
class DeviceFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = context.contentResolver

    private fun uri(name: String) = Uri.parse("content://${PickedFilesProvider.AUTHORITY}/files/$name")

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(PickedFilesProvider::class.java, PickedFilesProvider.AUTHORITY)
        PickedFilesProvider.queries = 0
    }

    @Test
    fun `the provider's name, type and size describe the file, nothing is read yet`() {
        val file = describePicked(resolver, uri("report"))

        assertEquals("Quarterly report.pdf", file.name)
        assertEquals("application/pdf", file.mime)
        assertEquals(2048L, file.sizeBytes)
        assertEquals(1, PickedFilesProvider.queries)
    }

    @Test
    fun `an unknown size stays unknown`() {
        val file = describePicked(resolver, uri("nosize"))

        assertEquals("photo.jpg", file.name)
        assertEquals("image/jpeg", file.mime)
        assertNull(file.sizeBytes)
    }

    @Test
    fun `a blank name falls back to the last path segment, an unknown type to octet-stream`() {
        val file = describePicked(resolver, uri("blank"))

        assertEquals("blank", file.name)
        assertEquals("application/octet-stream", file.mime)
        assertNull(file.sizeBytes)
    }

    @Test
    fun `a provider that refuses the query, answers nothing or an empty cursor still gives a file`() {
        listOf("denied", "nothing", "empty").forEach { segment ->
            val file = describePicked(resolver, uri(segment))
            assertEquals(segment, file.name)
            assertNull(segment, file.sizeBytes)
        }
    }

    @Test
    fun `a uri without path is called attachment`() {
        val file = describePicked(resolver, Uri.parse("content://${PickedFilesProvider.AUTHORITY}"))

        assertEquals("attachment", file.name)
    }

    @Test
    fun `open streams the bytes of the picked file`() {
        val uri = uri("report")
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream("fictitious report".toByteArray()))

        val bytes = describePicked(resolver, uri).open()?.use { it.readBytes() }

        assertEquals("fictitious report", bytes?.toString(Charsets.UTF_8))
    }

    @Test
    fun `a file that lost its permission or is gone opens as null`() {
        val denied = uri("report")
        val gone = uri("nosize")
        shadowOf(resolver).registerInputStreamSupplier(denied, Supplier<InputStream> { throw SecurityException("Permission revoked") })
        shadowOf(resolver).registerInputStreamSupplier(gone, Supplier<InputStream> { throw FileNotFoundException("deleted") })

        assertNull(describePicked(resolver, denied).open())
        assertNull(describePicked(resolver, gone).open())
    }

    /** A document provider of the system picker, answering by the last path segment. */
    class PickedFilesProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            queries++
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return when (uri.lastPathSegment) {
                "report" -> MatrixCursor(columns).apply { addRow(arrayOf<Any?>("Quarterly report.pdf", 2048L)) }
                "nosize" -> MatrixCursor(columns).apply { addRow(arrayOf<Any?>("photo.jpg", null)) }
                "blank" -> MatrixCursor(columns).apply { addRow(arrayOf<Any?>("  ", null)) }
                "denied" -> throw SecurityException("Permission denial")
                "empty" -> MatrixCursor(columns)
                else -> null
            }
        }

        override fun getType(uri: Uri): String? =
            when (uri.lastPathSegment) {
                "report" -> "application/pdf"
                "nosize" -> "image/jpeg"
                else -> null
            }

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            const val AUTHORITY = "ch.sonensei.cockpit.test.picked"

            @Volatile var queries = 0
        }
    }
}
