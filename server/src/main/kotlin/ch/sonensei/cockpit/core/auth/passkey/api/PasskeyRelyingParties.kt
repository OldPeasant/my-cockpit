package ch.sonensei.cockpit.core.auth.passkey.api

import ch.sonensei.cockpit.core.auth.passkey.domain.RelyingParty
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponents
import org.springframework.web.util.UriComponentsBuilder

/**
 * The WebAuthn relying party (SPEC.md §4.1), from `cockpit.auth.passkey.*` and `cockpit.domain`
 * (env `COCKPIT_DOMAIN`, which the deployment already passes to the server):
 * - `rp-id`: the domain passkeys are bound to (`cockpit.example.org`). Unset: `cockpit.domain`; if that is unset
 *   too, the host of the request.
 * - `origins`: comma-separated accepted origins, the web origin (`https://cockpit.example.org`) and the Android app
 *   (`android:apk-key-hash:<base64url SHA-256 of the signing certificate>`). Unset: `https://<cockpit.domain>`, plus,
 *   when the request's host equals `cockpit.domain`, the request's own host and port in both schemes
 *   (`https://<host>[:port]` and `http://<host>[:port]`); if `cockpit.domain` is unset too, the origin of the request.
 *   The Android origin can never come from a request, so the app needs `origins` set explicitly.
 * - `rp-name`: shown by the authenticator.
 *
 * Accepting both schemes for the configured domain covers a proxy that terminates TLS (Caddy forwards plain HTTP,
 * so the server sees `http`), a non-default HTTPS port (`https://localhost:18443`) and the e2e stack
 * (`http://localhost:<port>`). This is safe: the host must equal the configured domain, and browsers run WebAuthn
 * only in secure contexts (HTTPS, or `localhost`), so a plain-HTTP origin on a public domain never produces a
 * passkey response; the signed client data still has to match the challenge and the RP ID.
 *
 * The request-based defaults without any configuration make local development work out of the box.
 */
@Component
class PasskeyRelyingParties(
	@Value("\${cockpit.auth.passkey.rp-id:}") private val rpId: String,
	@Value("\${cockpit.auth.passkey.rp-name:My Cockpit}") private val rpName: String,
	@Value("\${cockpit.auth.passkey.origins:}") private val origins: List<String>,
	@Value("\${cockpit.domain:}") private val domain: String,
) {
	fun resolve(request: HttpServletRequest): RelyingParty {
		val requestOrigin =
			UriComponentsBuilder
				.fromUriString(request.requestURL.toString())
				.replacePath(null)
				.replaceQuery(null)
				.build()
		val requestHost = requireNotNull(requestOrigin.host)
		val configuredDomain = domain.trim()
		val configuredOrigins = origins.map(String::trim).filter(String::isNotEmpty).toSet()
		return RelyingParty(
			id = rpId.trim().ifEmpty { configuredDomain.ifEmpty { requestHost } },
			name = rpName,
			origins =
				configuredOrigins.ifEmpty {
					if (configuredDomain.isEmpty()) {
						setOf(requestOrigin.toUriString())
					} else {
						domainOrigins(configuredDomain, requestOrigin)
					}
				},
		)
	}

	private fun domainOrigins(
		domain: String,
		requestOrigin: UriComponents,
	): Set<String> {
		val result = linkedSetOf("https://$domain")
		if (requestOrigin.host.equals(domain, ignoreCase = true)) {
			val port = if (requestOrigin.port == -1) "" else ":${requestOrigin.port}"
			result += "https://$domain$port"
			result += "http://$domain$port"
		}
		return result
	}
}
