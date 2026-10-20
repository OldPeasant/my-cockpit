package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailSecurity
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Server endpoint of one protocol. */
data class MailServer(
	val host: String,
	val port: Int,
	val security: MailSecurity,
)

/**
 * A new account. For Google and Microsoft accounts the servers default to the provider's ([ProviderServers]) and
 * [username] to the connection's email address; password accounts need all of them.
 */
data class NewMailAccount(
	val type: MailAccountType,
	val displayName: String?,
	val emailAddress: String,
	val imap: MailServer?,
	val smtp: MailServer?,
	val username: String?,
	val password: String?,
	val oauthConnectionId: UUID?,
	val signatureMd: String?,
	val notifyNewMail: Boolean?,
	val syncWindowMonths: Int?,
)

/** Changes to an account; `null` leaves a field as it is. An empty [signatureMd] removes the signature. */
data class MailAccountChanges(
	val displayName: String? = null,
	val emailAddress: String? = null,
	val imap: MailServer? = null,
	val smtp: MailServer? = null,
	val username: String? = null,
	val password: String? = null,
	val oauthConnectionId: UUID? = null,
	val signatureMd: String? = null,
	val notifyNewMail: Boolean? = null,
	val syncWindowMonths: Int? = null,
)

/** Result of [MailAccountService.testConnection]: IMAP and SMTP are checked independently. */
data class ConnectionTestResult(
	val imap: MailConnectionError?,
	val smtp: MailConnectionError?,
)

/** The IMAP/SMTP servers of the OAuth providers (XOAUTH2). */
object ProviderServers {
	fun imap(type: MailAccountType): MailServer? =
		when (type) {
			MailAccountType.GOOGLE -> MailServer("imap.gmail.com", 993, MailSecurity.SSL_TLS)
			MailAccountType.MICROSOFT -> MailServer("outlook.office365.com", 993, MailSecurity.SSL_TLS)
			MailAccountType.IMAP_SMTP -> null
		}

	fun smtp(type: MailAccountType): MailServer? =
		when (type) {
			MailAccountType.GOOGLE -> MailServer("smtp.gmail.com", 465, MailSecurity.SSL_TLS)
			MailAccountType.MICROSOFT -> MailServer("smtp.office365.com", 587, MailSecurity.STARTTLS)
			MailAccountType.IMAP_SMTP -> null
		}

	fun provider(type: MailAccountType): OAuthProvider? =
		when (type) {
			MailAccountType.GOOGLE -> OAuthProvider.GOOGLE
			MailAccountType.MICROSOFT -> OAuthProvider.MICROSOFT
			MailAccountType.IMAP_SMTP -> null
		}
}

/**
 * Mail accounts (SPEC.md §7.1). Passwords are stored encrypted and never leave the server; Google and Microsoft
 * accounts reference an OAuth connection of the same provider. Every change publishes a `MAIL_ACCOUNT` change event,
 * and changes of servers or credentials close the account's pooled IMAP connections.
 */
