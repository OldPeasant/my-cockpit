package ch.sonensei.cockpit.core.oauth.infra

import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthIdentity
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
import ch.sonensei.cockpit.core.oauth.domain.TokenGrant
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64

/**
 * Talks to the providers' token endpoints (RFC 6749 §4.1.3 code exchange with PKCE, §6 refresh) and revocation
 * endpoints (RFC 7009). The client authenticates with `client_secret_post`. Tokens never appear in logs or exceptions.
 */
@Component
class OAuthHttpClient(
	private val properties: OAuthProperties,
	private val json: JsonMapper,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	private val rest: RestClient =
		RestClient
			.builder()
			.requestFactory(
				JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
					.apply { setReadTimeout(READ_TIMEOUT) },
			).build()

	fun exchangeCode(
		provider: OAuthProvider,
		code: String,
		codeVerifier: String,
		scope: String?,
	): TokenGrant =
		tokenRequest(
			provider,
			form(
				"grant_type" to "authorization_code",
				"code" to code,
				"code_verifier" to codeVerifier,
				"redirect_uri" to properties.redirectUri(provider),
				"scope" to scope,
			),
		)

	fun refresh(
		provider: OAuthProvider,
		refreshToken: String,
		scope: String?,
	): TokenGrant = tokenRequest(provider, form("grant_type" to "refresh_token", "refresh_token" to refreshToken, "scope" to scope))

	/** Revokes [token] at the provider, if it has a revocation endpoint. Best effort: failures are only logged. */
	fun revoke(
		provider: OAuthProvider,
		token: String,
	) {
		val registration = properties.registration(provider)
		if (registration.revocationUri.isBlank()) return
		try {
			rest
				.post()
				.uri(registration.revocationUri)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form("token" to token))
				.retrieve()
				.toBodilessEntity()
		} catch (e: RestClientException) {
			log.warn("Revoking the {} token failed ({}); the local connection is removed anyway", provider, e.javaClass.simpleName)
		}
	}

	/**
	 * Reads the account identity from an ID token received directly from the token endpoint over TLS, which OpenID
	 * Connect Core §3.1.3.7 allows to use without checking its signature.
	 */
	fun identity(idToken: String?): OAuthIdentity {
		val parts = idToken?.split('.')
		if (parts == null || parts.size != 3) throw OAuthProviderUnavailableException("The token response has no ID token")
		val claims: Map<String, Any?> =
			try {
				json.readValue(Base64.getUrlDecoder().decode(parts[1]))
			} catch (e: IllegalArgumentException) {
				throw OAuthProviderUnavailableException("The ID token is malformed", e)
			} catch (e: JacksonException) {
				throw OAuthProviderUnavailableException("The ID token is malformed", e)
			}
		val subject =
			(claims["sub"] as? String)?.takeIf { it.isNotBlank() } ?: throw OAuthProviderUnavailableException("The ID token has no subject")
		val email = (claims["email"] as? String) ?: (claims["preferred_username"] as? String)?.takeIf { '@' in it }
		return OAuthIdentity(subject, email, claims["name"] as? String)
	}

	private fun tokenRequest(
		provider: OAuthProvider,
		form: MultiValueMap<String, String>,
	): TokenGrant {
		val registration = properties.registration(provider)
		form.add("client_id", registration.clientId)
		form.add("client_secret", registration.clientSecret)
		val body: Map<String, Any?> =
			try {
				rest
					.post()
					.uri(registration.tokenUri)
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.accept(MediaType.APPLICATION_JSON)
					.body(form)
					.retrieve()
					.onStatus({ it.is4xxClientError }) { _, response ->
						throw OAuthGrantRejectedException(errorCode(response.body.readAllBytes()))
					}.body(MAP_TYPE)
					?: throw OAuthProviderUnavailableException("The token endpoint of $provider returned no body")
			} catch (e: RestClientException) {
				throw OAuthProviderUnavailableException("The token endpoint of $provider failed (${e.javaClass.simpleName})", e)
			}
		val accessToken =
			(body["access_token"] as? String)?.takeIf { it.isNotBlank() }
				?: throw OAuthProviderUnavailableException("The token response of $provider has no access token")
		val expiresIn =
			when (val value = body["expires_in"]) {
				is Number -> value.toLong()
				is String -> value.toLongOrNull()
				else -> null
			} ?: DEFAULT_EXPIRES_IN_SECONDS
		return TokenGrant(
			accessToken = accessToken,
			expiresIn = Duration.ofSeconds(expiresIn),
			refreshToken = (body["refresh_token"] as? String)?.takeIf { it.isNotBlank() },
			scope = body["scope"] as? String,
			idToken = body["id_token"] as? String,
		)
	}

	/** The RFC 6749 §5.2 `error` code of an error response; anything else becomes `invalid_request`. */
	private fun errorCode(body: ByteArray): String {
		val error =
			try {
				json.readValue<Map<String, Any?>>(body)["error"] as? String
			} catch (_: JacksonException) {
				null
			}
		return error?.takeIf { ERROR_CODE.matches(it) } ?: "invalid_request"
	}

	private fun form(vararg entries: Pair<String, String?>): MultiValueMap<String, String> =
		LinkedMultiValueMap<String, String>().apply {
			entries.forEach { (key, value) -> if (value != null) add(key, value) }
		}

	private companion object {
		val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
		val READ_TIMEOUT: Duration = Duration.ofSeconds(20)
		const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
		val ERROR_CODE = Regex("[a-z_]{1,64}")
		val MAP_TYPE = object : ParameterizedTypeReference<Map<String, Any?>>() {}
	}
}
