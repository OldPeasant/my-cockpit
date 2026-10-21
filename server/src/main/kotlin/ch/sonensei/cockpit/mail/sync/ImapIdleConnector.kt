package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.imap.MailImapProperties
import ch.sonensei.cockpit.mail.imap.MailSessions
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailSecurity
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessagingException
import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** The server does not offer IDLE (RFC 2177); the folder has to be polled. */
class IdleNotSupportedException : RuntimeException("The server does not support IDLE")

/** A connection that has one folder open for IDLE. */
interface IdleSession : AutoCloseable {
	/**
	 * Waits in IDLE until the server reports something about the folder (new, expunged or changed messages) or [refresh]
	 * ends the IDLE, then returns. Throws when the connection fails or is closed.
	 */
	fun await()

	/** Ends a running [await] with a NOOP (the next [await] starts a new IDLE); keeps the connection alive. */
	fun refresh()

	/** Closes the connection; a running [await] fails. Never throws. */
	override fun close()
}

/** Opens [IdleSession]s. */
fun interface IdleConnector {
	/**
	 * Connects to [account]'s server and opens folder [path] read-only.
	 *
	 * @throws IdleNotSupportedException when the server has no IDLE
	 * @throws RemoteFolderNotFoundException when the folder does not exist
	 * @throws MailConnectionException when the server cannot be reached or rejects the login
	 */
	fun open(
		account: MailAccount,
		path: String,
	): IdleSession
}

/**
 * [IdleConnector] over IMAP (Angus Mail). Each session has its own connection outside the [ImapConnectionPool]
 * (ch.sonensei.cockpit.mail.imap.ImapConnectionPool), because an IDLE holds its connection for good. Its read timeout
 * is [MailImapProperties.readTimeout] longer than [MailPushProperties.reIdleInterval], so a quiet IDLE is ended by
 * [IdleSession.refresh] before the socket times out. A connection that died silently is only noticed by the next
 * refresh (or the read timeout); until then the poll of [MailPushService] covers the Inbox.
 */
@Component
class ImapIdleConnector(
	private val sessions: MailSessions,
	private val imap: MailImapProperties,
	private val push: MailPushProperties,
) : IdleConnector {
	override fun open(
		account: MailAccount,
		path: String,
	): IdleSession {
		val credentials = sessions.credentials(account)
		val protocol = if (account.imapSecurity == MailSecurity.SSL_TLS) "imaps" else "imap"
		val properties =
			sessions.imapProperties(account, credentials.xoauth2).apply {
				put(
					"mail.$protocol.timeout",
					push.reIdleInterval
						.plus(imap.readTimeout)
						.toMillis()
						.toString(),
				)
			}
		val store = Session.getInstance(properties).getStore(protocol) as IMAPStore
		try {
			store.connect(account.imapHost, account.imapPort, credentials.username, credentials.secret)
			if (!store.hasCapability("IDLE")) throw IdleNotSupportedException()
			val folder = store.getFolder(path) as IMAPFolder
			folder.open(Folder.READ_ONLY)
			return ImapIdleSession(store, folder)
		} catch (e: FolderNotFoundException) {
			close(store)
			throw RemoteFolderNotFoundException(path, e)
		} catch (e: MessagingException) {
			close(store)
			throw MailConnectionException.of(e)
		} catch (e: RuntimeException) {
			close(store)
			throw e
		}
	}

	private class ImapIdleSession(
		private val store: IMAPStore,
		private val folder: IMAPFolder,
	) : IdleSession {
		override fun await() {
			try {
				folder.idle(true)
			} catch (e: MessagingException) {
				throw MailConnectionException.of(e)
			}
		}

		override fun refresh() {
			// Any command on the folder ends a running IDLE (Angus sends DONE first).
			folder.doCommand { protocol ->
				protocol.simpleCommand("NOOP", null)
				null
			}
		}

		override fun close() = close(store)
	}

	private companion object {
		private val log = LoggerFactory.getLogger(ImapIdleConnector::class.java)

		fun close(store: IMAPStore) {
			try {
				store.close()
			} catch (e: Exception) {
				log.debug("Closing an IMAP IDLE connection failed", e)
			}
		}
	}
}
