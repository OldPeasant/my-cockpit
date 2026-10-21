package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.bookmarks.domain.Bookmark
import ch.sonensei.cockpit.bookmarks.domain.BookmarkChanges
import ch.sonensei.cockpit.bookmarks.domain.BookmarkService
import ch.sonensei.cockpit.bookmarks.domain.FieldChange
import ch.sonensei.cockpit.bookmarks.domain.MetadataStatus
import ch.sonensei.cockpit.bookmarks.domain.PageMetadata
import ch.sonensei.cockpit.bookmarks.infra.BookmarkTrashContributor
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import com.github.tomakehurst.wiremock.client.WireMock.get as stubGet

/**
 * Edge cases of bookmarks beyond [BookmarkWebTests]: storing fetched metadata (limits, NUL characters, overwrite with
 * missing values, stale results), the change event of the background fetch, a PATCH of the same URL, tag and field
 * limits, and the schema defaults. Same Spring test configuration as [BookmarkWebTests], so the context is shared.
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
class BookmarkEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val service: BookmarkService,
	@Autowired private val trash: BookmarkTrashContributor,
	@Autowired private val context: ConfigurableApplicationContext,
) : PostgresTestBase() {
	private val server = WireMockServer(wireMockConfig().dynamicPort())

	/** Every change event with the name of the thread that published it (also background threads). */
	private val published = CopyOnWriteArrayList<Pair<ChangeEvent, String>>()
	private val listener = ApplicationListener.forPayload<ChangeEvent> { published += it to Thread.currentThread().name }

	@BeforeAll
	fun start() {
		server.start()
		context.addApplicationListener(listener)
	}

	@AfterAll
	fun stop() {
		context.removeApplicationListener(listener)
		server.stop()
	}

	@BeforeEach
	@AfterEach
	fun reset() {
		server.resetAll()
		jdbc.execute("delete from bookmark")
		jdbc.execute("delete from link")
		jdbc.execute("delete from search_document")
		clock.set(MutableClockTestConfiguration.START)
		published.clear()
	}

	// --- helpers

	private fun page(path: String) = "http://127.0.0.1:${server.port()}$path"

	private fun stubPage(
		path: String,
		html: String,
	) = server.stubFor(stubGet(urlEqualTo(path)).willReturn(aResponse().withHeader("Content-Type", "text/html; charset=utf-8").withBody(html)))

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun create(body: String) =
		mockMvc.post(BOOKMARKS) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun patch(
		bookmark: JsonNode,
		body: String,
	) = mockMvc.patch("$BOOKMARKS/${bookmark.id()}") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		header(HttpHeaders.IF_MATCH, "\"${bookmark.get("version").asLong()}\"")
	}

	private fun fetch(id: String): JsonNode = mockMvc.get("$BOOKMARKS/$id").andExpect { status { isOk() } }.json()

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

	/** A bookmark whose own background fetch (of a page WireMock does not serve: 404) has finished as `FAILED`. */
	private fun failedBookmark(
		path: String = "/none",
		title: String? = null,
		description: String? = null,
	): Bookmark {
		val created =
			service.create(
				BookmarkChanges(
					url = FieldChange(page(path)),
					title = title?.let { FieldChange(it) },
					description = description?.let { FieldChange(it) },
				),
			)
		return settled(created.id)
	}

	private fun settled(id: UUID): Bookmark {
		val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
		while (System.nanoTime() < deadline) {
			val bookmark = service.get(id)
			if (bookmark.metadataStatus != MetadataStatus.PENDING) return bookmark
			Thread.sleep(25)
		}
		error("The metadata of bookmark $id was not fetched in time")
	}

	private fun searchTitle(id: UUID): String? =
		jdbc
			.queryForList("select title from search_document where item_type = 'BOOKMARK' and item_id = ?", String::class.java, id)
			.singleOrNull()

	// --- the background fetch

	@Test
	fun `the background fetch publishes an UPDATED change event from the worker thread`() {
		stubPage("/event", "<title>Event page</title>")

		val created = create("""{"url":"${page("/event")}"}""").andExpect { status { isCreated() } }.json()
		settled(UUID.fromString(created.id()))

		val events = published.filter { it.first.id.toString() == created.id() }
		events.map { it.first.op } shouldContain ChangeOp.UPDATED
		events.first { it.first.op == ChangeOp.UPDATED }.second shouldStartWith "bookmark-fetch"
		events.first().first.op shouldBe ChangeOp.CREATED
	}

	@Test
	fun `a PATCH with the same URL (also with spaces around it) does not fetch the page again`() {
		stubPage("/same", """<head><title>Same</title><link rel="icon" href="/s.ico"></head>""")
		val created = create("""{"url":"${page("/same")}"}""").andExpect { status { isCreated() } }.json()
		settled(UUID.fromString(created.id()))
		server.verify(1, getRequestedFor(urlEqualTo("/same")))

		val changed = patch(fetch(created.id()), """{"url":"  ${page("/same")} ","readLater":true}""").andExpect { status { isOk() } }.json()

		changed.text("metadataStatus") shouldBe "FETCHED"
		changed.text("favicon") shouldBe page("/s.ico")
		changed.get("readLater").asBoolean() shouldBe true
		Thread.sleep(300)
		server.verify(1, getRequestedFor(urlEqualTo("/same")))
		fetch(created.id()).text("metadataStatus") shouldBe "FETCHED"
	}

	@Test
	fun `a PATCH with the unencoded form of the stored URL does not fetch the page again`() {
		stubPage("/two%20words", "<title>Two words</title>")
		val created = create("""{"url":"${page("/two words")}"}""").andExpect { status { isCreated() } }.json()
		created.text("url") shouldBe page("/two%20words")
		val fetched = settled(UUID.fromString(created.id()))
		fetched.metadataStatus shouldBe MetadataStatus.FETCHED

		val changed = patch(fetch(created.id()), """{"url":"${page("/two words")}"}""").andExpect { status { isOk() } }.json()

		changed.text("url") shouldBe page("/two%20words")
		changed.text("metadataStatus") shouldBe "FETCHED"
		service.get(fetched.id).metadataFetch shouldBe fetched.metadataFetch
		server.verify(1, getRequestedFor(urlEqualTo("/two%20words")))
	}

	@Test
	fun `the URL length limit applies to the encoded form`() {
		val base = "https://unreachable.invalid/"
		// 28 + 400 characters as typed, but each 'é' becomes '%C3%A9' (6 characters): 2 428 once encoded.
		create("""{"url":"$base${E_ACUTE_JSON.repeat(400)}"}""").andExpect { status { isBadRequest() } }
		// 28 + 336 * 6 = 2 044 once encoded: within the limit.
		create("""{"url":"$base${E_ACUTE_JSON.repeat(336)}"}""").andExpect { status { isCreated() } }
		jdbc.queryForObject("select count(*) from bookmark", Long::class.java) shouldBe 1
	}

	// --- applyMetadata

	@Test
	fun `stored metadata bumps the version, re-indexes and fills only empty fields`() {
		val bookmark = failedBookmark(title = "Own title")

		service.applyMetadata(
			bookmark.id,
			bookmark.metadataFetch,
			false,
			PageMetadata("Page title", "Page text", "https://cdn.example/f.ico"),
		) shouldBe
			true

		val stored = service.get(bookmark.id)
		stored.metadataStatus shouldBe MetadataStatus.FETCHED
		stored.title shouldBe "Own title"
		stored.description shouldBe "Page text"
		stored.favicon shouldBe "https://cdn.example/f.ico"
		(stored.version > bookmark.version) shouldBe true
		searchTitle(bookmark.id) shouldBe "Own title"
	}

	@Test
	fun `with overwrite, values the page does not have keep the current ones`() {
		val bookmark = failedBookmark(title = "Kept title", description = "Kept text")
		service.applyMetadata(bookmark.id, bookmark.metadataFetch, false, PageMetadata(favicon = "https://cdn.example/old.ico"))

		service.applyMetadata(bookmark.id, bookmark.metadataFetch, true, PageMetadata(title = "New title")) shouldBe true

		val stored = service.get(bookmark.id)
		stored.title shouldBe "New title"
		stored.description shouldBe "Kept text"
		stored.favicon shouldBe "https://cdn.example/old.ico"
		searchTitle(bookmark.id) shouldBe "New title"
	}

	@Test
	fun `fetched text is cleaned of NUL characters and whitespace and cut to the field limits`() {
		val bookmark = failedBookmark()
		val longTitle = "t".repeat(499) + " and more words"
		val longDescription = "d".repeat(2_500)

		service.applyMetadata(bookmark.id, bookmark.metadataFetch, false, PageMetadata(longTitle, longDescription)) shouldBe true
		service.get(bookmark.id).title shouldBe "t".repeat(499)
		service.get(bookmark.id).description shouldBe "d".repeat(BookmarkService.MAX_DESCRIPTION_LENGTH)

		service.applyMetadata(
			bookmark.id,
			bookmark.metadataFetch,
			true,
			PageMetadata("Tom\u0000atoes\n\t on   the balcony", "\u0000 Bal\u0000cony "),
		) shouldBe
			true
		val cleaned = service.get(bookmark.id)
		cleaned.title shouldBe "Tomatoes on the balcony"
		cleaned.description shouldBe "Balcony"
	}

	@Test
	fun `fetched text that is only whitespace or NUL characters counts as missing`() {
		val bookmark = failedBookmark(title = "Mine", description = "My text")

		service.applyMetadata(bookmark.id, bookmark.metadataFetch, true, PageMetadata(" \u0000 \n", "\u0000")) shouldBe true

		val stored = service.get(bookmark.id)
		stored.title shouldBe "Mine"
		stored.description shouldBe "My text"
		stored.metadataStatus shouldBe MetadataStatus.FETCHED
	}

	@Test
	fun `cutting a fetched title does not split a character in two`() {
		val bookmark = failedBookmark()
		// 499 characters plus an emoji (two UTF-16 chars) cross the 500 limit in the middle of the emoji.
		val title = "a".repeat(499) + "🍅 tomato"

		service.applyMetadata(bookmark.id, bookmark.metadataFetch, false, PageMetadata(title = title)) shouldBe true

		val stored = service.get(bookmark.id).title!!
		withClue("stored ${stored.length} chars ending in U+%04X".format(stored.last().code)) {
			stored shouldBe "a".repeat(499)
		}
	}

	@Test
	fun `cutting a fetched description does not split a character in two, a whole one at the limit is kept`() {
		val bookmark = failedBookmark()
		val limit = BookmarkService.MAX_DESCRIPTION_LENGTH

		fun store(description: String) = service.applyMetadata(bookmark.id, bookmark.metadataFetch, true, PageMetadata(description = description))

		store("d".repeat(limit - 1) + "🍅 more") shouldBe true
		service.get(bookmark.id).description shouldBe "d".repeat(limit - 1)

		// The emoji ends exactly at the limit: nothing is cut.
		store("d".repeat(limit - 2) + "🍅 more") shouldBe true
		service.get(bookmark.id).description shouldBe "d".repeat(limit - 2) + "🍅"
	}

	@Test
	fun `a fetched favicon that is too long or has NUL characters is ignored`() {
		val bookmark = failedBookmark()
		val tooLong = "https://cdn.example/" + "f".repeat(BookmarkService.MAX_URL_LENGTH)

		service.applyMetadata(bookmark.id, bookmark.metadataFetch, false, PageMetadata(favicon = tooLong)) shouldBe true
		service.get(bookmark.id).favicon shouldBe null
		service.applyMetadata(bookmark.id, bookmark.metadataFetch, false, PageMetadata(favicon = "https://cdn.example/a\u0000.ico")) shouldBe true
		service.get(bookmark.id).favicon shouldBe null
		val exact = "https://cdn.example/" + "f".repeat(BookmarkService.MAX_URL_LENGTH - "https://cdn.example/".length)
		service.applyMetadata(bookmark.id, bookmark.metadataFetch, false, PageMetadata(favicon = exact)) shouldBe true
		service.get(bookmark.id).favicon shouldBe exact
	}

	@Test
	fun `a failed fetch only sets the status and keeps the fields`() {
		val bookmark = failedBookmark(title = "Mine")
		service.applyMetadata(
			bookmark.id,
			bookmark.metadataFetch,
			false,
			PageMetadata(description = "Fetched", favicon = "https://cdn.example/f.ico"),
		)

		service.applyMetadata(bookmark.id, bookmark.metadataFetch, true, null) shouldBe true

		val stored = service.get(bookmark.id)
		stored.metadataStatus shouldBe MetadataStatus.FAILED
		stored.title shouldBe "Mine"
		stored.description shouldBe "Fetched"
		stored.favicon shouldBe "https://cdn.example/f.ico"
	}

	@Test
	fun `metadata for an unknown, trashed or since re-pointed bookmark is ignored`() {
		val bookmark = failedBookmark()
		val metadata = PageMetadata(title = "Stale")

		service.applyMetadata(UUID.randomUUID(), bookmark.metadataFetch, false, metadata) shouldBe false
		service.applyMetadata(bookmark.id, bookmark.metadataFetch + 1, false, metadata) shouldBe false
		service.get(bookmark.id).title shouldBe null
		service.get(bookmark.id).version shouldBe bookmark.version

		service.delete(bookmark.id) {}
		service.applyMetadata(bookmark.id, bookmark.metadataFetch, false, metadata) shouldBe false
		jdbc.queryForObject("select title from bookmark where id = ?", String::class.java, bookmark.id) shouldBe null
	}

	@Test
	fun `the result of an older fetch is ignored once a newer one was requested`() {
		val bookmark = failedBookmark()

		val refreshed = service.refreshMetadata(bookmark.id) {}

		refreshed.metadataFetch shouldBe bookmark.metadataFetch + 1
		service.applyMetadata(bookmark.id, bookmark.metadataFetch, false, PageMetadata(title = "Stale")) shouldBe false
		val stored = service.get(bookmark.id)
		stored.title shouldBe null
		(stored.metadataStatus == MetadataStatus.FETCHED) shouldBe false
		// The refresh's own fetch (404) still decides the status.
		settled(bookmark.id).metadataStatus shouldBe MetadataStatus.FAILED
	}

	@Test
	fun `restoring a bookmark deleted while its page was fetched fetches the page again`() {
		server.stubFor(
			stubGet(urlEqualTo("/slow")).willReturn(
				aResponse().withHeader("Content-Type", "text/html").withBody("<title>Slow page</title>").withFixedDelay(500),
			),
		)
		val created = service.create(BookmarkChanges(url = FieldChange(page("/slow"))))
		service.delete(created.id) {}

		mockMvc.post("/api/v1/trash/BOOKMARK/${created.id}/restore") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }

		val restored = settled(created.id)
		restored.metadataStatus shouldBe MetadataStatus.FETCHED
		restored.title shouldBe "Slow page"
		restored.metadataFetch shouldBe created.metadataFetch + 1
		server.verify(2, getRequestedFor(urlEqualTo("/slow")))
	}

	@Test
	fun `restoring a bookmark whose fetch had finished does not fetch again`() {
		val bookmark = failedBookmark()
		service.delete(bookmark.id) {}

		mockMvc.post("/api/v1/trash/BOOKMARK/${bookmark.id}/restore") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }

		Thread.sleep(300)
		val restored = service.get(bookmark.id)
		restored.metadataStatus shouldBe MetadataStatus.FAILED
		restored.metadataFetch shouldBe bookmark.metadataFetch
		server.verify(1, getRequestedFor(urlEqualTo("/none")))
	}

	@Test
	fun `on startup bookmarks still pending are listed for a new fetch, others not`() {
		val pending = UUID.randomUUID()
		jdbc.update(
			"insert into bookmark (id, created_at, updated_at, version, url, metadata_fetch) values (?, now(), now(), 0, 'https://unreachable.invalid/p', 3)",
			pending,
		)
		val trashed = UUID.randomUUID()
		jdbc.update(
			"insert into bookmark (id, created_at, updated_at, version, url, deleted_at) values (?, now(), now(), 0, 'https://unreachable.invalid/t', now())",
			trashed,
		)
		failedBookmark()

		service.pendingMetadataRequests().map { it.id to it.fetch } shouldBe listOf(pending to 3L)
	}

	// --- limits and validation

	@Test
	fun `at most 50 distinct tags of at most 50 characters are accepted`() {
		val fifty = (1..50).map { "tag$it" }
		val accepted =
			create("""{"url":"${page("/t")}","tags":${jsonMapper.writeValueAsString(fifty + "TAG1")}}""")
				.andExpect {
					status { isCreated() }
				}.json()
		accepted.get("tags").values() shouldHaveSize 50

		create("""{"url":"${page("/t")}","tags":${jsonMapper.writeValueAsString(fifty + "tag51")}}""").andExpect { status { isBadRequest() } }
		create("""{"url":"${page("/t")}","tags":["${"k".repeat(50)}"]}""").andExpect { status { isCreated() } }
		create("""{"url":"${page("/t")}","tags":["${"k".repeat(51)}"]}""").andExpect { status { isBadRequest() } }
		create("""{"url":"${page("/t")}","tags":["#"]}""").andExpect { status { isBadRequest() } }
		create("""{"url":"${page("/t")}","tags":["bad\u0000tag"]}""").andExpect { status { isBadRequest() } }
		jdbc.queryForObject("select count(*) from bookmark", Long::class.java) shouldBe 2
	}

	@Test
	fun `a 51st tag on PATCH is rejected and the bookmark stays unchanged`() {
		val created = create("""{"url":"${page("/t")}","title":"Before","tags":["one"]}""").andExpect { status { isCreated() } }.json()
		val current = fetch(created.id())

		patch(current, """{"title":"After","tags":${jsonMapper.writeValueAsString((1..51).map { "t$it" })}}""").andExpect {
			status { isBadRequest() }
		}

		val unchanged = fetch(created.id())
		unchanged.text("title") shouldBe "Before"
		unchanged.get("tags").values().map { it.asString() } shouldBe listOf("one")
		unchanged.get("version").asLong() shouldBe current.get("version").asLong()
	}

	@Test
	fun `field limits are enforced at their boundaries and NUL characters are rejected`() {
		val base = "https://unreachable.invalid/"
		val longestUrl = base + "u".repeat(BookmarkService.MAX_URL_LENGTH - base.length)
		create("""{"url":"$longestUrl"}""").andExpect { status { isCreated() } }
		create("""{"url":"${longestUrl}u"}""").andExpect { status { isBadRequest() } }
		create(
			"""{"url":"${page("/l")}","title":"${"t".repeat(500)}","description":"${"d".repeat(2_000)}"}""",
		).andExpect { status { isCreated() } }
		create("""{"url":"${page("/l")}","description":"${"d".repeat(2_001)}"}""").andExpect { status { isBadRequest() } }
		create("""{"url":"${page("/l")}","title":"bad\u0000title"}""").andExpect { status { isBadRequest() } }
		create("""{"url":"${page("/l")}","description":"bad\u0000text"}""").andExpect { status { isBadRequest() } }
		create("""{"url":"${page("/l")}\u0000"}""").andExpect { status { isBadRequest() } }
		create("""{"url":"https://exa mple.org/"}""").andExpect { status { isBadRequest() } }
		jdbc.queryForObject("select count(*) from bookmark", Long::class.java) shouldBe 2
	}

	@Test
	fun `a blank title or description is stored as empty`() {
		val created =
			create("""{"url":"https://unreachable.invalid/b","title":"   ","description":" "}""")
				.andExpect {
					status { isCreated() }
				}.json()

		created.text("title") shouldBe null
		created.text("description") shouldBe null
		created.text("displayTitle") shouldBe "unreachable.invalid"
	}

	@Test
	fun `invalid list parameters give 400`() {
		mockMvc.get(BOOKMARKS) { param("tag", "bad\u0000tag") }.andExpect { status { isBadRequest() } }
		mockMvc.get("$BOOKMARKS?limit=0").andExpect { status { isBadRequest() } }
		mockMvc.get("$BOOKMARKS?limit=201").andExpect { status { isBadRequest() } }
		mockMvc.get("$BOOKMARKS?read=maybe").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `a stale If-Match on refresh-metadata is refused and nothing is fetched`() {
		stubPage("/r", "<title>R</title>")
		val created = create("""{"url":"${page("/r")}"}""").andExpect { status { isCreated() } }.json()
		settled(UUID.fromString(created.id()))

		mockMvc
			.post("$BOOKMARKS/${created.id()}/refresh-metadata") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"${created.get("version").asLong()}\"")
			}.andExpect { status { isPreconditionFailed() } }

		Thread.sleep(300)
		server.verify(1, getRequestedFor(urlEqualTo("/r")))
		fetch(created.id()).text("metadataStatus") shouldBe "FETCHED"
	}

	// --- trash and schema

	@Test
	fun `the Trash only restores or purges bookmarks that are in it`() {
		val bookmark = failedBookmark()

		trash.restore(bookmark.id) shouldBe false
		trash.purge(bookmark.id) shouldBe false
		trash.restore(UUID.randomUUID()) shouldBe false
		trash.purge(UUID.randomUUID()) shouldBe false
		service.get(bookmark.id).id shouldBe bookmark.id
	}

	@Test
	fun `the schema defaults a new row to not read later and a pending fetch`() {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into bookmark (id, created_at, updated_at, version, url) values (?, now(), now(), 0, 'https://unreachable.invalid/raw')",
			id,
		)

		jdbc.queryForMap("select read_later, metadata_status from bookmark where id = ?", id) shouldBe
			mapOf("read_later" to false, "metadata_status" to "PENDING")
	}

	private companion object {
		const val BOOKMARKS = "/api/v1/bookmarks"

		/** A JSON escape of 'é', so the request body stays ASCII. */
		const val E_ACUTE_JSON = "\\u00e9"
	}
}
