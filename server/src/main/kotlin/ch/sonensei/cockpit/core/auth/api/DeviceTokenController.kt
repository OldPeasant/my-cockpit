package ch.sonensei.cockpit.core.auth.api

import ch.sonensei.cockpit.core.auth.domain.DeviceInfo
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenResult
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
import ch.sonensei.cockpit.core.auth.domain.DeviceTokens
import ch.sonensei.cockpit.core.auth.domain.LoginCredentials
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.infra.SecurityConfiguration
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Bearer tokens and devices of the Android app (SPEC.md §4.1). The token endpoints are public and need no CSRF
 * token; the app then sends `Authorization: Bearer <accessToken>` on every API request and refreshes the pair before
 * the access token expires.
 */
@RestController
@RequestMapping(SecurityConfiguration.AUTH_PATH)
@Tag(name = "Auth", description = "Web login sessions and device tokens")
class DeviceTokenController(
	private val devices: DeviceTokenService,
) {
	@PostMapping("/token", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "issueDeviceToken",
		summary = "Log a device in and get its access and refresh tokens",
		description =
			"Takes the same credentials as the web login (exactly one of `totp` and `recoveryCode`) plus a device name, " +
				"and is throttled together with it. The access token is valid for 15 minutes, the refresh token until " +
				"it is rotated or 90 days without a refresh. No CSRF token is needed.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "Device registered",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TokenResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Missing fields, or not exactly one of totp and recoveryCode",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "401",
		description = "Wrong credentials (does not say which part was wrong)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "429",
		description = "Too many failed attempts; retry after the given number of seconds",
		headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait", schema = Schema(type = "integer"))],
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun issue(
		@Valid @RequestBody request: TokenRequest,
	): ResponseEntity<Any> {
		val totp = request.totp?.takeIf { it.isNotBlank() }
		val recoveryCode = request.recoveryCode?.takeIf { it.isNotBlank() }
		if ((totp == null) == (recoveryCode == null)) {
			throw InvalidRequestException("totp", "exactly one of totp and recoveryCode is required")
		}
		val credentials = LoginCredentials(request.username, request.password, totp, recoveryCode, rememberDevice = false)
		return when (val result = devices.issue(credentials, request.deviceName.trim())) {
			is DeviceTokenResult.Issued -> tokens(result.tokens)
			is DeviceTokenResult.Rejected -> rejected(result.result)
		}
	}

	@PostMapping("/token/refresh", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "refreshDeviceToken",
		summary = "Rotate a device's tokens",
		description =
			"Returns a new access and refresh token; the refresh token sent is no longer valid. Sending a refresh " +
				"token again after it was rotated revokes the device. No CSRF token is needed.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "New tokens",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TokenResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Missing refresh token",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "401",
		description = "Unknown, expired, already rotated or revoked refresh token",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun refresh(
		@Valid @RequestBody request: RefreshRequest,
	): ResponseEntity<Any> =
		devices.refresh(request.refreshToken)?.let(::tokens)
			?: problem(HttpStatus.UNAUTHORIZED, "Invalid refresh token")

	@GetMapping("/devices", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listDevices", summary = "Devices logged in with tokens, oldest first")
	@ApiResponse(responseCode = "200", description = "The devices")
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	fun list(): List<DeviceResponse> = devices.list().map(DeviceResponse::of)

	@DeleteMapping("/devices/{id}", produces = [PROBLEM_JSON])
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "revokeDevice", summary = "Revoke a device; its tokens stop working immediately")
	@ApiResponse(responseCode = "204", description = "Revoked")
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	@ApiResponse(
		responseCode = "404",
		description = "No such device",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun revoke(
		@PathVariable id: UUID,
	) {
		if (!devices.revoke(id)) throw NotFoundException("Device not found")
	}

	private fun tokens(tokens: DeviceTokens): ResponseEntity<Any> =
		ResponseEntity
			.ok()
			.cacheControl(CacheControl.noStore())
			.body(TokenResponse.of(tokens))

	private fun rejected(result: LoginResult): ResponseEntity<Any> =
		when (result) {
			is LoginResult.Throttled -> {
				// Whole seconds, rounded up, as for the web login.
				val seconds = (result.retryAfter.toMillis() + 999) / 1000
				problem(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts, retry later", seconds)
			}

			else -> {
				problem(HttpStatus.UNAUTHORIZED, "Invalid credentials")
			}
		}

	private fun problem(
		status: HttpStatus,
		detail: String,
		retryAfterSeconds: Long? = null,
	): ResponseEntity<Any> {
		val builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
		if (retryAfterSeconds != null) builder.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
		return builder.body(ProblemDetail.forStatusAndDetail(status, detail))
	}
}

class TokenRequest(
	@field:NotBlank
	@field:Size(max = 64)
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val username: String,
	@field:NotBlank
	@field:Size(max = 256)
	@field:Schema(format = "password", maxLength = 256, requiredMode = RequiredMode.REQUIRED)
	val password: String,
	@field:Size(max = 16)
	@field:Schema(description = "Current 6-digit code from the authenticator app", example = "123456")
	val totp: String? = null,
	@field:Size(max = 64)
	@field:Schema(description = "One of the recovery codes, instead of totp", example = "abcde-fghjk")
	val recoveryCode: String? = null,
	@field:NotBlank
	@field:Size(max = 100)
	@field:Schema(description = "Shown in the device list", example = "Pixel 9", maxLength = 100, requiredMode = RequiredMode.REQUIRED)
	val deviceName: String,
) {
	override fun toString(): String = "TokenRequest(username=$username, deviceName=$deviceName, ******)"
}

class RefreshRequest(
	@field:NotBlank
	@field:Size(max = 256)
	@field:Schema(format = "password", maxLength = 256, requiredMode = RequiredMode.REQUIRED)
	val refreshToken: String,
) {
	override fun toString(): String = "RefreshRequest(******)"
}

class TokenResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val deviceId: UUID,
	@field:Schema(description = "Send as `Authorization: Bearer <accessToken>`", requiredMode = RequiredMode.REQUIRED)
	val accessToken: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val accessTokenExpiresAt: Instant,
	@field:Schema(description = "Opaque; valid for one refresh", requiredMode = RequiredMode.REQUIRED)
	val refreshToken: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val refreshTokenExpiresAt: Instant,
	@field:Schema(allowableValues = ["Bearer"], requiredMode = RequiredMode.REQUIRED)
	val tokenType: String = "Bearer",
) {
	override fun toString(): String = "TokenResponse(deviceId=$deviceId, ******)"

	companion object {
		fun of(tokens: DeviceTokens) =
			TokenResponse(tokens.deviceId, tokens.accessToken, tokens.accessTokenExpiresAt, tokens.refreshToken, tokens.refreshTokenExpiresAt)
	}
}

data class DeviceResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val name: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val createdAt: Instant,
	@field:Schema(description = "Last login or token refresh", requiredMode = RequiredMode.REQUIRED)
	val lastSeenAt: Instant,
) {
	companion object {
		fun of(device: DeviceInfo) = DeviceResponse(device.id, device.name, device.createdAt, device.lastSeenAt)
	}
}

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
