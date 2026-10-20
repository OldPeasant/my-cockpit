package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.infra.ThumbnailStore
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile

@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class PreviewEdgeCaseWebTests(
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
		jdbc.execute("delete from folder where special is null")
	}

	private fun upload(
		name: String,
		content: ByteArray,
		mime: String,
		folderId: UUID? = null,
	): String =
		mockMvc
			.multipart(FILES) {
				with(csrf())
				file(MockMultipartFile("file", name, mime, content))
				folderId?.let { param("folderId", it.toString()) }
			}.andExpect { status { isCreated() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)["id"]
			.asString()

	private fun image(
		width: Int,
		height: Int,
		format: String,
	): ByteArray {
		val type = if (format == "jpeg") BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
		val image = BufferedImage(width, height, type)
		val g = image.createGraphics()
		g.color = Color.GREEN
		g.fillRect(0, 0, width, height)
		g.dispose()
		return ByteArrayOutputStream().also { ImageIO.write(image, format, it) shouldBe true }.toByteArray()
	}

	private fun dimensions(bytes: ByteArray) = ImageIO.read(ByteArrayInputStream(bytes)).let { it.width to it.height }

	private fun preview(id: String) = mockMvc.get("$FILES/$id/preview")

	private fun thumbnailOk(id: String) =
		mockMvc
			.get("$FILES/$id/thumbnail")
			.andExpect { status { isOk() } }
			.andReturn()
			.response

	private fun cachedThumbnails(id: String) =
		files.file(UUID.fromString(id)).storageKey.let { key -> ThumbnailStore.SIZES.map { thumbnails.root.resolve("$it/$key.png") } }

	// --- thumbnails

	@Test
	fun `purging a folder deletes the cached thumbnails of the files inside it, also in subfolders`() {
		val folder = files.createFolder("Fictitious photos", null)
		val sub = files.createFolder("Summer", folder.id)
		val top = upload("Top.png", image(400, 400, "png"), "image/png", folder.id)
		val nested = upload("Nested.png", image(400, 200, "png"), "image/png", sub.id)
		listOf(top, nested).forEach { id ->
			mockMvc.get("$FILES/$id/thumbnail").andExpect { status { isOk() } }
			mockMvc.get("$FILES/$id/thumbnail") { param("size", "1024") }.andExpect { status { isOk() } }
		}
		val cached = cachedThumbnails(top) + cachedThumbnails(nested)
		cached.forEach { it.isRegularFile() shouldBe true }

		files.deleteFolder(folder.id)
		files.purgeFolder(folder.id) shouldBe true

		cached.forEach { it.isRegularFile() shouldBe false }
	}

	@Test
	fun `a 1024 thumbnail of an image between the sizes keeps its original size`() {
		val id = upload("Medium.png", image(600, 300, "png"), "image/png")

		val response =
			mockMvc
				.get("$FILES/$id/thumbnail") { param("size", "1024") }
				.andExpect { status { isOk() } }
				.andReturn()
				.response
		dimensions(response.contentAsByteArray) shouldBe (600 to 300)
	}

	@Test
	fun `a small jpeg is rotated by its EXIF orientation without being enlarged`() {
		val id = upload("Small rotated.jpg", PreviewWebTests.withExifOrientation(image(100, 60, "jpeg"), 6), "image/jpeg")

		val response = thumbnailOk(id)
		dimensions(response.contentAsByteArray) shouldBe (60 to 100)
	}

	@Test
	fun `a 1024 jpeg thumbnail honors the EXIF orientation too`() {
		val id = upload("Tall.jpg", PreviewWebTests.withExifOrientation(image(2000, 1000, "jpeg"), 8), "image/jpeg")

		val response =
			mockMvc
				.get("$FILES/$id/thumbnail") { param("size", "1024") }
				.andExpect { status { isOk() } }
				.andReturn()
				.response
		response.contentType shouldBe "image/jpeg"
		dimensions(response.contentAsByteArray) shouldBe (512 to 1024)
	}

	@Test
	fun `thumbnail accepts a mime type with other case and parameters`() {
		val id = upload("Upper.png", image(512, 512, "png"), "Image/PNG; foo=bar")

		val response = thumbnailOk(id)
		response.contentType shouldBe "image/png"
		dimensions(response.contentAsByteArray) shouldBe (256 to 256)
	}

	@Test
	fun `thumbnail responses may be cached privately by the client`() {
		val id = upload("Cacheable.png", image(10, 10, "png"), "image/png")

		val cacheControl = thumbnailOk(id).getHeader(HttpHeaders.CACHE_CONTROL)!!
		cacheControl shouldContain "private"
		cacheControl shouldContain "max-age=86400"
	}

	@Test
	fun `an image header claiming more than 100 megapixels is rejected with 422`() {
		val id = upload("Bomb.png", pngHeaderOnly(20_000, 20_000), "image/png")

		mockMvc.get("$FILES/$id/thumbnail").andExpect {
			status { isUnprocessableContent() }
			content { contentType("application/problem+json") }
			jsonPath("$.detail") { value("The image has more than ${ThumbnailStore.MAX_PIXELS} pixels") }
		}
	}

	@Test
	fun `thumbnail size that is not a number is a bad request`() {
		val id = upload("Ok.png", image(10, 10, "png"), "image/png")
		mockMvc.get("$FILES/$id/thumbnail") { param("size", "big") }.andExpect { status { isBadRequest() } }
		mockMvc.get("$FILES/$id/thumbnail") { param("size", "0") }.andExpect { status { isBadRequest() } }
	}

	// --- text previews

	@Test
	fun `a text file of exactly 256 KB is not truncated and one byte more is`() {
		val limit = 256 * 1024
		val exact = upload("Exact.txt", ByteArray(limit) { 'a'.code.toByte() }, "text/plain")
		val over = upload("Over.txt", ByteArray(limit + 1) { 'b'.code.toByte() }, "text/plain")

		preview(exact)
			.andExpect { header { string(PREVIEW_TRUNCATED, "false") } }
			.andReturn()
			.response
			.getContentAsString(Charsets.UTF_8)
			.length shouldBe limit
		preview(over)
			.andExpect { header { string(PREVIEW_TRUNCATED, "true") } }
			.andReturn()
			.response
			.getContentAsString(Charsets.UTF_8) shouldBe "b".repeat(limit)
	}

	@Test
	fun `an empty text file previews as empty text`() {
		val id = upload("Empty.md", ByteArray(0), "text/markdown")

		preview(id)
			.andExpect {
				status { isOk() }
				header { string(PREVIEW_TRUNCATED, "false") }
			}.andReturn()
			.response
			.contentAsString shouldBe ""
	}

	@Test
	fun `json, yaml and csv are previewed as plain text and markdown with parameters stays markdown`() {
		for ((name, mime) in listOf("data.json" to "application/json", "conf.yaml" to "application/yaml", "list.csv" to "text/csv")) {
			val id = upload(name, "fictitious: true".toByteArray(), mime)
			preview(id).andExpect {
				status { isOk() }
				content { contentType("text/plain;charset=UTF-8") }
				content { string("fictitious: true") }
			}
		}
		val md = upload("Readme.md", "# Fictitious".toByteArray(), "Text/Markdown; charset=UTF-8")
		preview(md).andExpect { content { contentType("text/markdown;charset=UTF-8") } }
	}

	@Test
	fun `a text preview is never sniffed as html by the browser`() {
		val id = upload("Page.htm", "<html><script>alert(1)</script></html>".toByteArray(), "text/html")

		preview(id).andExpect {
			content { contentType("text/plain;charset=UTF-8") }
			header { string("X-Content-Type-Options", "nosniff") }
		}
	}

	@Test
	fun `a non-ascii file name gets an RFC 8187 inline content disposition`() {
		val id = upload("Übersicht €.txt", "Fictitious".toByteArray(), "text/plain")

		preview(id).andExpect {
			header {
				string(
					HttpHeaders.CONTENT_DISPOSITION,
					"inline; filename=\"_bersicht _.txt\"; filename*=UTF-8''%C3%9Cbersicht%20%E2%82%AC.txt",
				)
			}
		}
	}

	// --- inline previews

	@Test
	fun `video is served inline with byte range support`() {
		val content = ByteArray(64) { it.toByte() }
		val id = upload("Fictitious clip.webm", content, "video/webm")

		preview(id).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ACCEPT_RANGES, "bytes") }
		}
		mockMvc.get("$FILES/$id/preview") { header(HttpHeaders.RANGE, "bytes=10-19") }.andExpect {
			status { isPartialContent() }
			content { contentType("video/webm") }
			header { string(HttpHeaders.CONTENT_RANGE, "bytes 10-19/64") }
			content { bytes(content.copyOfRange(10, 20)) }
		}
		// An open-ended range, as browsers send it for seeking.
		mockMvc.get("$FILES/$id/preview") { header(HttpHeaders.RANGE, "bytes=60-") }.andExpect {
			status { isPartialContent() }
			header { string(HttpHeaders.CONTENT_RANGE, "bytes 60-63/64") }
		}
	}

	@Test
	fun `an unsatisfiable range on an inline preview is 416`() {
		val id = upload("Short.mp3", ByteArray(16), "audio/mpeg")

		mockMvc.get("$FILES/$id/preview") { header(HttpHeaders.RANGE, "bytes=100-200") }.andExpect {
			status { isRequestedRangeNotSatisfiable() }
		}
	}

	@Test
	fun `gif and webp images are served inline with their own mime type`() {
		val gif = upload("Anim.gif", image(4, 4, "gif"), "image/gif")
		preview(gif).andExpect {
			status { isOk() }
			content { contentType("image/gif") }
		}
		val webp = upload("Pic.webp", ByteArray(8), "image/webp")
		preview(webp).andExpect {
			status { isOk() }
			content { contentType("image/webp") }
			header { string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"Pic.webp\"; filename*=UTF-8''Pic.webp") }
		}
	}

	@Test
	fun `octet-stream and other unknown types have no preview`() {
		val id = upload("blob.bin", ByteArray(4), "application/octet-stream")
		preview(id).andExpect {
			status { isUnsupportedMediaType() }
			content { contentType("application/problem+json") }
		}
	}

	companion object {
		private const val FILES = "/api/v1/files"
		private const val PREVIEW_TRUNCATED = "X-Preview-Truncated"

		/** A PNG with only a signature and an IHDR claiming [width] x [height] pixels. */
		fun pngHeaderOnly(
			width: Int,
			height: Int,
		): ByteArray {
			val ihdr =
				ByteBuffer
					.allocate(17)
					.put("IHDR".toByteArray())
					.putInt(width)
					.putInt(height)
					.put(byteArrayOf(8, 6, 0, 0, 0))
					.array()
			val crc = CRC32().apply { update(ihdr) }.value.toInt()
			val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
			return signature + ByteBuffer.allocate(4).putInt(13).array() + ihdr + ByteBuffer.allocate(4).putInt(crc).array()
		}
	}
}
