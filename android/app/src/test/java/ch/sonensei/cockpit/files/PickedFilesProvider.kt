package ch.sonensei.cockpit.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.os.BundleCompat
import org.robolectric.Robolectric
import java.io.File
import java.lang.reflect.Modifier

/**
 * A stand-in for the document providers the system pickers return URIs of (Downloads, Drive, ...). What a URI
 * answers depends on its last segment, see the constants; the bytes live in `cache/picked/<segment>`.
 */
class PickedFilesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row: Pair<String, Long?>? =
            when (uri.lastPathSegment) {
                REISE -> "Reise.pdf" to 2048L
                NO_SIZE -> "notes.txt" to null
                BLANK_NAME -> " " to 5L
                NO_ROW -> null
                BROKEN -> throw IllegalStateException("fictitious provider failure")
                else -> uri.lastPathSegment!! to null
            }
        row?.let { (name, size) ->
            cursor.addRow(columns.map<String, Any?> { if (it == OpenableColumns.DISPLAY_NAME) name else size }.toTypedArray())
        }
        return cursor
    }

    override fun getType(uri: Uri): String? =
        when (uri.lastPathSegment) {
            REISE -> "application/pdf"
            NO_SIZE -> "text/plain"
            else -> null
        }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (uri.lastPathSegment == DENIED) throw SecurityException("fictitious: permission revoked")
        return ParcelFileDescriptor.open(file(context!!, uri.lastPathSegment!!), ParcelFileDescriptor.parseMode(mode))
    }

    /** `DocumentsContract.deleteDocument` arrives here: deletes the bytes, refused for [DENIED]. */
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method != DELETE_DOCUMENT) return null
        val uri = extras?.let { BundleCompat.getParcelable(it, EXTRA_URI, Uri::class.java) } ?: return null
        if (uri.lastPathSegment == DENIED) throw SecurityException("fictitious: permission revoked")
        file(context!!, uri.lastPathSegment!!).delete()
        return null
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    companion object {
        const val AUTHORITY = "ch.sonensei.cockpit.test.picked"

        /** Named `Reise.pdf`, 2048 bytes, `application/pdf`. */
        const val REISE = "reise"

        /** Named `notes.txt`, size unknown, `text/plain`. */
        const val NO_SIZE = "nosize"

        /** A blank display name, no type. */
        const val BLANK_NAME = "blank"

        /** The query finds no row, no type. */
        const val NO_ROW = "norow"

        /** The query throws. */
        const val BROKEN = "broken"

        /** Opening throws a SecurityException (the grant was revoked). */
        const val DENIED = "denied"

        /** The (hidden) method name `DocumentsContract.deleteDocument` calls. */
        private const val DELETE_DOCUMENT = "android:deleteDocument"

        /** The (hidden) `DocumentsContract.EXTRA_URI`: the document to delete. */
        private const val EXTRA_URI = "uri"

        fun uri(segment: String): Uri = Uri.parse("content://$AUTHORITY/picked/$segment")

        fun file(
            context: Context,
            segment: String,
        ): File = File(context.cacheDir, "picked/$segment").apply { parentFile!!.mkdirs() }

        /** Registers the provider for the running test (Robolectric forgets it after each test). */
        fun register() {
            Robolectric.buildContentProvider(PickedFilesProvider::class.java).create(AUTHORITY)
        }
    }
}

/**
 * `FileProvider` keeps the folders it shares in a static cache, filled on first use. Robolectric gives each test its
 * own data folder but keeps statics between tests, so a later test would be refused as outside the earlier folder.
 * On a phone the folder never changes within a process; tests that use the provider clear the cache first.
 */
internal fun resetFileProviderCache() {
    FileProvider::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type) }
        .forEach { field ->
            field.isAccessible = true
            val cache = field.get(null) as MutableMap<*, *>
            synchronized(cache) { cache.clear() }
        }
}
