package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipInputStream
import kotlin.random.Random

@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class FilesWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from search_document where item_type in ('FILE', 'FOLDER')")
		jdbc.execute("delete from upload_session")
		jdbc.execute("delete from file")
		while ((jdbc.queryForObject("select count(*) from folder", Long::class.java) ?: 0) > 0) {
			jdbc.execute("delete from folder f where not exists (select 1 from folder c where c.parent_id = f.id)")
		}
	}

	// --- helpers

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun postJson(
		path: String,
		body: String,
	) = mockMvc.post(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun patchJson(
		path: String,
		etag: String?,
		body: String,
	) = mockMvc.patch(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		etag?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun newFolder(
		name: String,
		parentId: String? = null,
	): JsonNode {
		val parent = parentId?.let { ""","parentId":"$it"""" } ?: ""
		return postJson(FOLDERS, """{"name":"$name"$parent}""").andExpect { status { isCreated() } }.json()
	}

	private fun upload(
		name: String,
		content: ByteArray,
		folderId: String? = null,
		mime: String = "text/plain",
	): JsonNode =
		mockMvc
			.multipart(FILES) {
				with(csrf())
				file(MockMultipartFile("file", name, mime, content))
				folderId?.let { param("folderId", it) }
			}.andExpect { status { isCreated() } }
			.json()

	private fun putChunk(
		uploadId: String,
		n: Int,
		bytes: ByteArray,
	) = mockMvc.put("$UPLOADS/$uploadId/chunks/$n") {
		with(csrf())
		contentType = MediaType.APPLICATION_OCTET_STREAM
		content = bytes
	}

	private fun complete(
		uploadId: String,
		sha256: String,
	) = postJson("$UPLOADS/$uploadId/complete", """{"sha256":"$sha256"}""")

	private fun download(id: String): ByteArray =
		mockMvc
			.get("$FILES/$id/content")
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsByteArray

	private fun JsonNode.elements(): List<JsonNode> = (0 until size()).map { get(it) }

	private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

	private fun etag(path: String): String =
		mockMvc
			.get(path)
			.andReturn()
			.response
			.getHeader(HttpHeaders.ETAG)!!

	private fun names(folderId: String? = null): List<String> =
		mockMvc
			.get(DRIVE) { folderId?.let { param("folderId", it) } }
			.andExpect { status { isOk() } }
			.json()["items"]
			.elements()
			.map { (it["folder"]?.takeIf { f -> !f.isNull } ?: it["file"])["name"].asString() }

	// --- multipart upload and download

	@Test
	fun `a multipart upload is stored and downloads with its name and mime type`() {
		val folder = newFolder("Documents")
		val content = "Fictitious minutes of the board".toByteArray()
		val file = upload("Minutes 2026.txt", content, folder["id"].asString())
		file["name"].asString() shouldBe "Minutes 2026.txt"
		file["folderId"].asString() shouldBe folder["id"].asString()
		file["size"].asLong() shouldBe content.size.toLong()
		file["sha256"].asString() shouldBe sha256(content)

		mockMvc.get("$FILES/${file["id"].asString()}/content").andExpect {
			status { isOk() }
			header { string(HttpHeaders.CONTENT_TYPE, "text/plain") }
			header { string(HttpHeaders.ACCEPT_RANGES, "bytes") }
			header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Minutes 2026.txt\"; filename*=UTF-8''Minutes%202026.txt") }
			content { bytes(content) }
		}
	}

	@Test
	fun `a non-ASCII name gets an ASCII fallback and an RFC 6266 filename star`() {
		val file = upload("Ärger \"neu\".txt", "x".toByteArray())
		mockMvc.get("$FILES/${file["id"].asString()}/content").andExpect {
			header {
				string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"_rger _neu_.txt\"; filename*=UTF-8''%C3%84rger%20%22neu%22.txt")
			}
		}
	}

	@Test
	fun `downloads support Range requests`() {
		val file = upload("alphabet.txt", "abcdefghijklmnopqrstuvwxyz".toByteArray())
		val id = file["id"].asString()
		mockMvc.get("$FILES/$id/content") { header(HttpHeaders.RANGE, "bytes=2-5") }.andExpect {
			status { isPartialContent() }
			header { string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/26") }
			content { string("cdef") }
		}
		mockMvc.get("$FILES/$id/content") { header(HttpHeaders.RANGE, "bytes=-3") }.andExpect {
			status { isPartialContent() }
			content { string("xyz") }
		}
		mockMvc.get("$FILES/$id/content") { header(HttpHeaders.RANGE, "bytes=100-200") }.andExpect {
			status { isRequestedRangeNotSatisfiable() }
		}
	}

	@Test
	fun `an upload without the file part is a 400 naming the part`() {
		mockMvc
			.multipart(FILES) {
				with(csrf())
				param("name", "x.txt")
			}.andExpect {
				status { isBadRequest() }
				jsonPath("$.errors[0].field") { value("file") }
			}
	}

	@Test
	fun `uploading into a missing folder is a 404`() {
		mockMvc
			.multipart(FILES) {
				with(csrf())
				file(MockMultipartFile("file", "a.txt", "text/plain", "a".toByteArray()))
				param("folderId", "01900000-0000-7000-8000-000000000000")
			}.andExpect { status { isNotFound() } }
	}

	// --- resumable uploads

	@Test
	fun `a 60 MB file uploads in chunks and downloads identical`() {
		val content = Random(60).nextBytes(60 * 1024 * 1024)
		val folder = newFolder("Videos")
		val session =
			postJson(UPLOADS, """{"name":"holiday.mp4","folderId":"${folder["id"].asString()}","mime":"video/mp4","size":${content.size}}""")
				.andExpect { status { isCreated() } }
				.json()
		val id = session["id"].asString()
		val chunkSize = session["chunkSize"].asInt()
		val chunkCount = session["chunkCount"].asInt()
		chunkCount shouldBe (content.size + chunkSize - 1) / chunkSize
		(chunkCount > 1) shouldBe true
		for (n in 0 until chunkCount) {
			putChunk(id, n, content.copyOfRange(n * chunkSize, minOf(content.size, (n + 1) * chunkSize)))
				.andExpect { status { isNoContent() } }
		}
		val file =
			complete(id, sha256(content))
				.andExpect {
					status { isCreated() }
					header { exists(HttpHeaders.ETAG) }
				}.json()
		file["size"].asLong() shouldBe content.size.toLong()
		file["mime"].asString() shouldBe "video/mp4"
		file["folderId"].asString() shouldBe folder["id"].asString()
		sha256(download(file["id"].asString())) shouldBe sha256(content)
		mockMvc.get("$UPLOADS/$id").andExpect { status { isNotFound() } }
	}

	@Test
	fun `an upload resumes after a missing chunk`() {
		val created = postJson(UPLOADS, """{"name":"probe.bin","size":1}""").json()
		val chunkSize = created["chunkSize"].asInt()
		postJson(UPLOADS, """{"name":"x","size":0}""") // a second upload must not interfere
		val content = Random(7).nextBytes(2 * chunkSize + 100)
		val id = postJson(UPLOADS, """{"name":"archive.bin","size":${content.size}}""").json()["id"].asString()
		val chunk = { n: Int -> content.copyOfRange(n * chunkSize, minOf(content.size, (n + 1) * chunkSize)) }
		putChunk(id, 0, chunk(0)).andExpect { status { isNoContent() } }
		putChunk(id, 2, chunk(2)).andExpect { status { isNoContent() } }

		complete(id, sha256(content)).andExpect {
			status { isConflict() }
			jsonPath("$.detail") { value("Chunks missing: 1") }
		}
		val status = mockMvc.get("$UPLOADS/$id").json()
		status["chunkCount"].asInt() shouldBe 3
		status["receivedChunks"].elements().map { it.asInt() } shouldContainExactly listOf(0, 2)

		putChunk(id, 1, chunk(1)).andExpect { status { isNoContent() } }
		val file = complete(id, sha256(content)).andExpect { status { isCreated() } }.json()
		download(file["id"].asString()) shouldBe content
	}

	@Test
	fun `a wrong sha256 stores nothing and keeps the upload open`() {
		val content = "Fictitious".toByteArray()
		val id = postJson(UPLOADS, """{"name":"a.txt","size":${content.size}}""").json()["id"].asString()
		putChunk(id, 0, content).andExpect { status { isNoContent() } }
		complete(id, sha256("other".toByteArray())).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("sha256") }
		}
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0
		complete(id, sha256(content).uppercase()).andExpect { status { isCreated() } }
	}

	@Test
	fun `chunks with the wrong length or number and oversized uploads are rejected`() {
		postJson(UPLOADS, """{"name":"huge.iso","size":${6L * 1024 * 1024 * 1024}}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("size") }
		}
		val id = postJson(UPLOADS, """{"name":"a.txt","size":10}""").json()["id"].asString()
		putChunk(id, 0, ByteArray(9)).andExpect { status { isBadRequest() } }
		putChunk(id, 0, ByteArray(11)).andExpect { status { isBadRequest() } }
		putChunk(id, 1, ByteArray(10)).andExpect { status { isBadRequest() } }
		mockMvc.get("$UPLOADS/$id").json()["receivedChunks"].size() shouldBe 0
	}

	@Test
	fun `an aborted upload is gone`() {
		val id = postJson(UPLOADS, """{"name":"a.txt","size":3}""").json()["id"].asString()
		putChunk(id, 0, "abc".toByteArray())
		mockMvc.delete("$UPLOADS/$id") { with(csrf()) }.andExpect { status { isNoContent() } }
		mockMvc.get("$UPLOADS/$id").andExpect { status { isNotFound() } }
		complete(id, sha256("abc".toByteArray())).andExpect { status { isNotFound() } }
	}

	// --- folder operations

	@Test
	fun `rename and move a file need its ETag`() {
		val target = newFolder("Archive")
		val file = upload("draft.txt", "d".toByteArray())
		val path = "$FILES/${file["id"].asString()}"
		patchJson(path, null, """{"name":"final.txt"}""").andExpect { status { isPreconditionRequired() } }
		patchJson(path, "\"999\"", """{"name":"final.txt"}""").andExpect { status { isPreconditionFailed() } }
		val moved =
			patchJson(path, etag(path), """{"name":"final.txt","folderId":"${target["id"].asString()}"}""")
				.andExpect { status { isOk() } }
				.json()
		moved["name"].asString() shouldBe "final.txt"
		moved["folderId"].asString() shouldBe target["id"].asString()
		patchJson(path, etag(path), """{"folderId":null}""").andExpect { status { isOk() } }.json()["folderId"].isNull shouldBe true
		patchJson(path, etag(path), """{"size":3}""").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `a folder cannot be moved into itself or below itself`() {
		val a = newFolder("A")
		val b = newFolder("B", a["id"].asString())
		val path = "$FOLDERS/${a["id"].asString()}"
		patchJson(path, etag(path), """{"parentId":"${a["id"].asString()}"}""").andExpect { status { isConflict() } }
		patchJson(path, etag(path), """{"parentId":"${b["id"].asString()}"}""").andExpect { status { isConflict() } }
		val bPath = "$FOLDERS/${b["id"].asString()}"
		val moved = patchJson(bPath, etag(bPath), """{"parentId":null,"name":"B2"}""").andExpect { status { isOk() } }.json()
		moved["path"].asString() shouldBe "/B2"
		patchJson(path, etag(path), """{"parentId":"${b["id"].asString()}"}""").andExpect { status { isOk() } }
		mockMvc.get(path).json()["path"].asString() shouldBe "/B2/A"
	}

	@Test
	fun `moving a folder next to one with the same name is a conflict`() {
		val a = newFolder("A")
		newFolder("Reports", a["id"].asString())
		val reports = newFolder("reports")
		val path = "$FOLDERS/${reports["id"].asString()}"
		patchJson(path, etag(path), """{"parentId":"${a["id"].asString()}"}""").andExpect { status { isConflict() } }
	}

	@Test
	fun `delete moves a folder to the Trash and lists no longer show it`() {
		val a = newFolder("A")
		val path = "$FOLDERS/${a["id"].asString()}"
		mockMvc.delete(path) { with(csrf()) }.andExpect { status { isPreconditionRequired() } }
		mockMvc
			.delete(path) {
				with(csrf())
				header(HttpHeaders.IF_MATCH, etag(path))
			}.andExpect { status { isNoContent() } }
		mockMvc.get(path).andExpect { status { isNotFound() } }
		names() shouldBe emptyList()
	}

	@Test
	fun `a folder lists folders first, then files, by name, in pages`() {
		val docs = newFolder("Docs")
		val id = docs["id"].asString()
		upload("b.txt", "b".toByteArray(), id)
		upload("A.txt", "a".toByteArray(), id)
		newFolder("zeta", id)
		newFolder("Alpha", id)
		upload("c.txt", "c".toByteArray(), id)
		names(id) shouldContainExactly listOf("Alpha", "zeta", "A.txt", "b.txt", "c.txt")

		val seen = mutableListOf<String>()
		var cursor: String? = null
		do {
			val page =
				mockMvc
					.get(DRIVE) {
						param("folderId", id)
						param("limit", "2")
						cursor?.let { param("cursor", it) }
					}.json()
			page["items"].forEach { seen += (it["folder"]?.takeIf { f -> !f.isNull } ?: it["file"])["name"].asString() }
			cursor = page["nextCursor"].takeIf { !it.isNull }?.asString()
		} while (cursor != null)
		seen shouldContainExactly listOf("Alpha", "zeta", "A.txt", "b.txt", "c.txt")
		names() shouldContainExactly listOf("Docs")
		mockMvc.get(DRIVE) { param("cursor", "bm9wZQ") }.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `disk usage counts active and trashed files`() {
		upload("a.txt", ByteArray(100))
		val b = upload("b.txt", ByteArray(50))
		val path = "$FILES/${b["id"].asString()}"
		mockMvc.delete(path) {
			with(csrf())
			header(HttpHeaders.IF_MATCH, etag(path))
		}
		val usage = mockMvc.get("$DRIVE/usage").andExpect { status { isOk() } }.json()
		usage["usedBytes"].asLong() shouldBe 100
		usage["fileCount"].asLong() shouldBe 1
		usage["trashBytes"].asLong() shouldBe 50
		usage["trashFileCount"].asLong() shouldBe 1
		(usage["totalSpaceBytes"].asLong() > 0) shouldBe true
		usage["maxFileSize"].asLong() shouldBe 5L * 1024 * 1024 * 1024
	}

	@Test
	fun `a folder downloads as a streamed zip with its subtree`() {
		val docs = newFolder("Documents")
		val sub = newFolder("Empty", docs["id"].asString())
		val deep = newFolder("Deep", docs["id"].asString())
		upload("a.txt", "first".toByteArray(), docs["id"].asString())
		upload("a.txt", "second".toByteArray(), docs["id"].asString())
		upload("b.txt", "deep".toByteArray(), deep["id"].asString())
		sub["name"].asString() shouldBe "Empty"

		val started = mockMvc.get("$FOLDERS/${docs["id"].asString()}/zip").andExpect { request { asyncStarted() } }.andReturn()
		// No async timeout (spring.mvc.async.request-timeout=-1), so a long zip is not cut off mid-stream.
		started.request.asyncContext!!.timeout shouldBe -1L
		val response = mockMvc.perform(asyncDispatch(started)).andReturn().response
		response.status shouldBe 200
		response.contentType shouldBe "application/zip"
		response.getHeader(HttpHeaders.CONTENT_DISPOSITION)!! shouldContain "filename*=UTF-8''Documents.zip"
		val entries = mutableMapOf<String, String>()
		ZipInputStream(ByteArrayInputStream(response.contentAsByteArray)).use { zip ->
			generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readAllBytes().decodeToString() }
		}
		entries.keys.sorted() shouldContainExactly
			listOf("Documents/", "Documents/Deep/", "Documents/Deep/b.txt", "Documents/Empty/", "Documents/a (2).txt", "Documents/a.txt")
		setOf(entries["Documents/a.txt"], entries["Documents/a (2).txt"]) shouldBe setOf("first", "second")
		entries["Documents/Deep/b.txt"] shouldBe "deep"
	}

	private companion object {
		const val FILES = "/api/v1/files"
		const val FOLDERS = "/api/v1/folders"
		const val DRIVE = "/api/v1/drive"
		const val UPLOADS = "/api/v1/uploads"
	}
}
