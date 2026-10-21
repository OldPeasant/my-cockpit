package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.contacts.domain.Change
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithAnonymousUser
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.UUID

/**
 * Edge cases of the contacts API (SPEC.md §8.3): optimistic locking, list replacement, limits, cursor and filter
 * escaping, Trash, resolver fallbacks, and vCard import/export of the different versions and encodings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@WithCockpitUser
class ContactEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val resolvers: ItemResolverRegistry,
	@Autowired private val service: ContactService,
	@Autowired private val transactionManager: PlatformTransactionManager,
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

	private fun delete(
		id: String,
		ifMatch: String,
	) = mockMvc.delete("$CONTACTS/$id") {
		with(csrf())
		header(HttpHeaders.IF_MATCH, ifMatch)
	}

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun newContact(body: String): JsonNode = post(body).andExpect { status { isCreated() } }.json()

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun fetch(id: String) = mockMvc.get("$CONTACTS/$id")

	private fun importBytes(bytes: ByteArray) =
		mockMvc.multipart("$CONTACTS/import") {
			with(csrf())
			file(MockMultipartFile("file", "contacts.vcf", "text/vcard", bytes))
		}

	private fun importFile(vcf: String) = importBytes(vcf.trimIndent().replace("\n", "\r\n").toByteArray())

	private fun export(vararg ids: String): String =
		mockMvc
			.get("$CONTACTS/export") { ids.forEach { param("ids", it) } }
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun listNames(vararg params: Pair<String, String>): List<String> =
		mockMvc
			.get(CONTACTS) { params.forEach { (name, value) -> param(name, value) } }
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.map { it.get("displayName").asString() }

	/** Walks every page with [limit] and returns the display names in order. */
	private fun allPages(limit: Int): List<String> {
		val names = mutableListOf<String>()
		var cursor: String? = null
		do {
			val page =
				mockMvc
					.get(CONTACTS) {
						param("limit", "$limit")
						cursor?.let { param("cursor", it) }
					}.andExpect { status { isOk() } }
					.json()
			names += page.get("items").values().map { it.get("displayName").asString() }
			cursor = page.get("nextCursor")?.takeUnless { it.isNull }?.asString()
		} while (cursor != null && names.size < 100)
		return names
	}

	private fun searchHits(q: String): String =
		mockMvc
			.get("/api/v1/search") { param("q", q) }
			.andExpect { status { isOk() } }
			.json()
			.toString()

	private fun JsonNode.texts(
		list: String,
		field: String,
	): List<String?> = get(list).values().map { it.get(field)?.takeUnless { v -> v.isNull }?.asString() }

	private fun photo(): String = Base64.getEncoder().encodeToString(PNG)

	// --- ETag / If-Match

	@Test
	fun `If-Match accepts a wildcard and a list of tags, but not a weak tag`() {
		val contact = newContact("""{"displayName":"Ines"}""")
		val id = contact.id()
		patch(id, """{"title":"Engineer"}""", "*").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
		}
		patch(id, """{"title":"Lead"}""", "\"9\", \"1\"").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"2\"") }
		}
		patch(id, """{"title":"Boss"}""", "W/\"2\"").isProblem(412)
		patch(id, """{"title":"Boss"}""", "  ").isProblem(428)
		fetch(id).andExpect { jsonPath("$.title") { value("Lead") } }
		delete(id, "*").andExpect { status { isNoContent() } }
	}

	@Test
	fun `a second client with the old ETag gets 412 and does not overwrite the first change`() {
		val contact = newContact("""{"displayName":"Jonas","title":"Old"}""")
		val id = contact.id()
		val stale = contact.etag()
		patch(id, """{"title":"First"}""", stale).andExpect { status { isOk() } }
		patch(id, """{"title":"Second"}""", stale).isProblem(412)
		delete(id, stale).isProblem(412)
		fetch(id).andExpect {
			jsonPath("$.title") { value("First") }
			header { string(HttpHeaders.ETAG, "\"1\"") }
		}
	}

	@Test
	fun `an update that races a committed concurrent change fails with an optimistic locking error`() {
		val id = UUID.fromString(newContact("""{"displayName":"Karin"}""").id())
		val concurrent =
			TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

		shouldThrow<OptimisticLockingFailureException> {
			service.update(id, ContactChanges(title = Change("Mine"))) {
				// Another request commits between the If-Match check and our write.
				concurrent.executeWithoutResult {
					jdbc.update("update contact set title = 'Theirs', version = version + 1 where id = ?", id)
				}
			}
		}
		jdbc.queryForObject("select title from contact where id = ?", String::class.java, id) shouldBe "Theirs"
		jdbc.queryForObject("select version from contact where id = ?", Long::class.java, id) shouldBe 1L
	}

	@Test
	fun `changes to a contact in the Trash or an unknown contact are 404`() {
		val contact = newContact("""{"displayName":"Lena"}""")
		val id = contact.id()
		delete(id, contact.etag()).andExpect { status { isNoContent() } }
		patch(id, """{"title":"x"}""", "*").isProblem(404)
		delete(id, "*").isProblem(404)
		fetch(UuidV7.generate().toString()).isProblem(404)
		fetch("not-a-uuid").andExpect { status { isBadRequest() } }
	}

	// --- PATCH semantics

	@Test
	fun `PATCH replaces lists as a whole and keeps the lists not sent`() {
		val contact =
			newContact(
				"""{"displayName":"Marta","emails":[{"address":"m1@example.org","label":"work"},{"address":"m2@example.org"}],""" +
					""""phones":[{"number":"+41 31 000 00 01"}],"addresses":[{"city":"Bern"}]}""",
			)
		val id = contact.id()

		val replaced = patch(id, """{"emails":[{"address":"m3@example.org","label":"home"}],"addresses":[]}""", "\"0\"").json()
		replaced.texts("emails", "address") shouldContainExactly listOf("m3@example.org")
		replaced.texts("emails", "label") shouldContainExactly listOf("home")
		replaced.texts("phones", "number") shouldContainExactly listOf("+41 31 000 00 01")
		replaced.get("addresses").size() shouldBe 0

		val cleared = patch(id, """{"emails":null}""", "\"1\"").json()
		cleared.get("emails").size() shouldBe 0
		cleared.texts("phones", "number") shouldContainExactly listOf("+41 31 000 00 01")
		jdbc.queryForObject("select emails::text from contact where id = ?::uuid", String::class.java, id) shouldBe "[]"
	}

	@Test
	fun `PATCH clears the photo with null, and a rejected PATCH changes nothing`() {
		val file =
			mockMvc
				.multipart("/api/v1/files") {
					with(csrf())
					file(MockMultipartFile("file", "nora.png", "image/png", PNG))
				}.andExpect { status { isCreated() } }
				.json()
		val contact = newContact("""{"displayName":"Nora","photoFileId":"${file.id()}"}""")
		val id = contact.id()

		patch(id, """{"title":"x","photoFileId":"${UuidV7.generate()}"}""", "\"0\"").isProblem(400, "photoFileId")
		patch(id, """{"title":"x","emails":[{"address":"broken"}]}""", "\"0\"").isProblem(400, "emails[0].address")
		patch(id, """{"emails":"not-a-list"}""", "\"0\"").isProblem(400, "emails")
		fetch(id).andExpect {
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.title") { doesNotExist() }
			jsonPath("$.photoFileId") { value(file.id()) }
		}

		patch(id, """{"photoFileId":null}""", "\"0\"").andExpect { jsonPath("$.photoFileId") { doesNotExist() } }
	}

	@Test
	fun `a contact whose photo went to the Trash can still be changed`() {
		val file =
			mockMvc
				.multipart("/api/v1/files") {
					with(csrf())
					file(MockMultipartFile("file", "olga.png", "image/png", PNG))
				}.andExpect { status { isCreated() } }
				.json()
		val id = newContact("""{"displayName":"Olga","photoFileId":"${file.id()}"}""").id()
		mockMvc
			.delete("/api/v1/files/${file.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, file.etag())
			}.andExpect { status { isNoContent() } }

		patch(id, """{"title":"Pilot"}""", "\"0\"").andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Pilot") }
		}
		// Setting the photo again is checked: the file is no longer in the drive.
		patch(id, """{"photoFileId":"${file.id()}"}""", "\"1\"").isProblem(400, "photoFileId")
	}

	@Test
	fun `PATCH that clears the display name and every source of it is rejected`() {
		val id = newContact("""{"displayName":"Olga"}""").id()
		patch(id, """{"displayName":""}""", "\"0\"").isProblem(400, "displayName")
		patch(id, """{"displayName":null,"organization":"  Fictional GmbH "}""", "\"0\"").andExpect {
			jsonPath("$.displayName") { value("Fictional GmbH") }
			jsonPath("$.organization") { value("Fictional GmbH") }
		}
	}

	@Test
	fun `fields are trimmed, blank values become null and blank notes are dropped`() {
		newContact(
			"""{"givenName":"  Paul ","familyName":" ","emails":[{"address":" paul@example.org ","label":"  "}],""" +
				""""phones":[{"number":" 079 ","label":" mobile "}],"addresses":[{"city":" Basel ","street":""}],"notesMd":"   "}""",
		).let {
			it.get("displayName").asString() shouldBe "Paul"
			it.get("givenName").asString() shouldBe "Paul"
			it.get("familyName").isNull shouldBe true
			it.texts("emails", "address") shouldContainExactly listOf("paul@example.org")
			it.texts("emails", "label") shouldContainExactly listOf(null)
			it.texts("phones", "number") shouldContainExactly listOf("079")
			it.texts("phones", "label") shouldContainExactly listOf("mobile")
			it.texts("addresses", "street") shouldContainExactly listOf(null)
			it.texts("addresses", "city") shouldContainExactly listOf("Basel")
			it.get("notesMd").isNull shouldBe true
		}
	}

	@Test
	fun `entries without their address or number are rejected`() {
		post("""{"displayName":"Ali","emails":[{"label":"work"}]}""").isProblem(400, "emails[0].address")
		post("""{"displayName":"Ali","phones":[{"label":"cell"}]}""").isProblem(400, "phones[0].number")
		post("""{"displayName":"Ali","emails":[{"address":"a@example.org"},{"address":"two@@example.org"}]}""")
			.isProblem(400, "emails[1].address")
		listNames() shouldBe emptyList()
	}

	// --- limits

	@Test
	fun `a contact has at most 100 emails, phones and addresses together`() {
		fun emails(n: Int) = (1..n).joinToString(",") { """{"address":"p$it@example.org"}""" }
		val phones = (1..40).joinToString(",") { """{"number":"+41 00 $it"}""" }
		newContact("""{"displayName":"Quinn","emails":[${emails(60)}],"phones":[$phones]}""")
		post("""{"displayName":"Rosa","emails":[${emails(61)}],"phones":[$phones]}""").isProblem(400, "emails")
	}

	@Test
	fun `field lengths are limited`() {
		newContact("""{"displayName":"Sara","notesMd":"${"n".repeat(100_000)}"}""")
		post("""{"displayName":"Sara","notesMd":"${"n".repeat(100_001)}"}""").isProblem(400, "notesMd")
		post("""{"displayName":"Sara","phones":[{"number":"1","label":"${"l".repeat(501)}"}]}""").isProblem(400, "phones[0].label")
		post("""{"displayName":"Sara","addresses":[{"city":"${"c".repeat(501)}"}]}""").isProblem(400, "addresses[0]")
		post("""{"displayName":"Sara","emails":[{"address":"a@${"b".repeat(500)}"}]}""").isProblem(400, "emails[0].address")
	}

	// --- list: cursor and filter

	@Test
	fun `the cursor pages through names containing the separator and equal names`() {
		listOf("A|B", "A|C", "A", "A||", "Same", "Same", "Same", "Z|").forEach { newContact("""{"displayName":"$it"}""") }
		val expected = listNames("limit" to "200")
		expected.size shouldBe 8
		allPages(1) shouldBe expected
		allPages(2) shouldBe expected
		allPages(3) shouldBe expected
	}

	@Test
	fun `limit must be between 1 and 200`() {
		mockMvc.get(CONTACTS) { param("limit", "0") }.isProblem(400, "limit")
		mockMvc.get(CONTACTS) { param("limit", "201") }.isProblem(400, "limit")
		mockMvc.get(CONTACTS) { param("limit", "200") }.andExpect { status { isOk() } }
	}

	@Test
	fun `a cursor without the id part is rejected`() {
		val cursor = Base64.getUrlEncoder().withoutPadding().encodeToString("|Alice".toByteArray())
		mockMvc.get(CONTACTS) { param("cursor", cursor) }.isProblem(400, "cursor")
		val noUuid = Base64.getUrlEncoder().withoutPadding().encodeToString("abc|Alice".toByteArray())
		mockMvc.get(CONTACTS) { param("cursor", noUuid) }.isProblem(400, "cursor")
	}

	@Test
	fun `q treats like wildcards and backslashes literally`() {
		listOf("a_b", "axb", "50%", "500", "back\\\\slash", "backslash").forEach { newContact("""{"displayName":"$it"}""") }
		listNames("q" to "_") shouldBe listOf("a_b")
		listNames("q" to "%") shouldBe listOf("50%")
		listNames("q" to "\\") shouldBe listOf("back\\slash")
		listNames("q" to "   ").size shouldBe 6
	}

	@Test
	fun `q excludes contacts in the Trash`() {
		val gone = newContact("""{"displayName":"Tanja"}""")
		newContact("""{"displayName":"Tanja Two"}""")
		delete(gone.id(), gone.etag()).andExpect { status { isNoContent() } }
		listNames("q" to "tanja") shouldBe listOf("Tanja Two")
		listNames() shouldBe listOf("Tanja Two")
	}

	@Test
	fun `q matches email addresses but not the labels or JSON keys around them`() {
		newContact("""{"displayName":"Ulla","emails":[{"address":"ulla@example.org","label":"work"}]}""")
		listNames("q" to "ULLA@EXAMPLE") shouldBe listOf("Ulla")
		// The assumption: q is a substring of the display name or an email address. "address", "label" and "work"
		// are none of these.
		listNames("q" to "address") shouldBe emptyList()
		listNames("q" to "label") shouldBe emptyList()
		listNames("q" to "work") shouldBe emptyList()
	}

	// --- search, resolver, trash

	@Test
	fun `search follows changes of the indexed fields`() {
		val contact = newContact("""{"displayName":"Viktor","phones":[{"number":"+41 99 123"}],"notesMd":"likes zebrafish"}""")
		val id = contact.id()
		searchHits("zebrafish") shouldContain id
		patch(id, """{"notesMd":"likes quokkas","organization":"Fictional Labs"}""", "\"0\"").andExpect { status { isOk() } }
		searchHits("zebrafish") shouldNotContain id
		searchHits("quokkas") shouldContain id
		searchHits("Fictional Labs") shouldContain id
	}

	@Test
	fun `the resolver falls back to the email address and knows no deleted or unknown contact`() {
		val resolver = resolvers.find(ItemType.CONTACT)!!
		val withEmail = newContact("""{"displayName":"Wanda","emails":[{"address":"wanda@example.org"}]}""").id()
		val bare = newContact("""{"displayName":"Xaver"}""").id()
		resolver.resolve(UUID.fromString(withEmail))!!.subtitle shouldBe "wanda@example.org"
		resolver.resolve(UUID.fromString(bare))!!.subtitle.shouldBeNull()
		resolver.resolve(UUID.fromString(bare))!!.icon shouldBe "person"
		resolver.resolve(UuidV7.generate()).shouldBeNull()
		resolver.exists(UUID.fromString(bare)) shouldBe true
	}

	@Test
	fun `restore re-indexes the latest state, purge needs the contact to be in the Trash`() {
		val contact = newContact("""{"displayName":"Yara"}""")
		val id = contact.id()
		patch(id, """{"displayName":"Yara Renamed"}""", "\"0\"").andExpect { status { isOk() } }
		mockMvc.delete("/api/v1/trash/CONTACT/$id") { with(csrf()) }.andExpect { status { isNotFound() } }
		mockMvc.post("/api/v1/trash/CONTACT/$id/restore") { with(csrf()) }.andExpect { status { isNotFound() } }
		delete(id, "\"1\"").andExpect { status { isNoContent() } }

		val trash = mockMvc.get("/api/v1/trash").json().toString()
		trash shouldContain "Yara Renamed"
		searchHits("Renamed") shouldNotContain id

		mockMvc.post("/api/v1/trash/CONTACT/$id/restore") { with(csrf()) }.andExpect { status { isNoContent() } }
		searchHits("Renamed") shouldContain id
		listNames() shouldBe listOf("Yara Renamed")
		mockMvc.get("/api/v1/trash").json().toString() shouldNotContain id
	}

	// --- vCard import

	@Test
	fun `import reads vCard 2_1 with quoted-printable, charsets, bare types and a base64 photo`() {
		val result =
			importFile(
				"""
				BEGIN:VCARD
				VERSION:2.1
				N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller;J=C3=BCrg;;;
				FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:J=C3=BCrg M=C3=BCller
				TEL;HOME;VOICE:+41 44 000 00 00
				EMAIL;INTERNET;PREF:juerg@example.ch
				ADR;WORK:;;Bahnhofstrasse 1;Zurich;ZH;8001;Switzerland
				NOTE;CHARSET=ISO-8859-1;ENCODING=QUOTED-PRINTABLE:Gr=FC=DFe
				PHOTO;ENCODING=BASE64;TYPE=PNG:${photo()}

				END:VCARD
				""",
			).andExpect { status { isOk() } }
				.json()
		result.get("skipped").size() shouldBe 0
		val card = result.get("imported")[0]
		card.get("displayName").asString() shouldBe "Jürg Müller"
		card.get("givenName").asString() shouldBe "Jürg"
		card.get("familyName").asString() shouldBe "Müller"
		card.texts("phones", "label") shouldContainExactly listOf("home, voice")
		card.texts("emails", "address") shouldContainExactly listOf("juerg@example.ch")
		card.texts("emails", "label") shouldContainExactly listOf(null)
		card.texts("addresses", "label") shouldContainExactly listOf("work")
		card.texts("addresses", "postalCode") shouldContainExactly listOf("8001")
		card.get("notesMd").asString() shouldBe "Grüße"
		mockMvc.get("/api/v1/files/${card.get("photoFileId").asString()}/content").andExpect {
			status { isOk() }
			content { bytes(PNG) }
		}
	}

	@Test
	fun `import reads vCard 4_0 tel URIs, data URI photos and basic dates, and ignores photo URLs`() {
		val result =
			importFile(
				"""
				BEGIN:VCARD
				VERSION:4.0
				FN:Hedy Lamarr
				N:Lamarr;Hedy;;;
				TEL;VALUE=uri;TYPE="voice,home":tel:+1-555-0199
				EMAIL;TYPE=home;PREF=1:hedy@example.net
				BDAY:19141109
				PHOTO:data:image/png;base64,${photo()}
				END:VCARD
				BEGIN:VCARD
				VERSION:4.0
				FN:Url Photo
				PHOTO:http://example.invalid/photo.jpg
				END:VCARD
				""",
			).andExpect { status { isOk() } }
				.json()
		val (hedy, url) = result.get("imported").values().toList()
		hedy.texts("phones", "number") shouldContainExactly listOf("+1-555-0199")
		hedy.texts("phones", "label") shouldContainExactly listOf("voice, home")
		hedy.texts("emails", "label") shouldContainExactly listOf("home")
		hedy.get("birthday").asString() shouldBe "1914-11-09"
		mockMvc.get("/api/v1/files/${hedy.get("photoFileId").asString()}/content").andExpect { content { bytes(PNG) } }
		url.get("photoFileId").isNull shouldBe true
		jdbc.queryForObject("select count(*) from file", Int::class.java) shouldBe 1
	}

	@Test
	fun `a skipped card leaves no photo behind and says why it was skipped`() {
		val cards =
			listOf(
				listOf("NOTE:no name, but a photo", "PHOTO;ENCODING=b;TYPE=PNG:${photo()}"),
				listOf("FN:Too Many") + (1..101).map { "EMAIL:many$it@example.org" },
				listOf("FN:Bad Mail", "EMAIL:not-an-address"),
				listOf("FN:Fine"),
			)
		val vcf =
			cards.joinToString("") { lines ->
				val card = listOf("BEGIN:VCARD", "VERSION:3.0") + lines + "END:VCARD"
				card.joinToString("\r\n", postfix = "\r\n")
			}
		val result =
			importBytes(vcf.toByteArray())
				.andExpect { status { isOk() } }
				.json()
		result.texts("imported", "displayName") shouldContainExactly listOf("Fine")
		val skipped = result.get("skipped").values().toList()
		skipped.map { it.get("index").asInt() } shouldBe listOf(0, 1, 2)
		skipped[0].get("reason").asString() shouldContain "displayName"
		skipped[1].get("reason").asString() shouldContain "100"
		skipped[2].get("reason").asString() shouldContain "emails[0].address"
		jdbc.queryForObject("select count(*) from file", Int::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from contact", Int::class.java) shouldBe 1
	}

	@Test
	fun `photo file names of imported cards are made safe`() {
		val result =
			importFile(
				"""
				BEGIN:VCARD
				VERSION:3.0
				FN:AC/DC \\ Band
				PHOTO;ENCODING=b;TYPE=PNG:${photo()}
				END:VCARD
				""",
			).andExpect { status { isOk() } }
				.json()
		val fileId = result.get("imported")[0].get("photoFileId").asString()
		val name = jdbc.queryForObject("select name from file where id = ?::uuid", String::class.java, fileId)
		name shouldStartWith "Contact photo AC_DC"
		name shouldNotContain "/"
	}

	@Test
	fun `a C1 control character in the name does not cost the card its import`() {
		val result =
			importFile(
				"""
				BEGIN:VCARD
				VERSION:3.0
				FN:Paul${'\u0085'}Next
				PHOTO;ENCODING=b;TYPE=PNG:${photo()}
				END:VCARD
				""",
			).andExpect { status { isOk() } }
				.json()
		result.get("skipped").size() shouldBe 0
		val fileId = result.get("imported")[0].get("photoFileId").asString()
		jdbc.queryForObject("select name from file where id = ?::uuid", String::class.java, fileId) shouldBe "Contact photo Paul_Next.png"
	}

	@Test
	fun `photos without a type are stored as JPEG, named after the family name or Contact`() {
		val result =
			importFile(
				"""
				BEGIN:VCARD
				VERSION:3.0
				N:Doe;Jane;;;
				PHOTO;ENCODING=b:${photo()}
				END:VCARD
				BEGIN:VCARD
				VERSION:3.0
				EMAIL:anon@example.org
				PHOTO;ENCODING=b:${photo()}
				END:VCARD
				""",
			).andExpect { status { isOk() } }
				.json()
		val files =
			result.get("imported").values().map {
				jdbc.queryForMap("select name, mime from file where id = ?::uuid", it.get("photoFileId").asString())
			}
		files.map { it["name"] } shouldContainExactly listOf("Contact photo Doe.jpg", "Contact photo Contact.jpg")
		files.map { it["mime"] } shouldContainExactly listOf("image/jpeg", "image/jpeg")
	}

	@Test
	fun `empty email, phone, address and organization properties are dropped instead of costing the card its import`() {
		val result =
			importFile(
				"""
				BEGIN:VCARD
				VERSION:3.0
				FN:Rhea Blank
				EMAIL;TYPE=INTERNET:
				EMAIL;TYPE=WORK:rhea@example.org
				TEL;TYPE=CELL:
				TEL;TYPE=HOME:+41 31 000 00 00
				ADR;TYPE=HOME:;;;;;;
				ORG:;
				END:VCARD
				""",
			).andExpect { status { isOk() } }
				.json()
		result.get("skipped").size() shouldBe 0
		val card = result.get("imported")[0]
		card.get("displayName").asString() shouldBe "Rhea Blank"
		card.texts("emails", "address") shouldContainExactly listOf("rhea@example.org")
		card.texts("phones", "number") shouldContainExactly listOf("+41 31 000 00 00")
		card.get("addresses").size() shouldBe 0
		card.get("organization").isNull shouldBe true
	}

	@Test
	fun `a photo over 5 MB is left out, the card is still imported`() {
		val big = Base64.getEncoder().encodeToString(ByteArray(5 * 1024 * 1024 + 1) { 7 })
		val vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Big Picture\r\nPHOTO;ENCODING=b;TYPE=PNG:$big\r\nEND:VCARD\r\n"
		val card = importBytes(vcf.toByteArray()).andExpect { status { isOk() } }.json().get("imported")[0]
		card.get("displayName").asString() shouldBe "Big Picture"
		card.get("photoFileId").isNull shouldBe true
		jdbc.queryForObject("select count(*) from file", Int::class.java) shouldBe 0
	}

	@Test
	fun `import refuses files over 20 MB and with more than 10000 cards`() {
		importBytes(ByteArray(20 * 1024 * 1024 + 1) { 'x'.code.toByte() }).isProblem(400, "file")
		val tooMany = (0..10_000).joinToString("") { "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:C$it\r\nEND:VCARD\r\n" }
		importBytes(tooMany.toByteArray()).isProblem(400, "file")
		jdbc.queryForObject("select count(*) from contact", Int::class.java) shouldBe 0
	}

	@Test
	fun `import of a file that is not UTF-8 or not a vCard does not fail with a server error`() {
		val latin1 = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Jürg\r\nEND:VCARD\r\n".toByteArray(Charsets.ISO_8859_1)
		importBytes(latin1).andExpect {
			status { isOk() }
			jsonPath("$.imported.length()") { value(1) }
		}
		importBytes(byteArrayOf(0, -1, -2, 3, 0x7f, -128)).isProblem(400, "file")
		importBytes(ByteArray(0)).isProblem(400, "file")
	}

	// --- vCard export

	@Test
	fun `labels with several types and every field survive an export and import round-trip`() {
		val original =
			newContact(
				"""{"givenName":"Zoe","familyName":"Quill","title":"Cartographer","organization":"Fictional Maps",""" +
					""""emails":[{"address":"zoe@example.org","label":"work, home"},{"address":"zq@example.net"}],""" +
					""""phones":[{"number":"+41 21 000 00 00","label":"home, voice"},{"number":"+41 79 111 11 11","label":"cell"}],""" +
					""""addresses":[{"label":"work, postal","street":"Rue 1","city":"Lausanne","region":"VD","postalCode":"1000","country":"CH"}],""" +
					""""birthday":"1990-02-28","notesMd":"Line one\nLine two, with; punctuation"}""",
			)
		val vcf = export(original.id())
		vcf shouldContain "UID:urn:uuid:${original.id()}"

		jdbc.execute("delete from contact")
		val copy =
			importBytes(vcf.toByteArray())
				.andExpect { status { isOk() } }
				.json()
				.get("imported")[0]
		for (field in listOf("displayName", "givenName", "familyName", "title", "organization", "birthday", "notesMd")) {
			copy.get(field).asString() shouldBe original.get(field).asString()
		}
		copy.texts("emails", "address") shouldContainExactly listOf("zoe@example.org", "zq@example.net")
		copy.texts("emails", "label") shouldContainExactly listOf("work, home", null)
		copy.texts("phones", "label") shouldContainExactly listOf("home, voice", "cell")
		copy.get("addresses")[0].get("label").asString() shouldBe "work, postal"
		for (part in listOf("street", "city", "region", "postalCode", "country")) {
			copy.get("addresses")[0].get(part).asString() shouldBe original.get("addresses")[0].get(part).asString()
		}
	}

	@Test
	fun `export keeps a free-text label with characters vCard parameters cannot hold`() {
		val id = newContact("""{"displayName":"Quote","phones":[{"number":"1","label":"Office \"Zurich\": main"}]}""").id()
		val vcf = export(id)
		vcf shouldContain "FN:Quote"
		vcf shouldContain "TEL;TYPE=\"office 'zurich': main\":1"
	}

	@Test
	fun `export lists contacts by name, once per id, and leaves out the Trash`() {
		val b = newContact("""{"displayName":"Bea"}""")
		val a = newContact("""{"displayName":"Abe"}""")
		val gone = newContact("""{"displayName":"Gone"}""")
		delete(gone.id(), gone.etag()).andExpect { status { isNoContent() } }

		val all = export()
		all shouldNotContain "FN:Gone"
		(all.indexOf("FN:Abe") < all.indexOf("FN:Bea")) shouldBe true
		all.split("BEGIN:VCARD").size - 1 shouldBe 2

		export(b.id(), b.id()).split("BEGIN:VCARD").size - 1 shouldBe 1
		mockMvc.get("$CONTACTS/export") { param("ids", gone.id()) }.isProblem(404)
		export(a.id()) shouldNotContain "FN:Bea"
	}

	@Test
	fun `export of no contacts is an empty file`() {
		export() shouldBe ""
	}

	@Test
	fun `export with an empty ids parameter exports every contact`() {
		newContact("""{"displayName":"Abe"}""")
		newContact("""{"displayName":"Bea"}""")
		val vcf =
			mockMvc
				.get("$CONTACTS/export") { param("ids", "") }
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString
		vcf shouldContain "FN:Abe"
		vcf shouldContain "FN:Bea"
	}

	// --- access, request shape, filter combinations

	@Test
	@WithAnonymousUser
	fun `every contacts endpoint needs a login`() {
		val id = UuidV7.generate().toString()
		mockMvc.get(CONTACTS).andExpect { status { isUnauthorized() } }
		fetch(id).andExpect { status { isUnauthorized() } }
		post("""{"displayName":"Intruder"}""").andExpect { status { isUnauthorized() } }
		patch(id, """{"title":"x"}""", "*").andExpect { status { isUnauthorized() } }
		delete(id, "*").andExpect { status { isUnauthorized() } }
		importFile("BEGIN:VCARD\nVERSION:3.0\nFN:Intruder\nEND:VCARD\n").andExpect { status { isUnauthorized() } }
		mockMvc.get("$CONTACTS/export").andExpect { status { isUnauthorized() } }
		jdbc.queryForObject("select count(*) from contact", Int::class.java) shouldBe 0
	}

	@Test
	fun `changing requests without a CSRF token are refused and change nothing`() {
		val contact = newContact("""{"displayName":"Hedy"}""")
		mockMvc
			.post(CONTACTS) {
				contentType = MediaType.APPLICATION_JSON
				content = """{"displayName":"Forged"}"""
			}.andExpect { status { isForbidden() } }
		mockMvc
			.patch("$CONTACTS/${contact.id()}") {
				contentType = MediaType.APPLICATION_JSON
				content = """{"title":"Forged"}"""
				header(HttpHeaders.IF_MATCH, contact.etag())
			}.andExpect { status { isForbidden() } }
		mockMvc
			.delete("$CONTACTS/${contact.id()}") { header(HttpHeaders.IF_MATCH, contact.etag()) }
			.andExpect { status { isForbidden() } }
		listNames() shouldBe listOf("Hedy")
		fetch(contact.id()).andExpect {
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.title") { doesNotExist() }
		}
	}

	@Test
	fun `a folder of the drive is not a photo`() {
		val folder =
			mockMvc
				.post("/api/v1/folders") {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = """{"name":"Portraits"}"""
				}.andExpect { status { isCreated() } }
				.json()
		post("""{"displayName":"Lise","photoFileId":"${folder.id()}"}""").isProblem(400, "photoFileId")
		listNames() shouldBe emptyList()
	}

	@Test
	fun `q stays applied on every page of the cursor`() {
		listOf("Mia Example", "Other", "Mila Example", "Mike Example", "Zed").forEach { newContact("""{"displayName":"$it"}""") }
		newContact("""{"displayName":"Nobody","emails":[{"address":"mi.example@example.org"}]}""")
		val names = mutableListOf<String>()
		var cursor: String? = null
		do {
			val page =
				mockMvc
					.get(CONTACTS) {
						param("q", "mi")
						param("limit", "1")
						cursor?.let { param("cursor", it) }
					}.andExpect { status { isOk() } }
					.json()
			names += page.get("items").values().map { it.get("displayName").asString() }
			cursor = page.get("nextCursor")?.takeUnless { it.isNull }?.asString()
		} while (cursor != null && names.size < 20)
		names shouldContainExactly listOf("Mia Example", "Mike Example", "Mila Example", "Nobody")
	}

	@Test
	fun `a body that is not a JSON object and an import without the file part are 400`() {
		post("""[{"displayName":"Array"}]""").andExpect { status { isBadRequest() } }
		post("""{"displayName":""").andExpect { status { isBadRequest() } }
		mockMvc
			.multipart("$CONTACTS/import") {
				with(csrf())
				file(MockMultipartFile("other", "contacts.vcf", "text/vcard", "BEGIN:VCARD".toByteArray()))
			}.andExpect { status { isBadRequest() } }
		listNames() shouldBe emptyList()
	}

	@Test
	fun `values of the wrong type are 400 naming the field, and a rejected PATCH changes nothing`() {
		post("""{"displayName":"A","emails":"a@example.org"}""").isProblem(400, "emails")
		post("""{"displayName":"A","phones":[{"number":{"nested":true}}]}""").isProblem(400, "phones")
		post("""{"displayName":"A","photoFileId":"not-a-uuid"}""").isProblem(400, "photoFileId")
		post("""{"displayName":["A"]}""").isProblem(400, "displayName")
		listNames() shouldBe emptyList()

		val contact = newContact("""{"displayName":"Olga","title":"Kept"}""")
		patch(contact.id(), """{"title":"Lost","addresses":"Main Street"}""", contact.etag()).isProblem(400, "addresses")
		fetch(contact.id()).andExpect {
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.title") { value("Kept") }
		}
	}

	// --- vCard import: display name derivation and integration with search, links and change events

	@Test
	fun `imported cards without FN get the display name derived from N, ORG or EMAIL`() {
		val result =
			importFile(
				"""
				BEGIN:VCARD
				VERSION:3.0
				N:Example;Erika;;;
				END:VCARD
				BEGIN:VCARD
				VERSION:3.0
				ORG:Fictional Widgets AG
				END:VCARD
				BEGIN:VCARD
				VERSION:3.0
				EMAIL:only.mail@example.org
				END:VCARD
				BEGIN:VCARD
				VERSION:3.0
				FN:
				N:;;;;
				END:VCARD
				""",
			).andExpect { status { isOk() } }
				.json()
		result.texts("imported", "displayName") shouldContainExactly
			listOf("Erika Example", "Fictional Widgets AG", "only.mail@example.org")
		result.get("skipped").values().map { it.get("index").asInt() } shouldBe listOf(3)
		result.get("skipped")[0].get("reason").asString() shouldContain "displayName"
	}

	@Test
	fun `imported contacts are searchable, resolvable and announced as created`(events: ApplicationEvents) {
		val result =
			importFile(
				"""
				BEGIN:VCARD
				VERSION:3.0
				FN:Quentin Quokka
				ORG:Fictional Marsupials Ltd
				NOTE:met at the zoo of nowhere
				END:VCARD
				""",
			).andExpect { status { isOk() } }
				.json()
		val id = result.get("imported")[0].id()

		searchHits("Quokka") shouldContain id
		searchHits("nowhere") shouldContain id
		val resolved = resolvers.find(ItemType.CONTACT)!!.resolve(UUID.fromString(id))!!
		resolved.title shouldBe "Quentin Quokka"
		resolved.subtitle shouldBe "Fictional Marsupials Ltd"
		events
			.stream(ChangeEvent::class.java)
			.toList()
			.filter { it.type == "CONTACT" }
			.map { it.op to it.id.toString() } shouldContainExactly listOf(ChangeOp.CREATED to id)
	}

	private companion object {
		const val CONTACTS = "/api/v1/contacts"
		val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
	}
}
