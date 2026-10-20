package ch.sonensei.cockpit.core.oauth.infra

import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * OAuth app registrations (`cockpit.oauth.*`, from `GOOGLE_CLIENT_ID/SECRET`, `MS_CLIENT_ID/SECRET/TENANT`, see
 * `docs/oauth-setup.md`). [publicUrl] is the origin the browser uses (`https://$COCKPIT_DOMAIN`); the redirect URI
 * registered with the providers is `<publicUrl>/api/v1/oauth/<provider>/callback`.
 */
@ConfigurationProperties("cockpit.oauth")
class OAuthProperties(
	val publicUrl: String,
	val google: Registration,
	val microsoft: Registration,
) {
	/** One provider's app: credentials and endpoints. [revocationUri] is optional (Microsoft has none). */
	class Registration(
		val clientId: String = "",
		val clientSecret: String = "",
		val authorizationUri: String,
		val tokenUri: String,
		val revocationUri: String = "",
	) {
		val configured: Boolean
			get() = clientId.isNotBlank() && clientSecret.isNotBlank()

		override fun toString(): String = "Registration(clientId=$clientId, authorizationUri=$authorizationUri, ******)"
	}

	fun registration(provider: OAuthProvider): Registration =
		when (provider) {
			OAuthProvider.GOOGLE -> google
			OAuthProvider.MICROSOFT -> microsoft
		}

	fun redirectUri(provider: OAuthProvider): String = "${publicUrl.trimEnd('/')}/api/v1/oauth/${provider.slug}/callback"
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthProperties::class)
class OAuthConfiguration
