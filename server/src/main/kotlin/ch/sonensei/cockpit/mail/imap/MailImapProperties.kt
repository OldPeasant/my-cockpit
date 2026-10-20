package ch.sonensei.cockpit.mail.imap

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * IMAP/SMTP connection settings (`cockpit.mail.*`). The defaults suit production; nothing has to be configured.
 *
 * [sslTrust] is Angus Mail's `mail.<protocol>.ssl.trust`: a space-separated list of hosts whose certificate is
 * trusted without validation. Empty (the default) validates every certificate against the JVM trust store; set it
 * only for test servers with self-signed certificates.
 */
@ConfigurationProperties("cockpit.mail")
class MailImapProperties(
	/** TCP connect timeout of IMAP and SMTP connections. */
	@DefaultValue("15s") val connectTimeout: Duration,
	/** Read/write timeout of IMAP and SMTP connections. */
	@DefaultValue("60s") val readTimeout: Duration,
	@DefaultValue("") val sslTrust: String,
	@DefaultValue val pool: Pool,
) {
	/** Limits of the per-account IMAP connection pool ([ImapConnectionPool]). */
	class Pool(
		/** Open IMAP connections per account (in use and idle). Providers limit them (Gmail: 15). */
		@DefaultValue("3") val maxConnectionsPerAccount: Int,
		/** How long a caller waits for a free connection before failing. */
		@DefaultValue("30s") val borrowTimeout: Duration,
		/** Idle connections older than this are closed instead of reused. */
		@DefaultValue("5m") val idleTimeout: Duration,
	) {
		init {
			require(maxConnectionsPerAccount > 0) { "cockpit.mail.pool.max-connections-per-account must be positive" }
		}
	}
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailImapProperties::class)
class MailImapConfiguration
