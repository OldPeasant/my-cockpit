package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.oauth.domain.OAuthAccessToken
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionNotFoundException
import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import javax.net.ssl.SSLHandshakeException

/** Session properties per security mode, credentials and failure classification, without a mail server. */
class MailSessionsTests {
	private val tokens = mockk<OAuthTokenProvider>()

	private fun properties(sslTrust: String = "") =
		MailImapProperties(
			connectTimeout = Duration.ofSeconds(15),
			readTimeout = Duration.ofSeconds(60),
			sslTrust = sslTrust,
			pool = MailImapProperties.Pool(3, Duration.ofSeconds(30), Duration.ofMinutes(5)),
		)

	private fun sessions(sslTrust: String = "") = MailSessions(properties(sslTrust), tokens)

	private fun account(
		imapSecurity: MailSecurity = MailSecurity.SSL_TLS,
		smtpSecurity: MailSecurity = MailSecurity.STARTTLS,
		type: MailAccountType = MailAccountType.IMAP_SMTP,
	) = MailAccount(
		type = type,
		displayName = "Work",
		emailAddress = "jane.doe@example.test",
		imapHost = "imap.example.test",
		imapPort = 993,
		imapSecurity = imapSecurity,
		smtpHost = "smtp.example.test",
		smtpPort = 587,
		smtpSecurity = smtpSecurity,
		username = "jane",
	)

	private fun Properties.keysStartingWith(prefix: String) = stringPropertyNames().filter { it.startsWith(prefix) }.toSet()

	@Nested
	inner class ImapProperties {
		@Test
		fun `implicit TLS uses imaps with ssl enabled and the host name checked`() {
			val props = sessions().imapProperties(account(imapSecurity = MailSecurity.SSL_TLS), xoauth2 = false)

			props.getProperty("mail.imaps.host") shouldBe "imap.example.test"
			props.getProperty("mail.imaps.port") shouldBe "993"
			props.getProperty("mail.imaps.ssl.enable") shouldBe "true"
			props.getProperty("mail.imaps.ssl.checkserveridentity") shouldBe "true"
			props.getProperty("mail.imaps.connectiontimeout") shouldBe "15000"
			props.getProperty("mail.imaps.timeout") shouldBe "60000"
			props.getProperty("mail.imaps.writetimeout") shouldBe "60000"
			props shouldNotContainKey "mail.imaps.starttls.enable"
			props shouldNotContainKey "mail.imaps.auth.mechanisms"
			props shouldNotContainKey "mail.imaps.ssl.trust"
			props.keysStartingWith("mail.imap.") shouldBe emptySet()
		}

		@Test
		fun `STARTTLS is enabled and required on plain imap, with the host name checked`() {
			val props = sessions().imapProperties(account(imapSecurity = MailSecurity.STARTTLS), xoauth2 = false)

			props.getProperty("mail.imap.starttls.enable") shouldBe "true"
			props.getProperty("mail.imap.starttls.required") shouldBe "true"
			props.getProperty("mail.imap.ssl.checkserveridentity") shouldBe "true"
			props shouldNotContainKey "mail.imap.ssl.enable"
			props.keysStartingWith("mail.imaps.") shouldBe emptySet()
		}

		@Test
		fun `NONE sets neither TLS nor STARTTLS nor a trust list`() {
			val props = sessions(sslTrust = "localhost").imapProperties(account(imapSecurity = MailSecurity.NONE), xoauth2 = false)

			props.getProperty("mail.imap.host") shouldBe "imap.example.test"
			props.keysStartingWith("mail.imap.ssl") shouldBe emptySet()
			props.keysStartingWith("mail.imap.starttls") shouldBe emptySet()
		}

		@Test
		fun `XOAUTH2 restricts the login mechanisms`() {
			sessions().imapProperties(account(), xoauth2 = true).getProperty("mail.imaps.auth.mechanisms") shouldBe "XOAUTH2"
			sessions()
				.imapProperties(account(imapSecurity = MailSecurity.STARTTLS), xoauth2 = true)
				.getProperty("mail.imap.auth.mechanisms") shouldBe "XOAUTH2"
		}

		@Test
		fun `a configured ssl trust list is passed for TLS and STARTTLS only`() {
			sessions(sslTrust = "localhost")
				.imapProperties(account(imapSecurity = MailSecurity.SSL_TLS), xoauth2 = false)
				.getProperty("mail.imaps.ssl.trust") shouldBe "localhost"
			sessions(sslTrust = "localhost")
				.imapProperties(account(imapSecurity = MailSecurity.STARTTLS), xoauth2 = false)
				.getProperty("mail.imap.ssl.trust") shouldBe "localhost"
		}

		@Test
		fun `a blank ssl trust list is not passed`() {
			sessions(sslTrust = "  ").imapProperties(account(), xoauth2 = false) shouldNotContainKey "mail.imaps.ssl.trust"
		}
	}

