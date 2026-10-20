package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.infra.ThumbnailStore
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import tools.jackson.databind.json.JsonMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class PreviewWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val files: FilesService,
	@Autowired private val thumbnails: ThumbnailStore,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from search_document where item_type in ('FILE', 'FOLDER')")
		jdbc.execute("delete from file")
	}

	private fun upload(
		name: String,
		content: ByteArray,
		mime: String,
	): String =
		mockMvc
			.multipart(FILES) {
				with(csrf())
				file(MockMultipartFile("file", name, mime, content))
			}.andExpect { status { isCreated() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)["id"]
			.asString()

	private fun thumbnail(
		id: String,
		size: Int? = null,
	) = mockMvc.get("$FILES/$id/thumbnail") { size?.let { param("size", it.toString()) } }

	private fun image(
		width: Int,
		height: Int,
		format: String,
	): ByteArray {
		val type = if (format == "jpeg") BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
		val image = BufferedImage(width, height, type)
		val g = image.createGraphics()
		g.color = Color.ORANGE
		g.fillRect(0, 0, width, height)
		g.dispose()
		return ByteArrayOutputStream().also { ImageIO.write(image, format, it) shouldBe true }.toByteArray()
	}

	private fun decoded(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

	// --- thumbnails

	@Test
	fun `png thumbnail scales the longer side to 256 by default and to 1024 on request`() {
		val id = upload("Fictitious plan.png", image(2000, 1000, "png"), "image/png")

		val small = thumbnail(id).andExpect { status { isOk() } }.andReturn().response
		small.contentType shouldBe "image/png"
		decoded(small.contentAsByteArray).let { (it.width to it.height) shouldBe (256 to 128) }

		val large = thumbnail(id, 1024).andExpect { status { isOk() } }.andReturn().response
		decoded(large.contentAsByteArray).let { (it.width to it.height) shouldBe (1024 to 512) }
	}

	@Test
	fun `jpeg thumbnail is a jpeg and a small image is not enlarged`() {
		val id = upload("Holiday.jpg", image(100, 60, "jpeg"), "image/jpeg")

		val response = thumbnail(id).andExpect { status { isOk() } }.andReturn().response
		response.contentType shouldBe "image/jpeg"
		decoded(response.contentAsByteArray).let { (it.width to it.height) shouldBe (100 to 60) }
	}

	@Test
	fun `jpeg thumbnail honors the EXIF orientation`() {
		// Orientation 6: the stored landscape pixels are shown rotated 90° clockwise, i.e. as portrait.
		val id = upload("Rotated.jpg", withExifOrientation(image(400, 200, "jpeg"), 6), "image/jpeg")

		val response = thumbnail(id).andExpect { status { isOk() } }.andReturn().response
		decoded(response.contentAsByteArray).let { (it.width to it.height) shouldBe (128 to 256) }
	}

	@Test
	fun `gif and webp images get png thumbnails`() {
		val gif = upload("Animation.gif", image(300, 300, "gif"), "image/gif")
		val webp = upload("Pixel.webp", Base64.getDecoder().decode(WEBP_1X1), "image/webp")

		thumbnail(gif).andExpect { status { isOk() } }.andReturn().response.let {
			it.contentType shouldBe "image/png"
			decoded(it.contentAsByteArray).width shouldBe 256
		}
		thumbnail(webp).andExpect { status { isOk() } }.andReturn().response.let {
			it.contentType shouldBe "image/png"
			decoded(it.contentAsByteArray).let { img -> (img.width to img.height) shouldBe (1 to 1) }
		}
	}

	@Test
	fun `thumbnails are cached on disk and deleted with the purged blob`() {
		val id = upload("Cached.png", image(600, 300, "png"), "image/png")
		thumbnail(id).andExpect { status { isOk() } }
		thumbnail(id, 1024).andExpect { status { isOk() } }
		val key = files.file(UUID.fromString(id)).storageKey
		val cached = ThumbnailStore.SIZES.map { thumbnails.root.resolve("$it/$key.png") }
		cached.forEach { it.isRegularFile() shouldBe true }

		// A second request serves the cached file unchanged.
		val modified = Files.getLastModifiedTime(cached[0])
		thumbnail(id).andExpect { status { isOk() } }
		Files.getLastModifiedTime(cached[0]) shouldBe modified

		files.deleteFile(UUID.fromString(id)) {}
		files.purgeFile(UUID.fromString(id)) shouldBe true
		cached.forEach { it.isRegularFile() shouldBe false }
		thumbnails.root.walk().none { it.fileName.toString().startsWith(key.substringAfterLast('/')) } shouldBe true
	}

	@Test
	fun `thumbnail rejects other sizes, non-images and undecodable images`() {
		val png = upload("Ok.png", image(10, 10, "png"), "image/png")
		thumbnail(png, 512).andExpect { status { isBadRequest() } }

		val text = upload("Notes.txt", "Fictitious".toByteArray(), "text/plain")
		thumbnail(text).andExpect {
			status { isUnsupportedMediaType() }
			content { contentType("application/problem+json") }
		}
		val svg = upload("Logo.svg", "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(), "image/svg+xml")
		thumbnail(svg).andExpect { status { isUnsupportedMediaType() } }

		val broken = upload("Broken.png", "not really a png".toByteArray(), "image/png")
		thumbnail(broken).andExpect {
			status { isUnprocessableContent() }
			content { contentType("application/problem+json") }
		}
		thumbnail(UUID.randomUUID().toString()).andExpect { status { isNotFound() } }
	}

	// --- previews

	@Test
	fun `text preview returns the whole of a short file as UTF-8 text`() {
		val id = upload("Grüsse.txt", "Grüezi mitenand ✓".toByteArray(), "text/plain")

		val response =
			mockMvc
				.get("$FILES/$id/preview")
				.andExpect {
					status { isOk() }
					header { string(PREVIEW_TRUNCATED, "false") }
				}.andReturn()
				.response
		response.contentType shouldBe "text/plain;charset=UTF-8"
		response.getHeader(HttpHeaders.CONTENT_DISPOSITION)!! shouldStartWith "inline;"
		response.getContentAsString(Charsets.UTF_8) shouldBe "Grüezi mitenand ✓"
	}

	@Test
	fun `markdown preview is cut at 256 KB without splitting a character`() {
		// 262143 ASCII bytes, then a 3-byte '€' that would straddle the 262144-byte limit.
		val content = ("#".repeat(256 * 1024 - 1) + "€" + "tail").toByteArray()
		val id = upload("Long.md", content, "text/markdown")

		val response =
			mockMvc
				.get("$FILES/$id/preview")
				.andExpect {
					status { isOk() }
					header { string(PREVIEW_TRUNCATED, "true") }
				}.andReturn()
				.response
		response.contentType shouldBe "text/markdown;charset=UTF-8"
		val text = response.getContentAsString(Charsets.UTF_8)
		text shouldBe "#".repeat(256 * 1024 - 1)
	}

	@Test
	fun `html is previewed as plain text and invalid UTF-8 is replaced`() {
		val bytes = "<script>alert(1)</script>".toByteArray() + byteArrayOf(0xFF.toByte())
		val id = upload("Page.html", bytes, "text/html")

		val response =
			mockMvc
				.get("$FILES/$id/preview")
				.andExpect { status { isOk() } }
				.andReturn()
				.response
		response.contentType shouldBe "text/plain;charset=UTF-8"
		response.getContentAsString(Charsets.UTF_8) shouldBe "<script>alert(1)</script>�"
	}

	@Test
	fun `pdf, audio, video and images are served inline with their mime type and range support`() {
		val pdf = "%PDF-1.4 fictitious".toByteArray()
		val pdfId = upload("Report.pdf", pdf, "application/pdf")
		mockMvc.get("$FILES/$pdfId/preview").andExpect {
			status { isOk() }
			content { contentType("application/pdf") }
			content { bytes(pdf) }
			header { string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"Report.pdf\"; filename*=UTF-8''Report.pdf") }
		}
		mockMvc
			.get("$FILES/$pdfId/preview") { header(HttpHeaders.RANGE, "bytes=0-3") }
			.andExpect {
				status { isPartialContent() }
				content { bytes("%PDF".toByteArray()) }
			}

		for ((name, mime) in listOf("Song.mp3" to "audio/mpeg", "Clip.mp4" to "video/mp4")) {
			val id = upload(name, ByteArray(64) { it.toByte() }, mime)
			mockMvc.get("$FILES/$id/preview").andExpect {
				status { isOk() }
				content { contentType(mime) }
			}
		}
		val png = upload("Image.png", image(4, 4, "png"), "image/png")
		mockMvc.get("$FILES/$png/preview").andExpect { content { contentType("image/png") } }
	}

	@Test
	fun `preview rejects types without a preview`() {
		val zip = upload("Archive.zip", ByteArray(10), "application/zip")
		mockMvc.get("$FILES/$zip/preview").andExpect {
			status { isUnsupportedMediaType() }
			jsonPath("$.detail") { value("No preview for files of type application/zip") }
		}
		val svg = upload("Logo.svg", "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(), "image/svg+xml")
		mockMvc.get("$FILES/$svg/preview").andExpect { status { isUnsupportedMediaType() } }
		mockMvc.get("$FILES/${UUID.randomUUID()}/preview").andExpect { status { isNotFound() } }
	}

	@Test
	fun `previews of trashed files are not found`() {
		val id = upload("Gone.txt", "Fictitious".toByteArray(), "text/plain")
		files.deleteFile(UUID.fromString(id)) {}
		mockMvc.get("$FILES/$id/preview").andExpect { status { isNotFound() } }
		mockMvc.get("$FILES/$id/thumbnail").andExpect { status { isNotFound() } }
	}

	companion object {
		private const val FILES = "/api/v1/files"
		private const val PREVIEW_TRUNCATED = "X-Preview-Truncated"

		/** A 1×1 lossless WebP. */
		private const val WEBP_1X1 = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA=="

		/** [jpeg] with an APP1 Exif segment (big-endian TIFF, one IFD entry: Orientation = [orientation]) after SOI. */
		fun withExifOrientation(
			jpeg: ByteArray,
			orientation: Int,
		): ByteArray {
			val tiff =
				byteArrayOf(
					'M'.code.toByte(),
					'M'.code.toByte(),
					0,
					42,
					0,
					0,
					0,
					8, // header, IFD at offset 8
					0,
					1, // one entry
					0x01,
					0x12,
					0,
					3,
					0,
					0,
					0,
					1,
					0,
					orientation.toByte(),
					0,
					0, // Orientation, SHORT, count 1
					0,
					0,
					0,
					0, // no next IFD
				)
			val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
			val length = payload.size + 2
			val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
			return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
		}
	}
}
