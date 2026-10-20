package ch.sonensei.cockpit.core.web

import ch.sonensei.cockpit.core.web.ConventionsTestApi.Companion.BASE
import ch.sonensei.cockpit.core.web.ConventionsTestApi.Companion.SECRET_MESSAGE
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders.ETAG
import org.springframework.http.HttpHeaders.IF_MATCH
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConventionsTestApi::class)
@WithMockUser
class ApiConventionsWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val controller: ConventionsTestApi.Controller,
) : PostgresTestBase() {
	@BeforeEach
	fun reset() = controller.reset()

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.status") { value(status) }
			jsonPath("$.title") { exists() }
			jsonPath("$.trace") { doesNotExist() }
			jsonPath("$.exception") { doesNotExist() }
		}

	@Nested
	inner class Errors {
		@Test
		fun `body validation gives 400 with field errors`() {
			mockMvc
				.patch("$BASE/items/a") {
					with(csrf())
					header(IF_MATCH, "\"1\"")
					contentType = MediaType.APPLICATION_JSON
					content = """{"name": ""}"""
				}.isProblem(400)
				.andExpect {
					jsonPath("$.detail") { value("Request validation failed") }
					jsonPath("$.errors[0].field") { value("name") }
					jsonPath("$.errors[0].message") { exists() }
				}
		}

		@Test
		fun `parameter validation gives 400 with field errors`() {
			mockMvc.get("$BASE/bounded?count=9").isProblem(400).andExpect {
				jsonPath("$.errors[0].field") { value("count") }
			}
		}

		@Test
		fun `several invalid parameters give one 400 listing every field error sorted by field`() {
			mockMvc.get("$BASE/range?to=0&from=9").isProblem(400).andExpect {
				jsonPath("$.errors.length()") { value(2) }
				jsonPath("$.errors[0].field") { value("from") }
				jsonPath("$.errors[1].field") { value("to") }
			}
		}

		@Test
		fun `validation in a service gives 400 with field errors instead of 500`() {
			mockMvc.get("$BASE/schedule?days=0").isProblem(400).andExpect {
				jsonPath("$.detail") { value("Request validation failed") }
				jsonPath("$.errors[0].field") { value("days") }
				jsonPath("$.errors[0].message") { exists() }
			}
			mockMvc.get("$BASE/schedule?days=3").andExpect {
				status { isOk() }
				jsonPath("$.days") { value(3) }
			}
		}

		@Test
		fun `authentication failure raised in a controller stays 401 and is not turned into a 500`() {
			val body =
				mockMvc
					.get("$BASE/bad-credentials")
					.andExpect { status { isUnauthorized() } }
					.andReturn()
					.response.contentAsString
			body shouldNotContain "fictitious-user"
			body shouldNotContain "An unexpected error occurred"
		}

		@Test
		fun `missing required parameter gives 400 problem with a field error`() {
			mockMvc.get("$BASE/bounded").isProblem(400).andExpect {
				jsonPath("$.errors.length()") { value(1) }
				jsonPath("$.errors[0].field") { value("count") }
				jsonPath("$.errors[0].message") { value("is required") }
			}
		}

		@Test
		fun `missing required header gives 400 problem with a field error`() {
			mockMvc.get("$BASE/client").isProblem(400).andExpect {
				jsonPath("$.errors.length()") { value(1) }
				jsonPath("$.errors[0].field") { value("X-Client-Name") }
				jsonPath("$.errors[0].message") { value("is required") }
			}
		}

		@Test
		fun `wrongly typed parameter gives 400 with a field error that does not echo the value`() {
			val body =
				mockMvc
					.get("$BASE/bounded?count=fictitious-value")
					.isProblem(400)
					.andExpect {
						jsonPath("$.errors.length()") { value(1) }
						jsonPath("$.errors[0].field") { value("count") }
						jsonPath("$.errors[0].message") { value("has an invalid value") }
					}.andReturn()
					.response.contentAsString
			body shouldNotContain "fictitious-value"
			body shouldNotContain "Exception"
		}

		@Test
		fun `unsupported method gives 405 problem`() {
			mockMvc.delete("$BASE/conflict") { with(csrf()) }.isProblem(405)
		}

		@Test
		fun `unsupported content type gives 415 problem`() {
			mockMvc
				.patch("$BASE/items/a") {
					with(csrf())
					header(IF_MATCH, "\"1\"")
					contentType = MediaType.TEXT_PLAIN
					content = "Beta"
				}.isProblem(415)
		}

		@Test
		fun `access denied stays 403 and is not turned into a 500`() {
			val body =
				mockMvc
					.get("$BASE/forbidden")
					.andExpect { status { isForbidden() } }
					.andReturn()
					.response.contentAsString
			body shouldNotContain "Not your item"
			body shouldNotContain "An unexpected error occurred"
		}

		@Test
		@WithAnonymousUser
		fun `unauthenticated request stays 401 and is not turned into a 500`() {
			val body =
				mockMvc
					.get("$BASE/items/a")
					.andExpect { status { isUnauthorized() } }
					.andReturn()
					.response.contentAsString
			body shouldNotContain "Alpha"
			body shouldNotContain "An unexpected error occurred"
		}

		@Test
		fun `validation done in code gives 400 with field errors`() {
			mockMvc.get("$BASE/invalid").isProblem(400).andExpect {
				jsonPath("$.errors[0].field") { value("due") }
				jsonPath("$.errors[0].message") { value("must be in the future") }
			}
		}

		@Test
		fun `malformed JSON gives 400 without parser internals`() {
			val body =
				mockMvc
					.patch("$BASE/items/a") {
						with(csrf())
						header(IF_MATCH, "\"1\"")
						contentType = MediaType.APPLICATION_JSON
						content = """{"name": """
					}.isProblem(400)
					.andReturn()
					.response.contentAsString
			body shouldNotContain "tools.jackson"
			body shouldNotContain "Exception"
		}

		@Test
		fun `unknown entity gives 404`() {
			mockMvc.get("$BASE/items/missing").isProblem(404).andExpect {
				jsonPath("$.detail") { value("Item not found") }
			}
		}

		@Test
		fun `unknown route gives 404 problem`() {
			mockMvc.get("/api/v1/does-not-exist").isProblem(404)
		}

		@Test
		fun `conflict gives 409`() {
			mockMvc.get("$BASE/conflict").isProblem(409).andExpect {
				jsonPath("$.detail") { value("Name already taken") }
			}
		}

		@Test
		fun `optimistic locking failure gives 409 without entity internals`() {
			val body =
				mockMvc
					.get("$BASE/optimistic-lock")
					.isProblem(409)
					.andReturn()
					.response.contentAsString
			body shouldNotContain "ConventionsTestApi"
		}

		@Test
		fun `any optimistic locking failure gives 409 without internals`() {
			val body =
				mockMvc
					.get("$BASE/optimistic-lock-generic")
					.isProblem(409)
					.andReturn()
					.response.contentAsString
			body shouldNotContain "another transaction"
		}

		@Test
		fun `unexpected exception gives a generic 500 without message or stack trace`() {
			val body =
				mockMvc
					.get("$BASE/boom")
					.isProblem(500)
					.andExpect { jsonPath("$.detail") { value("An unexpected error occurred") } }
					.andReturn()
					.response.contentAsString
			body shouldNotContain SECRET_MESSAGE
			body shouldNotContain "hunter2"
			body shouldNotContain "IllegalStateException"
			body shouldNotContain "at ch.sonensei"
		}
	}

	@Nested
	inner class ETagsAndIfMatch {
		@Test
		fun `GET returns the version as ETag`() {
			mockMvc.get("$BASE/items/a").andExpect {
				status { isOk() }
				header { string(ETAG, "\"1\"") }
				jsonPath("$.version") { value(1) }
			}
		}

		@Test
		fun `PATCH without If-Match gives 428`() {
			mockMvc
				.patch("$BASE/items/a") {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = """{"name": "Beta"}"""
				}.isProblem(428)
		}

		@Test
		fun `PATCH with a stale If-Match gives 412 and changes nothing`() {
			mockMvc
				.patch("$BASE/items/a") {
					with(csrf())
					header(IF_MATCH, "\"0\"")
					contentType = MediaType.APPLICATION_JSON
					content = """{"name": "Beta"}"""
				}.isProblem(412)
			mockMvc.get("$BASE/items/a").andExpect { jsonPath("$.name") { value("Alpha") } }
		}

		@Test
		fun `PATCH with the current If-Match updates and returns the new ETag`() {
			mockMvc
				.patch("$BASE/items/a") {
					with(csrf())
					header(IF_MATCH, "\"1\"")
					contentType = MediaType.APPLICATION_JSON
					content = """{"name": "Beta"}"""
				}.andExpect {
					status { isOk() }
					header { string(ETAG, "\"2\"") }
					jsonPath("$.name") { value("Beta") }
				}
		}

		@Test
		fun `DELETE without If-Match gives 428`() {
			mockMvc.delete("$BASE/items/a") { with(csrf()) }.isProblem(428)
		}

		@Test
		fun `DELETE with a stale If-Match gives 412`() {
			mockMvc
				.delete("$BASE/items/a") {
					with(csrf())
					header(IF_MATCH, "\"7\"")
				}.isProblem(412)
		}

		@Test
		fun `DELETE with the current If-Match gives 204`() {
			mockMvc
				.delete("$BASE/items/a") {
					with(csrf())
					header(IF_MATCH, "\"1\"")
				}.andExpect { status { isNoContent() } }
			mockMvc.get("$BASE/items/a").isProblem(404)
		}
	}

	@Nested
	inner class Pagination {
		@Test
		fun `pages through all items with nextCursor`() {
			var cursor: String? = null
			val seen = mutableListOf<Int>()
			repeat(3) {
				val result =
					mockMvc
						.get("$BASE/numbers") {
							param("limit", "3")
							cursor?.let { param("cursor", it) }
						}.andExpect { status { isOk() } }
						.andReturn()
				val json = result.response.contentAsString
				seen +=
					Regex("\"items\":\\[([0-9,]*)]")
						.find(json)!!
						.groupValues[1]
						.split(',')
						.map { it.toInt() }
				cursor = Regex("\"nextCursor\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
			}
			seen shouldBe (1..ConventionsTestApi.NUMBER_COUNT).toList()
			cursor shouldBe null
		}

		@Test
		fun `last page has a null nextCursor`() {
			mockMvc.get("$BASE/numbers?limit=50").andExpect {
				jsonPath("$.items.length()") { value(ConventionsTestApi.NUMBER_COUNT) }
				jsonPath("$.nextCursor") { value(nullValue()) }
			}
		}

		@Test
		fun `limit out of bounds gives 400`() {
			mockMvc.get("$BASE/numbers?limit=0").isProblem(400).andExpect {
				jsonPath("$.errors[0].field") { value("limit") }
			}
			mockMvc.get("$BASE/numbers?limit=201").isProblem(400)
		}

		@Test
		fun `non-numeric limit gives 400`() {
			mockMvc.get("$BASE/numbers?limit=abc").isProblem(400).andExpect {
				jsonPath("$.errors[0].field") { value("limit") }
				jsonPath("$.errors[0].message") { value("has an invalid value") }
			}
		}

		@Test
		fun `malformed cursor gives 400`() {
			mockMvc.get("$BASE/numbers?cursor=%%%").isProblem(400).andExpect {
				jsonPath("$.errors[0].field") { value("cursor") }
			}
		}
	}
}