	@Nested
	inner class SmtpProperties {
		@Test
		fun `STARTTLS submission requires STARTTLS and always authenticates`() {
			val props = sessions().smtpProperties(account(smtpSecurity = MailSecurity.STARTTLS), xoauth2 = false)

			props.getProperty("mail.smtp.host") shouldBe "smtp.example.test"
			props.getProperty("mail.smtp.port") shouldBe "587"
			props.getProperty("mail.smtp.auth") shouldBe "true"
			props.getProperty("mail.smtp.starttls.enable") shouldBe "true"
			props.getProperty("mail.smtp.starttls.required") shouldBe "true"
			props.getProperty("mail.smtp.ssl.checkserveridentity") shouldBe "true"
			props shouldNotContainKey "mail.smtp.ssl.enable"
			props shouldNotContainKey "mail.smtp.auth.mechanisms"
		}

		@Test
		fun `implicit TLS uses smtps`() {
			val props = sessions().smtpProperties(account(smtpSecurity = MailSecurity.SSL_TLS), xoauth2 = true)

			props.getProperty("mail.smtps.ssl.enable") shouldBe "true"
			props.getProperty("mail.smtps.auth") shouldBe "true"
			props.getProperty("mail.smtps.auth.mechanisms") shouldBe "XOAUTH2"
			props.getProperty("mail.smtps.ssl.checkserveridentity") shouldBe "true"
			props.keysStartingWith("mail.smtp.") shouldBe emptySet()
		}

		@Test
		fun `plain SMTP still authenticates but sets no TLS options`() {
			val props = sessions(sslTrust = "localhost").smtpProperties(account(smtpSecurity = MailSecurity.NONE), xoauth2 = false)

			props.getProperty("mail.smtp.auth") shouldBe "true"
			props.keysStartingWith("mail.smtp.ssl") shouldBe emptySet()
			props.keysStartingWith("mail.smtp.starttls") shouldBe emptySet()
		}
	}

	@Nested
	inner class Credentials {
		@Test
		fun `a password account logs in with its stored password`() {
			val account = account().apply { password = EncryptedSecret("fictitious-secret-1") }

			val credentials = sessions().credentials(account)

			credentials.username shouldBe "jane"
			credentials.secret shouldBe "fictitious-secret-1"
			credentials.xoauth2 shouldBe false
		}

		@Test
		fun `a password account without password fails with AUTHENTICATION_FAILED`() {
			shouldThrow<MailConnectionException> { sessions().credentials(account()) }.error shouldBe
				MailConnectionError.AUTHENTICATION_FAILED
		}

		@Test
		fun `an OAuth account logs in with XOAUTH2 and a mail access token of its connection`() {
			val connection = UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b6c")
			every { tokens.accessToken(connection, OAuthResource.MAIL) } returns
				OAuthAccessToken("fictitious-access-token", Instant.parse("2026-10-20T12:00:00Z"))
			val account = account(type = MailAccountType.GOOGLE).apply { oauthConnectionId = connection }

			val credentials = sessions().credentials(account)

			credentials.secret shouldBe "fictitious-access-token"
			credentials.xoauth2 shouldBe true
			credentials.username shouldBe "jane"
			verify(exactly = 1) { tokens.accessToken(connection, OAuthResource.MAIL) }
		}

		@Test
		fun `an OAuth account without connection needs a reconnect`() {
			shouldThrow<MailConnectionException> {
				sessions().credentials(account(type = MailAccountType.MICROSOFT))
			}.error shouldBe MailConnectionError.OAUTH_RECONNECT_REQUIRED
		}

		@Test
		fun `token provider failures are classified`() {
			val connection = UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b6d")
			val account = account(type = MailAccountType.GOOGLE).apply { oauthConnectionId = connection }

			every { tokens.accessToken(connection, OAuthResource.MAIL) } throws OAuthProviderUnavailableException("fictitious outage")
			shouldThrow<MailConnectionException> { sessions().credentials(account) }.let {
				it.error shouldBe MailConnectionError.OAUTH_UNAVAILABLE
				(it.cause is OAuthProviderUnavailableException) shouldBe true
			}

			every { tokens.accessToken(connection, OAuthResource.MAIL) } throws OAuthConnectionNotFoundException()
			shouldThrow<MailConnectionException> { sessions().credentials(account) }.error shouldBe
				MailConnectionError.OAUTH_RECONNECT_REQUIRED
		}

		@Test
		fun `toString never shows the secret`() {
			val credentials = MailCredentials("jane", "fictitious-secret-1", xoauth2 = false)

			credentials.toString() shouldNotContain "fictitious-secret-1"
			credentials.toString() shouldBe "MailCredentials(username=jane, xoauth2=false, ******)"
		}
	}

