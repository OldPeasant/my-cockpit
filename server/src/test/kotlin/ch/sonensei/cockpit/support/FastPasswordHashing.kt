package ch.sonensei.cockpit.support

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration

/**
 * Makes password hashing cheap in every Spring test context: the production [Argon2PasswordEncoder] bean
 * (Spring Security's recommended 16 MiB, 2 iterations) is replaced by [FAST_ENCODER], still Argon2id, with the
 * minimum cost.
 *
 * A setup hashes the password and 10 recovery codes, so the auth, setup and backup tests spent much of their time in
 * Argon2. The production parameters stay covered by `PasswordEncoderParametersTests`. Registered for all test
 * contexts in `META-INF/spring.factories`; being one shared customizer, it does not split the context cache.
 */
class FastPasswordHashing : ContextCustomizerFactory {
	override fun createContextCustomizer(
		testClass: Class<*>,
		configAttributes: List<ContextConfigurationAttributes>,
	): ContextCustomizer = Customizer

	private object Customizer : ContextCustomizer {
		override fun customizeContext(
			context: ConfigurableApplicationContext,
			mergedConfig: MergedContextConfiguration,
		) {
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
		/** Argon2id with 16-byte salt, 32-byte hash, parallelism 1, 8 KiB memory (the minimum) and 1 iteration. */
		val FAST_ENCODER = Argon2PasswordEncoder(16, 32, 1, 8, 1)
	}
}
