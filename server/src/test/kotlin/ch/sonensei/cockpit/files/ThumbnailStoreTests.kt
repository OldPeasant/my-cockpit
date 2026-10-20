package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.ThumbnailFormat
import ch.sonensei.cockpit.files.infra.ThumbnailStore
import ch.sonensei.cockpit.files.infra.UndecodableImageException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

class ThumbnailStoreTests {
	@TempDir
	lateinit var dataDir: Path

	private val blobs by lazy { BlobStore(dataDir) }
	private val store by lazy { ThumbnailStore(dataDir, blobs) }

	private fun blob(bytes: ByteArray): String = blobs.write(bytes.inputStream()).key

	private fun tempFiles() = store.root.resolve("tmp").listDirectoryEntries()

	private fun png(
		width: Int,
		height: Int,
	): ByteArray {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val g = image.createGraphics()
		g.color = Color.BLUE
		g.fillRect(0, 0, width, height)
		g.dispose()
		return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) shouldBe true }.toByteArray()
	}

	/** A PNG that only has a signature and an IHDR claiming [width] x [height] pixels (no pixel data). */
	private fun pngHeaderOnly(
		width: Int,
		height: Int,
	) = PreviewEdgeCaseWebTests.pngHeaderOnly(width, height)

	@Test
	fun `an image above 100 megapixels is rejected from its header without decoding`() {
		// 10000 x 10001 = 100,010,000 pixels: decoding would need ~400 MB of heap.
		val key = blob(pngHeaderOnly(10_000, 10_001))

		val e = shouldThrow<UndecodableImageException> { store.thumbnail(key, 256, ThumbnailFormat.PNG) }
		e.message!! shouldContain "more than ${ThumbnailStore.MAX_PIXELS} pixels"
		val cached = store.root.resolve("256/$key.png")
		cached.isRegularFile() shouldBe false
	}

	@Test
	fun `an image just below the pixel limit passes the size guard`() {
		// 9999 x 10000 = 99,990,000 pixels: allowed by the guard, then fails only because it has no pixel data.
		val key = blob(pngHeaderOnly(9_999, 10_000))

		val e = shouldThrow<UndecodableImageException> { store.thumbnail(key, 256, ThumbnailFormat.PNG) }
		e.message shouldBe "The image could not be decoded"
	}

	@Test
	fun `a huge image header with an overflowing pixel count is still rejected`() {
		// 65535 x 65535 multiplied as Int would overflow; the guard must multiply as Long.
		val key = blob(pngHeaderOnly(65_535, 65_535))

		shouldThrow<UndecodableImageException> { store.thumbnail(key, 1024, ThumbnailFormat.PNG) }.message!! shouldContain "pixels"
	}

	@Test
	fun `content that is no image format is undecodable and leaves no temporary file`() {
		val key = blob("Fictitious plain text, not an image".toByteArray())

		shouldThrow<UndecodableImageException> { store.thumbnail(key, 256, ThumbnailFormat.JPEG) }
		tempFiles().shouldBeEmpty()
	}

	@Test
	fun `an image with a valid signature but a truncated header is undecodable and caches nothing`() {
		// The PNG signature selects the PNG reader, but the IHDR chunk is cut off, so reading the size fails.
		val key = blob(png(40, 20).copyOfRange(0, 14))

		val e = shouldThrow<UndecodableImageException> { store.thumbnail(key, 256, ThumbnailFormat.PNG) }
		e.message shouldBe "The image could not be decoded"
		store.root.resolve("256/$key.png").isRegularFile() shouldBe false
		tempFiles().shouldBeEmpty()
	}

	@Test
	fun `a png thumbnail keeps transparency`() {
		val image = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB) // fully transparent
		val g = image.createGraphics()
		g.color = Color.RED
		g.fillRect(0, 0, 256, 512) // left half opaque
		g.dispose()
		val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
		val key = blob(bytes)

		val thumbnail = ImageIO.read(store.thumbnail(key, 256, ThumbnailFormat.PNG).toFile())
		(thumbnail.width to thumbnail.height) shouldBe (256 to 256)
		(thumbnail.getRGB(10, 128) ushr 24) shouldBe 0xFF
		(thumbnail.getRGB(245, 128) ushr 24) shouldBe 0
	}

	@Test
	fun `concurrent requests for the same thumbnail all get the same complete file`() {
		val key = blob(png(1600, 800))
		val threads = 8
		val pool = Executors.newFixedThreadPool(threads)
		val start = CountDownLatch(1)
		try {
			val results =
				(1..threads).map {
					pool.submit<Path> {
						start.await()
						store.thumbnail(key, 256, ThumbnailFormat.PNG)
					}
				}
			start.countDown()
			val paths = results.map { it.get(60, TimeUnit.SECONDS) }

			paths.toSet().size shouldBe 1
			val image = ImageIO.read(paths[0].toFile())
			(image.width to image.height) shouldBe (256 to 128)
			tempFiles().shouldBeEmpty()
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `delete removes every size and format and tolerates missing thumbnails`() {
		val key = blob(png(300, 300))
		val made =
			listOf(
				store.thumbnail(key, 256, ThumbnailFormat.PNG),
				store.thumbnail(key, 1024, ThumbnailFormat.PNG),
				store.thumbnail(key, 256, ThumbnailFormat.JPEG),
			)
		made.forEach { it.isRegularFile() shouldBe true }

		store.delete(key)
		made.forEach { Files.exists(it) shouldBe false }
		store.delete(key) // nothing left: no exception
	}

	@Test
	fun `temporary files left by an interrupted render are removed at startup, cached thumbnails are kept`() {
		val key = blob(png(300, 300))
		val cached = store.thumbnail(key, 256, ThumbnailFormat.PNG)
		val leftover = Files.write(store.root.resolve("tmp/thumb-123.png"), byteArrayOf(1, 2, 3))

		ThumbnailStore(dataDir, blobs)

		Files.exists(leftover) shouldBe false
		cached.isRegularFile() shouldBe true
	}

	@Test
	fun `storage keys that are not blob keys are refused`() {
		shouldThrow<IllegalArgumentException> { store.thumbnail("../../etc/passwd", 256, ThumbnailFormat.PNG) }
		shouldThrow<IllegalArgumentException> { store.delete("../x") }
	}
}