	@Nested
	inner class Classify {
		private fun classify(e: Throwable) = MailConnectionException.classify(e)

		@Test
		fun `a rejected login is AUTHENTICATION_FAILED`() {
			classify(AuthenticationFailedException("LOGIN failed")) shouldBe MailConnectionError.AUTHENTICATION_FAILED
		}

		@Test
		fun `OAuth failures need a reconnect, an unreachable provider is OAUTH_UNAVAILABLE`() {
			classify(OAuthReconnectRequiredException()) shouldBe MailConnectionError.OAUTH_RECONNECT_REQUIRED
			classify(OAuthConnectionNotFoundException()) shouldBe MailConnectionError.OAUTH_RECONNECT_REQUIRED
			classify(OAuthGrantRejectedException("invalid_client")) shouldBe MailConnectionError.OAUTH_RECONNECT_REQUIRED
			classify(OAuthProviderUnavailableException("timeout")) shouldBe MailConnectionError.OAUTH_UNAVAILABLE
		}

		@Test
		fun `TLS and certificate problems anywhere in the cause chain are TLS_FAILED`() {
			classify(MessagingException("connect failed", SSLHandshakeException("handshake"))) shouldBe MailConnectionError.TLS_FAILED
			classify(
				MessagingException("connect failed", RuntimeException("wrapped", CertificateException("expired"))),
			) shouldBe MailConnectionError.TLS_FAILED
		}

		@Test
		fun `a socket timeout is TIMEOUT`() {
			classify(MessagingException("read failed", SocketTimeoutException("Read timed out"))) shouldBe MailConnectionError.TIMEOUT
		}

		@Test
		fun `unknown hosts, refused connections and missing routes are CONNECTION_FAILED`() {
			classify(MessagingException("x", UnknownHostException("imap.invalid"))) shouldBe MailConnectionError.CONNECTION_FAILED
			classify(MessagingException("x", ConnectException("Connection refused"))) shouldBe MailConnectionError.CONNECTION_FAILED
			classify(MessagingException("x", NoRouteToHostException("No route"))) shouldBe MailConnectionError.CONNECTION_FAILED
		}

		@Test
		fun `a server without the required STARTTLS is TLS_FAILED`() {
			classify(MessagingException("STARTTLS is required but host does not support STARTTLS")) shouldBe
				MailConnectionError.TLS_FAILED
			classify(MessagingException("starttls required")) shouldBe MailConnectionError.TLS_FAILED
		}

		@Test
		fun `anything else is CONNECTION_FAILED`() {
			classify(MessagingException("BAD command")) shouldBe MailConnectionError.CONNECTION_FAILED
			classify(MessagingException(null)) shouldBe MailConnectionError.CONNECTION_FAILED
			classify(IllegalStateException("STARTTLS")) shouldBe MailConnectionError.CONNECTION_FAILED
		}

		@Test
		fun `a cyclic cause chain terminates`() {
			val first = RuntimeException("first")
			val second = RuntimeException("second", first)
			first.initCause(second)

			classify(first) shouldBe MailConnectionError.CONNECTION_FAILED
		}

		@Test
		fun `of keeps an existing MailConnectionException and wraps everything else with its cause`() {
			val existing = MailConnectionException(MailConnectionError.POOL_EXHAUSTED)
			(MailConnectionException.of(existing) === existing) shouldBe true

			val cause = MessagingException("x", SocketTimeoutException("Read timed out"))
			MailConnectionException.of(cause).let {
				it.error shouldBe MailConnectionError.TIMEOUT
				(it.cause === cause) shouldBe true
				it.message shouldBe MailConnectionError.TIMEOUT.message
			}
		}

		@Test
		fun `error messages are fixed texts without server details`() {
			MailConnectionError.entries.forEach { error ->
				MailConnectionException(error, MessagingException("NO [AUTHENTICATIONFAILED] imap.example.test jane")).message shouldBe
					error.message
			}
		}
	}
}
