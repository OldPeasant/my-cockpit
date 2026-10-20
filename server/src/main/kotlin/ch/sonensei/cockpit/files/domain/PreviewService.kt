package ch.sonensei.cockpit.files.domain

import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.ThumbnailFormat
import ch.sonensei.cockpit.files.infra.ThumbnailStore
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

/** The file has no thumbnail or preview of this kind (its mime type is not supported). */
class PreviewNotSupportedException(
	message: String,
) : RuntimeException(message)

/** A rendered thumbnail: the cached image at [path] with its [mime] type. */
data class Thumbnail(
	val path: Path,
	val mime: String,
)

/** What `GET /files/{id}/preview` serves. */
sealed interface Preview {
	/** The file's name, for `Content-Disposition`. */
	val name: String

	/** The start of a text file, decoded as UTF-8; [truncated] when the file is longer than [PreviewService.TEXT_LIMIT]. */
	data class Text(
		val text: String,
		val mime: String,
		val truncated: Boolean,
		override val name: String,
	) : Preview

	/** A file the client previews itself (PDF, audio, video, images), served inline with its [mime] type. */
	data class Inline(
		val path: Path,
		val mime: String,
		override val name: String,
	) : Preview
}

/**
 * Previews of drive files (SPEC.md §8.2): server-side thumbnails of JPEG, PNG, WebP and GIF images, the first
 * [TEXT_LIMIT] bytes of text and Markdown files, and the contents of PDFs, audio, video and images for the client's
 * own viewer.
 */
@Service
class PreviewService(
	private val files: FilesService,
	private val blobs: BlobStore,
	private val thumbnails: ThumbnailStore,
) {
	/**
	 * The thumbnail of image file [id] whose longer side is at most [size] (one of [ThumbnailStore.SIZES]) pixels.
	 *
	 * @throws PreviewNotSupportedException when the file is not a JPEG, PNG, WebP or GIF image
	 * @throws ch.sonensei.cockpit.files.infra.UndecodableImageException when its contents are not a decodable image
	 */
	fun thumbnail(
		id: UUID,
		size: Int,
	): Thumbnail {
		require(size in ThumbnailStore.SIZES) { "size must be one of ${ThumbnailStore.SIZES}" }
		val file = files.file(id)
		val format =
			when (baseMime(file.mime)) {
				"image/jpeg" -> ThumbnailFormat.JPEG
				in THUMBNAIL_MIMES -> ThumbnailFormat.PNG
				else -> throw PreviewNotSupportedException("Thumbnails exist only for JPEG, PNG, WebP and GIF images")
			}
		return Thumbnail(thumbnails.thumbnail(file.storageKey, size, format), format.mime)
	}

	/** @throws PreviewNotSupportedException when the file is neither text nor a type the client previews itself */
	fun preview(id: UUID): Preview {
		val file = files.file(id)
		val mime = baseMime(file.mime)
		return when {
			isText(mime) -> text(file, if (mime == MARKDOWN) MARKDOWN else PLAIN)
			mime == "application/pdf" || mime.startsWith("audio/") || mime.startsWith("video/") || mime in THUMBNAIL_MIMES ->
				Preview.Inline(blobs.path(file.storageKey), mime, file.name)
			else -> throw PreviewNotSupportedException("No preview for files of type $mime")
		}
	}

	private fun text(
		file: FileItem,
		mime: String,
	): Preview.Text {
		val bytes = blobs.open(file.storageKey).use { it.readNBytes(TEXT_LIMIT + 1) }
		val truncated = bytes.size > TEXT_LIMIT
		val length = if (truncated) utf8Boundary(bytes, TEXT_LIMIT) else bytes.size
		return Preview.Text(decodeUtf8(bytes, length), mime, truncated, file.name)
	}

	companion object {
		/** Text previews show at most this many bytes (256 KB) of the file. */
		const val TEXT_LIMIT = 256 * 1024

		private const val MARKDOWN = "text/markdown"
		private const val PLAIN = "text/plain"
		private val THUMBNAIL_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
		private val TEXT_APPLICATION_MIMES =
			setOf("application/json", "application/xml", "application/yaml", "application/x-yaml", "application/x-sh")

		/** The lowercase mime type without parameters, e.g. `text/plain` for `Text/Plain; charset=UTF-8`. */
		internal fun baseMime(mime: String): String = mime.substringBefore(';').trim().lowercase(Locale.ROOT)

		internal fun isText(mime: String): Boolean = mime.startsWith("text/") || mime in TEXT_APPLICATION_MIMES

		/**
		 * The largest length `<= limit` that does not cut a UTF-8 sequence of [bytes] in two: when the sequence starting
		 * before [limit] would end after it, the cut moves to its start.
		 */
		internal fun utf8Boundary(
			bytes: ByteArray,
			limit: Int,
		): Int {
			var start = limit
			// Step back over at most three continuation bytes (10xxxxxx) to the lead byte of the last sequence.
			while (start > 0 && limit - start < 3 && bytes[start - 1].toInt() and 0xC0 == 0x80) start--
			if (start == 0) return limit
			val lead = bytes[start - 1].toInt() and 0xFF
			val length =
				when {
					lead and 0xE0 == 0xC0 -> 2
					lead and 0xF0 == 0xE0 -> 3
					lead and 0xF8 == 0xF0 -> 4
					else -> 1
				}
			return if (start - 1 + length > limit) start - 1 else limit
		}

		/** Decodes the first [length] bytes as UTF-8; invalid bytes become U+FFFD. */
		internal fun decodeUtf8(
			bytes: ByteArray,
			length: Int,
		): String =
			Charsets.UTF_8
				.newDecoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE)
				.decode(ByteBuffer.wrap(bytes, 0, length))
				.toString()
	}
}
