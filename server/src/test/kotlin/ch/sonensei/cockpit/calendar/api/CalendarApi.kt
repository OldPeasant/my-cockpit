package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** HTTP helpers shared by the calendar API tests. */
class CalendarApi(
	private val mockMvc: MockMvc,
	private val jsonMapper: JsonMapper,
) {
	fun read(actions: ResultActionsDsl): JsonNode = jsonMapper.readTree(actions.andReturn().response.contentAsString)

	fun postJson(
		path: String,
		body: String,
	) = mockMvc.post(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	fun patchJson(
		path: String,
		body: String,
		ifMatch: String?,
	) = mockMvc.patch(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	fun deleteAt(
		path: String,
		ifMatch: String?,
	) = mockMvc.delete(path) {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	fun createCalendar(body: String = """{"name":"Personal"}"""): JsonNode =
		read(postJson(CALENDARS, body).andExpect { status { isCreated() } })

	fun createEvent(body: String): JsonNode = read(postJson(EVENTS, body).andExpect { status { isCreated() } })

	fun event(id: String): JsonNode = read(mockMvc.get("$EVENTS/$id").andExpect { status { isOk() } })

	fun patchEvent(
		id: String,
		body: String,
		query: String = "",
		ifMatch: String? = etag(event(id)),
	) = patchJson("$EVENTS/$id$query", body, ifMatch)

	fun patchEventOk(
		id: String,
		body: String,
		query: String = "",
	): JsonNode = read(patchEvent(id, body, query).andExpect { status { isOk() } })

	fun deleteEvent(
		id: String,
		query: String = "",
		ifMatch: String? = etag(event(id)),
	) = deleteAt("$EVENTS/$id$query", ifMatch)

	fun occurrences(
		from: String,
		to: String,
		vararg params: Pair<String, String>,
	): List<JsonNode> =
		read(
			mockMvc
				.get(EVENTS) {
					param("from", from)
					param("to", to)
					params.forEach { (name, value) -> param(name, value) }
				}.andExpect { status { isOk() } },
		).get("items").values().toList()

	companion object {
		const val CALENDARS = "/api/v1/calendar/calendars"
		const val EVENTS = "/api/v1/calendar/events"

		fun etag(node: JsonNode) = "\"${node.get("version").asLong()}\""

		fun JsonNode.id(): String = get("id").asString()

		fun JsonNode.text(field: String): String? = get(field)?.takeIf { !it.isNull }?.asString()

		fun ResultActionsDsl.isProblem(
			status: Int,
			vararg fields: String,
		) = andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
		}

		fun ApplicationEvents.changes(type: String = "EVENT"): List<Pair<ChangeOp, String>> =
			stream(ChangeEvent::class.java).toList().filter { it.type == type }.map { it.op to it.id.toString() }

		fun resetCalendarTables(jdbc: JdbcTemplate) {
			jdbc.execute("delete from calendar_source")
			jdbc.execute("delete from search_document")
			jdbc.execute("delete from link")
			jdbc.execute("delete from user_settings") // the user's time zone is the default, Europe/Zurich
		}
	}
}
