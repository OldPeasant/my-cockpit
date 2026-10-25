package ch.sonensei.cockpit.bookmarks.snapshot

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import com.github.tomakehurst.wiremock.client.WireMock.get as stubGet

/**
 * "Save snapshot" against Postgres, with the pages served by WireMock (same context as
 * [ch.sonensei.cockpit.bookmarks.BookmarkWebTests]: private networks allowed for this test only).
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
class BookmarkSnapshotWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val files: FilesService,
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
		jdbc.execute("delete from search_document")
		clock.set(MutableClockTestConfiguration.START)
	}

	private val article =
		"""
		<html><head><title>Growing tomatoes</title><script>steal()</script></head><body>
		<nav><a href="/">Home</a> <a href="/archive">Archive</a></nav>
		<div class="post-content">
		<p>The tomato plants on the balcony need water every morning, and in July also in the evening, every day.</p>
		<p>A deep pot keeps the roots cool, and mulch keeps the soil from drying out too fast. See <a href="/soil">soil</a>.</p>
		<img src="/photo.jpg">
		</div>
		<div class="comments"><p>Great post, thank you so much, it helped me a lot with my own plants!</p></div>
		</body></html>
		""".trimIndent()

	// --- helpers

	private fun page(path: String) = "http://127.0.0.1:${server.port()}$path"

	private fun stubPage(
		path: String,
		html: String,
		contentType: String = "text/html; charset=utf-8",
		status: Int = 200,
	) = server.stubFor(
		stubGet(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withHeader("Content-Type", contentType).withBody(html)),
	)

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun newBookmark(url: String): JsonNode =
		mockMvc
			.post(BOOKMARKS) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"url":"$url"}"""
			}.andExpect { status { isCreated() } }
			.json()
			.let { settled(it.id()) }

	private fun snapshot(
		id: String,
		ifMatch: String?,
	) = mockMvc.post("$BOOKMARKS/$id/snapshot") {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun fetch(id: String): JsonNode = mockMvc.get("$BOOKMARKS/$id").andExpect { status { isOk() } }.json()

	/** The bookmark once its background metadata fetch has finished, so its version no longer changes. */
	private fun settled(id: String): JsonNode {
		val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
		while (System.nanoTime() < deadline) {
			val bookmark = fetch(id)
			if (bookmark.get("metadataStatus").asString() != "PENDING") return bookmark
			Thread.sleep(25)
		}
		error("The metadata of bookmark $id was not fetched in time")
	}

	private fun content(fileId: String): String = files.open(UUID.fromString(fileId)).use { it.readBytes().toString(Charsets.UTF_8) }

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun JsonNode.snapshotFileId(): String? = get("snapshotFileId")?.takeUnless { it.isNull }?.asString()

	// --- tests

	@Test
	fun `save snapshot stores the main content as sanitized HTML in Attachments and links it to the bookmark`(events: ApplicationEvents) {
		stubPage("/article", article)
		val bookmark = newBookmark(page("/article"))
		events.clear()

		val result = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isOk() } }.andReturn()
		val saved = jsonMapper.readTree(result.response.contentAsString)

		result.response.getHeader(HttpHeaders.ETAG) shouldBe saved.etag()
		(saved.get("version").asLong() > bookmark.get("version").asLong()) shouldBe true
		val fileId = saved.snapshotFileId().shouldNotBeNull()
		fetch(bookmark.id()).snapshotFileId() shouldBe fileId
		val file = files.file(UUID.fromString(fileId))
		file.name shouldBe "Growing tomatoes (snapshot).html"
		file.mime shouldBe "text/html"
		file.folderId shouldBe files.specialFolder(SpecialFolder.ATTACHMENTS).id
		val html = content(fileId)
		html shouldContain "tomato plants on the balcony"
		html shouldContain "href=\"${page("/soil")}\""
		html shouldNotContain "steal()"
		html shouldNotContain "<script"
		html shouldNotContain "<img"
		html shouldNotContain "Great post"
		html shouldNotContain "Archive"
		events
			.stream(ChangeEvent::class.java)
			.filter { it.type == ItemType.BOOKMARK.name && it.id == UUID.fromString(bookmark.id()) && it.op == ChangeOp.UPDATED }
			.count() shouldBe 1
	}

	@Test
	fun `a new snapshot replaces the previous one, which goes to the Trash`() {
		stubPage("/article", article)
		val bookmark = newBookmark(page("/article"))
		val first = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isOk() } }.json()

		val second = snapshot(bookmark.id(), first.etag()).andExpect { status { isOk() } }.json()

		second.snapshotFileId() shouldNotBe first.snapshotFileId()
		val trashed =
			jdbc.queryForObject(
				"select deleted_at is not null from file where id = ?::uuid",
				Boolean::class.java,
				first.snapshotFileId(),
			)
		trashed shouldBe true
		files.file(UUID.fromString(second.snapshotFileId()!!)).name shouldBe "Growing tomatoes (snapshot).html"
	}

	@Test
	fun `if-match is required and checked`() {
		stubPage("/article", article)
		val bookmark = newBookmark(page("/article"))

		snapshot(bookmark.id(), null).andExpect { status { isEqualTo(428) } }
		snapshot(bookmark.id(), "\"999\"").andExpect { status { isEqualTo(412) } }
		snapshot(bookmark.id(), "*").andExpect { status { isOk() } }
		server
			.findAll(
				com.github.tomakehurst.wiremock.client.WireMock
					.getRequestedFor(urlEqualTo("/article")),
			).size shouldBe 2
	}

	@Test
	fun `an unknown or deleted bookmark is 404`() {
		snapshot(UUID.randomUUID().toString(), "*").andExpect { status { isNotFound() } }
	}

	@Test
	fun `a page that cannot be fetched is a 502 with an error code and stores nothing`() {
		stubPage("/missing", "gone", status = 404)
		stubPage("/pdf", "%PDF", contentType = "application/pdf")
		val missing = newBookmark(page("/missing"))
		val pdf = newBookmark(page("/pdf"))

		val notFound = snapshot(missing.id(), missing.etag()).andExpect { status { isEqualTo(502) } }.json()
		val notHtml = snapshot(pdf.id(), pdf.etag()).andExpect { status { isEqualTo(502) } }.json()

		notFound.get("errorCode").asString() shouldBe "HTTP_ERROR"
		notHtml.get("errorCode").asString() shouldBe "NOT_HTML"
		fetch(missing.id()).snapshotFileId() shouldBe null
		fetch(missing.id()).get("version").asLong() shouldBe missing.get("version").asLong()
	}

	@Test
	fun `a page without readable content is a 502 NO_CONTENT`() {
		stubPage("/empty", "<html><body><script>x()</script></body></html>")
		val bookmark = newBookmark(page("/empty"))

		val problem = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isEqualTo(502) } }.json()

		problem.get("errorCode").asString() shouldBe "NO_CONTENT"
		fetch(bookmark.id()).snapshotFileId() shouldBe null
	}

	@Test
	fun `a file name is made from the title without separators`() {
		stubPage(
			"/slash",
			"<head><title>A/B\\C test</title></head><body><p>${"Some long enough paragraph, with commas, here. ".repeat(3)}</p></body>",
		)
		val bookmark = newBookmark(page("/slash"))

		val saved = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isOk() } }.json()

		files.file(UUID.fromString(saved.snapshotFileId()!!)).name shouldBe "A_B_C test (snapshot).html"
	}

	@Test
	fun `other bookmark changes keep the snapshot`() {
		stubPage("/article", article)
		val bookmark = newBookmark(page("/article"))
		val saved = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isOk() } }.json()

		val changed =
			mockMvc
				.patch("$BOOKMARKS/${bookmark.id()}") {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = """{"readLater":true}"""
					header(HttpHeaders.IF_MATCH, saved.etag())
				}.andExpect { status { isOk() } }
				.json()

		changed.snapshotFileId() shouldBe saved.snapshotFileId()
	}

	private companion object {
		const val BOOKMARKS = "/api/v1/bookmarks"
	}
}
