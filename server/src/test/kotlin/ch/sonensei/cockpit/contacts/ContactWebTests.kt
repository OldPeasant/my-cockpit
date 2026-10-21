package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@WithCockpitUser
class ContactWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val resolvers: ItemResolverRegistry,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from contact")
		jdbc.execute("delete from search_document where item_type in ('CONTACT', 'FILE', 'FOLDER')")
		jdbc.execute("delete from file")
		jdbc.execute("update folder set parent_id = null")
		jdbc.execute("delete from folder")
	}

	// --- helpers

	private fun post(body: String) =
		mockMvc.post(CONTACTS) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun patch(
		id: String,
		body: String,
		ifMatch: String?,
	) = mockMvc.patch("$CONTACTS/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun newContact(body: String = ADA): JsonNode = post(body).andExpect { status { isCreated() } }.json()

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun fetch(id: String) = mockMvc.get("$CONTACTS/$id")

	private fun importFile(vcf: String) =
		mockMvc.multipart("$CONTACTS/import") {
			with(csrf())
			file(MockMultipartFile("file", "contacts.vcf", "text/vcard", vcf.toByteArray()))
		}

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun searchTitle(id: String): String? =
		jdbc
			.query("select title from search_document where item_type = 'CONTACT' and item_id = ?::uuid", { rs, _ -> rs.getString(1) }, id)
			.singleOrNull()

	private fun listNames(vararg params: Pair<String, String>): List<String> =
		mockMvc
			.get(CONTACTS) { params.forEach { (name, value) -> param(name, value) } }
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.map { it.get("displayName").asString() }

	private fun ApplicationEvents.changes(): List<Pair<ChangeOp, String>> =
		stream(ChangeEvent::class.java).toList().filter { it.type == "CONTACT" }.map { it.op to it.id.toString() }

	// --- CRUD

	@Test
	fun `POST creates a contact with ETag and Location, indexes it and announces it`(events: ApplicationEvents) {
		val result =
			post(ADA)
				.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.ETAG, "\"0\"") }
					jsonPath("$.displayName") { value("Ada Lovelace") }
					jsonPath("$.emails[0].address") { value("ada@example.org") }
					jsonPath("$.emails[0].label") { value("work") }
					jsonPath("$.phones[0].number") { value("+41 79 000 00 00") }
					jsonPath("$.addresses[0].city") { value("London") }
					jsonPath("$.birthday") { value("1815-12-10") }
					jsonPath("$.source") { value("LOCAL") }
					jsonPath("$.externalId") { doesNotExist() }
				}.andReturn()
		val id = jsonMapper.readTree(result.response.contentAsString).id()
		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost$CONTACTS/$id"
		fetch(id).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.organization") { value("Analytical Engines Ltd") }
		}
		searchTitle(id) shouldBe "Ada Lovelace"
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to id)
	}

	@Test
	fun `the display name is derived from the name, the organization or the email address`() {
		newContact("""{"givenName":"Grace","familyName":"Hopper"}""").get("displayName").asString() shouldBe "Grace Hopper"
		newContact("""{"organization":"Fictional AG"}""").get("displayName").asString() shouldBe "Fictional AG"
		newContact("""{"emails":[{"address":"x@example.org"}]}""").get("displayName").asString() shouldBe "x@example.org"
	}

	@Test
	fun `POST rejects contacts without any name, invalid entries, unknown fields and an unknown photo`() {
		post("""{}""").isProblem(400, "displayName")
		post("""{"displayName":"  "}""").isProblem(400, "displayName")
		post("""{"displayName":"A","emails":[{"address":"not-an-email"}]}""").isProblem(400, "emails[0].address")
		post("""{"displayName":"A","phones":[{"number":" "}]}""").isProblem(400, "phones[0].number")
		post("""{"displayName":"A","addresses":[{"label":"home"}]}""").isProblem(400, "addresses[0]")
		post("""{"displayName":"A","source":"GOOGLE"}""").isProblem(400, "source")
		post("""{"displayName":"A","birthday":"tomorrow"}""").isProblem(400, "birthday")
		post("""{"displayName":"A","photoFileId":"${UuidV7.generate()}"}""").isProblem(400, "photoFileId")
		post("""{"displayName":"${"x".repeat(501)}"}""").isProblem(400, "displayName")
		listNames() shouldBe emptyList()
	}

	@Test
	fun `a photo is a file of the drive`() {
		val file =
			mockMvc
				.multipart("/api/v1/files") {
					with(csrf())
					file(MockMultipartFile("file", "ada.png", "image/png", byteArrayOf(1, 2, 3)))
				}.andExpect { status { isCreated() } }
				.json()
		newContact("""{"displayName":"Ada","photoFileId":"${file.id()}"}""").get("photoFileId").asString() shouldBe file.id()
	}

	@Test
	fun `PATCH changes and clears fields, needs If-Match and re-indexes`(events: ApplicationEvents) {
		val contact = newContact()
		val id = contact.id()
		patch(id, """{"displayName":"Countess Lovelace"}""", null).isProblem(428)
		patch(id, """{"displayName":"Countess Lovelace"}""", "\"7\"").isProblem(412)
		patch(id, """{"source":"GOOGLE"}""", contact.etag()).isProblem(400, "source")

		patch(id, """{"displayName":"Countess Lovelace","phones":null,"birthday":null,"title":"Analyst"}""", contact.etag())
			.andExpect {
				status { isOk() }
				header { string(HttpHeaders.ETAG, "\"1\"") }
				jsonPath("$.displayName") { value("Countess Lovelace") }
				jsonPath("$.phones.length()") { value(0) }
				jsonPath("$.birthday") { doesNotExist() }
				jsonPath("$.title") { value("Analyst") }
				jsonPath("$.emails[0].address") { value("ada@example.org") }
			}
		searchTitle(id) shouldBe "Countess Lovelace"

		// A cleared display name is derived again.
		patch(id, """{"displayName":null}""", "\"1\"").andExpect { jsonPath("$.displayName") { value("Ada Lovelace") } }
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to id, ChangeOp.UPDATED to id, ChangeOp.UPDATED to id)
	}

	@Test
	fun `GET lists by display name with a cursor and filters by name or email`() {
		newContact("""{"displayName":"Charlie","emails":[{"address":"c@fictional.test"}]}""")
		newContact("""{"displayName":"Alice"}""")
		newContact("""{"displayName":"Bob"}""")

		val first =
			mockMvc
				.get(CONTACTS) { param("limit", "2") }
				.andExpect { status { isOk() } }
				.json()
		first.get("items").values().map { it.get("displayName").asString() } shouldBe listOf("Alice", "Bob")
		listNames("cursor" to first.get("nextCursor").asString()) shouldBe listOf("Charlie")

		listNames("q" to "ALI") shouldBe listOf("Alice")
		listNames("q" to "fictional.test") shouldBe listOf("Charlie")
		listNames("q" to "100%") shouldBe emptyList()
		mockMvc.get(CONTACTS) { param("cursor", "bm9wZQ") }.isProblem(400, "cursor")
	}

	// --- search, links, trash

	@Test
	fun `contacts are found by the global search and resolved for links`() {
		val id = newContact().id()
		val hits = mockMvc.get("/api/v1/search") { param("q", "Analytical") }.andExpect { status { isOk() } }.json()
		hits.toString() shouldContain id

		val resolved = resolvers.find(ItemType.CONTACT)!!.resolve(UUID.fromString(id))!!
		resolved.title shouldBe "Ada Lovelace"
		resolved.subtitle shouldBe "Analytical Engines Ltd"
		resolved.deepLink shouldBe "/contacts/$id"
	}

	@Test
	fun `DELETE moves the contact to the Trash, restore and purge go through the Trash`(events: ApplicationEvents) {
		val contact = newContact()
		val id = contact.id()
		mockMvc.delete("$CONTACTS/$id") { with(csrf()) }.isProblem(428)
		mockMvc
			.delete("$CONTACTS/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, contact.etag())
			}.andExpect { status { isNoContent() } }
		fetch(id).isProblem(404)
		searchTitle(id) shouldBe null
		resolvers.find(ItemType.CONTACT)!!.resolve(UUID.fromString(id)) shouldBe null
		mockMvc
			.get("/api/v1/trash")
			.andExpect { status { isOk() } }
			.json()
			.toString() shouldContain id

		mockMvc.post("/api/v1/trash/CONTACT/$id/restore") { with(csrf()) }.andExpect { status { isNoContent() } }
		fetch(id).andExpect { status { isOk() } }
		searchTitle(id) shouldBe "Ada Lovelace"

		val current = fetch(id).json()
		mockMvc.delete("$CONTACTS/$id") {
			with(csrf())
			header(HttpHeaders.IF_MATCH, current.etag())
		}
		mockMvc.delete("/api/v1/trash/CONTACT/$id") { with(csrf()) }.andExpect { status { isNoContent() } }
		jdbc.queryForObject("select count(*) from contact", Int::class.java) shouldBe 0
		events.changes().first() shouldBe (ChangeOp.CREATED to id)
	}

	// --- vCard

	@Test
	fun `import creates a contact per card, stores embedded photos and skips invalid cards`() {
		val photo = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
		val vcf =
			"""
			BEGIN:VCARD
			VERSION:3.0
			FN:Grace Hopper
			N:Hopper;Grace;;;
			EMAIL;TYPE=INTERNET,WORK:grace@example.org
			TEL;TYPE=CELL:+1 555 0100
			ADR;TYPE=HOME:;;1 Navy Way;Arlington;VA;22201;USA
			ORG:Fictional Navy;Computing
			TITLE:Rear Admiral
			BDAY:1906-12-09
			NOTE:Invented the compiler
			PHOTO;ENCODING=b;TYPE=PNG:$photo
			END:VCARD
			BEGIN:VCARD
			VERSION:3.0
			NOTE:no name at all
			END:VCARD
			BEGIN:VCARD
			VERSION:4.0
			FN:Alan Turing
			BDAY:--0623
			END:VCARD
			""".trimIndent().replace("\n", "\r\n")

		val result = importFile(vcf).andExpect { status { isOk() } }.json()
		val imported = result.get("imported").values().toList()
		imported.map { it.get("displayName").asString() } shouldBe listOf("Grace Hopper", "Alan Turing")
		result.get("skipped").values().map { it.get("index").asInt() } shouldBe listOf(1)

		val grace = imported[0]
		grace.get("givenName").asString() shouldBe "Grace"
		grace.get("familyName").asString() shouldBe "Hopper"
		grace.get("emails")[0].get("address").asString() shouldBe "grace@example.org"
		grace.get("emails")[0].get("label").asString() shouldBe "work"
		grace.get("phones")[0].get("label").asString() shouldBe "cell"
		grace.get("addresses")[0].get("city").asString() shouldBe "Arlington"
		grace.get("organization").asString() shouldBe "Fictional Navy, Computing"
		grace.get("title").asString() shouldBe "Rear Admiral"
		grace.get("birthday").asString() shouldBe "1906-12-09"
		grace.get("notesMd").asString() shouldBe "Invented the compiler"
		val photoId = grace.get("photoFileId").asString()
		mockMvc.get("/api/v1/files/$photoId/content").andExpect {
			status { isOk() }
			content { bytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
		}
		imported[1].get("birthday").isNull shouldBe true
		listNames() shouldBe listOf("Alan Turing", "Grace Hopper")
	}

	@Test
	fun `import rejects a file without vCards`() {
		importFile("hello").isProblem(400, "file")
	}

	@Test
	fun `export writes all contacts as vCard 3_0 that imports back`() {
		val ada = newContact().id()
		newContact("""{"displayName":"Bob"}""")
		val vcf =
			mockMvc
				.get("$CONTACTS/export")
				.andExpect {
					status { isOk() }
					content { contentTypeCompatibleWith("text/vcard") }
					header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contacts.vcf\"") }
				}.andReturn()
				.response.contentAsString
		vcf shouldContain "VERSION:3.0"
		vcf shouldContain "UID:urn:uuid:$ada"
		vcf shouldContain "FN:Ada Lovelace"
		vcf shouldContain "BDAY:1815-12-10"
		vcf shouldContain "FN:Bob"

		val only =
			mockMvc
				.get("$CONTACTS/export") { param("ids", ada) }
				.andReturn()
				.response.contentAsString
		only shouldNotContain "FN:Bob"
		mockMvc.get("$CONTACTS/export") { param("ids", UuidV7.generate().toString()) }.isProblem(404)

		jdbc.execute("delete from contact")
		val back =
			importFile(vcf)
				.andExpect { status { isOk() } }
				.json()
				.get("imported")
				.values()
				.toList()
		back.map { it.get("displayName").asString() } shouldBe listOf("Ada Lovelace", "Bob")
		val copy = back[0]
		copy.get("emails")[0].get("address").asString() shouldBe "ada@example.org"
		copy.get("emails")[0].get("label").asString() shouldBe "work"
		copy.get("phones")[0].get("number").asString() shouldBe "+41 79 000 00 00"
		copy.get("addresses")[0].get("street").asString() shouldBe "12 St James's Square"
		copy.get("organization").asString() shouldBe "Analytical Engines Ltd"
		copy.get("birthday").asString() shouldBe "1815-12-10"
		copy.get("notesMd").asString() shouldBe "Met at the *salon*"
	}

	private companion object {
		const val CONTACTS = "/api/v1/contacts"
		const val ADA =
			"""{"givenName":"Ada","familyName":"Lovelace","emails":[{"address":"ada@example.org","label":"work"}],""" +
				""""phones":[{"number":"+41 79 000 00 00","label":"mobile"}],""" +
				""""addresses":[{"label":"home","street":"12 St James's Square","city":"London","country":"UK"}],""" +
				""""organization":"Analytical Engines Ltd","birthday":"1815-12-10","notesMd":"Met at the *salon*"}"""
	}
}
