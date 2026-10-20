package ch.sonensei.cockpit.files.infra

import net.coobird.thumbnailator.Thumbnails
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/** Output format of a thumbnail: JPEG for JPEG sources, PNG (keeps transparency) for the others. */
enum class ThumbnailFormat(
	val mime: String,
	val extension: String,
) {
	JPEG("image/jpeg", "jpg"),
	PNG("image/png", "png"),
}

/** The image could not be decoded (corrupt, an unsupported variant, or more than [ThumbnailStore.MAX_PIXELS] pixels). */
class UndecodableImageException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Thumbnails of image blobs (SPEC.md §8.2), made with Thumbnailator and cached on disk under
 * `${COCKPIT_DATA_DIR}/thumbnails/<size>/<storage key>.<jpg|png>`. Blobs are immutable, so a cached thumbnail never
 * goes stale; [delete] removes them when the blob is purged. The longer side is scaled to the size (never up), and the
 * EXIF orientation of JPEGs is applied. A thumbnail is written to a temporary file and moved into place atomically,
 * so concurrent requests for the same thumbnail at worst both render it.
 */
@Component
class ThumbnailStore(
	@Value("\${cockpit.data-dir}") dataDir: Path,
	private val blobs: BlobStore,
) {
	val root: Path = dataDir.toAbsolutePath().normalize().resolve("thumbnails")
	private val tmp: Path = root.resolve("tmp")

	init {
		// Temporary files left behind by renders that were interrupted when the process stopped.
		if (Files.isDirectory(tmp)) Files.list(tmp).use { entries -> entries.forEach(Files::deleteIfExists) }
	}

	/**
	 * The cached thumbnail of the blob [storageKey] whose longer side is at most [size] pixels, rendered first if needed.
	 *
	 * @throws UndecodableImageException when the blob is not a decodable image
	 * @throws NoSuchFileException when the blob was deleted while the thumbnail was rendered
	 */
	fun thumbnail(
		storageKey: String,
		size: Int,
		format: ThumbnailFormat,
	): Path {
		val target = path(storageKey, size, format)
		if (Files.isRegularFile(target)) return target
		val source = blobs.path(storageKey)
		Files.createDirectories(tmp)
		val temp = Files.createTempFile(tmp, "thumb-", ".${format.extension}")
		try {
			render(source, size, format, temp)
			Files.createDirectories(target.parent)
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
			// The blob was purged while rendering: its [delete] already ran, so remove the thumbnail it could not see.
			if (!Files.exists(source)) {
				Files.deleteIfExists(target)
				throw NoSuchFileException(source.toString())
			}
		} finally {
			Files.deleteIfExists(temp)
		}
		return target
	}

	/** Deletes every cached thumbnail of the blob [storageKey]. */
	fun delete(storageKey: String) {
		for (size in SIZES) {
			for (format in ThumbnailFormat.entries) Files.deleteIfExists(path(storageKey, size, format))
		}
	}

	private fun path(
		storageKey: String,
		size: Int,
		format: ThumbnailFormat,
	): Path {
		require(BlobStore.isValidKey(storageKey)) { "Not a blob storage key" }
		return root.resolve(size.toString()).resolve("$storageKey.${format.extension}")
	}

	private fun render(
		source: Path,
		size: Int,
		format: ThumbnailFormat,
		target: Path,
	) {
		val longerSide = dimensions(source).let { (width, height) -> maxOf(width, height) }
		try {
			val builder = Thumbnails.of(source.toFile()).useExifOrientation(true)
			if (longerSide <= size) builder.scale(1.0) else builder.size(size, size)
			builder
				.outputFormat(format.extension)
				.apply { if (format == ThumbnailFormat.JPEG) outputQuality(JPEG_QUALITY) }
				.toFile(target.toFile())
		} catch (e: IOException) {
			throw UndecodableImageException("The image could not be decoded", e)
		} catch (e: IllegalArgumentException) {
			throw UndecodableImageException("The image could not be decoded", e)
		}
	}

	/** Width and height from the image header, without decoding the pixels (guards against decompression bombs). */
	private fun dimensions(source: Path): Pair<Int, Int> {
		val input = ImageIO.createImageInputStream(source.toFile()) ?: throw UndecodableImageException("The image could not be read")
		input.use {
			val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: throw UndecodableImageException("Not a supported image")
			try {
				reader.input = input
				val width = reader.getWidth(0)
				val height = reader.getHeight(0)
				if (width.toLong() * height > MAX_PIXELS) throw UndecodableImageException("The image has more than $MAX_PIXELS pixels")
				return width to height
			} catch (e: IOException) {
				throw UndecodableImageException("The image could not be decoded", e)
			} finally {
				reader.dispose()
			}
		}
	}

	companion object {
		/** The thumbnail sizes (longer side, pixels). */
		val SIZES = listOf(256, 1024)

		/** Images above this many pixels are not thumbnailed (decoding them would need gigabytes of heap). */
		const val MAX_PIXELS = 100_000_000L

		private const val JPEG_QUALITY = 0.85f
	}
}
