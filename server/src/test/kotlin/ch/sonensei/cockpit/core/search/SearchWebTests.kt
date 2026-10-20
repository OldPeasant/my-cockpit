package ch.sonensei.cockpit.core.search

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.support.PostgresTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeItemResolversConfiguration::class)
@WithCockpitUser
class SearchWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val indexer: SearchIndexer,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private lateinit var task: ItemRef
	private lateinit var note: ItemRef

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from search_document")
		fakes.reset()
		task = fakes.tasks.add("Plan holiday").also { indexer.upsert(it.type, it.id, "Plan holiday", "") }
		note = fakes.notes.add("Holiday ideas").also { indexer.upsert(it.type, it.id, "Holiday ideas", "beach or mountains") }
	}

	@Test
	fun `returns hits grouped by type with resolved display data`() {
		mockMvc
			.get(BASE) { param("q", "holi") }
			.andExpect {
				status { isOk() }
				content { contentType(MediaType.APPLICATION_JSON) }
				jsonPath("$.groups.length()") { value(2) }
				jsonPath("$.groups[?(@.type == 'TASK')].items[0].id") { value(task.id.toString()) }
				jsonPath("$.groups[?(@.type == 'TASK')].items[0].title") { value("Plan holiday") }
				jsonPath("$.groups[?(@.type == 'TASK')].items[0].deepLink") { value("/task/${task.id}") }
				jsonPath("$.groups[?(@.type == 'TASK')].items[0].icon") { value("task_icon") }
				jsonPath("$.groups[?(@.type == 'NOTE')].items[0].id") { value(note.id.toString()) }
				jsonPath("$.groups[0].items[0].updatedAt") { exists() }
			}
	}

	@Test
	fun `types filters, comma-separated or repeated`() {
		mockMvc
			.get(BASE) {
				param("q", "holiday")
				param("types", "NOTE")
			}.andExpect {
				status { isOk() }
				jsonPath("$.groups.length()") { value(1) }
				jsonPath("$.groups[0].type") { value("NOTE") }
			}
		mockMvc
			.get(BASE) {
				param("q", "holiday")
				param("types", "NOTE,TASK")
			}.andExpect {
				jsonPath("$.groups.length()") { value(2) }
			}
		mockMvc
			.get(BASE) {
				param("q", "holiday")
				param("types", "NOTE", "TASK")
			}.andExpect {
				jsonPath("$.groups.length()") { value(2) }
			}
	}

	@Test
	fun `limit caps the number of items`() {
		mockMvc
			.get(BASE) {
				param("q", "holiday")
				param("limit", "1")
			}.andExpect {
				status { isOk() }
				jsonPath("$.groups.length()") { value(1) }
				jsonPath("$.groups[0].items.length()") { value(1) }
			}
	}

	@Test
	fun `no match gives empty groups`() {
		mockMvc.get(BASE) { param("q", "zebra") }.andExpect {
			status { isOk() }
			jsonPath("$.groups.length()") { value(0) }
		}
	}

	@Test
	fun `invalid parameters are 400 problem details`() {
		listOf(
			mapOf("q" to "  "),
			mapOf("q" to "x".repeat(201)),
			mapOf("q" to "holiday", "limit" to "0"),
			mapOf("q" to "holiday", "limit" to "51"),
			mapOf("q" to "holiday", "types" to "UNKNOWN"),
			emptyMap(),
		).forEach { params ->
			mockMvc
				.get(BASE) { params.forEach { (name, value) -> param(name, value) } }
				.andExpect {
					status { isBadRequest() }
					content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
				}
		}
	}

	@Test
	@WithAnonymousUser
	fun `requires login`() {
		mockMvc.get(BASE) { param("q", "holiday") }.andExpect { status { isUnauthorized() } }
	}

	private companion object {
		const val BASE = "/api/v1/search"
	}
}
