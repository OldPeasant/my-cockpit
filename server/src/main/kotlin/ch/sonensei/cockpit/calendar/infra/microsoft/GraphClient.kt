package ch.sonensei.cockpit.calendar.infra.microsoft

import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionNotFoundException
import ch.sonensei.cockpit.core.oauth.domain.OAuthException
import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

/**
 * Calls Microsoft Graph (`cockpit.calendar.microsoft.api-url`) with the access token of an OAuth connection, asking for
 * times in UTC and bodies as text. Every call answers with its status and JSON body; [MicrosoftCalendarAdapter] decides
 * what a status means. Token failures, network errors, 401/403 and 5xx/429 answers become [CalendarProviderException]s
 * whose messages hold no token, no response body and no event content.
 *
 * Paging links (`@odata.nextLink`, `@odata.deltaLink`) are followed as paths relative to the API URL ([relative]), so
 * a token never goes to another host than the configured one.
 */
class GraphClient(
	apiUrl: String,
	private val tokens: OAuthTokenProvider,
	private val json: JsonMapper,
) {
	private val base: String = apiUrl.trimEnd('/')
	private val basePath: String = URI.create(base).rawPath.orEmpty()
	private val rest: RestClient =
		RestClient
			.builder()
			.requestFactory(
				JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
					.apply { setReadTimeout(READ_TIMEOUT) },
			).build()

	/** A status and the parsed body (a missing node when there was none or it was not JSON). */
	class Response(
		val status: Int,
		val body: JsonNode,
	) {
		val ok: Boolean get() = status in 200..299
	}

	/**
	 * Sends [method] to [path] (a URI template below the API URL; [variables] fill its `{placeholders}` and are encoded)
	 * with the query parameters [query] (null values are left out), the optional JSON [body] and `If-Match` header.
	 */
	fun call(
		connectionId: UUID,
		method: HttpMethod,
		path: String,
		variables: Map<String, String> = emptyMap(),
		query: Map<String, String?> = emptyMap(),
		body: JsonNode? = null,
		ifMatch: String? = null,
	): Response {
		val params = query.filterValues { it != null }
		val builder = UriComponentsBuilder.fromUriString(base + path)
		params.keys.forEach { builder.queryParam(it, "{q_$it}") }
		val uri = builder.encode().buildAndExpand(variables + params.entries.associate { "q_${it.key}" to it.value!! }).toUri()
		return send(connectionId, method, uri, body, ifMatch)
	}

	/** GETs [link], a path and query relative to the API URL (see [relative]). */
	fun follow(
		connectionId: UUID,
		link: String,
	): Response = send(connectionId, HttpMethod.GET, URI.create(base + link), null, null)

	/**
	 * The path and query of a Graph paging [link] relative to the API URL (e.g. `/me/calendarView/delta?$deltatoken=..`),
	 * whatever its host.
	 *
	 * @throws CalendarProviderException when [link] is no URL below the API's path
	 */
	fun relative(link: String): String {
		val uri =
			try {
				URI.create(link)
			} catch (e: IllegalArgumentException) {
				throw CalendarProviderException("Microsoft Graph sent an invalid paging link", e)
			}
		val path = uri.rawPath.orEmpty()
		if (!path.startsWith("$basePath/")) throw CalendarProviderException("Microsoft Graph sent a paging link outside the API")
		return path.removePrefix(basePath) + (uri.rawQuery?.let { "?$it" } ?: "")
	}

	private fun send(
		connectionId: UUID,
		method: HttpMethod,
		uri: URI,
		body: JsonNode?,
		ifMatch: String?,
	): Response {
		val token = accessToken(connectionId)
		val response =
			try {
				rest
					.method(method)
					.uri(uri)
					.header(HttpHeaders.AUTHORIZATION, "Bearer ${token.value}")
					.header(PREFER, "outlook.timezone=\"UTC\"", "outlook.body-content-type=\"text\"", "odata.maxpagesize=$PAGE_SIZE")
					.accept(MediaType.APPLICATION_JSON)
					.apply {
						ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
						body?.let { contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(it)) }
					}.exchange { _, res -> Response(res.statusCode.value(), parse(res.body.readAllBytes())) }
			} catch (e: RestClientException) {
				throw CalendarProviderException("Microsoft Graph could not be reached (${e.javaClass.simpleName})", e)
			}
		when {
			response.status == 401 -> throw CalendarProviderException("Microsoft Graph rejected the access token (HTTP 401)")
			response.status == 403 -> throw CalendarProviderException("Microsoft Graph refused access (HTTP 403)")
			response.status == 429 -> throw CalendarProviderException("Microsoft Graph throttled the requests (HTTP 429), retrying later")
			response.status >= 500 -> throw CalendarProviderException("Microsoft Graph failed (HTTP ${response.status})")
		}
		return response
	}

	private fun accessToken(connectionId: UUID) =
		try {
			tokens.accessToken(connectionId)
		} catch (e: OAuthException) {
			throw CalendarProviderException(
				when (e) {
					is OAuthReconnectRequiredException -> "The Microsoft account has to be connected again"
					is OAuthConnectionNotFoundException -> "The Microsoft connection of this source was removed"
					is OAuthProviderUnavailableException -> "Microsoft sign-in could not be reached, retrying later"
					is OAuthGrantRejectedException -> "Microsoft rejected the token refresh (${e.error})"
				},
				e,
			)
		}

	private fun parse(bytes: ByteArray): JsonNode =
		if (bytes.isEmpty()) {
			json.missingNode()
		} else {
			try {
				json.readTree(bytes)
			} catch (e: JacksonException) {
				json.missingNode()
			}
		}

	companion object {
		const val DEFAULT_API_URL = "https://graph.microsoft.com/v1.0"
		private const val PREFER = "Prefer"
		private const val PAGE_SIZE = 100
		private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
		private val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
	}
}
