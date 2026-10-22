package ch.sonensei.cockpit.files

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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

/**
 * Deletes the document the "save as" picker created for a save that failed, so no empty or cut-off copy stays where
 * the user chose. Best effort: a provider that refuses keeps it.
 */
internal fun discardDocument(
    resolver: ContentResolver,
    uri: Uri,
) {
    try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: Exception) {
        // Nothing more to do: the banner already says the file was not saved.
    }
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

    /**
     * A file name without path separators or characters file systems refuse; never empty, `.` or `..`. A long one is
     * cut to at most [MAX_NAME_BYTES] UTF-8 bytes (file systems count bytes) between characters, keeping its extension.
     */
    internal fun safeName(name: String): String {
        val cleaned =
            name
                .map { c -> if (c in FORBIDDEN || c.code < SPACE) '_' else c }
                .joinToString("")
                .trim()
        val fitted = fitBytes(cleaned)
        return fitted.takeUnless { it.isEmpty() || it.all { c -> c == '.' } } ?: "file"
    }

    private fun fitBytes(name: String): String {
        if (utf8Size(name) <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= MAX_EXTENSION) name.substring(dot) else ""
        val stem = name.substring(0, name.length - extension.length)
        return prefixOfBytes(stem, MAX_NAME_BYTES - utf8Size(extension)).trimEnd() + extension
    }

    /** The longest start of [text] of at most [max] UTF-8 bytes that does not split a character. */
    private fun prefixOfBytes(
        text: String,
        max: Int,
    ): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            bytes += utf8Size(codePoint)
            if (bytes > max) break
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    private fun utf8Size(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            bytes += utf8Size(codePoint)
            i += Character.charCount(codePoint)
        }
        return bytes
    }

    // A lone surrogate counts as 3 bytes, more than the `?` it is written as.
    private fun utf8Size(codePoint: Int) =
        when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
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
    private const val MAX_NAME_BYTES = 200
    private const val MAX_EXTENSION = 16
}
