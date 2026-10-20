package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.BlobTooLargeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.listDirectoryEntries

class BlobStoreTests {
	@TempDir
	lateinit var dataDir: Path

	private val store by lazy { BlobStore(dataDir) }

	@Test
	fun `a blob is stored under blobs ab cd uuid with its size and sha256`() {
		val content = "Fictitious invoice content".toByteArray()

		val blob = store.write(content.inputStream())

		blob.key shouldMatch Regex("""[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f-]{36}""")
		blob.key.substring(0, 2) + blob.key.substring(3, 5) shouldBe blob.key.substringAfterLast('/').substring(0, 4)
		blob.size shouldBe content.size.toLong()
		blob.sha256 shouldBe HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
		store.path(blob.key) shouldBe dataDir.resolve("blobs").resolve(blob.key)
		Files.readAllBytes(store.path(blob.key)) shouldBe content
		store.open(blob.key).use { it.readAllBytes() } shouldBe content
		store.root
			.resolve("tmp")
			.listDirectoryEntries()
			.shouldBeEmpty()
	}

	@Test
	fun `an empty blob and a multi-buffer blob are stored`() {
		store.write(ByteArray(0).inputStream()).size shouldBe 0
		val big = ByteArray(300_000) { (it % 251).toByte() }
		val blob = store.write(big.inputStream())
		blob.size shouldBe big.size.toLong()
		Files.readAllBytes(store.path(blob.key)) shouldBe big
	}

	@Test
	fun `content above the maximum size is rejected and leaves no file behind`() {
		shouldThrow<BlobTooLargeException> { store.write(ByteArray(11).inputStream(), maxSize = 10) }

		store.root
			.resolve("tmp")
			.listDirectoryEntries()
			.shouldBeEmpty()
		store.root.listDirectoryEntries().map { it.fileName.toString() } shouldBe listOf("tmp")
		store.write(ByteArray(10).inputStream(), maxSize = 10).size shouldBe 10
	}

	@Test
	fun `delete removes the blob and reports a missing one`() {
		val blob = store.write("x".byteInputStream())

		store.delete(blob.key) shouldBe true
		store.exists(blob.key) shouldBe false
		store.delete(blob.key) shouldBe false
		shouldThrow<NoSuchFileException> { store.open(blob.key) }
	}

	@Test
	fun `keys that are not storage keys are rejected, so no path escapes the blob folder`() {
		listOf(
			"../../etc/passwd",
			"ab/cd/../../../x",
			"/etc/passwd",
			"tmp/blob-1.part",
			"ab/cd/abcd0000-0000-4000-8000-00000000000",
			"aa/bb/abcd0000-0000-4000-8000-000000000000",
			"",
		).forEach { key ->
			BlobStore.isValidKey(key) shouldBe false
			shouldThrow<IllegalArgumentException> { store.path(key) }
		}
		BlobStore.isValidKey("ab/cd/abcd0000-0000-4000-8000-000000000000") shouldBe true
	}
}