@Service
class MailAccountService(
	private val accounts: MailAccountRepository,
	private val tokens: OAuthTokenProvider,
	private val sessions: MailSessions,
	private val pool: ImapConnectionPool,
	private val changes: ChangeEventPublisher,
	private val events: ApplicationEventPublisher,
) {
	@Transactional(readOnly = true)
	fun list(): List<MailAccount> = accounts.findAllByOrderByDisplayNameAscIdAsc()

	@Transactional(readOnly = true)
	fun get(id: UUID): MailAccount = accounts.findById(id).orElse(null) ?: throw NotFoundException("Mail account not found")

	@Transactional
	fun create(request: NewMailAccount): MailAccount {
		val errors = mutableListOf<FieldError>()
		val oauth = request.type != MailAccountType.IMAP_SMTP
		val connectionEmail = if (oauth) checkConnection(request.type, request.oauthConnectionId, errors) else null
		val imap = request.imap ?: ProviderServers.imap(request.type)
		val smtp = request.smtp ?: ProviderServers.smtp(request.type)
		val username = request.username?.takeIf { it.isNotBlank() } ?: if (oauth) connectionEmail ?: request.emailAddress else null
		if (imap == null) errors += FieldError("imap", "is required")
		if (smtp == null) errors += FieldError("smtp", "is required")
		if (username == null) errors += FieldError("username", "is required")
		if (oauth) {
			if (request.password != null) errors += FieldError("password", "must be empty for OAuth accounts")
		} else {
			if (request.password.isNullOrEmpty()) errors += FieldError("password", "is required")
			if (request.oauthConnectionId != null) errors += FieldError("oauthConnectionId", "must be empty for IMAP_SMTP accounts")
		}
		if (errors.isNotEmpty()) throw InvalidRequestException(errors.sortedBy { it.field })
		val account =
			MailAccount(
				type = request.type,
				displayName = request.displayName?.takeIf { it.isNotBlank() } ?: request.emailAddress,
				emailAddress = request.emailAddress,
				imapHost = imap!!.host,
				imapPort = imap.port,
				imapSecurity = imap.security,
				smtpHost = smtp!!.host,
				smtpPort = smtp.port,
				smtpSecurity = smtp.security,
				username = username!!,
			).apply {
				password = request.password?.let(::EncryptedSecret)
				oauthConnectionId = request.oauthConnectionId
				signatureMd = request.signatureMd?.takeIf { it.isNotEmpty() }
				request.notifyNewMail?.let { notifyNewMail = it }
				request.syncWindowMonths?.let { syncWindowMonths = it }
			}
		return accounts.saveAndFlush(account).also { changes.publish(ACCOUNT_EVENT, it.id, ChangeOp.CREATED) }
	}

	@Transactional
	fun update(
		id: UUID,
		request: MailAccountChanges,
		ifMatch: String?,
	): MailAccount {
		val account = get(id)
		ETags.checkIfMatch(ifMatch, account.version)
		val errors = mutableListOf<FieldError>()
		if (account.usesOAuth) {
			if (request.password != null) errors += FieldError("password", "must be empty for OAuth accounts")
			request.oauthConnectionId?.let { checkConnection(account.type, it, errors) }
		} else {
			if (request.password == "") errors += FieldError("password", "must not be empty")
			if (request.oauthConnectionId != null) errors += FieldError("oauthConnectionId", "must be empty for IMAP_SMTP accounts")
		}
		if (request.username?.isBlank() == true) errors += FieldError("username", "must not be blank")
		if (request.emailAddress?.isBlank() == true) errors += FieldError("emailAddress", "must not be blank")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors.sortedBy { it.field })

		val connectionBefore = connectionSettings(account)
		request.displayName?.takeIf { it.isNotBlank() }?.let { account.displayName = it }
		request.emailAddress?.let { account.emailAddress = it }
		request.imap?.let {
			account.imapHost = it.host
			account.imapPort = it.port
			account.imapSecurity = it.security
		}
		request.smtp?.let {
			account.smtpHost = it.host
			account.smtpPort = it.port
			account.smtpSecurity = it.security
		}
		request.username?.let { account.username = it }
		request.password?.let { account.password = EncryptedSecret(it) }
		request.oauthConnectionId?.let { account.oauthConnectionId = it }
		request.signatureMd?.let { account.signatureMd = it.ifEmpty { null } }
		request.notifyNewMail?.let { account.notifyNewMail = it }
		request.syncWindowMonths?.let { account.syncWindowMonths = it }

		val saved = accounts.saveAndFlush(account)
		changes.publish(ACCOUNT_EVENT, saved.id, ChangeOp.UPDATED)
		if (connectionSettings(saved) != connectionBefore) events.publishEvent(MailAccountConnectionChanged(saved.id))
		return saved
	}

	@Transactional
	fun delete(
		id: UUID,
		ifMatch: String?,
	) {
		val account = get(id)
		ETags.checkIfMatch(ifMatch, account.version)
		accounts.delete(account)
		accounts.flush()
		changes.publish(ACCOUNT_EVENT, id, ChangeOp.DELETED)
		events.publishEvent(MailAccountConnectionChanged(id))
	}

	/** Logs in to IMAP (through the pool, so a working connection is kept for the next sync) and to SMTP. */
	fun testConnection(id: UUID): ConnectionTestResult {
		val account = get(id)
		val imap =
			try {
				pool.withStore(account) { }
				null
			} catch (e: MailConnectionException) {
				e.error
			}
		val smtp =
			try {
				sessions.checkSmtp(account)
				null
			} catch (e: MailConnectionException) {
				e.error
			}
		return ConnectionTestResult(imap, smtp)
	}

	/** Checks that [connectionId] is a connection of [type]'s provider; returns its email address. */
	private fun checkConnection(
		type: MailAccountType,
		connectionId: UUID?,
		errors: MutableList<FieldError>,
	): String? {
		if (connectionId == null) {
			errors += FieldError("oauthConnectionId", "is required for $type accounts")
			return null
		}
		val connection = tokens.connection(connectionId)
		if (connection == null || connection.provider != ProviderServers.provider(type)) {
			errors += FieldError("oauthConnectionId", "is not a ${ProviderServers.provider(type)} connection")
			return null
		}
		return connection.email
	}

	/** What a pooled connection depends on; the password is compared by its value, never logged. */
	private fun connectionSettings(account: MailAccount) = ImapConnectionSettings.of(account)

	companion object {
		/** The `type` of account change events. */
		const val ACCOUNT_EVENT = "MAIL_ACCOUNT"
	}
}
