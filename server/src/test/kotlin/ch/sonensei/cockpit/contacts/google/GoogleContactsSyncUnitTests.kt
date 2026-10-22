package ch.sonensei.cockpit.contacts.google

import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.infra.ContactRepository
import ch.sonensei.cockpit.core.oauth.domain.OAuthAccessToken
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionInfo
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionNotFoundException
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionService
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * [GoogleContactsSync.syncAll] and [GoogleContactsSyncScheduler] without Spring or a database: which OAuth
 * connections are synced, and that a failing run never escapes the scheduler.
 *
 * The token provider records which connections a run asked a token for and answers "connection removed", so each run
 * stops before calling Google (the API URL points nowhere).
 */
class GoogleContactsSyncUnitTests {
	private val connections = mockk<OAuthConnectionService>()
	private val tokens = RecordingTokens()
	private val accounts = mockk<GoogleContactsAccountRepository>()
	private val links = mockk<GoogleContactLinkRepository>()

	private val sync =
		GoogleContactsSync(
			tokens = tokens,
			json = JsonMapper.builder().build(),
			connections = connections,
			accounts = accounts,
			links = links,
			lookup = mockk(relaxed = true),
			contacts = mockk<ContactRepository>(relaxed = true),
			service = mockk<ContactService>(relaxed = true),
			transactionManager = mockk<PlatformTransactionManager>(relaxed = true),
			clock = Clock.fixed(Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC),
			apiUrl = "http://127.0.0.1:9",
		)

	init {
		every { accounts.findById(any()) } returns Optional.empty()
		every { accounts.saveAndFlush(any<GoogleContactsAccount>()) } answers { firstArg() }
		every { links.findByConnectionId(any()) } returns emptyList()
	}

	class RecordingTokens : OAuthTokenProvider {
		val asked = mutableListOf<UUID>()

		override fun accessToken(
			connectionId: UUID,
			resource: OAuthResource,
		): OAuthAccessToken {
			asked += connectionId
			throw OAuthConnectionNotFoundException()
		}

		override fun connection(connectionId: UUID): OAuthConnectionInfo? = null
	}

	private fun connection(
		n: Int,
		provider: OAuthProvider = OAuthProvider.GOOGLE,
		status: OAuthConnectionStatus = OAuthConnectionStatus.CONNECTED,
		scopes: List<String> = listOf("openid", GoogleContactsSync.CONTACTS_SCOPE),
	) = OAuthConnectionInfo(
		id = id(n),
		provider = provider,
		email = "account$n@example.test",
		displayName = null,
		scopes = scopes,
		status = status,
		lastError = null,
		lastRefreshedAt = null,
		createdAt = Instant.parse("2026-01-01T00:00:00Z"),
		version = 0,
	)

	private fun id(n: Int): UUID = UUID.fromString("0192a5f0-0000-7000-8000-0000000000${n.toString().padStart(2, '0')}")

	@Test
	fun `syncAll syncs every connected Google connection that granted the contacts scope, and only those`() {
		every { connections.list() } returns
			listOf(
				connection(1),
				connection(2, scopes = listOf("openid", "https://www.googleapis.com/auth/calendar")),
				connection(3, status = OAuthConnectionStatus.RECONNECT_REQUIRED),
				connection(4, provider = OAuthProvider.MICROSOFT),
				connection(5, scopes = listOf("https://www.googleapis.com/auth/contacts.readonly")),
				connection(6, scopes = listOf(GoogleContactsSync.CONTACTS_SCOPE)),
			)

		sync.syncAll()

		tokens.asked shouldContainExactly listOf(id(1), id(6))
	}

	@Test
	fun `syncAll without connections does nothing`() {
		every { connections.list() } returns emptyList()

		sync.syncAll()

		tokens.asked.shouldBeEmpty()
		verify(exactly = 0) { accounts.saveAndFlush(any<GoogleContactsAccount>()) }
	}

	@Test
	fun `a failing account does not stop syncAll from syncing the next one`() {
		every { connections.list() } returns listOf(connection(1), connection(2))

		sync.syncAll()

		tokens.asked shouldContainExactly listOf(id(1), id(2))
	}

	@Test
	fun `a run that fails with a token error records the attempt and returns the state`() {
		val state = sync.sync(id(7))

		state.connectionId shouldBe id(7)
		tokens.asked shouldContainExactly listOf(id(7))
		verify { accounts.saveAndFlush(match<GoogleContactsAccount> { it.lastAttemptAt == Instant.parse("2026-10-22T08:00:00Z") }) }
	}

	// --- scheduler

	@Test
	fun `the scheduler runs syncAll`() {
		val sync = mockk<GoogleContactsSync>(relaxed = true)

		GoogleContactsSyncScheduler(sync).run()

		verify(exactly = 1) { sync.syncAll() }
	}

	@Test
	fun `the scheduler swallows a failing run so that the next one still happens`() {
		val sync = mockk<GoogleContactsSync>()
		every { sync.syncAll() } throws IllegalStateException("database down")
		val scheduler = GoogleContactsSyncScheduler(sync)

		shouldNotThrowAny { scheduler.run() }
		shouldNotThrowAny { scheduler.run() }

		verify(exactly = 2) { sync.syncAll() }
	}
}
