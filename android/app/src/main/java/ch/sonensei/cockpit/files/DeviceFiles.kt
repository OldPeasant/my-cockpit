package ch.sonensei.cockpit.files

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A file picked with the system picker; read only while it is uploaded. */
internal class PickedUpload(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val mime: String,
    override val sizeBytes: Long?,
) : UploadSource {
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
): UploadSource {
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
    return PickedUpload(resolver, uri, name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "upload", mime, size)
}

/** Writes into a document the user chose with the system "save as" picker (`null` when it cannot be opened). */
internal fun openForWriting(
    resolver: ContentResolver,
    uri: Uri,
): OutputStream? =
    try {
        resolver.openOutputStream(uri, "wt")
    } catch (_: SecurityException) {
        null
    } catch (_: FileNotFoundException) {
        null
    }

/** Its own class: a manifest can declare `FileProvider` itself only once (the notes' camera photos use it). */
class OpenedFilesProvider : FileProvider()

/**
 * Files opened in another app:downloaded into a cache folder that only this app's FileProvider shares, and handed
 * over with a read grant for that one file. Only the file opened last is kept.
 */
object OpenedFiles {
    /** Matches the provider declared in the manifest. */
    fun authority(context: Context) = "${context.packageName}.files"

    /** The cache file for [id], named like the drive file so the other app shows its name; earlier ones are deleted. */
    fun target(
        context: Context,
        id: UUID,
        name: String,
    ): File {
        val root = File(context.cacheDir, DIR)
        root.listFiles()?.filter { it.name != id.toString() }?.forEach { it.deleteRecursively() }
        val dir = File(root, id.toString()).apply { mkdirs() }
        return File(dir, safeName(name))
    }

    /** `ACTION_VIEW` of [file] with the drive's [mime] type, readable by the app that answers. */
    fun view(
        context: Context,
        file: File,
        mime: String,
    ): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(context, authority(context), file), viewType(mime))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Starts [intent]; `false` when no app on the phone opens it. */
    fun launch(
        context: Context,
        intent: Intent,
    ): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    /** A file name without path separators or characters file systems refuse; never empty, `.` or `..`. */
    internal fun safeName(name: String): String {
        val cleaned =
            name
                .map { c -> if (c in FORBIDDEN || c.code < SPACE) '_' else c }
                .joinToString("")
                .trim()
                .take(MAX_NAME)
        return cleaned.takeUnless { it.isEmpty() || it.all { c -> c == '.' } } ?: "file"
    }

    /** The mime type without parameters (`text/plain; charset=utf-8` → `text/plain`); any type when it is not a valid one. */
    internal fun viewType(mime: String): String =
        mime
            .substringBefore(';')
            .trim()
            .lowercase()
            .takeIf { Regex("^[a-z0-9.+-]+/[a-z0-9.+*-]+$").matches(it) } ?: "*/*"

    private const val DIR = "files-open"
    private const val FORBIDDEN = "/\\:*?\"<>|"
    private const val SPACE = 0x20
    private const val MAX_NAME = 120
}
