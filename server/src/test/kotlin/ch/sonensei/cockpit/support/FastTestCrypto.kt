package ch.sonensei.cockpit.support

import ch.sonensei.cockpit.backup.domain.BackupSecrets
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration

/**
 * Makes Argon2 cheap in every Spring test context, still Argon2id, with the minimum cost:
 * - the production [Argon2PasswordEncoder] bean (Spring Security's recommended 16 MiB, 2 passes) is replaced by
 *   [FAST_ENCODER]. A setup hashes the password and 10 recovery codes, so the auth and setup tests spent much of their
 *   time hashing;
 * - backups encrypt their secrets with [FAST_KDF] instead of [BackupSecrets.DEFAULT_KDF] (64 MiB, 3 passes), which
 *   every export with a passphrase and every restore of one derived again.
 *
 * Restores read the parameters from the backup, so they work with both. The production parameters stay covered by
 * [FastTestCryptoTests] and `BackupSecretsCryptoTests` (plain unit tests, not changed by this). Registered for all
 * test contexts in `META-INF/spring.factories`; being one shared customizer, it does not split the context cache.
 */
class FastTestCrypto : ContextCustomizerFactory {
	override fun createContextCustomizer(
		testClass: Class<*>,
		configAttributes: List<ContextConfigurationAttributes>,
	): ContextCustomizer = Customizer

	private object Customizer : ContextCustomizer {
		override fun customizeContext(
			context: ConfigurableApplicationContext,
			mergedConfig: MergedContextConfiguration,
		) {
			BackupSecrets.encryptionKdf = FAST_KDF
			context.beanFactory.addBeanPostProcessor(
				object : BeanPostProcessor {
					override fun postProcessAfterInitialization(
						bean: Any,
						beanName: String,
					): Any = if (bean is Argon2PasswordEncoder) FAST_ENCODER else bean
				},
			)
		}
	}

	companion object {
		/** Argon2id with 16-byte salt, 32-byte hash, parallelism 1, 8 KiB memory (the minimum) and 1 pass. */
		val FAST_ENCODER = Argon2PasswordEncoder(16, 32, 1, 8, 1)

		/** Argon2id with 8 KiB memory (the minimum [BackupSecrets.decrypt] accepts) and 1 pass. */
		val FAST_KDF = BackupSecrets.Kdf(memoryKiB = 8, iterations = 1, parallelism = 1)
	}
}
