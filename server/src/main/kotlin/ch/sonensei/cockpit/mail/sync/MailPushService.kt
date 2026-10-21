package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.mail.imap.MailAccountConnectionChanged
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailPushProperties::class)
class MailPushConfiguration

/**
 * Near-real-time new mail (SPEC.md §7.2): an [InboxWatcher] (IMAP IDLE) per account on its Inbox, and every
 * [MailPushProperties.pollInterval] a [poll] of the folder list and of every folder. The poll includes the Inbox, so
 * new mail still arrives within the poll interval when the IDLE connection died silently (a dropped connection is
 * only noticed by the next re-IDLE) or the server has no IDLE; with a working IDLE it finds nothing new. The poll
 * runs on its own thread, so slow mail servers never delay other scheduled jobs. What the syncs find is announced by
 * [MailHeaderSyncService] (change events, new-mail notification).
 *
 * Watchers start with the application and follow the accounts: created, changed connection settings (restart) and
 * deleted. Runs unless `cockpit.mail.push.enabled=false` (the test configuration turns it off).
 */
@Service
@ConditionalOnProperty(name = ["cockpit.mail.push.enabled"], havingValue = "true", matchIfMissing = true)
class MailPushService(
	private val accounts: MailAccountRepository,
	private val folders: MailFolderRepository,
	private val folderService: MailFolderService,
	private val sync: MailHeaderSyncService,
	private val connector: IdleConnector,
	private val properties: MailPushProperties,
) : SmartLifecycle {
	private val log = LoggerFactory.getLogger(javaClass)
	private val watchers = ConcurrentHashMap<UUID, InboxWatcher>()
	private val scheduler: ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor(
			Thread
				.ofPlatform()
				.daemon()
				.name("mail-re-idle")
				.factory(),
		)
	private val poller: ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor(
			Thread
				.ofPlatform()
				.daemon()
				.name("mail-poll")
				.factory(),
		)

	@Volatile private var running = false

	override fun start() {
		running = true
		reconcile()
		val interval = properties.pollInterval.toMillis()
		poller.scheduleWithFixedDelay(::poll, interval, interval, TimeUnit.MILLISECONDS)
	}

	override fun stop() {
		running = false
		synchronized(this) { watchers.keys.toList().forEach { watchers.remove(it)?.stop() } }
		scheduler.shutdownNow()
		poller.shutdownNow()
	}

	override fun isRunning() = running

	/** The watcher of an account, if one runs. */
	fun watcher(accountId: UUID): InboxWatcher? = watchers[accountId]

	/** Syncs the folder list and every folder of every account. A failing account is logged and skipped. */
	fun poll() {
		if (!running) return
		try {
			pollAccounts()
		} catch (e: Exception) {
			// The database or a bug; an exception must not end the scheduled poll. Retried on the next one.
			log.warn("Polling mail failed ({})", e.javaClass.name)
			log.debug("Poll failure", e)
		}
	}

	private fun pollAccounts() {
		reconcile()
		accounts.findAll().forEach { account ->
			try {
				folderService.sync(account.id)
				folders
					.findByAccountIdOrderByPathAsc(account.id)
					.filter { it.selectable }
					.forEach { folder ->
						try {
							sync.syncFolder(folder.id)
						} catch (e: RemoteFolderNotFoundException) {
							log.debug("Mail folder {} is gone from the server", folder.id, e)
						}
					}
			} catch (e: Exception) {
				log.warn("Polling mail account {} failed ({})", account.id, e.javaClass.name)
				log.debug("Poll failure", e)
			}
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun onAccountChanged(event: MailAccountConnectionChanged) = restart(event.accountId)

	/** A new account gets its watcher right away (creating one publishes only its change event). */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun onChange(event: ChangeEvent) {
		if (event.type == MailAccountService.ACCOUNT_EVENT && event.op == ChangeOp.CREATED) restart(event.id)
	}

	/** Starts watchers for accounts without one (or whose watcher ended) and stops those of deleted accounts. */
	@Synchronized
	fun reconcile() {
		if (!running) return
		val ids = accounts.findAll().map { it.id }.toSet()
		watchers.keys.filter { it !in ids }.forEach { watchers.remove(it)?.stop() }
		ids.forEach { id ->
			val current = watchers[id]
			if (current == null || (!current.alive && !current.unsupported)) start(id)
		}
	}

	@Synchronized
	private fun restart(accountId: UUID) {
		if (!running) return
		watchers.remove(accountId)?.stop()
		if (accounts.existsById(accountId)) start(accountId)
	}

	private fun start(accountId: UUID) {
		val watcher =
			InboxWatcher(
				accountId,
				connect = { connector.open(account(accountId), inbox(accountId).path) },
				sync = { sync.syncFolder(inbox(accountId).id) },
				properties = properties,
				scheduler = scheduler,
			)
		watchers[accountId] = watcher
		watcher.start()
	}

	private fun account(accountId: UUID) =
		accounts.findById(accountId).orElse(null) ?: throw IllegalStateException("Mail account $accountId is gone")

	/** The account's Inbox; the folder list is synced first when it is not cached yet. */
	private fun inbox(accountId: UUID): MailFolder =
		folders.findByAccountIdAndRole(accountId, MailFolderRole.INBOX)
			?: run {
				folderService.sync(accountId)
				folders.findByAccountIdAndRole(accountId, MailFolderRole.INBOX)
			}
			?: throw IllegalStateException("Mail account $accountId has no Inbox")
}
