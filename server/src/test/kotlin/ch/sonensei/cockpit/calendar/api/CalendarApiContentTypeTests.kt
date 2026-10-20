package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper

/**
 * Successful calendar responses are JSON, never Problem Details: neither in the served content type nor in the
 * documented contract that the clients are generated from (review finding CR-3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class CalendarApiContentTypeTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@Test
	fun `the occurrence list is served as JSON`() {
		CalendarApi.resetCalendarTables(jdbc)
		listOf(MediaType.APPLICATION_JSON, MediaType.ALL).forEach { accept ->
			mockMvc
				.get(CalendarApi.EVENTS) {
					this.accept = accept
					param("from", "2026-03-01T00:00:00Z")
					param("to", "2026-03-08T00:00:00Z")
				}.andExpect {
					status { isOk() }
					content { contentType(MediaType.APPLICATION_JSON) }
				}
		}
	}

	@Test
	fun `the contract documents successful calendar responses as JSON only`() {
		val paths =
			jsonMapper
				.readTree(
					mockMvc
						.get("/v3/api-docs")
						.andExpect { status { isOk() } }
						.andReturn()
						.response.contentAsString,
				).get("paths")
		val listed =
			paths
				.get(CalendarApi.EVENTS)
				.get("get")
				.get("responses")
				.get("200")
				.get("content")
				.propertyNames()
				.toList()
		listed shouldContainExactly listOf(MediaType.APPLICATION_JSON_VALUE)

		val calendarPaths = paths.propertyNames().filter { it.startsWith("/api/v1/calendar/") }
		calendarPaths.isNotEmpty() shouldBe true
		calendarPaths.forEach { path ->
			paths.get(path).properties().forEach { (method, operation) ->
				operation.get("responses").properties().filter { (status, _) -> status.startsWith("2") }.forEach { (status, response) ->
					withClue("$method $path $status") {
						(response.get("content")?.propertyNames()?.toList() ?: emptyList()) shouldNotContain
							MediaType.APPLICATION_PROBLEM_JSON_VALUE
					}
				}
			}
		}
	}
}
