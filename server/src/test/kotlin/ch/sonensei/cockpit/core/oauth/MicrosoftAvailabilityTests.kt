package ch.sonensei.cockpit.core.oauth

import ch.sonensei.cockpit.core.oauth.domain.MicrosoftAvailability
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus

/**
 * The Microsoft switch (docs/adr/0003-microsoft-deferred.md) without a Spring context: which providers it allows, the
 * 400 it raises, and that the shipped configuration turns Microsoft off unless `MICROSOFT_ENABLED` says otherwise.
 */
class MicrosoftAvailabilityTests {
	private val off = MicrosoftAvailability(enabled = false)
	private val on = MicrosoftAvailability(enabled = true)

	@Test
	fun `Google is always allowed, Microsoft only while enabled`() {
		off.allows(OAuthProvider.GOOGLE) shouldBe true
		off.allows(OAuthProvider.MICROSOFT) shouldBe false
		on.allows(OAuthProvider.GOOGLE) shouldBe true
		on.allows(OAuthProvider.MICROSOFT) shouldBe true
	}

	@Test
	fun `require refuses Microsoft while off with a 400 naming the field`() {
		val e = shouldThrow<InvalidRequestException> { off.require(OAuthProvider.MICROSOFT, "type") }

		e.status shouldBe HttpStatus.BAD_REQUEST
		e.message shouldBe "Microsoft is not enabled"
		e.fieldErrors shouldBe listOf(FieldError("type", "Microsoft is not enabled"))
	}

	@Test
	fun `require passes Google while off and every provider while on`() {
		off.require(OAuthProvider.GOOGLE, "provider")
		on.require(OAuthProvider.GOOGLE, "provider")
		on.require(OAuthProvider.MICROSOFT, "provider")
	}

	@Test
	fun `the shipped configuration keeps Microsoft off unless MICROSOFT_ENABLED is true`() {
		enabledWith(emptyMap()) shouldBe false
		enabledWith(mapOf("MICROSOFT_ENABLED" to "true")) shouldBe true
		enabledWith(mapOf("MICROSOFT_ENABLED" to "false")) shouldBe false
	}

	/** `cockpit.microsoft.enabled` as resolved from the main `application.properties` with [variables] set. */
	private fun enabledWith(variables: Map<String, Any>): Boolean? =
		resolver(variables).getProperty("cockpit.microsoft.enabled", Boolean::class.javaObjectType)

	/** The main `application.properties` with [variables] taking precedence, as the environment does at runtime. */
	private fun resolver(variables: Map<String, Any>): PropertySourcesPropertyResolver {
		val sources = MutablePropertySources()
		sources.addLast(MapPropertySource("environment", variables))
		PropertiesPropertySourceLoader()
			.load("application.properties", ClassPathResource("application.properties"))
			.forEach(sources::addLast)
		return PropertySourcesPropertyResolver(sources)
	}
}
