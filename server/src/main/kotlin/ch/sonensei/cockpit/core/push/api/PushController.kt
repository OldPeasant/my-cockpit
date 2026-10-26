package ch.sonensei.cockpit.core.push.api

import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.push.domain.PushService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Web Push subscriptions (SPEC.md §4.6). */
@RestController
@RequestMapping("/api/v1/push")
@Tag(
	name = "Web Push",
	description =
		"Web Push (VAPID) for notifications without an open tab. A browser subscribes with the server's public key; " +
			"the server then sends each `reminder` and `mail` SSE event to it as a push message " +
			"`{\"event\": <name>, \"data\": <event data>}` (encrypted, RFC 8291). `data` is left out when the message " +
			"would be too large. A subscription belongs to the login session that registered it and ends with it " +
			"(logout, idle expiry, session ended). Subscriptions the push service reports gone (404/410) are removed.",
)
class PushController(
	private val push: PushService,
) {
	@GetMapping("/vapid-public-key", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "getVapidPublicKey",
		summary = "The server's VAPID public key",
		description = "Generated at first start. Pass it as `applicationServerKey` to `PushManager.subscribe`.",
	)
	@ApiResponse(responseCode = "200", description = "The key")
	fun publicKey() = VapidPublicKeyResponse(push.publicKey())

	@PutMapping("/subscriptions", consumes = [MediaType.APPLICATION_JSON_VALUE])
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "putPushSubscription",
		summary = "Subscribe this browser",
		description =
			"Registers the browser's `PushSubscription` (its `toJSON()`) for the current login session; pushes stop " +
				"when that session ends. Idempotent: the same endpoint again replaces its keys and moves it to the " +
				"current session. Needs a session cookie: a device token cannot subscribe.",
	)
	@ApiResponse(responseCode = "204", description = "Subscribed")
	@ApiResponse(
		responseCode = "400",
		description = "Endpoint not an https URL, or invalid keys",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "401",
		description = "Not logged in (no body), or the login session ended during the request (Problem Details)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "403",
		description = "Not logged in with a browser session (e.g. a device token)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun subscribe(
		@AuthenticationPrincipal principal: CockpitPrincipal?,
		@Valid @RequestBody request: PushSubscriptionRequest,
	) {
		val sessionId =
			principal?.sessionId ?: throw ErrorResponseException(
				HttpStatus.FORBIDDEN,
				ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Web Push needs a browser login session"),
				null,
			)
		push.subscribe(sessionId, request.endpoint!!, request.keys!!.p256dh!!, request.keys.auth!!)
	}

	@DeleteMapping("/subscriptions", consumes = [MediaType.APPLICATION_JSON_VALUE])
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "deletePushSubscription",
		summary = "Unsubscribe a browser",
		description =
			"Removes the subscription with this endpoint; also succeeds when there is none. The endpoint goes in the " +
				"body, not the URL: it is a secret capability URL that must not end up in access logs.",
	)
	@ApiResponse(responseCode = "204", description = "Unsubscribed")
	@ApiResponse(
		responseCode = "400",
		description = "Missing endpoint",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun unsubscribe(
		@Valid @RequestBody request: PushUnsubscribeRequest,
	) = push.unsubscribe(request.endpoint!!)

	private companion object {
		const val PROBLEM_JSON = "application/problem+json"
	}
}

@Schema(description = "The server's VAPID public key")
data class VapidPublicKeyResponse(
	@field:Schema(
		description = "Uncompressed P-256 public key, unpadded base64url",
		requiredMode = RequiredMode.REQUIRED,
	)
	val publicKey: String,
)

// Nullable fields so a missing value reaches Bean Validation (400 with field errors) instead of failing in Jackson.
@Schema(description = "A browser's push subscription, as `PushSubscription.toJSON()` returns it")
class PushSubscriptionRequest(
	@field:NotBlank
	@field:Schema(description = "The push service URL (https)", requiredMode = RequiredMode.REQUIRED)
	val endpoint: String?,
	@field:NotNull
	@field:Valid
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val keys: PushSubscriptionKeys?,
)

class PushSubscriptionKeys(
	@field:NotBlank
	@field:Schema(description = "The browser's P-256 public key, base64url", requiredMode = RequiredMode.REQUIRED)
	val p256dh: String?,
	@field:NotBlank
	@field:Schema(description = "The browser's 16-byte authentication secret, base64url", requiredMode = RequiredMode.REQUIRED)
	val auth: String?,
)

class PushUnsubscribeRequest(
	@field:NotBlank
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val endpoint: String?,
)
