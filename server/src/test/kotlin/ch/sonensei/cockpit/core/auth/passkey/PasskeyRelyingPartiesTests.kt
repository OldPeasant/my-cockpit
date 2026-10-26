package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.passkey.api.PasskeyRelyingParties
import ch.sonensei.cockpit.core.auth.passkey.domain.RelyingParty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.mock.web.MockHttpServletRequest

class PasskeyRelyingPartiesTests {
	private val request =
		MockHttpServletRequest("POST", "/api/v1/auth/passkeys/authentication/options").apply {
			scheme = "https"
			serverName = "cockpit.example.org"
			serverPort = 8443
			queryString = "x=1"
		}

	private fun request(
		scheme: String,
		host: String,
		port: Int,
	) = MockHttpServletRequest("POST", "/api/v1/auth/passkeys/authentication/options").apply {
		this.scheme = scheme
		serverName = host
		serverPort = port
	}

	@Test
	fun `without configuration the relying party is the request's host and origin`() {
		PasskeyRelyingParties("", "My Cockpit", emptyList(), "").resolve(request) shouldBe
			RelyingParty("cockpit.example.org", "My Cockpit", setOf("https://cockpit.example.org:8443"))
	}

	@Test
	fun `configured RP ID and origins win over the request and the domain`() {
		val origins = listOf(" https://cockpit.example.org", "android:apk-key-hash:AAAA ", "")
		PasskeyRelyingParties(" example.org ", "Cockpit", origins, "other.example.org").resolve(request) shouldBe
			RelyingParty("example.org", "Cockpit", setOf("https://cockpit.example.org", "android:apk-key-hash:AAAA"))
	}

	@Test
	fun `the domain is the default RP ID and https origin when rp-id and origins are unset`() {
		// Behind Caddy: TLS is terminated by the proxy, the server sees plain http on the default port.
		PasskeyRelyingParties("", "My Cockpit", emptyList(), " cockpit.example.org ")
			.resolve(request("http", "cockpit.example.org", 80)) shouldBe
			RelyingParty(
				"cockpit.example.org",
				"My Cockpit",
				setOf("https://cockpit.example.org", "http://cockpit.example.org"),
			)
	}

	@Test
	fun `with the domain the request's own port is accepted in both schemes`() {
		// Caddy on a non-default HTTPS port, e.g. https://localhost:18443.
		PasskeyRelyingParties("", "My Cockpit", emptyList(), "localhost")
			.resolve(request("http", "localhost", 18443))
			.origins shouldBe setOf("https://localhost", "https://localhost:18443", "http://localhost:18443")
		// The e2e stack, reached at http://localhost:<port>.
		PasskeyRelyingParties("", "My Cockpit", emptyList(), "localhost")
			.resolve(request("http", "LOCALHOST", 5173))
			.origins shouldBe setOf("https://localhost", "https://localhost:5173", "http://localhost:5173")
	}

	@Test
	fun `with the domain a request for another host only gets the domain's https origin`() {
		PasskeyRelyingParties("", "My Cockpit", emptyList(), "cockpit.example.org")
			.resolve(request("http", "evil.example.net", 8080)) shouldBe
			RelyingParty("cockpit.example.org", "My Cockpit", setOf("https://cockpit.example.org"))
	}

	@Test
	fun `a configured rp-id wins over the domain, which still yields the default origins`() {
		PasskeyRelyingParties("example.org", "My Cockpit", emptyList(), "cockpit.example.org")
			.resolve(request("https", "cockpit.example.org", 443)) shouldBe
			RelyingParty(
				"example.org",
				"My Cockpit",
				setOf("https://cockpit.example.org", "http://cockpit.example.org"),
			)
	}

	@Test
	fun `configured origins win over the domain, which still yields the default RP ID`() {
		PasskeyRelyingParties("", "My Cockpit", listOf("android:apk-key-hash:AAAA"), "cockpit.example.org")
			.resolve(request) shouldBe
			RelyingParty("cockpit.example.org", "My Cockpit", setOf("android:apk-key-hash:AAAA"))
	}

	@Test
	fun `the app origin of each configured fingerprint is added to explicitly configured origins`() {
		val origins = listOf("https://cockpit.example.org", "android:apk-key-hash:AAAA")
		PasskeyRelyingParties("", "My Cockpit", origins, "cockpit.example.org", listOf(FINGERPRINT, " ", FINGERPRINT.lowercase()))
			.resolve(request) shouldBe
			RelyingParty(
				"cockpit.example.org",
				"My Cockpit",
				setOf("https://cockpit.example.org", "android:apk-key-hash:AAAA", APP_ORIGIN),
			)
	}

