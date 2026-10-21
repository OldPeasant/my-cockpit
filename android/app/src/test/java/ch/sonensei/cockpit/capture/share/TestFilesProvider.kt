package ch.sonensei.cockpit.capture.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Stands in for the sending app's provider: serves files from [dir] under `content://AUTHORITY/<name>`, with a
 * display name and a MIME type from the extension, as a gallery or file manager would.
 */
class TestFilesProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            if (file(uri).exists()) addRow(arrayOf(uri.lastPathSegment))
        }

    override fun getType(uri: Uri): String =
        when (uri.lastPathSegment?.substringAfterLast('.')) {
            "jpg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

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

    private fun file(uri: Uri): File = File(dir, requireNotNull(uri.lastPathSegment))

    companion object {
        const val AUTHORITY = "ch.sonensei.cockpit.test.files"
        lateinit var dir: File

        /** Writes a shared file and returns its content URI. */
        fun share(
            name: String,
            content: String,
        ): Uri {
            File(dir, name).writeText(content)
            return Uri.parse("content://$AUTHORITY/$name")
        }
    }
}
