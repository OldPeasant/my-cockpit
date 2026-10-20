package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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
import java.util.Base64
import java.util.HexFormat
import java.util.zip.ZipInputStream

/**
 * Edge cases of the files REST API (task files-api) beyond the main flows in [FilesWebTests]: special folders, moves
 * into the Trash, uploads whose folder disappears, the listing cursor across the folder/file boundary, zips without
 * trashed content, and PATCH/upload validation. Same Spring context as [FilesWebTests].
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class FilesApiEdgeCaseWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val files: FilesService,
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
		body: String,
		etag: String? = etag(path),
	) = mockMvc.patch(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		etag?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun deleteWithEtag(
		path: String,
		etag: String? = etag(path),
	) = mockMvc.delete(path) {
		with(csrf())
		etag?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun etag(path: String): String? =
		mockMvc
			.get(path)
			.andReturn()
			.response
			.getHeader(HttpHeaders.ETAG)

	private fun newFolder(
		name: String,
		parentId: String? = null,
	): String {
		val parent = parentId?.let { ""","parentId":"$it"""" } ?: ""
		return postJson(FOLDERS, """{"name":"$name"$parent}""").andExpect { status { isCreated() } }.json()["id"].asString()
	}

	private fun upload(
		name: String,
		content: ByteArray = name.toByteArray(),
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

	private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

	private fun JsonNode.elements(): List<JsonNode> = (0 until size()).map { get(it) }

	private fun JsonNode.entryName(): String = (get("folder")?.takeIf { !it.isNull } ?: get("file"))["name"].asString()

	private fun page(
		folderId: String,
		limit: Int,
		cursor: String? = null,
	): JsonNode =
		mockMvc
			.get(DRIVE) {
				param("folderId", folderId)
				param("limit", limit.toString())
				cursor?.let { param("cursor", it) }
			}.andExpect { status { isOk() } }
			.json()

	private fun zipEntries(folderId: String): Map<String, String> {
		val started = mockMvc.get("$FOLDERS/$folderId/zip").andExpect { request { asyncStarted() } }.andReturn()
		val response = mockMvc.perform(asyncDispatch(started)).andReturn().response
		response.status shouldBe 200
		val entries = mutableMapOf<String, String>()
		ZipInputStream(ByteArrayInputStream(response.contentAsByteArray)).use { zip ->
			generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readAllBytes().decodeToString() }
		}
		return entries
	}

	// --- special folders

	@Test
	fun `a special folder cannot be moved, renamed or deleted, but folders and files can move into it`() {
		val tasks = files.specialFolder(SpecialFolder.TASKS).id.toString()
		val user = newFolder("Projects")
		val path = "$FOLDERS/$tasks"

		patchJson(path, """{"parentId":"$user"}""").andExpect { status { isConflict() } }
		patchJson(path, """{"parentId":null}""").andExpect { status { isConflict() } }
		patchJson(path, """{"name":"Chores"}""").andExpect { status { isConflict() } }
		deleteWithEtag(path).andExpect { status { isConflict() } }
		mockMvc.get(path).json().let {
			it["special"].asString() shouldBe "TASKS"
			it["path"].asString() shouldBe "/Attachments/Tasks"
		}

		val userPath = "$FOLDERS/$user"
		patchJson(userPath, """{"parentId":"$tasks"}""").andExpect { status { isOk() } }.json()["path"].asString() shouldBe
			"/Attachments/Tasks/Projects"
		val file = upload("note.txt")["id"].asString()
		patchJson("$FILES/$file", """{"folderId":"$tasks"}""").andExpect { status { isOk() } }.json()["folderId"].asString() shouldBe tasks
	}

	// --- moves into the Trash or nowhere

	@Test
	fun `moving a file or a folder into a trashed or unknown folder is a 404 and changes nothing`() {
		val trashed = newFolder("Old")
		deleteWithEtag("$FOLDERS/$trashed").andExpect { status { isNoContent() } }
		val folder = newFolder("Keep")
		val file = upload("keep.txt")["id"].asString()

		for (target in listOf(trashed, UNKNOWN)) {
			patchJson("$FILES/$file", """{"folderId":"$target"}""").andExpect { status { isNotFound() } }
			patchJson("$FOLDERS/$folder", """{"parentId":"$target"}""").andExpect { status { isNotFound() } }
		}
		mockMvc.get("$FILES/$file").json()["folderId"].isNull shouldBe true
		mockMvc.get("$FOLDERS/$folder").json()["parentId"].isNull shouldBe true
	}

	@Test
	fun `a trashed file or folder cannot be changed or deleted again`() {
		val folder = newFolder("Gone")
		val file = upload("gone.txt")["id"].asString()
		val folderEtag = etag("$FOLDERS/$folder")
		val fileEtag = etag("$FILES/$file")
		deleteWithEtag("$FOLDERS/$folder").andExpect { status { isNoContent() } }
		deleteWithEtag("$FILES/$file").andExpect { status { isNoContent() } }

		patchJson("$FOLDERS/$folder", """{"name":"Back"}""", folderEtag).andExpect { status { isNotFound() } }
		patchJson("$FILES/$file", """{"name":"back.txt"}""", fileEtag).andExpect { status { isNotFound() } }
		deleteWithEtag("$FILES/$file", fileEtag).andExpect { status { isNotFound() } }
		mockMvc.get("$FILES/$file/content").andExpect { status { isNotFound() } }
		mockMvc.get("$FOLDERS/$folder/zip").andExpect { status { isNotFound() } }
		mockMvc.get(DRIVE) { param("folderId", folder) }.andExpect { status { isNotFound() } }
	}

	@Test
	fun `a stale ETag on delete is a 412 and keeps the item`() {
		val file = upload("draft.txt")["id"].asString()
		val stale = etag("$FILES/$file")
		patchJson("$FILES/$file", """{"name":"draft2.txt"}""").andExpect { status { isOk() } }
		deleteWithEtag("$FILES/$file", stale).andExpect { status { isPreconditionFailed() } }
		mockMvc.get("$FILES/$file").andExpect { status { isOk() } }

		val folder = newFolder("Plans")
		val staleFolder = etag("$FOLDERS/$folder")
		patchJson("$FOLDERS/$folder", """{"name":"Plans 2"}""").andExpect { status { isOk() } }
		deleteWithEtag("$FOLDERS/$folder", staleFolder).andExpect { status { isPreconditionFailed() } }
		patchJson("$FOLDERS/$folder", """{"name":"Plans 3"}""", staleFolder).andExpect { status { isPreconditionFailed() } }
		mockMvc.get("$FOLDERS/$folder").json()["name"].asString() shouldBe "Plans 2"
	}

	@Test
	fun `renaming a folder in place to another case of its name is allowed, to a sibling's name is a conflict`() {
		newFolder("Taken")
		val folder = newFolder("draft")
		patchJson("$FOLDERS/$folder", """{"name":"Draft"}""").andExpect { status { isOk() } }.json()["name"].asString() shouldBe "Draft"
		patchJson("$FOLDERS/$folder", """{"name":"TAKEN"}""").andExpect { status { isConflict() } }
		patchJson("$FOLDERS/$folder", """{"name":"  "}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("name") }
		}
	}

	// --- PATCH body validation

	@Test
	fun `PATCH bodies with unknown fields, wrong types or invalid ids are 400 with field errors`() {
		val folder = newFolder("Docs")
		val file = upload("a.txt")["id"].asString()

		patchJson("$FILES/$file", """{"parentId":null}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("parentId") }
		}
		patchJson("$FOLDERS/$folder", """{"folderId":null}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("folderId") }
		}
		patchJson("$FILES/$file", """{"name":null}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("name") }
		}
		patchJson("$FILES/$file", """{"folderId":"nope"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("folderId") }
		}
		patchJson("$FOLDERS/$folder", """{"name":"a/b"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("name") }
		}
		patchJson("$FILES/$file", """["name"]""").andExpect { status { isBadRequest() } }
		mockMvc.get("$FILES/$file").json()["name"].asString() shouldBe "a.txt"
	}

	@Test
	fun `an empty PATCH body keeps the item as it is`() {
		val folder = newFolder("Docs")
		val file = upload("a.txt", folderId = folder)["id"].asString()
		patchJson("$FILES/$file", "{}").andExpect { status { isOk() } }.json().let {
			it["name"].asString() shouldBe "a.txt"
			it["folderId"].asString() shouldBe folder
		}
		patchJson("$FOLDERS/$folder", "{}").andExpect { status { isOk() } }.json()["name"].asString() shouldBe "Docs"
	}

	// --- multipart upload

	@Test
	fun `the name parameter overrides the part's filename and the mime type is guessed for octet-stream parts`() {
		val file =
			mockMvc
				.multipart(FILES) {
					with(csrf())
					file(MockMultipartFile("file", "IMG_0001.bin", "application/octet-stream", "png".toByteArray()))
					param("name", "Holiday.png")
				}.andExpect { status { isCreated() } }
				.json()
		file["name"].asString() shouldBe "Holiday.png"
		file["mime"].asString() shouldBe "image/png"

		upload("data.unknownext", mime = "application/octet-stream")["mime"].asString() shouldBe "application/octet-stream"
	}

	@Test
	fun `a file with an unparsable stored mime type downloads as octet-stream`() {
		val file = upload("odd.txt", mime = "not a mime type")["id"].asString()
		mockMvc.get("$FILES/$file/content").andExpect {
			status { isOk() }
			header { string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE) }
		}
	}

	@Test
	fun `an empty file uploads and downloads`() {
		val file = upload("empty.txt", ByteArray(0))
		file["size"].asLong() shouldBe 0
		mockMvc.get("$FILES/${file["id"].asString()}/content").andExpect {
			status { isOk() }
			content { bytes(ByteArray(0)) }
		}
	}

	@Test
	fun `a partial download keeps the attachment header, and several ranges come as multipart byteranges`() {
		val id = upload("Zählerstände.txt", "abcdefghijklmnopqrstuvwxyz".toByteArray())["id"].asString()
		mockMvc.get("$FILES/$id/content") { header(HttpHeaders.RANGE, "bytes=0-0") }.andExpect {
			status { isPartialContent() }
			header { string(HttpHeaders.CONTENT_RANGE, "bytes 0-0/26") }
			header {
				string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Z_hlerst_nde.txt\"; filename*=UTF-8''Z%C3%A4hlerst%C3%A4nde.txt")
			}
			content { string("a") }
		}
		// The last byte exactly, and an open-ended range starting at it.
		mockMvc.get("$FILES/$id/content") { header(HttpHeaders.RANGE, "bytes=25-") }.andExpect {
			status { isPartialContent() }
			header { string(HttpHeaders.CONTENT_RANGE, "bytes 25-25/26") }
			content { string("z") }
		}
		// One past the last byte is not satisfiable.
		mockMvc.get("$FILES/$id/content") { header(HttpHeaders.RANGE, "bytes=26-") }.andExpect {
			status { isRequestedRangeNotSatisfiable() }
		}
		val multi =
			mockMvc
				.get("$FILES/$id/content") { header(HttpHeaders.RANGE, "bytes=0-1,24-25") }
				.andExpect { status { isPartialContent() } }
				.andReturn()
				.response
		multi.contentType!!.startsWith("multipart/byteranges") shouldBe true
		val body = multi.contentAsString
		body.contains("Content-Range: bytes 0-1/26") shouldBe true
		body.contains("Content-Range: bytes 24-25/26") shouldBe true
		body.contains("ab") shouldBe true
		body.contains("yz") shouldBe true
	}

	@Test
	fun `a part without a content type gets the mime type guessed from its name`() {
		val file =
			mockMvc
				.multipart(FILES) {
					with(csrf())
					file(MockMultipartFile("file", "scan.pdf", null, "%PDF fictitious".toByteArray()))
				}.andExpect { status { isCreated() } }
				.json()
		file["name"].asString() shouldBe "scan.pdf"
		file["mime"].asString() shouldBe "application/pdf"
	}

	@Test
	fun `a part without a filename needs the name parameter`() {
		mockMvc
			.multipart(FILES) {
				with(csrf())
				file(MockMultipartFile("file", "", null, "x".toByteArray()))
			}.andExpect {
				status { isBadRequest() }
				jsonPath("$.errors[0].field") { value("name") }
			}
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0

		mockMvc
			.multipart(FILES) {
				with(csrf())
				file(MockMultipartFile("file", "", null, "x".toByteArray()))
				param("name", "notes.txt")
			}.andExpect {
				status { isCreated() }
				jsonPath("$.name") { value("notes.txt") }
				jsonPath("$.mime") { value("text/plain") }
			}
	}

	@Test
	fun `unknown ids are 404 and malformed ids are 400`() {
		mockMvc.get("$FILES/$UNKNOWN").andExpect { status { isNotFound() } }
		mockMvc.get("$FILES/$UNKNOWN/content").andExpect { status { isNotFound() } }
		mockMvc.get("$FOLDERS/$UNKNOWN").andExpect { status { isNotFound() } }
		mockMvc.get("$FOLDERS/$UNKNOWN/zip").andExpect { status { isNotFound() } }
		mockMvc.get("$UPLOADS/$UNKNOWN").andExpect { status { isNotFound() } }
		mockMvc
			.put("$UPLOADS/$UNKNOWN/chunks/0") {
				with(csrf())
				contentType = MediaType.APPLICATION_OCTET_STREAM
				content = ByteArray(1)
			}.andExpect { status { isNotFound() } }
		mockMvc.delete("$UPLOADS/$UNKNOWN") { with(csrf()) }.andExpect { status { isNotFound() } }
		mockMvc.get("$FILES/not-an-id").andExpect { status { isBadRequest() } }
	}

	// --- resumable uploads

	private fun createUpload(body: String): String = postJson(UPLOADS, body).andExpect { status { isCreated() } }.json()["id"].asString()

	private fun putChunk(
		id: String,
		n: Int,
		bytes: ByteArray,
	) = mockMvc.put("$UPLOADS/$id/chunks/$n") {
		with(csrf())
		contentType = MediaType.APPLICATION_OCTET_STREAM
		content = bytes
	}

	private fun complete(
		id: String,
		sha256: String,
	) = postJson("$UPLOADS/$id/complete", """{"sha256":"$sha256"}""")

	@Test
	fun `an upload into a folder trashed before complete is a 404 and stores no file`() {
		val folder = newFolder("Inbox")
		val content = "Fictitious scan".toByteArray()
		val id = createUpload("""{"name":"scan.pdf","folderId":"$folder","size":${content.size}}""")
		putChunk(id, 0, content).andExpect { status { isNoContent() } }
		deleteWithEtag("$FOLDERS/$folder").andExpect { status { isNoContent() } }

		complete(id, sha256(content)).andExpect { status { isNotFound() } }
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0
		// The upload itself is still there and can be aborted.
		mockMvc.delete("$UPLOADS/$id") { with(csrf()) }.andExpect { status { isNoContent() } }
	}

	@Test
	fun `an upload into a trashed or unknown folder cannot start`() {
		val folder = newFolder("Old")
		deleteWithEtag("$FOLDERS/$folder").andExpect { status { isNoContent() } }
		postJson(UPLOADS, """{"name":"a.txt","folderId":"$folder","size":1}""").andExpect { status { isNotFound() } }
		postJson(UPLOADS, """{"name":"a.txt","folderId":"$UNKNOWN","size":1}""").andExpect { status { isNotFound() } }
	}

	@Test
	fun `upload requests are validated`() {
		postJson(UPLOADS, """{"size":1}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("name") }
		}
		postJson(UPLOADS, """{"name":"a.txt"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("size") }
		}
		postJson(UPLOADS, """{"name":"a.txt","size":-1}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("size") }
		}
		postJson(UPLOADS, """{"name":"..","size":1}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("name") }
		}
		postJson(UPLOADS, """{"name":"max.bin","size":${5L * 1024 * 1024 * 1024}}""").andExpect { status { isCreated() } }

		val id = createUpload("""{"name":"a.txt","size":1}""")
		postJson("$UPLOADS/$id/complete", "{}").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("sha256") }
		}
		complete(id, "abc").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("sha256") }
		}
		putChunk(id, -1, ByteArray(1)).andExpect { status { isBadRequest() } }
	}

	@Test
	fun `an upload without mime gets a default, a blank one too`() {
		mockMvc.get("$UPLOADS/${createUpload("""{"name":"a.bin","size":1}""")}").json()["mime"].asString() shouldBe
			"application/octet-stream"
		mockMvc.get("$UPLOADS/${createUpload("""{"name":"b.bin","size":1,"mime":"  "}""")}").json()["mime"].asString() shouldBe
			"application/octet-stream"
	}

	@Test
	fun `an empty file uploads without chunks`() {
		val id = createUpload("""{"name":"empty.txt","size":0}""")
		mockMvc.get("$UPLOADS/$id").json().let {
			it["chunkCount"].asInt() shouldBe 0
			it["receivedChunks"].size() shouldBe 0
		}
		putChunk(id, 0, ByteArray(0)).andExpect { status { isBadRequest() } }
		val file = complete(id, sha256(ByteArray(0))).andExpect { status { isCreated() } }.json()
		file["size"].asLong() shouldBe 0
		file["folderId"].isNull shouldBe true
	}

	@Test
	fun `a completed upload cannot be completed or aborted again, and its file is in the listing`() {
		val content = "Fictitious".toByteArray()
		val id = createUpload("""{"name":"once.txt","mime":"text/plain","size":${content.size}}""")
		putChunk(id, 0, content).andExpect { status { isNoContent() } }
		val location =
			complete(id, sha256(content))
				.andExpect { status { isCreated() } }
				.andReturn()
				.response
				.getHeader(HttpHeaders.LOCATION)!!
		complete(id, sha256(content)).andExpect { status { isNotFound() } }
		mockMvc.delete("$UPLOADS/$id") { with(csrf()) }.andExpect { status { isNotFound() } }
		putChunk(id, 0, content).andExpect { status { isNotFound() } }

		val fileId = location.substringAfterLast('/')
		location shouldBe "http://localhost/api/v1/files/$fileId"
		mockMvc
			.get(DRIVE)
			.json()["items"]
			.elements()
			.map { it.entryName() } shouldContainExactly listOf("once.txt")
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 1
	}

	@Test
	fun `a resent chunk replaces the earlier one`() {
		val content = "abc".toByteArray()
		val id = createUpload("""{"name":"a.txt","size":3}""")
		putChunk(id, 0, "xyz".toByteArray()).andExpect { status { isNoContent() } }
		putChunk(id, 0, content).andExpect { status { isNoContent() } }
		val file = complete(id, sha256(content)).andExpect { status { isCreated() } }.json()
		file["sha256"].asString() shouldBe sha256(content)
	}

	// --- listing

	@Test
	fun `the listing cursor crosses from folders to files without losing or repeating an entry`() {
		val docs = newFolder("Docs")
		newFolder("beta", docs)
		newFolder("Alpha", docs)
		upload("alpha", folderId = docs) // same name as a folder, listed with the files
		upload("Beta.txt", folderId = docs)

		val first = page(docs, 2)
		first["items"].elements().map { it["type"].asString() to it.entryName() } shouldContainExactly
			listOf("FOLDER" to "Alpha", "FOLDER" to "beta")
		val second = page(docs, 2, first["nextCursor"].asString())
		second["items"].elements().map { it["type"].asString() to it.entryName() } shouldContainExactly
			listOf("FILE" to "alpha", "FILE" to "Beta.txt")
		second["nextCursor"].isNull shouldBe true

		val straddling = page(docs, 3)
		straddling["items"].elements().map { it.entryName() } shouldContainExactly listOf("Alpha", "beta", "alpha")
		page(docs, 3, straddling["nextCursor"].asString())["items"].elements().map { it.entryName() } shouldContainExactly
			listOf("Beta.txt")

		page(docs, 4)["nextCursor"].isNull shouldBe true
	}

	@Test
	fun `files with the same name are ordered by id and paged without loss`() {
		val docs = newFolder("Docs")
		val ids = (1..3).map { upload("same.txt", "v$it".toByteArray(), docs)["id"].asString() }
		val seen = mutableListOf<String>()
		var cursor: String? = null
		do {
			val page = page(docs, 1, cursor)
			page["items"].elements().forEach { seen += it["file"]["id"].asString() }
			cursor = page["nextCursor"].takeIf { !it.isNull }?.asString()
		} while (cursor != null)
		seen shouldContainExactly ids.sorted()
	}

	@Test
	fun `trashed entries are not listed`() {
		val docs = newFolder("Docs")
		val sub = newFolder("Sub", docs)
		val file = upload("old.txt", folderId = docs)["id"].asString()
		upload("new.txt", folderId = docs)
		deleteWithEtag("$FOLDERS/$sub").andExpect { status { isNoContent() } }
		deleteWithEtag("$FILES/$file").andExpect { status { isNoContent() } }
		page(docs, 50)["items"].elements().map { it.entryName() } shouldContainExactly listOf("new.txt")
	}

	@Test
	fun `malformed cursors and limits are 400`() {
		fun cursor(raw: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
		for (bad in listOf("5|$UNKNOWN|a", "x|$UNKNOWN|a", "0|no-uuid|a", "0|$UNKNOWN", "only")) {
			mockMvc.get(DRIVE) { param("cursor", cursor(bad)) }.andExpect {
				status { isBadRequest() }
				jsonPath("$.errors[0].field") { value("cursor") }
			}
		}
		mockMvc.get(DRIVE) { param("limit", "0") }.andExpect { status { isBadRequest() } }
		mockMvc.get(DRIVE) { param("limit", "201") }.andExpect { status { isBadRequest() } }
		mockMvc.get(DRIVE) { param("limit", "200") }.andExpect { status { isOk() } }
		// A crafted but well-formed cursor is just a position: the names after it.
		newFolder("m-folder")
		mockMvc
			.get(DRIVE) { param("cursor", cursor("0|$UNKNOWN|a|b")) }
			.andExpect { status { isOk() } }
			.json()["items"]
			.elements()
			.map { it.entryName() } shouldContainExactly listOf("m-folder")
	}

	// --- zip

	@Test
	fun `a zip leaves out trashed files and folders and numbers a file named like a subfolder`() {
		val docs = newFolder("Docs")
		val trashedSub = newFolder("Old", docs)
		upload("inside-old.txt", folderId = trashedSub)
		val keep = newFolder("Report", docs)
		upload("Report", "a file named like the folder".toByteArray(), docs)
		upload("inside.txt", "kept".toByteArray(), keep)
		val trashedFile = upload("deleted.txt", folderId = docs)["id"].asString()
		deleteWithEtag("$FOLDERS/$trashedSub").andExpect { status { isNoContent() } }
		deleteWithEtag("$FILES/$trashedFile").andExpect { status { isNoContent() } }

		val entries = zipEntries(docs)
		entries.keys.sorted() shouldContainExactly listOf("Docs/", "Docs/Report (2)", "Docs/Report/", "Docs/Report/inside.txt")
		entries["Docs/Report (2)"] shouldBe "a file named like the folder"
		entries["Docs/Report/inside.txt"] shouldBe "kept"
	}

	@Test
	fun `file names that differ only in case get numbers in a zip`() {
		val docs = newFolder("Docs")
		upload("Notes.TXT", "upper".toByteArray(), docs)
		upload("notes.txt", "lower".toByteArray(), docs)
		val entries = zipEntries(docs)
		// Same lower-case name: ordered by id (creation order of UUIDv7), so the second upload gets the number.
		entries.keys.sorted() shouldContainExactly listOf("Docs/", "Docs/Notes.TXT", "Docs/notes (2).txt")
		entries["Docs/Notes.TXT"] shouldBe "upper"
		entries["Docs/notes (2).txt"] shouldBe "lower"
	}

	@Test
	fun `an empty folder zips to its directory entry, with a non-ASCII name in the header`() {
		val folder = newFolder("Über")
		val started = mockMvc.get("$FOLDERS/$folder/zip").andExpect { request { asyncStarted() } }.andReturn()
		val response = mockMvc.perform(asyncDispatch(started)).andReturn().response
		response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"_ber.zip\"; filename*=UTF-8''%C3%9Cber.zip"
		zipEntries(folder).keys shouldContainExactly setOf("Über/")
	}

	@Test
	fun `usage counts folders not in the Trash`() {
		val a = newFolder("A")
		newFolder("B", a)
		val c = newFolder("C")
		deleteWithEtag("$FOLDERS/$c").andExpect { status { isNoContent() } }
		val usage = mockMvc.get("$DRIVE/usage").json()
		usage["folderCount"].asLong() shouldBe 2
		usage["usedBytes"].asLong() shouldBe 0
		(usage["usableSpaceBytes"].asLong() in 0..usage["totalSpaceBytes"].asLong()) shouldBe true
		usage["trashBytes"].asLong() shouldBe 0
		usage.get("unknown").shouldBeNull()
	}

	// --- API contract

	@Test
	fun `every parameter of the files endpoints in the OpenAPI contract has a schema, so client generators accept it`() {
		// OpenAPI 3 requires `schema` or `content` on each parameter; the Android client generator validates this.
		val paths = mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }.json()["paths"]
		val filesPaths = paths.propertyNames().filter { p -> listOf(FILES, FOLDERS, DRIVE, UPLOADS).any { p.startsWith(it) } }
		filesPaths.size shouldBe 12
		val withoutSchema =
			filesPaths.flatMap { path ->
				val operations = paths[path]
				operations.propertyNames().flatMap { method ->
					operations[method]["parameters"]
						?.elements()
						.orEmpty()
						.filter { it["schema"] == null && it["content"] == null }
						.map { "$method $path ${it["in"].asString()} ${it["name"].asString()}" }
				}
			}
		withoutSchema.shouldBeEmpty()
	}

	@Test
	fun `the OpenAPI contract allows null for the folder fields that mean the drive root`() {
		val schemas = mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }.json()["components"]["schemas"]
		listOf(
			"FolderPatchRequest" to "parentId",
			"FilePatchRequest" to "folderId",
			"CreateFolderRequest" to "parentId",
			"CreateUploadRequest" to "folderId",
		).forEach { (schema, field) ->
			val property = schemas[schema]["properties"][field]
			property["type"].elements().map { it.asString() } shouldContainExactly listOf("string", "null")
			property["format"].asString() shouldBe "uuid"
		}
	}

	@Test
	fun `listFolder and getUpload document their 200 body as application json only`() {
		val paths = mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }.json()["paths"]
		listOf(paths[DRIVE]["get"], paths["$UPLOADS/{id}"]["get"]).forEach { operation ->
			operation["responses"]["200"]["content"].propertyNames().toList() shouldContainExactly listOf("application/json")
		}
	}

	@Test
	fun `an explicit null folder id on create means the drive root, as the contract allows`() {
		val folder = postJson(FOLDERS, """{"name":"Receipts","parentId":null}""").andExpect { status { isCreated() } }.json()
		folder["parentId"].isNull shouldBe true
		folder["path"].asString() shouldBe "/Receipts"
		val upload = postJson(UPLOADS, """{"name":"scan.pdf","folderId":null,"size":1}""").andExpect { status { isCreated() } }.json()
		upload["folderId"].isNull shouldBe true
	}

	@Test
	fun `listFolder and getUpload answer a client that accepts only application json, also with errors`() {
		// The generated web client sends `Accept: application/json` for these operations.
		val folder = newFolder("Inbox")
		mockMvc
			.get(DRIVE) { accept = MediaType.APPLICATION_JSON }
			.andExpect {
				status { isOk() }
				content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
				jsonPath("$.items[0].folder.id") { value(folder) }
			}
		val id = createUpload("""{"name":"a.bin","size":1}""")
		mockMvc
			.get("$UPLOADS/$id") { accept = MediaType.APPLICATION_JSON }
			.andExpect {
				status { isOk() }
				content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
				jsonPath("$.id") { value(id) }
			}
		mockMvc
			.get(DRIVE) {
				accept = MediaType.APPLICATION_JSON
				param("folderId", UNKNOWN)
			}.andExpect {
				status { isNotFound() }
				jsonPath("$.status") { value(404) }
			}
		mockMvc
			.get("$UPLOADS/$UNKNOWN") { accept = MediaType.APPLICATION_JSON }
			.andExpect {
				status { isNotFound() }
				jsonPath("$.status") { value(404) }
			}
	}

	private companion object {
		const val FILES = "/api/v1/files"
		const val FOLDERS = "/api/v1/folders"
		const val DRIVE = "/api/v1/drive"
		const val UPLOADS = "/api/v1/uploads"
		const val UNKNOWN = "01900000-0000-7000-8000-000000000000"
	}
}
