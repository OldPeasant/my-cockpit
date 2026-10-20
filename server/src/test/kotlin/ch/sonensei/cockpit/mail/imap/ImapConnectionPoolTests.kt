package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionDisconnected
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.angus.mail.imap.IMAPStore
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The per-account IMAP pool with fake stores and a controllable clock (no mail server). */
class ImapConnectionPoolTests {
	private class MutableClock(
		var now: Instant,
	) : Clock() {
		override fun instant(): Instant = now

		override fun getZone(): ZoneId = ZoneOffset.UTC

		override fun withZone(zone: ZoneId?): Clock = this
	}

	private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
	private val sessions = mockk<MailSessions>()
	private val repository = mockk<MailAccountRepository>()
	private val opened = mutableListOf<IMAPStore>()

	private fun store(): IMAPStore =
		mockk<IMAPStore>(relaxed = true).also {
			every { it.isConnected } returns true
			opened += it
		}

	private fun pool(
		max: Int = 2,
		borrowTimeout: Duration = Duration.ofMillis(100),
		idleTimeout: Duration = Duration.ofMinutes(5),
	) = ImapConnectionPool(
		sessions,
		MailImapProperties(Duration.ofSeconds(15), Duration.ofSeconds(60), "", MailImapProperties.Pool(max, borrowTimeout, idleTimeout)),
		repository,
		clock,
	)

	private fun account(
		oauthConnection: UUID? = null,
		id: UUID? = null,
		imapHost: String = "imap.example.test",
	) = MailAccount(
		type = if (oauthConnection == null) MailAccountType.IMAP_SMTP else MailAccountType.GOOGLE,
		displayName = "Work",
		emailAddress = "jane.doe@example.test",
		imapHost = imapHost,
		imapPort = 993,
		imapSecurity = MailSecurity.SSL_TLS,
		smtpHost = "smtp.example.test",
		smtpPort = 465,
		smtpSecurity = MailSecurity.SSL_TLS,
		username = "jane",
		id = id,
	).apply { oauthConnectionId = oauthConnection }

	private val account = account()

	init {
		every { sessions.connectImap(any()) } answers { store() }
	}

	@Test
	fun `an idle connection younger than the idle timeout is reused`() {
		val pool = pool()
		val first = pool.withStore(account) { it }

		clock.now = clock.now.plus(Duration.ofMinutes(5).minusSeconds(1))
		val second = pool.withStore(account) { it }

		(second === first) shouldBe true
		opened.size shouldBe 1
		verify(exactly = 0) { first.close() }
	}

	@Test
	fun `an idle connection at or beyond the idle timeout is closed and replaced`() {
		val pool = pool()
		val first = pool.withStore(account) { it }

		clock.now = clock.now.plus(Duration.ofMinutes(5))
		val second = pool.withStore(account) { it }

		(second === first) shouldBe false
		verify(exactly = 1) { first.close() }
		pool.openConnections(account.id) shouldBe 1
		pool.idleConnections(account.id) shouldBe 1
	}

	@Test
	fun `an idle connection opened with an account loaded before an update is closed instead of reused`() {
		val pool = pool()
		val stale = account(id = account.id)
		val updated = account(id = account.id, imapHost = "imap.new.example.test")
		// The update committed and evicted the pool after the caller had loaded the account: it opens a new pool.
		pool.evict(account.id)
		val old = pool.withStore(stale) { it }
		pool.idleConnections(account.id) shouldBe 1

		val current = pool.withStore(updated) { it }

		(current === old) shouldBe false
		verify(exactly = 1) { old.close() }
		verify(exactly = 1) { sessions.connectImap(match { it.imapHost == "imap.new.example.test" }) }
		pool.openConnections(account.id) shouldBe 1
		(pool.withStore(updated) { it } === current) shouldBe true
	}

	@Test
	fun `an idle connection is reused by another instance of the account with the same settings`() {
		val pool = pool()
		val first = pool.withStore(account) { it }

		val reloaded = account(id = account.id).apply { displayName = "Renamed" }

		(pool.withStore(reloaded) { it } === first) shouldBe true
		verify(exactly = 0) { first.close() }
	}

