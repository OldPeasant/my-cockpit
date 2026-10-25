package ch.sonensei.cockpit.bookmarks.snapshot

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.shouldBe
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
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.github.tomakehurst.wiremock.client.WireMock.get as stubGet

/**
 * Edge cases of "Save snapshot" against Postgres (same context as [BookmarkSnapshotWebTests]): the bookmark changing
 * while its page is fetched, file names from unusual titles, redirects, a previous snapshot already deleted, and
 * problem details that do not leak the URL.
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
class BookmarkSnapshotEdgeCaseWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val files: FilesService,
	@Autowired private val snapshots: BookmarkSnapshotService,
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

	private val prose =
		"The tomato plants on the balcony need water every morning, and in July also in the evening, every day. " +
			"A deep pot keeps the roots cool, and mulch keeps the soil from drying out too fast."

	private fun articleWithTitle(title: String) =
		"<html><head><title>$title</title></head><body><article><p>$prose</p></article></body></html>"

	// --- helpers

	private fun page(path: String) = "http://127.0.0.1:${server.port()}$path"

	private fun stubPage(
		path: String,
		html: String,
		delayMillis: Int = 0,
	) = server.stubFor(
		stubGet(urlEqualTo(path)).willReturn(
			aResponse().withHeader("Content-Type", "text/html; charset=utf-8").withBody(html).withFixedDelay(delayMillis),
		),
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

	private fun settled(id: String): JsonNode {
		val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
		while (System.nanoTime() < deadline) {
			val bookmark = fetch(id)
			if (bookmark.get("metadataStatus").asString() != "PENDING") return bookmark
			Thread.sleep(25)
		}
		error("The metadata of bookmark $id was not fetched in time")
	}

	private fun requests(path: String): Int = server.allServeEvents.count { it.request.url == path }

	/** Runs [action] (outside the request thread) once WireMock has received [count] requests for [path]. */
	private fun whenRequested(
		path: String,
		count: Int,
		action: () -> Unit,
	): CompletableFuture<Unit> =
		CompletableFuture.supplyAsync {
			val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
			while (requests(path) < count) {
				check(System.nanoTime() < deadline) { "$path was not requested $count times in time" }
				Thread.sleep(10)
			}
			action()
		}

	private fun activeAttachmentCount(): Long =
		jdbc.queryForObject(
			"select count(*) from file where folder_id = ? and deleted_at is null",
			Long::class.java,
			files.specialFolder(SpecialFolder.ATTACHMENTS).id,
		)!!

	private fun fileName(saved: JsonNode): String = files.file(UUID.fromString(saved.snapshotFileId()!!)).name

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun JsonNode.snapshotFileId(): String? = get("snapshotFileId")?.takeUnless { it.isNull }?.asString()

	// --- the bookmark changes while its page is fetched

	@Test
	fun `a URL changed while the page was fetched is a 409 and stores no file`() {
		stubPage("/slow", articleWithTitle("Slow page"))
		val bookmark = newBookmark(page("/slow"))
		val metadataRequests = requests("/slow")
		stubPage("/slow", articleWithTitle("Slow page"), delayMillis = 800)
		val attachments = activeAttachmentCount()
		val change =
			whenRequested("/slow", metadataRequests + 1) {
				jdbc.update("update bookmark set url = ? where id = ?::uuid", page("/elsewhere"), bookmark.id())
			}

		val problem = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isConflict() } }.json()

		change.get(5, TimeUnit.SECONDS)
		problem.get("status").asInt() shouldBe 409
		fetch(bookmark.id()).snapshotFileId() shouldBe null
		activeAttachmentCount() shouldBe attachments
	}

	@Test
	fun `a bookmark deleted while the page was fetched is a 404 and stores no file`() {
		stubPage("/slow", articleWithTitle("Slow page"))
		val bookmark = newBookmark(page("/slow"))
		val metadataRequests = requests("/slow")
		stubPage("/slow", articleWithTitle("Slow page"), delayMillis = 800)
		val attachments = activeAttachmentCount()
		val delete =
			whenRequested("/slow", metadataRequests + 1) {
				jdbc.update("update bookmark set deleted_at = now() where id = ?::uuid", bookmark.id())
			}

		snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isNotFound() } }

		delete.get(5, TimeUnit.SECONDS)
		activeAttachmentCount() shouldBe attachments
	}

	@Test
	fun `two overlapping snapshots leave exactly one active snapshot file, the one the bookmark points to`() {
		stubPage("/slow", articleWithTitle("Slow page"))
		val bookmark = newBookmark(page("/slow"))
		stubPage("/slow", articleWithTitle("Slow page"), delayMillis = 500)
		val attachments = activeAttachmentCount()

		// The service directly (no request thread, so no security context needed); the other one through the API.
		val other = CompletableFuture.supplyAsync { runCatching { snapshots.save(UUID.fromString(bookmark.id())) {} } }
		val status = snapshot(bookmark.id(), "*").andReturn().response.status
		val otherResult = other.get(10, TimeUnit.SECONDS)

		// Depending on how the transactions interleave, both succeed one after the other or one gets a conflict.
		(status == 200 || status == 409) shouldBe true
		(status == 200 || otherResult.isSuccess) shouldBe true
		val current = fetch(bookmark.id()).snapshotFileId()
		activeAttachmentCount() shouldBe attachments + 1
		jdbc.queryForObject(
			"select count(*) from file where folder_id = ? and deleted_at is null and id = ?::uuid",
			Long::class.java,
			files.specialFolder(SpecialFolder.ATTACHMENTS).id,
			current,
		) shouldBe 1L
	}

	@Test
	fun `a bookmark in the Trash is 404 and its page is not fetched`() {
		stubPage("/article", articleWithTitle("Growing tomatoes"))
		val bookmark = newBookmark(page("/article"))
		mockMvc
			.delete("$BOOKMARKS/${bookmark.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, bookmark.etag())
			}.andExpect { status { isNoContent() } }
		val before = requests("/article")

		snapshot(bookmark.id(), "*").andExpect { status { isNotFound() } }

		requests("/article") shouldBe before
	}

	@Test
	fun `a stale If-Match is 412 and the page is not fetched`() {
		stubPage("/article", articleWithTitle("Growing tomatoes"))
		val bookmark = newBookmark(page("/article"))
		val before = requests("/article")

		snapshot(bookmark.id(), "\"${bookmark.get("version").asLong() - 1}\"").andExpect { status { isPreconditionFailed() } }

		requests("/article") shouldBe before
		fetch(bookmark.id()).snapshotFileId() shouldBe null
	}

	// --- file names

	@Test
	fun `a long title is cut to 100 characters in the file name`() {
		stubPage("/long", articleWithTitle("T".repeat(150)))
		val bookmark = newBookmark(page("/long"))

		val saved = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isOk() } }.json()

		fileName(saved) shouldBe "${"T".repeat(100)} (snapshot).html"
	}

	@Test
	fun `a long title is not cut in the middle of a surrogate pair, and trailing blanks are trimmed`() {
		stubPage("/emoji", articleWithTitle("a".repeat(99) + "🍅 tomato"))
		// The 100th character is a blank: the cut name ends with it, which is trimmed.
		stubPage("/blank", articleWithTitle("b".repeat(99) + " x" + "c".repeat(20)))
		val emoji = newBookmark(page("/emoji"))
		val blank = newBookmark(page("/blank"))

		val emojiName = fileName(snapshot(emoji.id(), emoji.etag()).andExpect { status { isOk() } }.json())
		val blankName = fileName(snapshot(blank.id(), blank.etag()).andExpect { status { isOk() } }.json())

		emojiName shouldBe "${"a".repeat(99)} (snapshot).html"
		blankName shouldBe "${"b".repeat(99)} (snapshot).html"
	}

	@Test
	fun `control characters in the title are replaced in the file name`() {
		stubPage(
			"/control",
			"<html><head><meta property=\"og:title\" content=\"Bell&#7;and&#1;null\"></head><body><article><p>$prose</p></article></body></html>",
		)
		val bookmark = newBookmark(page("/control"))

		val saved = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isOk() } }.json()

		fileName(saved) shouldBe "Bell_and_null (snapshot).html"
	}

	@Test
	fun `a page without any title is named after the bookmark`() {
		stubPage("/untitled", "<html><body><div><p>$prose</p></div></body></html>")
		val bookmark = newBookmark(page("/untitled"))

		val saved = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isOk() } }.json()

		fileName(saved) shouldBe "${bookmark.get("displayTitle").asString()} (snapshot).html"
	}

	// --- fetching

	@Test
	fun `after a redirect, relative links resolve against the final page`() {
		server.stubFor(
			stubGet(urlEqualTo("/moved")).willReturn(aResponse().withStatus(301).withHeader("Location", "/new/article")),
		)
		stubPage("/new/article", "<html><body><article><p>$prose See <a href=\"soil\">the soil guide</a>.</p></article></body></html>")
		val bookmark = newBookmark(page("/moved"))

		val saved = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isOk() } }.json()

		val html = files.open(UUID.fromString(saved.snapshotFileId()!!)).use { it.readBytes().toString(Charsets.UTF_8) }
		html shouldContain "href=\"${page("/new/soil")}\""
	}

	@Test
	fun `a failed fetch answers problem details that do not reveal the URL`() {
		server.stubFor(stubGet(urlEqualTo("/secret-path")).willReturn(aResponse().withStatus(500).withBody("internal secret")))
		val bookmark = newBookmark(page("/secret-path"))

		val response =
			snapshot(bookmark.id(), bookmark.etag())
				.andExpect {
					status { isEqualTo(502) }
					content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
				}.andReturn()
				.response.contentAsString

		jsonMapper.readTree(response).get("errorCode").asString() shouldBe "HTTP_ERROR"
		response shouldNotContain "secret-path"
		response shouldNotContain "127.0.0.1"
		response shouldNotContain "internal secret"
	}

	@Test
	fun `a new snapshot still succeeds when the user already deleted the previous one`() {
		stubPage("/article", articleWithTitle("Growing tomatoes"))
		val bookmark = newBookmark(page("/article"))
		val first = snapshot(bookmark.id(), bookmark.etag()).andExpect { status { isOk() } }.json()
		files.deleteFile(UUID.fromString(first.snapshotFileId()!!))

		val second = snapshot(bookmark.id(), first.etag()).andExpect { status { isOk() } }.json()

		files.file(UUID.fromString(second.snapshotFileId()!!)).name shouldBe "Growing tomatoes (snapshot).html"
		fetch(bookmark.id()).snapshotFileId() shouldBe second.snapshotFileId()
	}

	private companion object {
		const val BOOKMARKS = "/api/v1/bookmarks"
	}
}
