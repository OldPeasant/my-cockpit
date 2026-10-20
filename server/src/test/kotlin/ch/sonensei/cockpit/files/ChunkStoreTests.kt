package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.files.domain.UploadLimits
import ch.sonensei.cockpit.files.domain.UploadSession
import ch.sonensei.cockpit.files.infra.ChunkLengthException
import ch.sonensei.cockpit.files.infra.ChunkStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.util.unit.DataSize
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.listDirectoryEntries

/** Plain unit tests of the on-disk chunks of resumable uploads, and the chunk arithmetic of an upload session. */
class ChunkStoreTests {
	@TempDir
	lateinit var dataDir: Path

	private val store by lazy { ChunkStore(dataDir) }
	private val uploadId = UUID.fromString("01900000-0000-7000-8000-0000000000c1")
	private val otherId = UUID.fromString("01900000-0000-7000-8000-0000000000c2")

	private fun dir() = store.root.resolve(uploadId.toString())

	@Test
	fun `chunks are stored under uploads id n and read back in order as one stream`() {
		store.write(uploadId, 1, "def".byteInputStream(), 3)
		store.write(uploadId, 0, "abc".byteInputStream(), 3)
		store.write(uploadId, 2, "g".byteInputStream(), 1)

		store.root shouldBe dataDir.toAbsolutePath().normalize().resolve("uploads")
		Files.readString(dir().resolve("1")) shouldBe "def"
		store.received(uploadId) shouldContainExactly listOf(0, 1, 2)
		store.open(uploadId, 3).use { it.readAllBytes().decodeToString() } shouldBe "abcdefg"
		// No temporary files stay behind.
		dir().listDirectoryEntries().map { it.fileName.toString() }.sorted() shouldContainExactly listOf("0", "1", "2")
	}

	@Test
	fun `received sorts numerically and ignores files that are no chunk`() {
		for (n in listOf(10, 2, 0)) store.write(uploadId, n, ByteArray(1).inputStream(), 1)
		Files.writeString(dir().resolve("chunk-leftover.part"), "x")

		store.received(uploadId) shouldContainExactly listOf(0, 2, 10)
		store.received(otherId).shouldBeEmpty()
	}

	@Test
	fun `a too short or too long chunk is rejected and leaves nothing behind`() {
		shouldThrow<ChunkLengthException> { store.write(uploadId, 0, "ab".byteInputStream(), 3) }.expected shouldBe 3
		shouldThrow<ChunkLengthException> { store.write(uploadId, 0, "abcd".byteInputStream(), 3) }
		store.received(uploadId).shouldBeEmpty()
		dir().listDirectoryEntries().shouldBeEmpty()
	}

	@Test
	fun `an overlong stream is not read to its end`() {
		var read = 0L
		val endless =
			object : InputStream() {
				override fun read(): Int = 1.also { read++ }

				override fun read(
					b: ByteArray,
					off: Int,
					len: Int,
				): Int {
					b.fill(1, off, off + len)
					read += len
					return len
				}
			}
		shouldThrow<ChunkLengthException> { store.write(uploadId, 0, endless, 10) }
		read shouldBe 11
	}

	@Test
	fun `sending a chunk again replaces it, and a failed resend keeps the old one`() {
		store.write(uploadId, 0, "old".byteInputStream(), 3)
		store.write(uploadId, 0, "new".byteInputStream(), 3)
		Files.readString(dir().resolve("0")) shouldBe "new"

		shouldThrow<ChunkLengthException> { store.write(uploadId, 0, "x".byteInputStream(), 3) }
		Files.readString(dir().resolve("0")) shouldBe "new"
	}

	@Test
	fun `a failing input stream leaves no partial chunk`() {
		val broken =
			object : InputStream() {
				private val start = ByteArrayInputStream("ab".toByteArray())

				override fun read(): Int = start.read().takeIf { it >= 0 } ?: throw IOException("connection reset")
			}
		shouldThrow<IOException> { store.write(uploadId, 0, broken, 5) }
		dir().listDirectoryEntries().shouldBeEmpty()
	}

	@Test
	fun `an empty chunk list opens as an empty stream`() {
		store.open(uploadId, 0).use { it.readAllBytes() } shouldBe ByteArray(0)
	}

	@Test
	fun `delete removes the chunks of one upload only, and is a no-op for an unknown upload`() {
		store.write(uploadId, 0, "a".byteInputStream(), 1)
		store.write(otherId, 0, "b".byteInputStream(), 1)

		store.delete(uploadId)
		store.delete(UUID.fromString("01900000-0000-7000-8000-0000000000c3"))

		Files.exists(dir()) shouldBe false
		store.received(uploadId).shouldBeEmpty()
		store.received(otherId) shouldContainExactly listOf(0)
	}

	// --- UploadSession and UploadLimits

	@Test
	fun `a session splits its size into full chunks and a shorter last one`() {
		val session = UploadSession("a.bin", null, "application/octet-stream", 10, 4)
		session.chunkCount shouldBe 3
		(0 until 3).map(session::chunkLength) shouldContainExactly listOf(4L, 4L, 2L)
		shouldThrow<IllegalArgumentException> { session.chunkLength(3) }
		shouldThrow<IllegalArgumentException> { session.chunkLength(-1) }
	}

	@Test
	fun `a size that is a multiple of the chunk size has no short chunk, and an empty file has no chunks`() {
		UploadSession("a.bin", null, "application/octet-stream", 8, 4).let {
			it.chunkCount shouldBe 2
			it.chunkLength(1) shouldBe 4
		}
		UploadSession("b.bin", null, "application/octet-stream", 1, 4).chunkCount shouldBe 1
		UploadSession("c.bin", null, "application/octet-stream", 0, 4).chunkCount shouldBe 0
	}

	@Test
	fun `a 5 GB file in 8 MB chunks has 640 chunks`() {
		val limits = UploadLimits(DataSize.ofGigabytes(5), DataSize.ofMegabytes(8))
		val session = UploadSession("disk.img", null, "application/octet-stream", limits.maxSize, limits.chunkSize)
		session.chunkCount shouldBe 640
		session.chunkLength(639) shouldBe 8L * 1024 * 1024
	}

	@Test
	fun `upload limits must be positive and the chunk size must fit an int`() {
		shouldThrow<IllegalArgumentException> { UploadLimits(DataSize.ofBytes(0), DataSize.ofMegabytes(8)) }
		shouldThrow<IllegalArgumentException> { UploadLimits(DataSize.ofGigabytes(5), DataSize.ofBytes(0)) }
		shouldThrow<ArithmeticException> { UploadLimits(DataSize.ofGigabytes(5), DataSize.ofGigabytes(3)) }
	}
}
