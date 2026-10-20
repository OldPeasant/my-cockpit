package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionNotFoundException
import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailSecurity
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.Transport
import org.eclipse.angus.mail.imap.IMAPStore
import org.springframework.stereotype.Component
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Properties
import javax.net.ssl.SSLException

/** Why talking to a mail server failed. The API returns it as `errorCode`; clients show a matching hint. */
enum class MailConnectionError(
	/** A fixed message, safe to show: it never contains server responses, hosts or credentials. */
	val message: String,
) {
	AUTHENTICATION_FAILED("The server rejected the username or password"),
	TLS_FAILED("The secure connection failed (certificate, TLS version or STARTTLS not offered)"),
	TIMEOUT("The server did not answer in time"),
	CONNECTION_FAILED("The server could not be reached"),
	OAUTH_RECONNECT_REQUIRED("The OAuth connection has to be connected again"),
	OAUTH_UNAVAILABLE("The OAuth provider could not be reached"),
	POOL_EXHAUSTED("All connections of this account are busy"),
}

/** A failed IMAP/SMTP operation. [message] is [MailConnectionError.message]; the original exception is the [cause]. */
class MailConnectionException(
	val error: MailConnectionError,
	cause: Throwable? = null,
) : RuntimeException(error.message, cause) {
	companion object {
		/** Classifies an Angus Mail or OAuth failure. */
		fun of(e: Throwable): MailConnectionException = e as? MailConnectionException ?: MailConnectionException(classify(e), e)

		fun classify(e: Throwable): MailConnectionError {
			when (e) {
				is AuthenticationFailedException -> return MailConnectionError.AUTHENTICATION_FAILED
				is OAuthReconnectRequiredException, is OAuthConnectionNotFoundException, is OAuthGrantRejectedException ->
					return MailConnectionError.OAUTH_RECONNECT_REQUIRED
				is OAuthProviderUnavailableException -> return MailConnectionError.OAUTH_UNAVAILABLE
			}
			for (cause in generateSequence(e) { it.cause }.take(MAX_CAUSES)) {
				when (cause) {
					is SSLException, is CertificateException -> return MailConnectionError.TLS_FAILED
					is SocketTimeoutException -> return MailConnectionError.TIMEOUT
					is UnknownHostException, is ConnectException, is NoRouteToHostException ->
						return MailConnectionError.CONNECTION_FAILED
				}
			}
			// Angus reports a server without the required STARTTLS as a plain MessagingException.
			if (e is MessagingException && e.message.orEmpty().contains("STARTTLS", ignoreCase = true)) {
				return MailConnectionError.TLS_FAILED
			}
			return MailConnectionError.CONNECTION_FAILED
		}

		private const val MAX_CAUSES = 10
	}
}

/** Login of one connection: a password, or for XOAUTH2 an access token. `toString` never shows [secret]. */
class MailCredentials(
	val username: String,
	val secret: String,
	val xoauth2: Boolean,
) {
	override fun toString(): String = "MailCredentials(username=$username, xoauth2=$xoauth2, ******)"
}

/**
 * Builds Angus Mail sessions for an account (SPEC.md §7.1) and opens IMAP stores and SMTP transports with them:
 * implicit TLS ([MailSecurity.SSL_TLS]), STARTTLS (required, never silently plain) or plain ([MailSecurity.NONE]).
 * Certificates and host names are always checked. Google and Microsoft accounts log in with XOAUTH2 using an access
 * token of their OAuth connection ([OAuthResource.MAIL]).
 */
@Component
class MailSessions(
	private val properties: MailImapProperties,
	private val tokens: OAuthTokenProvider,
) {
	/** The credentials for [account]; fetches (and if needed refreshes) the OAuth access token. */
	fun credentials(account: MailAccount): MailCredentials =
		try {
			if (account.usesOAuth) {
				val connectionId =
					account.oauthConnectionId ?: throw MailConnectionException(MailConnectionError.OAUTH_RECONNECT_REQUIRED)
				MailCredentials(account.username, tokens.accessToken(connectionId, OAuthResource.MAIL).value, xoauth2 = true)
			} else {
				val password = account.password ?: throw MailConnectionException(MailConnectionError.AUTHENTICATION_FAILED)
				MailCredentials(account.username, password.reveal(), xoauth2 = false)
			}
		} catch (e: RuntimeException) {
			throw MailConnectionException.of(e)
		}

	/** A connected IMAP store. The caller closes it. */
	fun connectImap(account: MailAccount): IMAPStore {
		val credentials = credentials(account)
		val protocol = if (account.imapSecurity == MailSecurity.SSL_TLS) "imaps" else "imap"
		val session = Session.getInstance(imapProperties(account, credentials.xoauth2))
		val store = session.getStore(protocol) as IMAPStore
		try {
			store.connect(account.imapHost, account.imapPort, credentials.username, credentials.secret)
		} catch (e: MessagingException) {
			throw MailConnectionException.of(e)
		}
		return store
	}

	/** Opens and closes an SMTP connection, including the login. */
	fun checkSmtp(account: MailAccount) {
		val credentials = credentials(account)
		val protocol = if (account.smtpSecurity == MailSecurity.SSL_TLS) "smtps" else "smtp"
		val session = Session.getInstance(smtpProperties(account, credentials.xoauth2))
		val transport: Transport = session.getTransport(protocol)
		try {
			transport.connect(account.smtpHost, account.smtpPort, credentials.username, credentials.secret)
		} catch (e: MessagingException) {
			throw MailConnectionException.of(e)
		} finally {
			runCatching { transport.close() }
		}
	}

	internal fun imapProperties(
		account: MailAccount,
		xoauth2: Boolean,
	): Properties {
		val protocol = if (account.imapSecurity == MailSecurity.SSL_TLS) "imaps" else "imap"
		return common(protocol, account.imapHost, account.imapPort, account.imapSecurity).apply {
			if (xoauth2) {
				put("mail.$protocol.auth.mechanisms", "XOAUTH2")
			}
		}
	}

	internal fun smtpProperties(
		account: MailAccount,
		xoauth2: Boolean,
	): Properties {
		val protocol = if (account.smtpSecurity == MailSecurity.SSL_TLS) "smtps" else "smtp"
		return common(protocol, account.smtpHost, account.smtpPort, account.smtpSecurity).apply {
			put("mail.$protocol.auth", "true")
			if (xoauth2) {
				put("mail.$protocol.auth.mechanisms", "XOAUTH2")
			}
		}
	}

	private fun common(
		protocol: String,
		host: String,
		port: Int,
		security: MailSecurity,
	) = Properties().apply {
		put("mail.$protocol.host", host)
		put("mail.$protocol.port", port.toString())
		put("mail.$protocol.connectiontimeout", properties.connectTimeout.toMillis().toString())
		put("mail.$protocol.timeout", properties.readTimeout.toMillis().toString())
		put("mail.$protocol.writetimeout", properties.readTimeout.toMillis().toString())
		when (security) {
			MailSecurity.SSL_TLS -> {
				put("mail.$protocol.ssl.enable", "true")
			}

			MailSecurity.STARTTLS -> {
				put("mail.$protocol.starttls.enable", "true")
				put("mail.$protocol.starttls.required", "true")
			}

			MailSecurity.NONE -> {}
		}
		if (security != MailSecurity.NONE) {
			put("mail.$protocol.ssl.checkserveridentity", "true")
			if (properties.sslTrust.isNotBlank()) put("mail.$protocol.ssl.trust", properties.sslTrust)
		}
	}
}
