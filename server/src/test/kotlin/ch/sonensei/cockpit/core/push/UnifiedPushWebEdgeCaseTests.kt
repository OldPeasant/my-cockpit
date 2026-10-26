package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.push.infra.UnifiedPushRegistrationRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.put
import java.util.Base64

/** Limits and malformed input of `PUT`/`DELETE /api/v1/push/unifiedpush/{instanceId}`. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class UnifiedPushWebEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val repository: UnifiedPushRegistrationRepository,
) : PostgresTestBase() {
	@BeforeEach
	fun setUp() = repository.deleteAll()

	private fun put(
		instanceId: String,
		content: String,
	) = mockMvc.put("$BASE/$instanceId") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		this.content = content
	}

	private fun endpoint(path: String = "up") = """{"endpoint":"https://ntfy.example.test/$path"}"""

	@Test
	fun `instance ids of 1 and 128 allowed characters are accepted, 129 are not`() {
		put("a", endpoint()).andExpect { status { isNoContent() } }
		put("A-z_0.9", endpoint()).andExpect { status { isNoContent() } }
		put("x".repeat(128), endpoint()).andExpect { status { isNoContent() } }
		put("x".repeat(129), endpoint()).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("instanceId") }
		}

		repository.findAll().size shouldBe 3
	}

	@Test
	fun `DELETE with an invalid instance id answers 400`() {
		mockMvc.delete("$BASE/${"x".repeat(129)}") { with(csrf()) }.andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("instanceId") }
		}
	}

	@Test
	fun `an endpoint of 2048 characters is accepted, a longer one is not`() {
		val prefix = "https://ntfy.example.test/"
		put("ok", endpoint("x".repeat(2048 - prefix.length))).andExpect { status { isNoContent() } }
		put("long", endpoint("x".repeat(2049 - prefix.length))).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("endpoint") }
		}

		repository.findAll().map { it.instanceId } shouldBe listOf("ok")
	}

	@Test
	fun `a blank endpoint answers 400`() {
		put("phone", """{"endpoint":"   "}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("endpoint") }
		}
		put("phone", """{"endpoint":null}""").andExpect { status { isBadRequest() } }

		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `only auth without p256dh names p256dh`() {
		put("phone", """{"endpoint":"https://ntfy.example.test/up","auth":"${PushTestKeys.AUTH}"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("p256dh") }
		}
	}

	@Test
	fun `keys that are not base64url, not uncompressed or not on the curve answer 400 for the right field`() {
		val point = Base64.getUrlDecoder().decode(PushTestKeys.P256DH)
		val compressedPrefix = point.copyOf().also { it[0] = 0x02 }
		val offCurve = point.copyOf().also { it[64] = (it[64] + 1).toByte() }
		val b64 = Base64.getUrlEncoder().withoutPadding()

		listOf(
			"p256dh" to """"p256dh":"not*base64","auth":"${PushTestKeys.AUTH}"""",
			"p256dh" to """"p256dh":"${b64.encodeToString(compressedPrefix)}","auth":"${PushTestKeys.AUTH}"""",
			"p256dh" to """"p256dh":"${b64.encodeToString(offCurve)}","auth":"${PushTestKeys.AUTH}"""",
			"auth" to """"p256dh":"${PushTestKeys.P256DH}","auth":"not*base64"""",
		).forEach { (field, keys) ->
			put("phone", """{"endpoint":"https://ntfy.example.test/up",$keys}""").andExpect {
				status { isBadRequest() }
				jsonPath("$.errors[0].field") { value(field) }
			}
		}

		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `padded base64url keys are accepted`() {
		val padded = Base64.getUrlEncoder().encodeToString(Base64.getUrlDecoder().decode(PushTestKeys.AUTH))

		put("phone", """{"endpoint":"https://ntfy.example.test/up","p256dh":"${PushTestKeys.P256DH}=","auth":"$padded"}""")
			.andExpect { status { isNoContent() } }

		val stored = repository.findByInstanceId("phone").shouldNotBeNull()
		stored.keys.shouldNotBeNull()
	}

	@Test
	fun `malformed JSON answers 400 and stores nothing`() {
		put("phone", """{"endpoint":""").andExpect { status { isBadRequest() } }

		repository.findAll().shouldBeEmpty()
	}

	private companion object {
		const val BASE = "/api/v1/push/unifiedpush"
	}
}
