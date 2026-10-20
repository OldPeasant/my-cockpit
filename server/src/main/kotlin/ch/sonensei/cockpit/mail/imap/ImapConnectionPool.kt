package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionDisconnected
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import org.eclipse.angus.mail.imap.IMAPStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Published when an account's connection settings or credentials changed or it was deleted. */
data class MailAccountConnectionChanged(
	val accountId: UUID,
)

/**
 * The settings an IMAP connection of an account was opened with. An idle connection is only reused by a caller whose
 * account has the same settings, so a connection opened with an account loaded before an update is never kept.
 */
internal data class ImapConnectionSettings(
	val host: String,
	val port: Int,
	val security: MailSecurity,
	val username: String,
	val password: EncryptedSecret?,
	val oauthConnectionId: UUID?,
) {
	companion object {
		fun of(account: MailAccount) =
			ImapConnectionSettings(
				account.imapHost,
				account.imapPort,
				account.imapSecurity,
				account.username,
				account.password,
				account.oauthConnectionId,
			)
	}
}

/**
 * IMAP connections per account (SPEC.md §7.2): at most [MailImapProperties.Pool.maxConnectionsPerAccount] open at a
 * time, idle ones reused for [MailImapProperties.Pool.idleTimeout] and closed after it (checked on borrow and once a
 * minute by [evictExpired]). A caller that finds every connection busy waits up
 * to [MailImapProperties.Pool.borrowTimeout] and then fails with [MailConnectionError.POOL_EXHAUSTED].
 *
 * ```
 * val folders = pool.withStore(account) { store -> store.defaultFolder.list("*") }
 * ```
 *
 * Changing or deleting an account ([MailAccountConnectionChanged], after commit) and disconnecting its OAuth
 * connection close its idle connections; connections in use are closed when they are returned (until then they count
 * neither against the old nor against the new limit of that account). An idle connection opened with other settings
 * than those of the borrowing [MailAccount] (e.g. by a caller that loaded the account before an update) is closed
 * instead of reused.
 */
@Component
class ImapConnectionPool(
	private val sessions: MailSessions,
	private val properties: MailImapProperties,
	private val accounts: MailAccountRepository,
	private val clock: Clock,
) : DisposableBean {
	private val log = LoggerFactory.getLogger(javaClass)
	private val pools = ConcurrentHashMap<UUID, AccountPool>()

	/** Runs [block] with a connected store of [account]; the store goes back to the pool afterwards. */
	fun <T> withStore(
		account: MailAccount,
		block: (IMAPStore) -> T,
	): T {
		val pool = pools.computeIfAbsent(account.id) { AccountPool(properties.pool.maxConnectionsPerAccount) }
		val settings = ImapConnectionSettings.of(account)
		val store = pool.borrow(account, settings)
		var healthy = false
		try {
			val result = block(store)
			healthy = true
			return result
		} finally {
			pool.giveBack(store, healthy, settings)
		}
	}

	/** Closes the idle connections of an account; connections in use are closed when they come back. */
	fun evict(accountId: UUID) {
		pools.remove(accountId)?.retire()
	}

	/** Closes idle connections unused for [MailImapProperties.Pool.idleTimeout], also of accounts nobody borrows from. */
	@Scheduled(fixedDelay = EVICTION_INTERVAL_SECONDS, initialDelay = EVICTION_INTERVAL_SECONDS, timeUnit = TimeUnit.SECONDS)
	fun evictExpired() {
		val cutoff = clock.instant().minus(properties.pool.idleTimeout)
		pools.values.forEach { it.closeExpired(cutoff) }
	}

	/** Open connections (in use + idle) of an account. */
	fun openConnections(accountId: UUID): Int = pools[accountId]?.open() ?: 0

	/** Idle connections of an account. */
	fun idleConnections(accountId: UUID): Int = pools[accountId]?.idleCount() ?: 0

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun onAccountChanged(event: MailAccountConnectionChanged) = evict(event.accountId)

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun onOAuthDisconnected(event: OAuthConnectionDisconnected) {
		accounts.findAll().filter { it.oauthConnectionId == event.connectionId }.forEach { evict(it.id) }
	}

	override fun destroy() {
		pools.keys.toList().forEach(::evict)
	}

	private class Idle(
		val store: IMAPStore,
		val since: Instant,
		val settings: ImapConnectionSettings,
	)

	private inner class AccountPool(
		max: Int,
	) {
		private val permits = Semaphore(max, true)
		private val idle = ArrayDeque<Idle>()
		private var inUse = 0
		private var retired = false

		fun borrow(
			account: MailAccount,
			settings: ImapConnectionSettings,
		): IMAPStore {
			val acquired =
				try {
					permits.tryAcquire(properties.pool.borrowTimeout.toMillis(), TimeUnit.MILLISECONDS)
				} catch (e: InterruptedException) {
					Thread.currentThread().interrupt()
					throw MailConnectionException(MailConnectionError.POOL_EXHAUSTED, e)
				}
			if (!acquired) throw MailConnectionException(MailConnectionError.POOL_EXHAUSTED)
			try {
				reusable(settings)?.let { return it }
				val store = sessions.connectImap(account)
				synchronized(this) { inUse++ }
				return store
			} catch (e: RuntimeException) {
				permits.release()
				throw e
			}
		}

		/**
		 * The most recently used idle connection that is still fresh and alive; stale ones and those opened with other
		 * [settings] are closed.
		 */
		private fun reusable(settings: ImapConnectionSettings): IMAPStore? {
			val outdated =
				synchronized(this) {
					idle.filter { it.settings != settings }.also { idle.removeAll { it.settings != settings } }
				}
			outdated.forEach { close(it.store) }
			val cutoff = clock.instant().minus(properties.pool.idleTimeout)
			while (true) {
				val candidate =
					synchronized(this) {
						val next = idle.removeLastOrNull() ?: return null
						inUse++
						next
					}
				// isConnected sends a NOOP, so a connection the server dropped is detected here.
				if (candidate.since.isAfter(cutoff) && candidate.store.isConnected) return candidate.store
				close(candidate.store)
				synchronized(this) { inUse-- }
			}
		}

		fun giveBack(
			store: IMAPStore,
			healthy: Boolean,
			settings: ImapConnectionSettings,
		) {
			try {
				val alive = healthy && store.isConnected
				val keep =
					synchronized(this) {
						inUse--
						val keep = alive && !retired
						if (keep) idle.addLast(Idle(store, clock.instant(), settings))
						keep
					}
				if (!keep) close(store)
			} finally {
				permits.release()
			}
		}

		fun closeExpired(cutoff: Instant) {
			val expired =
				synchronized(this) {
					idle.filter { !it.since.isAfter(cutoff) }.also { idle.removeAll { !it.since.isAfter(cutoff) } }
				}
			expired.forEach { close(it.store) }
		}

		fun retire() {
			val toClose =
				synchronized(this) {
					retired = true
					idle.map { it.store }.also { idle.clear() }
				}
			toClose.forEach(::close)
		}

		@Synchronized fun open(): Int = inUse + idle.size

		@Synchronized fun idleCount(): Int = idle.size
	}

	private companion object {
		const val EVICTION_INTERVAL_SECONDS = 60L
	}

	private fun close(store: IMAPStore) {
		try {
			store.close()
		} catch (e: Exception) {
			log.debug("Closing an IMAP connection failed", e)
		}
	}
}
