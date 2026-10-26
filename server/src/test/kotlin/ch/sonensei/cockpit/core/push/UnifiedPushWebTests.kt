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
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.put

/** `PUT`/`DELETE /api/v1/push/unifiedpush/{instanceId}` over HTTP, against Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class UnifiedPushWebTests(
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

	@Test
	fun `PUT stores the endpoint and a second PUT replaces it`() {
		put("phone", """{"endpoint":"https://ntfy.example.org/up1"}""").andExpect { status { isNoContent() } }
		put("phone", """{"endpoint":"https://ntfy.example.org/up2","p256dh":"${PushTestKeys.P256DH}","auth":"${PushTestKeys.AUTH}"}""")
			.andExpect { status { isNoContent() } }

		val stored = repository.findAll()
		stored.size shouldBe 1
		stored[0].instanceId shouldBe "phone"
		stored[0].endpoint shouldBe "https://ntfy.example.org/up2"
		stored[0].keys.shouldNotBeNull().p256dh shouldBe PushTestKeys.P256DH
	}

	@Test
	fun `PUT without keys after one with keys clears them`() {
		put("phone", """{"endpoint":"https://ntfy.example.org/up","p256dh":"${PushTestKeys.P256DH}","auth":"${PushTestKeys.AUTH}"}""")
		put("phone", """{"endpoint":"https://ntfy.example.org/up"}""").andExpect { status { isNoContent() } }

		repository.findAll().single().keys shouldBe null
	}

	@Test
	fun `DELETE removes the endpoint and is idempotent`() {
		put("phone", """{"endpoint":"https://ntfy.example.org/up"}""")
		put("tablet", """{"endpoint":"https://ntfy.example.org/other"}""")

		mockMvc.delete("$BASE/phone") { with(csrf()) }.andExpect { status { isNoContent() } }
		mockMvc.delete("$BASE/phone") { with(csrf()) }.andExpect { status { isNoContent() } }

		repository.findAll().map { it.instanceId } shouldBe listOf("tablet")
	}

	@Test
	fun `invalid requests answer 400 with the field`() {
		put("phone", """{}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("endpoint") }
		}
		put("phone", """{"endpoint":"ftp://ntfy.example.org/up"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("endpoint") }
		}
		put("phone", """{"endpoint":"not a url"}""").andExpect { status { isBadRequest() } }
		put("phone", """{"endpoint":"https://ntfy.example.org/up","p256dh":"${PushTestKeys.P256DH}"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("auth") }
		}
		put("phone", """{"endpoint":"https://ntfy.example.org/up","p256dh":"AAAA","auth":"${PushTestKeys.AUTH}"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("p256dh") }
		}
		put("phone", """{"endpoint":"https://ntfy.example.org/up","p256dh":"${PushTestKeys.P256DH}","auth":"AAAA"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("auth") }
		}
		put("bad id!", """{"endpoint":"https://ntfy.example.org/up"}""").andExpect { status { isBadRequest() } }

		repository.findAll().shouldBeEmpty()
	}

	@Test
	@WithAnonymousUser
	fun `anonymous requests answer 401`() {
		put("phone", """{"endpoint":"https://ntfy.example.org/up"}""").andExpect { status { isUnauthorized() } }
		mockMvc.delete("$BASE/phone") { with(csrf()) }.andExpect { status { isUnauthorized() } }
	}

	private companion object {
		const val BASE = "/api/v1/push/unifiedpush"
	}
}
