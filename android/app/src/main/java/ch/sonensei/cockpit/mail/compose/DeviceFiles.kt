package ch.sonensei.cockpit.mail.compose

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/** A file picked with the system picker; read only when uploaded. */
internal class DeviceFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val mime: String,
    override val sizeBytes: Long?,
) : PickedFile {
    override fun open(): InputStream? =
        try {
            resolver.openInputStream(uri)
        } catch (_: SecurityException) {
            null
        } catch (_: FileNotFoundException) {
            null
        }
}

/** Name, type and size of the picked [uri] as its provider tells them; nothing is read yet. */
internal fun describePicked(
    resolver: ContentResolver,
    uri: Uri,
): PickedFile {
    var name: String? = null
    var size: Long? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                size = if (cursor.isNull(1)) null else cursor.getLong(1)
            }
        }
    }
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    return DeviceFile(resolver, uri, name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "attachment", mime, size)
}