	@Test
	fun `an idle connection is not reused after any connection setting changed`() {
		val changes: List<Pair<String, MailAccount.() -> Unit>> =
			listOf(
				"port" to { imapPort = 143 },
				"security" to { imapSecurity = MailSecurity.STARTTLS },
				"username" to { username = "jane.doe" },
				"password" to { password = EncryptedSecret("fictitious-secret-2") },
				"OAuth connection" to { oauthConnectionId = UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b70") },
			)
		changes.forEach { (setting, change) ->
			val pool = pool()
			val before = account(id = account.id).apply { password = EncryptedSecret("fictitious-secret-1") }
			val old = pool.withStore(before) { it }

			val after = account(id = account.id).apply { password = EncryptedSecret("fictitious-secret-1") }.apply(change)
			val current = pool.withStore(after) { it }

			withClue(setting) {
				(current === old) shouldBe false
				verify(exactly = 1) { old.close() }
				pool.openConnections(account.id) shouldBe 1
			}
		}
	}

	@Test
	fun `the password is compared by value, so a reloaded account with the same password reuses the connection`() {
		val pool = pool()
		val first = pool.withStore(account(id = account.id).apply { password = EncryptedSecret("fictitious-secret-1") }) { it }

		val reloaded = account(id = account.id).apply { password = EncryptedSecret("fictitious-secret-1") }

		(pool.withStore(reloaded) { it } === first) shouldBe true
		verify(exactly = 0) { first.close() }
	}

	@Test
	fun `a connection a stale caller returns after the update is closed on the next borrow of the updated account`() {
		val pool = pool()
		val stale = account(id = account.id)
		val updated = account(id = account.id, imapHost = "imap.new.example.test")
		val staleInUse = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val staleCall =
				executor.submit<IMAPStore> {
					pool.withStore(stale) {
						staleInUse.countDown()
						release.await(10, TimeUnit.SECONDS)
						it
					}
				}
			staleInUse.await(10, TimeUnit.SECONDS) shouldBe true
			val current = pool.withStore(updated) { it }
			release.countDown()
			val returnedStale = staleCall.get(10, TimeUnit.SECONDS)
			pool.idleConnections(account.id) shouldBe 2

			(pool.withStore(updated) { it } === current) shouldBe true

			verify(exactly = 1) { returnedStale.close() }
			verify(exactly = 0) { current.close() }
			pool.openConnections(account.id) shouldBe 1
		} finally {
			release.countDown()
			executor.shutdown()
			executor.awaitTermination(10, TimeUnit.SECONDS)
		}
	}

	@Test
	fun `the eviction sweep closes expired idle connections without a borrow and keeps fresh ones`() {
		val pool = pool()
		val other = account()
		val old = pool.withStore(account) { it }
		clock.now = clock.now.plus(Duration.ofMinutes(3))
		val fresh = pool.withStore(other) { it }

		clock.now = clock.now.plus(Duration.ofMinutes(2))
		pool.evictExpired()

		verify(exactly = 1) { old.close() }
		verify(exactly = 0) { fresh.close() }
		pool.openConnections(account.id) shouldBe 0
		pool.idleConnections(other.id) shouldBe 1
		(pool.withStore(other) { it } === fresh) shouldBe true
	}

	@Test
	fun `the eviction sweep leaves connections in use alone`() {
		val pool = pool()

		pool.withStore(account) { store ->
			clock.now = clock.now.plus(Duration.ofHours(1))
			pool.evictExpired()
			verify(exactly = 0) { store.close() }
			pool.openConnections(account.id) shouldBe 1
		}

		pool.idleConnections(account.id) shouldBe 1
	}

	@Test
	fun `the eviction sweep keeps an idle connection just younger than the idle timeout`() {
		val pool = pool()
		val store = pool.withStore(account) { it }

		clock.now = clock.now.plus(Duration.ofMinutes(5).minusMillis(1))
		pool.evictExpired()

		verify(exactly = 0) { store.close() }
		pool.idleConnections(account.id) shouldBe 1
	}

	@Test
	fun `the eviction sweep does nothing for evicted accounts and an empty pool`() {
		val pool = pool()
		pool.evictExpired()
		val store = pool.withStore(account) { it }
		pool.evict(account.id)

		clock.now = clock.now.plus(Duration.ofHours(1))
		pool.evictExpired()

		verify(exactly = 1) { store.close() }
		pool.openConnections(account.id) shouldBe 0
	}

	@Test
	fun `an idle connection the server dropped is replaced`() {
		val pool = pool()
		val first = pool.withStore(account) { it }
		every { first.isConnected } returns false

		val second = pool.withStore(account) { it }

		(second === first) shouldBe false
		verify(exactly = 1) { first.close() }
		pool.openConnections(account.id) shouldBe 1
	}

	@Test
	fun `a connection that dropped during use is not returned to the pool`() {
		val pool = pool()

		pool.withStore(account) { every { it.isConnected } returns false }

		pool.openConnections(account.id) shouldBe 0
		verify(exactly = 1) { opened.single().close() }
	}

	@Test
	fun `a failing operation closes its connection and frees the permit`() {
		val pool = pool(max = 1)

		shouldThrow<IllegalStateException> { pool.withStore(account) { error("fictitious failure") } }

		verify(exactly = 1) { opened.single().close() }
		pool.openConnections(account.id) shouldBe 0
		pool.withStore(account) { "ok" } shouldBe "ok"
	}

