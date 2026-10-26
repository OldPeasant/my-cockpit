package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.push.domain.VapidKeyService
import ch.sonensei.cockpit.core.push.infra.PushSubscriptionRepository
import ch.sonensei.cockpit.core.push.infra.VapidKeyRepository
import ch.sonensei.cockpit.core.push.infra.WebPushCrypto
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

/** `/api/v1/push` over HTTP (SPEC.md §4.6), against Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class PushWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val subscriptions: PushSubscriptionRepository,
	@Autowired private val keys: VapidKeyRepository,
	@Autowired private val vapidKeys: VapidKeyService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
) : PostgresTestBase() {
	private val browser = PushTestBrowser()

	@BeforeEach
	@AfterEach
	fun clean() = subscriptions.deleteAll()

	private fun subscription(
		endpoint: String = ENDPOINT,
		p256dh: String = browser.p256dh,
		auth: String = browser.auth,
	) = jsonMapper.writeValueAsString(mapOf("endpoint" to endpoint, "keys" to mapOf("p256dh" to p256dh, "auth" to auth)))

	private fun put(content: String) =
		mockMvc.put(SUBSCRIPTIONS) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			this.content = content
		}

	private fun publicKey(): String =
		jsonMapper
			.readTree(
				mockMvc
					.get("$BASE/vapid-public-key")
					.andExpect { status { isOk() } }
					.andReturn()
					.response.contentAsString,
			)["publicKey"]
			.asString()

	@Test
	fun `the VAPID key pair exists after startup and stays the same`() {
		keys.findAll() shouldHaveSize 1
		val key = publicKey()

		WebPushCrypto.publicKey(WebPushCrypto.decode(key)) // a valid P-256 point
		publicKey() shouldBe key
		withClue("the private key is stored encrypted") {
			val stored = jdbc.queryForObject("select private_key from push_vapid_key", String::class.java)!!
			val pkcs8 = Base64.getEncoder().encodeToString(vapidKeys.current().privateKey.encoded)
			stored shouldNotBe pkcs8
		}
	}

	@Test
	fun `a restore that emptied the table gets a new key pair on next use`() {
		val before = publicKey()
		jdbc.execute("delete from push_vapid_key")

		val after = publicKey()

		after shouldNotBe before
		keys.findAll() shouldHaveSize 1
	}

	@Test
	fun `subscribing stores the subscription, again with the same endpoint replaces its keys`() {
		put(subscription()).andExpect { status { isNoContent() } }
		val other = PushTestBrowser()
		put(subscription(p256dh = other.p256dh, auth = other.auth)).andExpect { status { isNoContent() } }

		val stored = subscriptions.findAll().single()
		stored.endpoint shouldBe ENDPOINT
		stored.p256dh shouldBe other.p256dh
		stored.auth shouldBe other.auth
	}

	@Test
	fun `invalid subscriptions are rejected with 400`() {
		put(subscription(endpoint = "http://push.example.test/x")).andExpect { status { isBadRequest() } }
		put(subscription(endpoint = "https://user:pw@push.example.test/x")).andExpect { status { isBadRequest() } }
		put(subscription(endpoint = "/relative")).andExpect { status { isBadRequest() } }
		put(subscription(p256dh = WebPushCrypto.encode(ByteArray(65) { 4 }))).andExpect { status { isBadRequest() } }
		put(subscription(p256dh = "not base64 !")).andExpect { status { isBadRequest() } }
		put(subscription(auth = WebPushCrypto.encode(ByteArray(8)))).andExpect { status { isBadRequest() } }
		put("""{"endpoint":"$ENDPOINT"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("keys") }
		}

		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `unsubscribing removes the subscription and succeeds when there is none`() {
		put(subscription()).andExpect { status { isNoContent() } }
		val body = jsonMapper.writeValueAsString(mapOf("endpoint" to ENDPOINT))

		repeat(2) {
			mockMvc
				.delete(SUBSCRIPTIONS) {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = body
				}.andExpect { status { isNoContent() } }
		}

		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	@WithAnonymousUser
	fun `the API needs a login`() {
		mockMvc.get("$BASE/vapid-public-key").andExpect { status { isUnauthorized() } }
		put(subscription()).andExpect { status { isUnauthorized() } }
		subscriptions.findAll().shouldBeEmpty()
	}

	companion object {
		const val BASE = "/api/v1/push"
		const val SUBSCRIPTIONS = "$BASE/subscriptions"
		const val ENDPOINT = "https://push.example.test/send/fictitious-subscription"
	}
}
