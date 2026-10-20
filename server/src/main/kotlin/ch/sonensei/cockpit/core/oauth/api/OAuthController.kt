package ch.sonensei.cockpit.core.oauth.api

import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.oauth.domain.OAuthCallbackResult
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionInfo
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionService
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthPurpose
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * OAuth connections for Google and Microsoft (SPEC.md §7.1). The browser navigates to [start], consents at the
 * provider and comes back through [callback], which redirects to the SPA's integrations page with the outcome.
 */
@RestController
@RequestMapping(OAuthController.BASE)
@Tag(name = "OAuth", description = "Connections to Google and Microsoft accounts, shared by calendar, mail and contacts")
class OAuthController(
	private val service: OAuthConnectionService,
	private val cookies: SessionCookies,
) {
	@GetMapping("/providers", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listOAuthProviders", summary = "The providers and whether their OAuth app is configured")
	@ApiResponse(responseCode = "200", description = "Both providers")
	fun providers(): List<OAuthProviderResponse> = OAuthProvider.entries.map { OAuthProviderResponse(it, it.slug, service.isConfigured(it)) }

	@GetMapping("/{provider}/start")
	@Operation(
		operationId = "startOAuthConnection",
		summary = "Start connecting a provider account (browser navigation)",
		description =
			"Redirects to the provider's consent screen (authorization code flow with PKCE) asking for the combined " +
				"calendar, mail and contacts scopes. The flow is bound to the current browser session and expires after " +
				"10 minutes. Connecting an account that is already connected updates that connection.",
	)
	@ApiResponse(
		responseCode = "302",
		description = "Redirect to the provider",
		headers = [Header(name = HttpHeaders.LOCATION, description = "The provider's authorization URL", schema = Schema(type = "string"))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Missing or unknown purpose",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "401",
		description = "No browser session",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "Unknown provider",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The provider's OAuth app is not configured on this server",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun start(
		@Parameter(schema = Schema(allowableValues = ["google", "microsoft"])) @PathVariable provider: String,
		@Parameter(description = "What the account is connected for; passed back to the SPA after the callback")
		@RequestParam purpose: OAuthPurpose,
		request: HttpServletRequest,
	): ResponseEntity<Any> {
		val oauthProvider = provider(provider)
		val session =
			cookies.read(request)
				?: return ResponseEntity
					.status(HttpStatus.UNAUTHORIZED)
					.contentType(MediaType.APPLICATION_PROBLEM_JSON)
					.body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Connecting an account needs a browser session"))
		val location = service.start(oauthProvider, purpose, session)
		return ResponseEntity.status(HttpStatus.FOUND).location(location).build()
	}

	@GetMapping("/{provider}/callback")
	@Operation(
		operationId = "completeOAuthConnection",
		summary = "The provider's redirect back (browser navigation)",
		description =
			"Accepts only the state of a pending flow of the same browser session, exchanges the code and stores the " +
				"encrypted refresh token. Redirects to `/settings/integrations` with `oauth=connected&provider=&purpose=" +
				"&connectionId=` or `oauth=error&provider=&error=` (`invalid_state`, `access_denied`, " +
				"`token_exchange_failed`, `no_refresh_token`) and, when known, `purpose=`.",
	)
	@ApiResponse(
		responseCode = "302",
		description = "Redirect to the SPA",
		headers = [Header(name = HttpHeaders.LOCATION, description = "The SPA page showing the outcome", schema = Schema(type = "string"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "Unknown provider",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun callback(
		@Parameter(schema = Schema(allowableValues = ["google", "microsoft"])) @PathVariable provider: String,
		@RequestParam(required = false) code: String?,
		@RequestParam(required = false) state: String?,
		@RequestParam(required = false) error: String?,
		request: HttpServletRequest,
	): ResponseEntity<Void> {
		val result = service.complete(provider(provider), state, code, error, cookies.read(request))
		val target = UriComponentsBuilder.fromPath(INTEGRATIONS_PAGE)
		when (result) {
			is OAuthCallbackResult.Connected -> {
				target
					.queryParam("oauth", "connected")
					.queryParam("provider", result.provider.slug)
					.queryParam("purpose", result.purpose)
					.queryParam("connectionId", result.connectionId)
			}

			is OAuthCallbackResult.Failed -> {
				target
					.queryParam("oauth", "error")
					.queryParam("provider", result.provider.slug)
					.queryParam("error", result.error.code)
				result.purpose?.let { target.queryParam("purpose", it) }
			}
		}
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target.build().toUriString())).build()
	}

	@GetMapping("/connections", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listOAuthConnections", summary = "The connected accounts with their status, oldest first")
	@ApiResponse(responseCode = "200", description = "All connections (tokens are never returned)")
	fun list(): List<OAuthConnectionResponse> = service.list().map(OAuthConnectionResponse::of)

	@GetMapping("/connections/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getOAuthConnection", summary = "One connection")
	@ApiResponse(
		responseCode = "200",
		description = "The connection, with its version as ETag",
		headers = [Header(name = HttpHeaders.ETAG, schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = OAuthConnectionResponse::class))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such connection",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<OAuthConnectionResponse> = service.get(id).let { ETags.ok(OAuthConnectionResponse.of(it), it.version) }

	@DeleteMapping("/connections/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "disconnectOAuthConnection",
		summary = "Disconnect an account",
		description =
			"Removes the connection and its tokens, and revokes the refresh token at Google (Microsoft has no " +
				"revocation endpoint: remove the app under the account's privacy settings). Calendar sources and mail " +
				"accounts using the connection stop syncing.",
	)
	@ApiResponse(responseCode = "204", description = "Disconnected")
	@ApiResponse(
		responseCode = "404",
		description = "No such connection",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "412",
		description = "If-Match is stale",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "428",
		description = "If-Match is missing",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun disconnect(
		@PathVariable id: UUID,
		@Parameter(`in` = ParameterIn.HEADER, description = "The connection's ETag", required = true)
		@RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	) = service.disconnect(id, ifMatch)

	private fun provider(slug: String): OAuthProvider = OAuthProvider.fromSlug(slug) ?: throw NotFoundException("Unknown OAuth provider")

	companion object {
		const val BASE = "/api/v1/oauth"

		/** The SPA page the callback returns to (Settings → Integrations). */
		const val INTEGRATIONS_PAGE = "/settings/integrations"

		private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		private const val PROBLEM = "#/components/schemas/ProblemDetail"
	}
}

data class OAuthProviderResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val provider: OAuthProvider,
	@field:Schema(description = "The path segment of /api/v1/oauth/{provider}/start", requiredMode = RequiredMode.REQUIRED)
	val slug: String,
	@field:Schema(description = "Whether the server has client id and secret for this provider", requiredMode = RequiredMode.REQUIRED)
	val configured: Boolean,
)

data class OAuthConnectionResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val provider: OAuthProvider,
	@field:Schema(description = "The account's email address, if the provider shared it")
	val email: String?,
	val displayName: String?,
	@field:Schema(description = "Granted scopes", requiredMode = RequiredMode.REQUIRED)
	val scopes: List<String>,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val status: OAuthConnectionStatus,
	@field:Schema(description = "OAuth error code of the last failed token refresh, e.g. invalid_grant")
	val lastError: String?,
	val lastRefreshedAt: Instant?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val createdAt: Instant,
	@field:Schema(description = "The ETag value for If-Match", requiredMode = RequiredMode.REQUIRED)
	val version: Long,
) {
	companion object {
		fun of(info: OAuthConnectionInfo): OAuthConnectionResponse =
			OAuthConnectionResponse(
				id = info.id,
				provider = info.provider,
				email = info.email,
				displayName = info.displayName,
				scopes = info.scopes,
				status = info.status,
				lastError = info.lastError,
				lastRefreshedAt = info.lastRefreshedAt,
				createdAt = info.createdAt,
				version = info.version,
			)
	}
}
