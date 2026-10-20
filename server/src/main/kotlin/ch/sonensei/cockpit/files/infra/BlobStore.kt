package ch.sonensei.cockpit.files.infra

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** A blob written by [BlobStore.write]: its storage key, size in bytes and lowercase hex SHA-256. */
data class StoredBlob(
	val key: String,
	val size: Long,
	val sha256: String,
)

data class DiskSpace(
	val total: Long,
	val usable: Long,
)

/** The content was larger than the allowed maximum; nothing was stored. */
class BlobTooLargeException(
	val maxSize: Long,
) : RuntimeException("Content exceeds the maximum size of $maxSize bytes")

/**
 * File contents on disk (SPEC.md §8.2): each blob is `${COCKPIT_DATA_DIR}/blobs/ab/cd/<uuid>`, where `<uuid>` is a
 * random UUID and `ab`, `cd` its first two hex pairs (so directories stay small). The storage key is the relative
 * path `ab/cd/<uuid>`. Blobs are immutable: [write] streams into a temporary file below `blobs/tmp/`, syncs it and
 * moves it into place atomically, so a reader never sees a partial blob.
 */
@Component
class BlobStore(
	@Value("\${cockpit.data-dir}") dataDir: Path,
) {
	val root: Path = dataDir.toAbsolutePath().normalize().resolve("blobs")
	private val tmp: Path = root.resolve("tmp")

	/**
	 * Stores [content] (read to its end, not closed) as a new blob and returns its key, size and SHA-256.
	 *
	 * @throws BlobTooLargeException when [content] is longer than [maxSize] bytes
	 */
	fun write(
		content: InputStream,
		maxSize: Long = Long.MAX_VALUE,
	): StoredBlob {
		Files.createDirectories(tmp)
		val temp = Files.createTempFile(tmp, "blob-", ".part")
		try {
			val digest = MessageDigest.getInstance("SHA-256")
			var size = 0L
			FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
				val out = Channels.newOutputStream(channel)
				val buffer = ByteArray(BUFFER_SIZE)
				while (true) {
					val read = content.read(buffer)
					if (read < 0) break
					size += read
					if (size > maxSize) throw BlobTooLargeException(maxSize)
					digest.update(buffer, 0, read)
					out.write(buffer, 0, read)
				}
				channel.force(true)
			}
			val key = newKey()
			val target = path(key)
			Files.createDirectories(target.parent)
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
			return StoredBlob(key, size, HexFormat.of().formatHex(digest.digest()))
		} catch (e: Exception) {
			Files.deleteIfExists(temp)
			throw e
		}
	}

	/** Total and usable bytes of the file system that holds the blobs. */
	fun space(): DiskSpace {
		Files.createDirectories(root)
		val store = Files.getFileStore(root)
		return DiskSpace(store.totalSpace, store.usableSpace)
	}

	/** Opens the blob for reading. @throws java.nio.file.NoSuchFileException when it does not exist */
	fun open(key: String): InputStream = Files.newInputStream(path(key))

	fun exists(key: String): Boolean = Files.isRegularFile(path(key))

	/** Deletes the blob; `false` when it did not exist. */
	fun delete(key: String): Boolean = Files.deleteIfExists(path(key))

	/**
	 * The blob's path below [root].
	 *
	 * @throws IllegalArgumentException when [key] is not a storage key made by this store (e.g. `../x`)
	 */
	fun path(key: String): Path {
		require(isValidKey(key)) { "Not a blob storage key" }
		return root.resolve(key)
	}

	private fun newKey(): String {
		val uuid = UUID.randomUUID().toString()
		return "${uuid.substring(0, 2)}/${uuid.substring(2, 4)}/$uuid"
	}

	companion object {
		private const val BUFFER_SIZE = 64 * 1024
		private val KEY = Regex("""([0-9a-f]{2})/([0-9a-f]{2})/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""")

		fun isValidKey(key: String): Boolean {
			val match = KEY.matchEntire(key) ?: return false
			val (a, b, uuid) = match.destructured
			return uuid.startsWith(a + b)
		}
	}
}