	@Test
	fun `a failed login frees the permit`() {
		val pool = pool(max = 1)
		every { sessions.connectImap(any()) } throws MailConnectionException(MailConnectionError.AUTHENTICATION_FAILED)

		repeat(2) {
			shouldThrow<MailConnectionException> { pool.withStore(account) { } }.error shouldBe MailConnectionError.AUTHENTICATION_FAILED
		}
		pool.openConnections(account.id) shouldBe 0
	}

	@Test
	fun `a caller waiting longer than the borrow timeout fails with POOL_EXHAUSTED`() {
		val pool = pool(max = 1, borrowTimeout = Duration.ofMillis(50))
		val held = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		try {
			executor.submit {
				pool.withStore(account) {
					held.countDown()
					release.await(10, TimeUnit.SECONDS)
				}
			}
			held.await(10, TimeUnit.SECONDS) shouldBe true

			shouldThrow<MailConnectionException> { pool.withStore(account) { } }.error shouldBe MailConnectionError.POOL_EXHAUSTED
			pool.openConnections(account.id) shouldBe 1
		} finally {
			release.countDown()
			executor.shutdown()
			executor.awaitTermination(10, TimeUnit.SECONDS)
		}
		opened.size shouldBe 1
	}

	@Test
	fun `pools of different accounts have separate limits`() {
		val pool = pool(max = 1)
		val other = account()

		pool.withStore(account) {
			pool.withStore(other) { }
		}

		pool.openConnections(account.id) shouldBe 1
		pool.openConnections(other.id) shouldBe 1
	}

	@Test
	fun `an interrupted caller fails with POOL_EXHAUSTED and keeps its interrupt flag`() {
		val pool = pool()
		Thread.currentThread().interrupt()
		try {
			shouldThrow<MailConnectionException> { pool.withStore(account) { } }.error shouldBe MailConnectionError.POOL_EXHAUSTED
			Thread.currentThread().isInterrupted shouldBe true
		} finally {
			Thread.interrupted()
		}
	}

	@Test
	fun `evicting closes idle connections, and a connection in use is closed when it comes back`() {
		val pool = pool()
		val inUse = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val busy =
				executor.submit<IMAPStore> {
					pool.withStore(account) {
						inUse.countDown()
						release.await(10, TimeUnit.SECONDS)
						it
					}
				}
			inUse.await(10, TimeUnit.SECONDS) shouldBe true
			val idle = pool.withStore(account) { it }
			pool.openConnections(account.id) shouldBe 2
			pool.idleConnections(account.id) shouldBe 1

			pool.evict(account.id)

			verify(exactly = 1) { idle.close() }
			pool.openConnections(account.id) shouldBe 0
			release.countDown()
			val returned = busy.get(10, TimeUnit.SECONDS)
			(returned === idle) shouldBe false
			verify(exactly = 1) { returned.close() }
			pool.withStore(account) { it } shouldBe opened.last()
			opened.size shouldBe 3
			pool.openConnections(account.id) shouldBe 1
		} finally {
			release.countDown()
			executor.shutdown()
			executor.awaitTermination(10, TimeUnit.SECONDS)
		}
	}

	@Test
	fun `an account change event evicts its connections`() {
		val pool = pool()
		val store = pool.withStore(account) { it }

		pool.onAccountChanged(MailAccountConnectionChanged(account.id))

		verify(exactly = 1) { store.close() }
		pool.idleConnections(account.id) shouldBe 0
	}

	@Test
	fun `disconnecting an OAuth connection evicts only the accounts using it`() {
		val connection = UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b6e")
		val google = account(connection)
		val otherGoogle = account(UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b6f"))
		every { repository.findAll() } returns listOf(account, google, otherGoogle)
		val pool = pool()
		listOf(account, google, otherGoogle).forEach { a -> pool.withStore(a) { } }

		pool.onOAuthDisconnected(OAuthConnectionDisconnected(connection, OAuthProvider.GOOGLE))

		pool.idleConnections(google.id) shouldBe 0
		pool.idleConnections(account.id) shouldBe 1
		pool.idleConnections(otherGoogle.id) shouldBe 1
	}

	@Test
	fun `a failing close is ignored`() {
		val pool = pool()
		val store = pool.withStore(account) { it }
		every { store.close() } throws IllegalStateException("fictitious close failure")

		pool.evict(account.id)

		pool.openConnections(account.id) shouldBe 0
	}

	@Test
	fun `shutdown closes every idle connection`() {
		val pool = pool()
		val other = account()
		pool.withStore(account) { }
		pool.withStore(other) { }

		pool.destroy()

		opened.forEach { verify(exactly = 1) { it.close() } }
		pool.openConnections(account.id) shouldBe 0
		pool.openConnections(other.id) shouldBe 0
	}

	@Test
	fun `the pool size must be positive`() {
		shouldThrow<IllegalArgumentException> { MailImapProperties.Pool(0, Duration.ofSeconds(1), Duration.ofMinutes(1)) }
	}
}
