package ch.sonensei.cockpit.core.push.api

import ch.sonensei.cockpit.core.push.domain.UnifiedPushService
import ch.sonensei.cockpit.core.push.domain.WebPushKeys
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** UnifiedPush endpoints of the owner's app installations. */
@RestController
@RequestMapping("/api/v1/push/unifiedpush")
@Tag(
	name = "Push",
	description =
		"Push registrations. When a reminder fires or new mail arrives, the server POSTs a push to every registered " +
			"UnifiedPush endpoint. Its payload is `{\"kind\":\"reminder\"|\"mail\",\"id\":\"<uuid>\"}` (the reminder's " +
			"id, or the newest new message's), never item contents; with Web Push keys it is encrypted (RFC 8291, " +
			"`aes128gcm`). Endpoints answering 404 or 410 are removed.",
)
class UnifiedPushController(
	private val service: UnifiedPushService,
) {
	@PutMapping("/{instanceId}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [PROBLEM_JSON])
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "registerUnifiedPush",
		summary = "Register or update the UnifiedPush endpoint of an instance",
		description = "Idempotent: replaces the endpoint and keys the instance registered before.",
	)
	@ApiResponse(responseCode = "204", description = "Registered")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid instance id, endpoint or keys",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	fun register(
		@Parameter(description = "The app's UnifiedPush instance", schema = Schema(pattern = INSTANCE_ID))
		@PathVariable
		instanceId: String,
		@Valid @RequestBody request: UnifiedPushRegistrationRequest,
	) {
		checkInstanceId(instanceId)
		val keys =
			when {
				request.p256dh == null && request.auth == null -> null
				request.p256dh == null || request.auth == null ->
					throw InvalidRequestException(
						listOf(FieldError(if (request.p256dh == null) "p256dh" else "auth", "must be set together with the other key")),
					)
				else ->
					try {
						WebPushKeys(request.p256dh, request.auth)
					} catch (e: IllegalArgumentException) {
						throw InvalidRequestException(if (e.message.orEmpty().startsWith("auth")) "auth" else "p256dh", e.message.orEmpty())
					}
			}
		service.register(instanceId, request.endpoint!!, keys)
	}

	@DeleteMapping("/{instanceId}", produces = [PROBLEM_JSON])
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "unregisterUnifiedPush",
		summary = "Remove the UnifiedPush endpoint of an instance",
		description = "Idempotent: an instance without an endpoint answers 204 as well.",
	)
	@ApiResponse(responseCode = "204", description = "Removed (or there was nothing to remove)")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid instance id",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	fun unregister(
		@Parameter(description = "The app's UnifiedPush instance", schema = Schema(pattern = INSTANCE_ID))
		@PathVariable
		instanceId: String,
	) {
		checkInstanceId(instanceId)
		service.unregister(instanceId)
	}

	// Checked here rather than with @Pattern: method validation would report body field errors under "request".
	private fun checkInstanceId(instanceId: String) {
		if (!INSTANCE_ID_REGEX.matches(instanceId)) {
			throw InvalidRequestException("instanceId", "must be 1 to 128 letters, digits, '.', '_' or '-'")
		}
	}

	private companion object {
		const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		const val INSTANCE_ID = "^[A-Za-z0-9._-]{1,128}$"
		val INSTANCE_ID_REGEX = Regex(INSTANCE_ID)
	}
}

// Nullable fields so a missing value reaches Bean Validation (400 with field errors) instead of failing in Jackson.
class UnifiedPushRegistrationRequest(
	@field:NotBlank
	@field:Size(max = 2048)
	@field:Schema(description = "The endpoint URL the distributor gave the app", requiredMode = RequiredMode.REQUIRED)
	val endpoint: String?,
	@field:Size(max = 128)
	@field:Schema(description = "Web Push public key (base64url, uncompressed P-256 point); together with `auth`")
	val p256dh: String?,
	@field:Size(max = 64)
	@field:Schema(description = "Web Push authentication secret (base64url, 16 bytes); together with `p256dh`")
	val auth: String?,
)
