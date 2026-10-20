package ch.sonensei.cockpit.files.infra

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Enumeration
import java.util.UUID

/** A chunk did not have the length its upload expects; nothing was stored. */
class ChunkLengthException(
	val expected: Long,
) : RuntimeException("The chunk must have $expected bytes")

/**
 * The chunks of resumable uploads on disk: chunk `n` of upload `id` is `${COCKPIT_DATA_DIR}/uploads/<id>/<n>`. A chunk
 * is written to a temporary file next to it and moved into place atomically, so a chunk on disk is always complete;
 * sending a chunk again replaces it.
 */
@Component
class ChunkStore(
	@Value("\${cockpit.data-dir}") dataDir: Path,
) {
	val root: Path = dataDir.toAbsolutePath().normalize().resolve("uploads")

	/**
	 * Stores [content] (read to its end, or until it is longer than [length]) as chunk [n] of [uploadId].
	 *
	 * @throws ChunkLengthException when [content] is not exactly [length] bytes long
	 */
	fun write(
		uploadId: UUID,
		n: Int,
		content: InputStream,
		length: Long,
	) {
		val dir = dir(uploadId)
		Files.createDirectories(dir)
		val temp = Files.createTempFile(dir, "chunk-", ".part")
		try {
			val written = Files.newOutputStream(temp).use { out -> content.copyAtMost(out, length + 1) }
			if (written != length) throw ChunkLengthException(length)
			Files.move(temp, dir.resolve(n.toString()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (e: Exception) {
			Files.deleteIfExists(temp)
			throw e
		}
	}

	/** The numbers of the chunks of [uploadId] stored so far, in order. */
	fun received(uploadId: UUID): List<Int> {
		val dir = dir(uploadId)
		if (!Files.isDirectory(dir)) return emptyList()
		return Files.list(dir).use { paths -> paths.toList().mapNotNull { it.fileName.toString().toIntOrNull() }.sorted() }
	}

	/** The chunks `0 until count` of [uploadId] as one stream. All of them must exist. */
	fun open(
		uploadId: UUID,
		count: Int,
	): InputStream {
		val dir = dir(uploadId)
		val numbers = (0 until count).iterator()
		// Opens each chunk only when the previous one is read to its end (SequenceInputStream closes it then).
		return SequenceInputStream(
			object : Enumeration<InputStream> {
				override fun hasMoreElements() = numbers.hasNext()

				override fun nextElement(): InputStream = Files.newInputStream(dir.resolve(numbers.next().toString()))
			},
		)
	}

	/** Deletes every chunk of [uploadId]. */
	fun delete(uploadId: UUID) {
		val dir = dir(uploadId)
		if (!Files.isDirectory(dir)) return
		Files.list(dir).use { paths -> paths.toList().forEach(Files::deleteIfExists) }
		Files.deleteIfExists(dir)
	}

	private fun dir(uploadId: UUID): Path = root.resolve(uploadId.toString())

	private fun InputStream.copyAtMost(
		out: java.io.OutputStream,
		max: Long,
	): Long {
		val buffer = ByteArray(BUFFER_SIZE)
		var total = 0L
		while (total < max) {
			val read = read(buffer, 0, minOf(buffer.size.toLong(), max - total).toInt())
			if (read < 0) break
			out.write(buffer, 0, read)
			total += read
		}
		return total
	}

	private companion object {
		const val BUFFER_SIZE = 64 * 1024
	}
}
