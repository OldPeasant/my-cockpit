package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import com.github.tomakehurst.wiremock.client.WireMock.get as stubGet

/**
 * The bookmarks API against Postgres, with the pages served by WireMock (private networks allowed only for this test
 * context: the SSRF checks themselves are covered by [PageFetcherTests]).
 */
@SpringBootTest(
	properties = [
		"cockpit.bookmarks.fetch.allow-private-networks=true",
		"cockpit.bookmarks.fetch.timeout=2s",
		"cockpit.bookmarks.fetch.max-size=64KB",
	],
)
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookmarkWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val resolvers: ItemResolverRegistry,
) : PostgresTestBase() {
	private val server = WireMockServer(wireMockConfig().dynamicPort())

	@BeforeAll
	fun start() = server.start()

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	@AfterEach
	fun reset() {
		server.resetAll()
		jdbc.execute("delete from bookmark")
		jdbc.execute("delete from link")
		jdbc.execute("delete from search_document")
		clock.set(MutableClockTestConfiguration.START)
	}

	// --- helpers

	private fun page(path: String) = "http://127.0.0.1:${server.port()}$path"

	private fun stubPage(
		path: String,
		html: String,
	) = server.stubFor(stubGet(urlEqualTo(path)).willReturn(aResponse().withHeader("Content-Type", "text/html; charset=utf-8").withBody(html)))

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun post(
		path: String,
		body: String? = null,
		ifMatch: String? = null,
	) = mockMvc.post(path) {
		with(csrf())
		body?.let {
			contentType = MediaType.APPLICATION_JSON
			content = it
		}
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun patch(
		id: String,
		body: String,
		ifMatch: String?,
	) = mockMvc.patch("$BOOKMARKS/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun newBookmark(body: String): JsonNode = post(BOOKMARKS, body).andExpect { status { isCreated() } }.json()

	private fun fetch(id: String): JsonNode = mockMvc.get("$BOOKMARKS/$id").andExpect { status { isOk() } }.json()

	private fun change(
		bookmark: JsonNode,
		body: String,
	): JsonNode = patch(bookmark.id(), body, bookmark.etag()).andExpect { status { isOk() } }.json()

	private fun list(query: String = ""): JsonNode = mockMvc.get("$BOOKMARKS$query").andExpect { status { isOk() } }.json()

	/** The bookmark once its background metadata fetch has finished. */
	private fun settled(id: String): JsonNode {
		val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
		while (System.nanoTime() < deadline) {
			val bookmark = fetch(id)
			if (bookmark.get("metadataStatus").asString() != "PENDING") return bookmark
			Thread.sleep(25)
		}
		error("The metadata of bookmark $id was not fetched in time")
	}

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

	private fun JsonNode.titles(): List<String> = get("items").values().map { it.get("displayTitle").asString() }

	private fun searchDocument(id: String): Map<String, Any?>? =
		jdbc
			.queryForList("select title, body from search_document where item_type = 'BOOKMARK' and item_id = ?::uuid", id)
			.singleOrNull()

	// --- create and metadata

	@Test
	fun `create returns a pending bookmark, then title, description and favicon are fetched from the page`() {
		stubPage(
			"/article",
			"""
			<html><head><title>Fallback</title>
			<meta property="og:title" content="How to grow tomatoes">
			<meta property="og:description" content="  A guide
			   for the balcony ">
			<link rel="icon" href="/img/fav.png">
			</head><body>...</body></html>
			""".trimIndent(),
		)

		val result = post(BOOKMARKS, """{"url":"  ${page("/article")} ","tags":["Garden"],"readLater":true}""").andReturn()
		val created = jsonMapper.readTree(result.response.contentAsString)

		result.response.status shouldBe 201
		result.response.getHeader(HttpHeaders.ETAG) shouldBe created.etag()
		result.response.getHeader(HttpHeaders.LOCATION)!!.endsWith("$BOOKMARKS/${created.id()}") shouldBe true
		created.text("url") shouldBe page("/article")
		created.get("tags").values().map { it.asString() } shouldContainExactly listOf("Garden")
		created.get("readLater").asBoolean() shouldBe true
		created.get("readAt").isNull shouldBe true

		val bookmark = settled(created.id())
		bookmark.text("metadataStatus") shouldBe "FETCHED"
		bookmark.text("title") shouldBe "How to grow tomatoes"
		bookmark.text("displayTitle") shouldBe "How to grow tomatoes"
		bookmark.text("description") shouldBe "A guide for the balcony"
		bookmark.text("favicon") shouldBe page("/img/fav.png")
		(bookmark.get("version").asLong() > created.get("version").asLong()) shouldBe true
		searchDocument(created.id())!!["title"] shouldBe "How to grow tomatoes"
	}

	@Test
	fun `a title and description the user gave are kept, the empty favicon is filled`() {
		stubPage("/p", """<head><title>Page title</title><meta name="description" content="Page text"></head>""")

		val bookmark = settled(newBookmark("""{"url":"${page("/p")}","title":"My title","description":"My note"}""").id())

		bookmark.text("title") shouldBe "My title"
		bookmark.text("description") shouldBe "My note"
		bookmark.text("favicon") shouldBe page("/favicon.ico")
	}

	@Test
	fun `a page that cannot be fetched leaves the fields empty with status FAILED`() {
		server.stubFor(stubGet(urlEqualTo("/gone")).willReturn(aResponse().withStatus(404)))
		server.stubFor(stubGet(urlEqualTo("/file.pdf")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody("%PDF")))

		for (path in listOf("/gone", "/file.pdf")) {
			val bookmark = settled(newBookmark("""{"url":"${page(path)}"}""").id())

			bookmark.text("metadataStatus") shouldBe "FAILED"
			bookmark.text("title") shouldBe null
			bookmark.text("favicon") shouldBe null
			bookmark.text("displayTitle") shouldBe "127.0.0.1"
		}
	}

	@Test
	fun `a new URL clears the favicon and fetches again, filling only empty fields`() {
		stubPage("/one", """<head><title>One</title><link rel="icon" href="/one.ico"></head>""")
		stubPage("/two", """<head><title>Two</title><meta name="description" content="Second"><link rel="icon" href="/two.ico"></head>""")
		val first = settled(newBookmark("""{"url":"${page("/one")}"}""").id())

		val changed = change(first, """{"url":"${page("/two")}"}""")

		changed.text("metadataStatus") shouldBe "PENDING"
		changed.text("favicon") shouldBe null
		val bookmark = settled(first.id())
		bookmark.text("title") shouldBe "One"
		bookmark.text("description") shouldBe "Second"
		bookmark.text("favicon") shouldBe page("/two.ico")
	}

	@Test
	fun `refresh-metadata fetches again and replaces title, description and favicon`() {
		stubPage("/p", """<head><title>Old title</title></head>""")
		val first = settled(newBookmark("""{"url":"${page("/p")}","description":"mine"}""").id())
		stubPage("/p", """<head><title>New title</title><meta name="description" content="New text"><link rel="icon" href="/n.ico"></head>""")

		post("$BOOKMARKS/${first.id()}/refresh-metadata").andExpect { status { isPreconditionRequired() } }
		val refreshed = post("$BOOKMARKS/${first.id()}/refresh-metadata", ifMatch = first.etag()).andExpect { status { isOk() } }.json()

		refreshed.text("metadataStatus") shouldBe "PENDING"
		val bookmark = settled(first.id())
		bookmark.text("title") shouldBe "New title"
		bookmark.text("description") shouldBe "New text"
		bookmark.text("favicon") shouldBe page("/n.ico")
	}

	@Test
	fun `a fetch that finishes after the URL changed again is ignored`() {
		server.stubFor(
			stubGet(urlEqualTo("/slow")).willReturn(
				aResponse().withHeader("Content-Type", "text/html").withBody("<title>Slow</title>").withFixedDelay(700),
			),
		)
		stubPage("/fast", "<title>Fast</title>")
		val created = newBookmark("""{"url":"${page("/slow")}"}""")

		change(created, """{"url":"${page("/fast")}"}""")
		val bookmark = settled(created.id())
		Thread.sleep(1_000)

		fetch(created.id()).text("title") shouldBe "Fast"
		bookmark.text("title") shouldBe "Fast"
	}

	// --- validation

	@Test
	fun `invalid URLs and fields are rejected with 400`() {
		for (body in listOf(
			"{}",
			"""{"url":""}""",
			"""{"url":"ftp://example.org/x"}""",
			"""{"url":"javascript:alert(1)"}""",
			"""{"url":"https:///no-host"}""",
			"""{"url":"https://example.org/${"x".repeat(2_100)}"}""",
			"""{"url":"https://example.org/","title":"${"t".repeat(501)}"}""",
			"""{"url":"https://example.org/","tags":[" "]}""",
			"""{"url":"https://example.org/","tags":[null]}""",
		)) {
			post(BOOKMARKS, body).andExpect { status { isBadRequest() } }
		}
		jdbc.queryForObject("select count(*) from bookmark", Long::class.java) shouldBe 0
	}

	@Test
	fun `PATCH changes only the sent fields, needs a current If-Match and rejects unknown fields and nulls`() {
		val bookmark = newBookmark("""{"url":"https://unreachable.invalid/a","title":"A","description":"About A","tags":["x"]}""")

		patch(bookmark.id(), """{"title":"B"}""", null).andExpect { status { isPreconditionRequired() } }
		patch(bookmark.id(), """{"title":"B"}""", "\"999\"").andExpect { status { isPreconditionFailed() } }
		patch(bookmark.id(), """{"color":"red"}""", bookmark.etag()).andExpect { status { isBadRequest() } }
		patch(bookmark.id(), """{"url":null}""", bookmark.etag()).andExpect { status { isBadRequest() } }
		patch(bookmark.id(), """{"readLater":"maybe"}""", bookmark.etag()).andExpect { status { isBadRequest() } }
		val current = fetch(bookmark.id())
		val changed = change(current, """{"description":null,"tags":["y","Y"]}""")

		changed.text("title") shouldBe "A"
		changed.text("description") shouldBe null
		changed.get("tags").values().map { it.asString() } shouldContainExactly listOf("y")
		changed.text("url") shouldBe "https://unreachable.invalid/a"
	}

	@Test
	fun `unknown bookmarks give 404`() {
		val id = UUID.randomUUID()
		mockMvc.get("$BOOKMARKS/$id").andExpect { status { isNotFound() } }
		patch(id.toString(), """{"title":"x"}""", "\"0\"").andExpect { status { isNotFound() } }
		post("$BOOKMARKS/$id/refresh-metadata", ifMatch = "\"0\"").andExpect { status { isNotFound() } }
	}

	// --- read later, tags, list

	@Test
	fun `read-later queue, read state and filters`() {
		val queued = newBookmark("""{"url":"https://unreachable.invalid/1","title":"Queued","readLater":true}""")
		clock.advance(Duration.ofMinutes(1))
		val done = newBookmark("""{"url":"https://unreachable.invalid/2","title":"Done","readLater":true}""")
		clock.advance(Duration.ofMinutes(1))
		newBookmark("""{"url":"https://unreachable.invalid/3","title":"Reference"}""")
		settled(queued.id())
		val doneNow = settled(done.id())

		clock.advance(Duration.ofMinutes(1))
		val read = change(doneNow, """{"read":true}""")
		val readAt = read.text("readAt")!!
		clock.advance(Duration.ofMinutes(1))
		change(read, """{"read":true}""").text("readAt") shouldBe readAt

		list().titles() shouldContainExactly listOf("Reference", "Done", "Queued")
		list("?readLater=true&read=false").titles() shouldContainExactly listOf("Queued")
		list("?read=true").titles() shouldContainExactly listOf("Done")
		list("?readLater=false").titles() shouldContainExactly listOf("Reference")

		change(fetch(done.id()), """{"read":false}""").get("readAt").isNull shouldBe true
		list("?readLater=true&read=false").titles() shouldContainExactly listOf("Done", "Queued")
	}

	@Test
	fun `tags reuse the spelling of other bookmarks, filter the list and are counted`() {
		newBookmark("""{"url":"https://unreachable.invalid/1","title":"One","tags":["Kotlin","#news"]}""")
		newBookmark("""{"url":"https://unreachable.invalid/2","title":"Two","tags":["kotlin"]}""")

		list("?tag=KOTLIN").titles() shouldContainExactly listOf("Two", "One")
		mockMvc.get(BOOKMARKS) { param("tag", "#news") }.json().titles() shouldContainExactly listOf("One")
		val tags =
			mockMvc
				.get("$BOOKMARKS/tags")
				.andExpect { status { isOk() } }
				.json()
				.get("items")
		tags.values().map { it.text("tag") to it.get("count").asLong() } shouldContainExactly listOf("Kotlin" to 2L, "news" to 1L)
	}

	@Test
	fun `list pages with a cursor`() {
		repeat(3) {
			newBookmark("""{"url":"https://unreachable.invalid/$it","title":"B$it"}""")
			clock.advance(Duration.ofSeconds(1))
		}

		val first = list("?limit=2")
		first.titles() shouldContainExactly listOf("B2", "B1")
		val second = list("?limit=2&cursor=${first.text("nextCursor")}")
		second.titles() shouldContainExactly listOf("B0")
		second.get("nextCursor").isNull shouldBe true
		mockMvc.get("$BOOKMARKS?cursor=bm90LWEtY3Vyc29y").andExpect { status { isBadRequest() } }
	}

	// --- resolver, search, trash, events

	@Test
	fun `bookmarks resolve for generic views`() {
		val bookmark = newBookmark("""{"url":"https://www.unreachable.invalid/a","readLater":true}""")
		val resolver = resolvers.find(ItemType.BOOKMARK)!!

		resolver.resolve(UUID.fromString(bookmark.id())) shouldBe
			ResolvedItem(title = "unreachable.invalid", subtitle = "Read later", icon = "bookmark", deepLink = "/bookmarks/${bookmark.id()}")
		resolver.resolve(UUID.randomUUID()) shouldBe null
	}

	@Test
	fun `bookmarks are searchable by title, URL, description and tags`() {
		val bookmark =
			newBookmark("""{"url":"https://unreachable.invalid/sourdough","title":"Bread","description":"Starter feeding","tags":["baking"]}""")

		val document = searchDocument(bookmark.id())!!
		document["title"] shouldBe "Bread"
		document["body"] shouldBe "https://unreachable.invalid/sourdough\nStarter feeding\nbaking"
		val hits =
			mockMvc
				.get("/api/v1/search?q=starter&types=BOOKMARK")
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString
		(bookmark.id() in hits) shouldBe true
	}

	@Test
	fun `a deleted bookmark goes to the Trash and leaves the search, restore and purge work`() {
		val bookmark = settled(newBookmark("""{"url":"https://unreachable.invalid/x","title":"Trashed","tags":["t"]}""").id())

		mockMvc.delete("$BOOKMARKS/${bookmark.id()}") { with(csrf()) }.andExpect { status { isPreconditionRequired() } }
		mockMvc
			.delete("$BOOKMARKS/${bookmark.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, bookmark.etag())
			}.andExpect { status { isNoContent() } }

		mockMvc.get("$BOOKMARKS/${bookmark.id()}").andExpect { status { isNotFound() } }
		list().get("items").isEmpty shouldBe true
		searchDocument(bookmark.id()) shouldBe null
		resolvers.find(ItemType.BOOKMARK)!!.resolve(UUID.fromString(bookmark.id())) shouldBe null
		val trash =
			mockMvc
				.get("/api/v1/trash")
				.andExpect { status { isOk() } }
				.json()
				.get("items")
		trash.values().single().text("title") shouldBe "Trashed"

		mockMvc.post("/api/v1/trash/BOOKMARK/${bookmark.id()}/restore") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }
		fetch(bookmark.id()).text("title") shouldBe "Trashed"
		searchDocument(bookmark.id())!!["body"] shouldBe "https://unreachable.invalid/x\nt"

		val restored = fetch(bookmark.id())
		mockMvc
			.delete("$BOOKMARKS/${bookmark.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, restored.etag())
			}.andExpect { status { isNoContent() } }
		mockMvc.delete("/api/v1/trash/BOOKMARK/${bookmark.id()}") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }
		jdbc.queryForObject("select count(*) from bookmark", Long::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from bookmark_tag", Long::class.java) shouldBe 0
	}

	@Test
	fun `every change publishes a change event`(events: ApplicationEvents) {
		// ApplicationEvents records the test thread only, so the background metadata update is not among them.
		val bookmark = newBookmark("""{"url":"https://unreachable.invalid/e"}""")
		val changed = change(settled(bookmark.id()), """{"readLater":true}""")
		mockMvc
			.delete("$BOOKMARKS/${bookmark.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, changed.etag())
			}.andExpect { status { isNoContent() } }

		events
			.stream(ChangeEvent::class.java)
			.filter { it.type == ItemType.BOOKMARK.name }
			.map { it.op }
			.toList() shouldContainExactly listOf(ChangeOp.CREATED, ChangeOp.UPDATED, ChangeOp.DELETED)
	}

	private companion object {
		const val BOOKMARKS = "/api/v1/bookmarks"
	}
}
