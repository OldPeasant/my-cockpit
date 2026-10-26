package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.auth.cockpitUser
import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.push.domain.PushService
import ch.sonensei.cockpit.core.push.domain.VapidKey
import ch.sonensei.cockpit.core.push.domain.VapidKeyService
import ch.sonensei.cockpit.core.push.infra.PushSubscriptionRepository
import ch.sonensei.cockpit.core.push.infra.VapidKeyRepository
import ch.sonensei.cockpit.core.push.infra.WebPushCrypto
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
	@Autowired private val push: PushService,
) : PostgresTestBase() {
	private val browser = PushTestBrowser()
	private val sessions = PushTestSessions(jdbc)

	/** The owner logged in with a browser session; [WithCockpitUser] alone has none. */
	private lateinit var owner: CockpitPrincipal

	@BeforeEach
	fun setUp() {
		subscriptions.deleteAll()
		owner = sessions.principal()
	}

	@AfterEach
	fun clean() = subscriptions.deleteAll()

	private fun subscription(
		endpoint: String = ENDPOINT,
		p256dh: String = browser.p256dh,
		auth: String = browser.auth,
	) = jsonMapper.writeValueAsString(mapOf("endpoint" to endpoint, "keys" to mapOf("p256dh" to p256dh, "auth" to auth)))

	private fun put(
		content: String,
		user: CockpitPrincipal? = owner,
	) = mockMvc.put(SUBSCRIPTIONS) {
		with(csrf())
		user?.let { with(cockpitUser(it)) }
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
	fun `subscribing keeps id, created time and version, and bumps the version only when the keys change`() {
		val created = push.subscribe(owner.sessionId!!, ENDPOINT, browser.p256dh, browser.auth)
		created.version shouldBe 0
		created.createdAt shouldNotBe null
		created.updatedAt shouldBe created.createdAt

		val again = push.subscribe(owner.sessionId!!, ENDPOINT, browser.p256dh, browser.auth)
		again.id shouldBe created.id
		again.version shouldBe 0

		val other = PushTestBrowser()
		val changed = push.subscribe(owner.sessionId!!, ENDPOINT, other.p256dh, other.auth)
		changed.id shouldBe created.id
		changed.createdAt shouldBe created.createdAt
		changed.version shouldBe 1
		changed.p256dh shouldBe other.p256dh
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
		put(subscription(), user = null).andExpect { status { isUnauthorized() } }
		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `subscribing needs a browser session, a login without one (a device token) gets 403`() {
		put(subscription(), user = owner.copy(sessionId = null)).andExpect {
			status { isForbidden() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}

		subscriptions.findAll().shouldBeEmpty()
	}

	private fun delete(content: String) =
		mockMvc.delete(SUBSCRIPTIONS) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			this.content = content
		}

	@Test
	fun `endpoints up to 2048 characters are accepted, longer ones are rejected`() {
		val prefix = "https://push.example.test/send/"
		val longest = prefix + "a".repeat(PushService.MAX_ENDPOINT_LENGTH - prefix.length)

		put(subscription(endpoint = longest)).andExpect { status { isNoContent() } }
		put(subscription(endpoint = longest + "a")).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("endpoint") }
		}

		subscriptions.findAll().map { it.endpoint } shouldBe listOf(longest)
	}

	@Test
	fun `endpoints without a host or with an unparsable URL are rejected, an upper-case scheme is fine`() {
		listOf("https:opaque", "https:///no-host", "https://push.example.test/a b", "ftp://push.example.test/x", "   ")
			.forEach { endpoint ->
				withClue(endpoint) { put(subscription(endpoint = endpoint)).andExpect { status { isBadRequest() } } }
			}
		subscriptions.findAll().shouldBeEmpty()

		put(subscription(endpoint = "HTTPS://push.example.test/send/upper")).andExpect { status { isNoContent() } }
		subscriptions.findAll() shouldHaveSize 1
	}

	@Test
	fun `rejected keys name the field in the problem details`() {
		val compressed = WebPushCrypto.encode(byteArrayOf(2) + ByteArray(32) { 1 })

		put(subscription(p256dh = compressed)).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("keys.p256dh") }
		}
		put(subscription(auth = WebPushCrypto.encode(ByteArray(17)))).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("keys.auth") }
		}
		put(subscription(auth = "not base64 !")).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("keys.auth") }
		}
		put(subscription(auth = "")).andExpect { status { isBadRequest() } }

		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `keys in padded standard base64 are accepted`() {
		val p256dh = Base64.getEncoder().encodeToString(browser.publicKey)
		val auth = Base64.getEncoder().encodeToString(browser.authSecret)

		put(subscription(p256dh = p256dh, auth = auth)).andExpect { status { isNoContent() } }

		val stored = subscriptions.findAll().single()
		withClue("stored as unpadded base64url") {
			stored.p256dh shouldBe browser.p256dh
			stored.auth shouldBe browser.auth
		}
	}

	@Test
	fun `unsubscribing needs an endpoint and leaves other subscriptions alone`() {
		put(subscription()).andExpect { status { isNoContent() } }
		put(subscription(endpoint = "$ENDPOINT-other")).andExpect { status { isNoContent() } }

		delete("{}").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("endpoint") }
		}
		delete(jsonMapper.writeValueAsString(mapOf("endpoint" to ENDPOINT))).andExpect { status { isNoContent() } }

		subscriptions.findAll().map { it.endpoint } shouldBe listOf("$ENDPOINT-other")
	}

	@Test
	@WithAnonymousUser
	fun `unsubscribing needs a login`() {
		delete(jsonMapper.writeValueAsString(mapOf("endpoint" to ENDPOINT))).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `instances generating the key pair at the same time all end up with the one stored`() {
		repeat(3) {
			jdbc.execute("delete from push_vapid_key")
			val threads = 6
			val start = CyclicBarrier(threads)
			val pool = Executors.newFixedThreadPool(threads)
			try {
				val results =
					(1..threads)
						.map {
							pool.submit<String> {
								start.await()
								vapidKeys.current().publicKeyBase64Url
							}
						}.map { it.get(30, TimeUnit.SECONDS) }

				results.toSet() shouldHaveSize 1
				keys.findAll().single().publicKey shouldBe results.first()
			} finally {
				pool.shutdownNow()
			}
		}
	}

	@Test
	fun `the same browser subscribing twice at the same time ends with one subscription`() {
		val threads = 4
		val start = CyclicBarrier(threads)
		val pool = Executors.newFixedThreadPool(threads)
		try {
			val outcomes =
				(1..threads)
					.map {
						pool.submit<Result<Unit>> {
							start.await()
							runCatching { push.subscribe(owner.sessionId!!, ENDPOINT, browser.p256dh, browser.auth) }.map { }
						}
					}.map { it.get(30, TimeUnit.SECONDS) }

			subscriptions.findAll() shouldHaveSize 1
			withClue("PUT is documented as idempotent; failures: ${outcomes.mapNotNull { it.exceptionOrNull() }}") {
				outcomes.all { it.isSuccess } shouldBe true
			}
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `a stored key pair the master key can't decrypt does not fail startup and is kept`() {
		jdbc.execute("delete from push_vapid_key")
		try {
			// Version byte 1, then nonce and tag that fail authentication: like a key encrypted with another master key.
			val foreign = Base64.getEncoder().encodeToString(ByteArray(40) { 1 })
			insertVapidKey(privateKeyColumn = foreign)

			shouldNotThrowAny { vapidKeys.generateAtStartup() }

			shouldThrow<RuntimeException> { vapidKeys.current() }
			withClue("the unreadable key is not silently replaced") { vapidKeyRows() shouldBe listOf(foreign) }
		} finally {
			restoreUsableKeyPair()
		}
	}

	@Test
	fun `a stored private key that is not a PKCS8 key does not fail startup`() {
		jdbc.execute("delete from push_vapid_key")
		try {
			keys.saveAndFlush(
				VapidKey(browser.p256dh, EncryptedSecret(Base64.getEncoder().encodeToString("not a key".toByteArray()))),
			)

			shouldNotThrowAny { vapidKeys.generateAtStartup() }

			shouldThrow<RuntimeException> { vapidKeys.current() }
			vapidKeyRows() shouldHaveSize 1
		} finally {
			restoreUsableKeyPair()
		}
	}

	@Test
	fun `startup generates the key pair when there is none`() {
		jdbc.execute("delete from push_vapid_key")
		try {
			vapidKeys.generateAtStartup()

			vapidKeyRows() shouldHaveSize 1
			vapidKeys.current().publicKeyBase64Url shouldBe keys.findAll().single().publicKey
		} finally {
			restoreUsableKeyPair()
		}
	}

	@Test
	fun `the Web Push API has its own OpenAPI tag and no tag name is repeated`() {
		// Regression: PushController and UnifiedPushController both declared the tag "Push"; the
		// Android generator rejects the contract with "attribute tags.Push is repeated".
		val contract =
			jsonMapper.readTree(
				mockMvc
					.get("/v3/api-docs")
					.andExpect { status { isOk() } }
					.andReturn()
					.response
					.getContentAsString(Charsets.UTF_8),
			)
		val tagNames = contract["tags"].toList().map { it["name"].asString() }

		withClue("tag names in the contract: $tagNames") {
			tagNames shouldBe tagNames.distinct()
			tagNames.count { it == WEB_PUSH_TAG } shouldBe 1
		}
		val operations = listOf("$BASE/vapid-public-key" to "get", SUBSCRIPTIONS to "put", SUBSCRIPTIONS to "delete")
		for ((path, method) in operations) {
			withClue("$method $path") {
				contract["paths"][path][method]["tags"].toList().map { it.asString() } shouldBe listOf(WEB_PUSH_TAG)
			}
		}
	}

	private fun insertVapidKey(privateKeyColumn: String) =
		jdbc.update(
			"""insert into push_vapid_key (id, created_at, updated_at, version, public_key, private_key)
				values (?, now(), now(), 0, ?, ?)""",
			UuidV7.generate(),
			browser.p256dh,
			privateKeyColumn,
		)

	private fun vapidKeyRows(): List<String?> = jdbc.queryForList("select private_key from push_vapid_key", String::class.java)

	/** Other tests expect the key pair generated at startup. */
	private fun restoreUsableKeyPair() {
		jdbc.execute("delete from push_vapid_key")
		vapidKeys.current()
	}

	companion object {
		const val BASE = "/api/v1/push"
		const val SUBSCRIPTIONS = "$BASE/subscriptions"
		const val ENDPOINT = "https://push.example.test/send/fictitious-subscription"
		const val WEB_PUSH_TAG = "Web Push"
	}
}
