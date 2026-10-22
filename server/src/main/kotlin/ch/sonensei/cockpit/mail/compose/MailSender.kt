package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.imap.MailSessions
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailSecurity
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.springframework.stereotype.Component

/**
 * The SMTP server refused the message or some of its recipients. [rejected] are the refused recipient addresses
 * (from the message, safe to show); empty when the server refused the message as a whole.
 */
class MailSendRejectedException(
	val rejected: List<String>,
	cause: Throwable,
) : RuntimeException("The mail server refused the message", cause)

/**
 * Sends a message over the account's SMTP server (SPEC.md §7.1), with the same security and login as
 * [MailSessions.checkSmtp]: password, or XOAUTH2 with the OAuth connection's access token for Google and Microsoft.
 */
@Component
class MailSender(
	private val sessions: MailSessions,
) {
	/**
	 * Sends [message] to its recipients (`Bcc` included; the header itself is not transmitted).
	 *
	 * @throws MailSendRejectedException when the server refused the message or a recipient
	 * @throws MailConnectionException when the server could not be reached, the login failed, ...
	 */
	fun send(
		account: MailAccount,
		message: MimeMessage,
	) {
		val credentials = sessions.credentials(account)
		val protocol = if (account.smtpSecurity == MailSecurity.SSL_TLS) "smtps" else "smtp"
		val session = Session.getInstance(sessions.smtpProperties(account, credentials.xoauth2))
		val transport = session.getTransport(protocol)
		try {
			transport.connect(account.smtpHost, account.smtpPort, credentials.username, credentials.secret)
			transport.sendMessage(message, message.allRecipients)
		} catch (e: SendFailedException) {
			val rejected = e.invalidAddresses.orEmpty().mapNotNull { (it as? InternetAddress)?.address }
			throw MailSendRejectedException(rejected.distinct(), e)
		} catch (e: MessagingException) {
			throw MailConnectionException.of(e)
		} finally {
			runCatching { transport.close() }
		}
	}
}
