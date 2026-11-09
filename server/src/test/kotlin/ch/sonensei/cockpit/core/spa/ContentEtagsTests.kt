package ch.sonensei.cockpit.core.spa

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.AbstractResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.UrlResource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** [ContentEtags] on its own, without a Spring context. */
class ContentEtagsTests {
	private val etags = ContentEtags(cacheDigests = false)
	private val cached = ContentEtags(cacheDigests = true)

	private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

	@Test
	fun `etag is the lowercase hex sha-256 of the content`(
		@TempDir dir: Path,
	) {
		val abc = Files.writeString(dir.resolve("abc.txt"), "abc")
		val empty = Files.writeString(dir.resolve("empty.txt"), "")

		// Known SHA-256 test vectors.
		etags.etag(FileSystemResource(abc)) shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
		etags.etag(FileSystemResource(empty)) shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	}

	@Test
	fun `etag is stable for the same content and differs between contents`(
		@TempDir dir: Path,
	) {
		val first = Files.writeString(dir.resolve("a.js"), "console.log('fixture')")
		val second = Files.writeString(dir.resolve("b.js"), "console.log('fixture')")
		val other = Files.writeString(dir.resolve("c.js"), "console.log('other')")

		etags.etag(FileSystemResource(first)) shouldBe etags.etag(FileSystemResource(first))
		// A fresh instance (another server start) computes the same value.
		ContentEtags(cacheDigests = false).etag(FileSystemResource(first)) shouldBe etags.etag(FileSystemResource(first))
		etags.etag(FileSystemResource(second)) shouldBe etags.etag(FileSystemResource(first))
		etags.etag(FileSystemResource(other)) shouldNotBe etags.etag(FileSystemResource(first))
	}

	@Test
	fun `content larger than the read buffer is hashed completely`(
		@TempDir dir: Path,
	) {
		// Several 8 KiB buffers plus a partial one; a change in the last byte must change the etag.
		val bytes = ByteArray(3 * 8192 + 17) { (it % 251).toByte() }
		val file = Files.write(dir.resolve("chunk-BIG.js"), bytes)
		val before = etags.etag(FileSystemResource(file))
		before shouldBe sha256(bytes)

		bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
		Files.write(file, bytes)
		etags.etag(FileSystemResource(file)) shouldBe sha256(bytes)
		etags.etag(FileSystemResource(file)) shouldNotBe before
	}

	@Test
	fun `file on disk changed in place with same size and timestamp gets a new etag`(
		@TempDir dir: Path,
	) {
		val time = FileTime.from(Instant.parse("1980-02-01T00:00:00Z"))
		val index = dir.resolve("index.html")
		Files.writeString(index, "<title>build-1</title>")
		Files.setLastModifiedTime(index, time)
		val before = etags.etag(FileSystemResource(index))

		Files.writeString(index, "<title>build-2</title>")
		Files.setLastModifiedTime(index, time)

		etags.etag(FileSystemResource(index)) shouldNotBe before
		etags.etag(FileSystemResource(index)) shouldBe sha256("<title>build-2</title>".toByteArray())
	}

	@Test
	fun `entry inside a jar gets the content digest`(
		@TempDir dir: Path,
	) {
		val html = "<!doctype html><title>Cockpit jar fixture</title>"
		val jar = dir.resolve("app.jar")
		JarOutputStream(Files.newOutputStream(jar)).use { out ->
			out.putNextEntry(JarEntry("static/index.html"))
			out.write(html.toByteArray())
			out.closeEntry()
		}
		val entry = UrlResource(URI.create("jar:${jar.toUri()}!/static/index.html"))
		entry.isFile shouldBe false

		etags.etag(entry) shouldBe sha256(html.toByteArray())
	}

	@Test
	fun `with caching a jar entry is read once and its digest cached per url`() {
		val resource = InMemoryResource("jar:file:/fictitious/app.jar!/static/index.html", "<title>build-1</title>")
		val first = cached.etag(resource)
		first shouldBe sha256("<title>build-1</title>".toByteArray())

		// The jar does not change while the server runs, so even changed bytes are not read again.
		resource.content = "<title>build-2</title>"
		cached.etag(resource) shouldBe first
		resource.reads shouldBe 1

		// Another URL has its own entry; a new instance (a restart after a deploy) sees the new content.
		val other = InMemoryResource("jar:file:/fictitious/app.jar!/static/main-ABC123.js", "console.log('x')")
		cached.etag(other) shouldBe sha256("console.log('x')".toByteArray())
		other.reads shouldBe 1
		ContentEtags(cacheDigests = true).etag(resource) shouldBe sha256("<title>build-2</title>".toByteArray())
	}

	@Test
	fun `with caching a real file (the unpacked jar of the image) is read once and stays content based`(
		@TempDir dir: Path,
	) {
		val index = CountingFileResource(Files.writeString(dir.resolve("index.html"), "<title>build-1</title>"))
		val bundle = CountingFileResource(Files.writeString(dir.resolve("main-ABC123.js"), "console.log('x')"))
		index.isFile shouldBe true

		val first = cached.etag(index)
		first shouldBe sha256("<title>build-1</title>".toByteArray())
		cached.etag(index) shouldBe first
		index.reads shouldBe 1

		cached.etag(bundle) shouldBe sha256("console.log('x')".toByteArray())
		cached.etag(bundle) shouldNotBe first
		bundle.reads shouldBe 1
	}

	@Test
	fun `without caching a file is read on every request`(
		@TempDir dir: Path,
	) {
		val file = Files.writeString(dir.resolve("index.html"), "<title>dev</title>")
		val resource = CountingFileResource(file)
		etags.etag(resource)
		etags.etag(resource)
		resource.reads shouldBe 2
	}

	@Test
	fun `without caching a non-file resource is read on every request`() {
		val resource = InMemoryResource("jar:file:/fictitious/app.jar!/static/index.html", "<title>build-1</title>")
		etags.etag(resource)
		resource.content = "<title>build-2</title>"
		etags.etag(resource) shouldBe sha256("<title>build-2</title>".toByteArray())
		resource.reads shouldBe 2
	}

	@Test
	fun `read error propagates and is not cached`() {
		val resource = InMemoryResource("jar:file:/fictitious/app.jar!/static/index.html", "<title>ok</title>")
		resource.failing = true
		shouldThrow<IOException> { cached.etag(resource) }

		// A later successful read computes and caches the digest instead of a stale failure.
		resource.failing = false
		cached.etag(resource) shouldBe sha256("<title>ok</title>".toByteArray())
	}

	/** A non-file resource (like a boot jar entry) that counts how often its content is read. */
	private class InMemoryResource(
		private val url: String,
		var content: String,
	) : AbstractResource() {
		var reads = 0
		var failing = false

		override fun getDescription(): String = "in-memory $url"

		override fun getURL(): URL = URI.create(url).toURL()

		override fun isFile(): Boolean = false

		override fun getInputStream(): InputStream {
			reads++
			if (failing) throw IOException("fictitious read failure")
			return ByteArrayInputStream(content.toByteArray())
		}
	}

	private class CountingFileResource(
		path: Path,
	) : FileSystemResource(path) {
		var reads = 0

		override fun getInputStream(): InputStream {
			reads++
			return super.getInputStream()
		}
	}
}
