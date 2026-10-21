package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.core.web.ProblemDetailsAdvice
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The HTTP side of triage actions ([MailActionController]) with the service replaced: request validation (400 with
 * field errors), the mapping of service exceptions to Problem Details and the shape of the per-id results.
 */
class MailActionControllerTests {
	private val actions = mockk<MailActionService>()
	private val mockMvc =
		MockMvcBuilders
			.standaloneSetup(MailActionController(actions))
			.setControllerAdvice(ProblemDetailsAdvice())
			.build()
	private val json = JsonMapper.builder().build()
	private val first = UUID.fromString("01900000-0000-7000-8000-000000000001")
	private val second = UUID.fromString("01900000-0000-7000-8000-000000000002")
	private val folder = UUID.fromString("01900000-0000-7000-8000-0000000000f1")

	private fun post(body: String): MockHttpServletResponse =
		mockMvc
			.post("/api/v1/mail/messages/actions") {
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andReturn()
			.response

	private fun MockHttpServletResponse.body(): JsonNode = json.readTree(contentAsString)

	private fun MockHttpServletResponse.errorFields(): List<String> = body()["errors"].toList().map { it["field"].asString() }

	private fun ids(vararg ids: UUID) = ids.joinToString(",", "[", "]") { "\"$it\"" }

	@Test
	fun `a valid request is handed to the service and returns one result per outcome`() {
		every { actions.apply(listOf(first, second), MailAction.FLAG, null) } returns
			listOf(MailActionOutcome.ok(first), MailActionOutcome.failed(second, "NOT_FOUND", "gone"))

		val response = post("""{"ids":${ids(first, second)},"action":"FLAG"}""")

		response.status shouldBe 200
		val results = response.body()["results"]
		results.size() shouldBe 2
		results[0]["id"].asString() shouldBe first.toString()
		results[0]["ok"].asBoolean() shouldBe true
		results[0]["errorCode"].isNull shouldBe true
		results[0]["detail"].isNull shouldBe true
		results[1]["ok"].asBoolean() shouldBe false
		results[1]["errorCode"].asString() shouldBe "NOT_FOUND"
		results[1]["detail"].asString() shouldBe "gone"
	}

	@Test
	fun `every action name is accepted and targetFolderId is passed through`() {
		every { actions.apply(any(), any(), any()) } returns emptyList()

		MailAction.entries.forEach { action ->
			val target = if (action == MailAction.MOVE) ",\"targetFolderId\":\"$folder\"" else ""
			post("""{"ids":${ids(first)},"action":"${action.name}"$target}""").status shouldBe 200
		}

		verify { actions.apply(listOf(first), MailAction.MOVE, folder) }
		verify(exactly = MailAction.entries.size - 1) { actions.apply(listOf(first), neq(MailAction.MOVE), null) }
	}

	@Test
	fun `empty or missing ids are rejected without calling the service`() {
		post("""{"ids":[],"action":"READ"}""").let {
			it.status shouldBe 400
			it.errorFields() shouldBe listOf("ids")
		}
		post("""{"action":"READ"}""").let {
			it.status shouldBe 400
			it.errorFields() shouldBe listOf("ids")
		}
		confirmVerified(actions)
	}

	@Test
	fun `at most 1000 ids are accepted`() {
		every { actions.apply(any(), any(), any()) } returns emptyList()
		val thousand = (1..MailActionRequest.MAX_IDS).map { UUID(0x0190_0000_0000_7000L, it.toLong()) }

		post("""{"ids":${ids(*thousand.toTypedArray())},"action":"READ"}""").status shouldBe 200

		val tooMany = thousand + UUID(0x0190_0000_0000_7000L, 1001L)
		post("""{"ids":${ids(*tooMany.toTypedArray())},"action":"READ"}""").let {
			it.status shouldBe 400
			it.errorFields() shouldBe listOf("ids")
		}
		verify(exactly = 1) { actions.apply(any(), any(), any()) }
	}

	@Test
	fun `a null id is rejected`() {
		val response = post("""{"ids":["$first",null],"action":"READ"}""")

		response.status shouldBe 400
		response.errorFields() shouldBe listOf("ids")
		confirmVerified(actions)
	}

	@Test
	fun `a missing action is rejected`() {
		val response = post("""{"ids":${ids(first)}}""")

		response.status shouldBe 400
		response.errorFields() shouldBe listOf("action")
		confirmVerified(actions)
	}

	@Test
	fun `missing ids and action are reported together`() {
		val response = post("{}")

		response.status shouldBe 400
		response.errorFields() shouldBe listOf("action", "ids")
	}

	@Test
	fun `an unknown action, a malformed id or malformed JSON is a 400 without echoing the input`() {
		listOf(
			"""{"ids":${ids(first)},"action":"SHRED"}""",
			"""{"ids":["not-a-uuid"],"action":"READ"}""",
			"""{"ids":${ids(first)},"action":"READ","targetFolderId":"not-a-uuid"}""",
			"""{"ids":""",
		).forEach { body ->
			val response = post(body)
			response.status shouldBe 400
			response.contentAsString.contains("SHRED") shouldBe false
			response.contentAsString.contains("not-a-uuid") shouldBe false
		}
		confirmVerified(actions)
	}

	@Test
	fun `a targetFolderId problem found by the service is a 400 with its field error`() {
		every { actions.apply(listOf(first), MailAction.READ, folder) } throws
			InvalidRequestException("targetFolderId", "is only allowed for MOVE")

		val response = post("""{"ids":${ids(first)},"action":"READ","targetFolderId":"$folder"}""")

		response.status shouldBe 400
		response.errorFields() shouldBe listOf("targetFolderId")
		response.body()["errors"][0]["message"].asString() shouldBe "is only allowed for MOVE"
	}

	@Test
	fun `an unknown target folder is a 404 Problem Details`() {
		every { actions.apply(listOf(first), MailAction.MOVE, folder) } throws NotFoundException("Mail folder not found")

		val response = post("""{"ids":${ids(first)},"action":"MOVE","targetFolderId":"$folder"}""")

		response.status shouldBe 404
		response.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
		response.body()["detail"].asString() shouldBe "Mail folder not found"
	}

	@Test
	fun `a non-JSON body is not accepted`() {
		val response =
			mockMvc
				.post("/api/v1/mail/messages/actions") {
					contentType = MediaType.TEXT_PLAIN
					content = "READ $first"
				}.andReturn()
				.response

		response.status shouldBe 415
		confirmVerified(actions)
	}
}
