package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.domain.UploadService
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.ChunkStore
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import kotlin.io.path.walk

/**
 * Resumable uploads at the service level (task files-api): expiry of abandoned uploads with a controlled clock, and
 * that completing, aborting or failing an upload leaves no chunks or blobs behind. Same context as
 * [FilesConcurrencyTests].
 */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class UploadServiceTests(
	@Autowired private val uploads: UploadService,
	@Autowired private val chunks: ChunkStore,
	@Autowired private val blobs: BlobStore,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from search_document where item_type in ('FILE', 'FOLDER')")
		jdbc.execute("delete from upload_session")
		jdbc.execute("delete from file")
		clock.set(MutableClockTestConfiguration.START)
	}

	private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

	private fun chunkDirExists(id: java.util.UUID) = Files.isDirectory(chunks.root.resolve(id.toString()))

	private fun blobFiles(): Set<String> {
		if (!Files.isDirectory(blobs.root)) return emptySet()
		return blobs.root
			.walk()
			.filter { Files.isRegularFile(it) && !blobs.root.relativize(it).startsWith("tmp") }
			.map { blobs.root.relativize(it).toString() }
			.toSet()
	}

	@Test
	fun `an upload older than 7 days is deleted with its chunks when the next upload is created`() {
		val old = uploads.create("abandoned.bin", null, null, 3)
		uploads.putChunk(old.id, 0, "abc".byteInputStream())
		chunkDirExists(old.id) shouldBe true

		// Exactly 7 days old: still kept.
		clock.advance(UploadService.EXPIRY)
		val recent = uploads.create("recent.bin", null, null, 1)
		uploads.status(old.id).receivedChunks shouldContainExactly listOf(0)

		clock.advance(Duration.ofSeconds(1))
		uploads.create("trigger.bin", null, null, 1)

		shouldThrow<NotFoundException> { uploads.status(old.id) }
		shouldThrow<NotFoundException> { uploads.complete(old.id, sha256("abc".toByteArray())) }
		chunkDirExists(old.id) shouldBe false
		uploads.status(recent.id).session.name shouldBe "recent.bin"
	}

	@Test
	fun `an expired upload is not deleted by anything but creating an upload`() {
		val old = uploads.create("idle.bin", null, null, 3)
		clock.advance(UploadService.EXPIRY.plusDays(1))
		uploads.putChunk(old.id, 0, "abc".byteInputStream())
		// Expiry is lazy (no scheduler): the stale upload still completes until someone creates a new one.
		uploads.complete(old.id, sha256("abc".toByteArray())).size shouldBe 3
	}

	@Test
	fun `completing removes the session and its chunks, aborting too`() {
		val content = "Fictitious ledger".toByteArray()
		val done = uploads.create("ledger.txt", null, "text/plain", content.size.toLong())
		uploads.putChunk(done.id, 0, content.inputStream())
		val file = uploads.complete(done.id, sha256(content))
		file.mime shouldBe "text/plain"
		chunkDirExists(done.id) shouldBe false

		val aborted = uploads.create("x.txt", null, null, 1)
		uploads.putChunk(aborted.id, 0, "x".byteInputStream())
		uploads.abort(aborted.id)
		chunkDirExists(aborted.id) shouldBe false
		jdbc.queryForObject("select count(*) from upload_session", Long::class.java) shouldBe 0
	}

	@Test
	fun `a hash mismatch rolls back the assembled blob and keeps the chunks for a retry`() {
		val before = blobFiles()
		val content = "Fictitious contract".toByteArray()
		val session = uploads.create("contract.txt", null, null, content.size.toLong())
		uploads.putChunk(session.id, 0, content.inputStream())

		val error = shouldThrow<InvalidRequestException> { uploads.complete(session.id, sha256("other".toByteArray())) }
		error.fieldErrors.map { it.field } shouldContainExactly listOf("sha256")
		blobFiles() shouldBe before
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0
		uploads.status(session.id).receivedChunks shouldContainExactly listOf(0)
	}

	@Test
	fun `a chunk whose upload ends while it is written is not left on disk`() {
		val session = uploads.create("race.bin", null, null, 3)
		// The upload ends (as a concurrent abort or complete would) after the chunk was read, before it is moved
		// into place, and its after-commit cleanup has already run.
		val content =
			object : InputStream() {
				private val bytes = "abc".byteInputStream()

				override fun read(): Int = bytes.read().also { if (it < 0) endUpload(session.id) }

				override fun read(
					b: ByteArray,
					off: Int,
					len: Int,
				): Int = bytes.read(b, off, len).also { if (it < 0) endUpload(session.id) }
			}

		shouldThrow<NotFoundException> { uploads.putChunk(session.id, 0, content) }
		chunkDirExists(session.id) shouldBe false
	}

	@Test
	fun `a chunk write whose directory an abort deletes is a not found, without leftovers`() {
		val session = uploads.create("race.bin", null, null, 3)
		// A real abort during the write: its cleanup also deletes the chunk's temporary file.
		val content =
			object : InputStream() {
				private val bytes = "abc".byteInputStream()

				override fun read(): Int = bytes.read().also { if (it < 0) abortOnce(session.id) }

				override fun read(
					b: ByteArray,
					off: Int,
					len: Int,
				): Int = bytes.read(b, off, len).also { if (it < 0) abortOnce(session.id) }
			}

		shouldThrow<NotFoundException> { uploads.putChunk(session.id, 0, content) }
		chunkDirExists(session.id) shouldBe false
	}

	private fun endUpload(id: UUID) {
		jdbc.update("delete from upload_session where id = ?", id)
	}

	private fun abortOnce(id: UUID) {
		if (jdbc.queryForObject("select count(*) from upload_session where id = ?", Long::class.java, id) == 1L) uploads.abort(id)
	}

	@Test
	fun `complete verifies the size too and reports it with a hash mismatch, storing nothing`() {
		val before = blobFiles()
		val session = uploads.create("ledger.csv", null, null, 3)
		// Only a damaged chunk on disk can have another length than the upload expects: the API checks each chunk.
		chunks.write(session.id, 0, "abcd".byteInputStream(), 4)

		val sizeOnly = shouldThrow<InvalidRequestException> { uploads.complete(session.id, sha256("abcd".toByteArray())) }
		sizeOnly.fieldErrors.map { it.field } shouldContainExactly listOf("size")
		val both = shouldThrow<InvalidRequestException> { uploads.complete(session.id, sha256("abc".toByteArray())) }
		both.fieldErrors.map { it.field } shouldContainExactly listOf("size", "sha256")

		blobFiles() shouldBe before
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0
		// The upload stays open: the damaged chunk can be sent again and the upload completed.
		uploads.putChunk(session.id, 0, "abc".byteInputStream())
		uploads.complete(session.id, sha256("abc".toByteArray())).size shouldBe 3
	}

	@Test
	fun `a chunk whose stream breaks off is not stored and the upload stays open for a resend`() {
		val session = uploads.create("interrupted.bin", null, null, 3)
		val broken =
			object : InputStream() {
				override fun read(): Int = throw java.io.IOException("connection reset (fictitious)")
			}

		// The session still exists, so the I/O error is the client's broken stream, not a concurrent end of the upload.
		shouldThrow<java.io.IOException> { uploads.putChunk(session.id, 0, broken) }
		uploads.status(session.id).receivedChunks.shouldBeEmpty()

		uploads.putChunk(session.id, 0, "abc".byteInputStream())
		uploads.status(session.id).receivedChunks shouldContainExactly listOf(0)
	}

	@Test
	fun `status ignores chunk files beyond the upload's chunk count`() {
		val session = uploads.create("a.bin", null, null, 2)
		chunks.write(session.id, 5, "zz".byteInputStream(), 2)
		uploads.status(session.id).receivedChunks.shouldBeEmpty()
	}
}
