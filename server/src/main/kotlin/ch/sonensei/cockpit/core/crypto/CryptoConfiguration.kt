package ch.sonensei.cockpit.core.crypto

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Creates the [SecretCipher] from `cockpit.master-key` (`COCKPIT_MASTER_KEY`). The bean is eager, so the
 * application refuses to start without a valid key. Tests set a fictitious key in `src/test/resources/config`.
 */
@Configuration(proxyBeanMethods = false)
class CryptoConfiguration {
	@Bean
	fun secretCipher(
		@Value("\${cockpit.master-key:}") masterKey: String,
	): SecretCipher = SecretCipher(MasterKey.decode(masterKey))
}

/** Turns a missing/invalid master key into a short startup error instead of a bean-creation stack trace. */
class InvalidMasterKeyFailureAnalyzer : AbstractFailureAnalyzer<InvalidMasterKeyException>() {
	override fun analyze(
		rootFailure: Throwable,
		cause: InvalidMasterKeyException,
	): FailureAnalysis =
		FailureAnalysis(
			"The master key for encrypting stored credentials is unusable: ${cause.message}",
			"Provide $MASTER_KEY_ENV in the server environment (keep it secret and backed up: without it, " +
				"stored credentials cannot be decrypted).",
			cause,
		)
}
