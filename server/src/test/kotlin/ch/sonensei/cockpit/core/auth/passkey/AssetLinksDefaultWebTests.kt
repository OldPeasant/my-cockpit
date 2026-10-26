package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.cockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.head
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/**
 * `/.well-known/assetlinks.json` without any `cockpit.auth.passkey.android-*` configuration, in the shared default
 * context: a valid empty file for anyone, and only GET/HEAD are opened by the public rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssetLinksDefaultWebTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	@Test
	fun `without fingerprints assetlinks json is an empty JSON list, public and cacheable`() {
		mockMvc.get(PATH).andExpect {
			status { isOk() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
			jsonPath("$") { isArray() }
			jsonPath("$.length()") { value(0) }
			header { string("Cache-Control", "max-age=3600, public") }
		}
		mockMvc.head(PATH).andExpect { status { isOk() } }
	}

	@Test
	fun `state-changing requests to assetlinks json still need a login`() {
		mockMvc.post(PATH) { with(csrf()) }.andExpect { status { isUnauthorized() } }
		mockMvc.put(PATH) { with(csrf()) }.andExpect { status { isUnauthorized() } }
		mockMvc.delete(PATH) { with(csrf()) }.andExpect { status { isUnauthorized() } }
		mockMvc.post(PATH).andExpect { status { isForbidden() } }
	}

	@Test
	fun `the public rule does not open the API under a similar path`() {
		mockMvc.get("/api/v1/.well-known/assetlinks.json").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `assetlinks json is not part of the published API contract`() {
		val contract =
			mockMvc
				.get("/v3/api-docs.yaml") { with(cockpitUser()) }
				.andExpect { status { isOk() } }
				.andReturn()
				.response
				.getContentAsString(Charsets.UTF_8)

		contract shouldNotContain "assetlinks"
		contract shouldNotContain "well-known/"
		contract.isNotBlank() shouldBe true
	}

	companion object {
		const val PATH = "/.well-known/assetlinks.json"
	}
}