	@Test
	fun `the app origin of a fingerprint is added to the request-derived default origins`() {
		PasskeyRelyingParties("", "My Cockpit", emptyList(), "", listOf(FINGERPRINT)).resolve(request) shouldBe
			RelyingParty("cockpit.example.org", "My Cockpit", setOf("https://cockpit.example.org:8443", APP_ORIGIN))
		// An app origin is never derived from a request: a request for another host still only adds the configured one.
		PasskeyRelyingParties("", "My Cockpit", emptyList(), "cockpit.example.org", listOf(FINGERPRINT))
			.resolve(request("http", "evil.example.net", 8080))
			.origins shouldBe setOf("https://cockpit.example.org", APP_ORIGIN)
	}

	@Test
	fun `an invalid android fingerprint fails the relying party configuration`() {
		shouldThrow<IllegalArgumentException> { PasskeyRelyingParties("", "My Cockpit", emptyList(), "", listOf("AB:CD")) }
	}

	@Test
	fun `Spring binds cockpit domain to the default relying party`() {
		ApplicationContextRunner()
			.withBean(PasskeyRelyingParties::class.java)
			.withPropertyValues("cockpit.domain=cockpit.example.org")
			.run { context ->
				context.getBean(PasskeyRelyingParties::class.java).resolve(request("http", "cockpit.example.org", 80)) shouldBe
					RelyingParty("cockpit.example.org", "My Cockpit", setOf("https://cockpit.example.org", "http://cockpit.example.org"))
			}
	}

	@Test
	fun `the deployment's COCKPIT_DOMAIN and COCKPIT_AUTH_PASSKEY_ environment variables are read`() {
		fun runner(environment: Map<String, Any>) =
			ApplicationContextRunner()
				.withBean(PasskeyRelyingParties::class.java)
				.withInitializer { context ->
					context.beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
					context.environment.propertySources.addFirst(
						SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment),
					)
				}
		runner(mapOf("COCKPIT_DOMAIN" to "cockpit.example.org")).run { context ->
			context.getBean(PasskeyRelyingParties::class.java).resolve(request("http", "cockpit.example.org", 80)) shouldBe
				RelyingParty("cockpit.example.org", "My Cockpit", setOf("https://cockpit.example.org", "http://cockpit.example.org"))
		}
		runner(
			mapOf(
				"COCKPIT_DOMAIN" to "cockpit.example.org",
				"COCKPIT_AUTH_PASSKEY_RP_ID" to "example.org",
				"COCKPIT_AUTH_PASSKEY_RP_NAME" to "Fictitious Cockpit",
				"COCKPIT_AUTH_PASSKEY_ORIGINS" to "https://cockpit.example.org,android:apk-key-hash:AAAA",
			),
		).run { context ->
			context.getBean(PasskeyRelyingParties::class.java).resolve(request("http", "cockpit.example.org", 80)) shouldBe
				RelyingParty("example.org", "Fictitious Cockpit", setOf("https://cockpit.example.org", "android:apk-key-hash:AAAA"))
		}
		runner(
			mapOf(
				"COCKPIT_DOMAIN" to "cockpit.example.org",
				"COCKPIT_AUTH_PASSKEY_ANDROID_FINGERPRINTS" to FINGERPRINT,
			),
		).run { context ->
			context.getBean(PasskeyRelyingParties::class.java).resolve(request("http", "cockpit.example.org", 80)).origins shouldBe
				setOf("https://cockpit.example.org", "http://cockpit.example.org", APP_ORIGIN)
		}
	}

	companion object {
		/** A fictitious signing certificate fingerprint. */
		const val FINGERPRINT = "AB:12:CD:34:EF:56:AB:78:CD:90:EF:12:AB:34:CD:56:EF:78:AB:90:CD:12:EF:34:AB:56:CD:78:EF:90:AB:12"

		/** Its app origin, computed independently (Python base64.urlsafe_b64encode of the 32 bytes, unpadded). */
		const val APP_ORIGIN = "android:apk-key-hash:qxLNNO9Wq3jNkO8SqzTNVu94q5DNEu80q1bNeO-QqxI"
	}
}
