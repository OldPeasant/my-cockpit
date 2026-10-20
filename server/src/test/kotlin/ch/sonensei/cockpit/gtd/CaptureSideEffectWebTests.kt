package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

/**
 * Side effects of quick capture (gtd-capture-api) beyond [CaptureWebTests] and [CaptureEdgeCaseWebTests]: the file
 * links as the link system shows them, no auto-created projects or contexts, and nothing left behind by a rejected
 * capture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class CaptureSideEffectWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val files: FilesService,
	@Autowired private val linkRows: LinkRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		linkRows.deleteAll()
		jdbc.execute("update project set next_task_id = null")
		jdbc.execute("delete from checklist_item")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from project")
		jdbc.execute("delete from context")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from file")
		clock.set(MutableClockTestConfiguration.START)
	}

	private fun post(
		path: String,
		vararg fields: Pair<String, Any?>,
	) = mockMvc.post(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = jsonMapper.writeValueAsString(mapOf(*fields))
	}

	private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

	@Test
	fun `a captured file is shown by the link api on the task and on the file`() {
		val scan = files.storeFile(null, "receipt.txt", "text/plain", "fictitious content".byteInputStream())
		val taskId =
			post(CAPTURE, "text" to "File the receipt", "fileIds" to listOf(scan.id))
				.andExpect { status { isCreated() } }
				.andReturn()
				.response
				.contentAsString
				.let(jsonMapper::readTree)
				.get("id")
				.asString()

		mockMvc.get("/api/v1/links?type=TASK&id=$taskId").andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].direction") { value("OUTGOING") }
			jsonPath("$.items[0].origin") { value("MANUAL") }
			jsonPath("$.items[0].target.type") { value("FILE") }
			jsonPath("$.items[0].target.id") { value(scan.id.toString()) }
			jsonPath("$.items[0].target.available") { value(true) }
		}
		mockMvc.get("/api/v1/links?type=FILE&id=${scan.id}").andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].direction") { value("INCOMING") }
			jsonPath("$.items[0].source.type") { value("TASK") }
			jsonPath("$.items[0].source.id") { value(taskId) }
			jsonPath("$.items[0].source.title") { value("File the receipt") }
		}
	}

	@Test
	fun `unknown project and context tokens create no project or context, in capture and preview`() {
		post("$CAPTURE/preview", "text" to "Plan trip #new-project @office").andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Plan trip #new-project @office") }
		}
		post(CAPTURE, "text" to "Plan trip #new-project @office").andExpect {
			status { isCreated() }
			jsonPath("$.title") { value("Plan trip #new-project @office") }
			jsonPath("$.projectId") { doesNotExist() }
			jsonPath("$.contextIds") { isEmpty() }
		}
		count("project") shouldBe 0
		count("context") shouldBe 0
		count("task") shouldBe 1
	}

	@Test
	fun `a capture rejected after its files were checked leaves no task and no link`() {
		val scan = files.storeFile(null, "quote.txt", "text/plain", "fictitious content".byteInputStream())
		post(CAPTURE, "text" to "Quote ^${"a".repeat(501)}", "fileIds" to listOf(scan.id)).andExpect {
			status { isBadRequest() }
		}
		post(CAPTURE, "text" to "Quote", "notes" to "n".repeat(100_001), "fileIds" to listOf(scan.id)).andExpect {
			status { isBadRequest() }
		}
		count("task") shouldBe 0
		count("link") shouldBe 0
	}

	private companion object {
		const val CAPTURE = "/api/v1/capture"
	}
}
