package ch.sonensei.cockpit.core.auth.api

import ch.sonensei.cockpit.core.auth.domain.TotpEnrollmentResult
import ch.sonensei.cockpit.core.auth.domain.TotpEnrollmentService
import ch.sonensei.cockpit.core.auth.infra.SecurityConfiguration
import ch.sonensei.cockpit.core.web.InvalidRequestException
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * TOTP re-enrollment after a restore without the TOTP secret (SPEC.md §10.2). The login of such an account answers
 * `totpEnrollmentRequired: true`; its session may only call these endpoints, `me` and `logout` until [confirm] succeeds.
 */
@RestController
@RequestMapping(SecurityConfiguration.TOTP_ENROLLMENT_PATH)
@Tag(name = "Auth", description = "Web login sessions and device tokens")
class TotpEnrollmentController(
	private val enrollment: TotpEnrollmentService,
) {
	@PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "startTotpEnrollment",
		summary = "Start setting up TOTP again after a restore",
		description =
			"Only while the account must enroll TOTP again (`totpEnrollmentRequired`). Returns a new TOTP secret; " +
				"calling it again replaces the secret. Confirm it with a code from the authenticator app.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The new TOTP secret",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TotpEnrollmentResponse::class))],
	)
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	@ApiResponse(
		responseCode = "409",
		description = "TOTP re-enrollment is not required",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun start(): ResponseEntity<TotpEnrollmentResponse> {
		val started = enrollment.start()
		return ResponseEntity
			.ok()
			.cacheControl(CacheControl.noStore())
			.body(TotpEnrollmentResponse(started.totpSecret, started.otpauthUri))
	}

	@PostMapping("/confirm", produces = [PROBLEM_JSON])
	@Operation(
		operationId = "confirmTotpEnrollment",
		summary = "Finish setting up TOTP again",
		description =
			"Checks a code for the secret of the last start; shares the login throttle. Afterwards the session has full " +
				"access and TOTP works for the login again.",
	)
	@ApiResponse(responseCode = "204", description = "TOTP set up; the account is a normal one again")
	@ApiResponse(
		responseCode = "400",
		description = "Missing code, or not a valid code for the new secret",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	@ApiResponse(
		responseCode = "409",
		description = "TOTP re-enrollment is not required",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "429",
		description = "Too many failed attempts; retry after the given number of seconds",
		headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait", schema = Schema(type = "integer"))],
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun confirm(
		@Valid @RequestBody request: ConfirmTotpEnrollmentRequest,
	): ResponseEntity<Any> =
		when (val result = enrollment.confirm(request.code.trim())) {
			TotpEnrollmentResult.Confirmed -> {
				ResponseEntity.noContent().build()
			}

			TotpEnrollmentResult.InvalidCode -> {
				throw InvalidRequestException("code", "is not a valid code for the new TOTP secret")
			}

			is TotpEnrollmentResult.Throttled -> {
				// Whole seconds, rounded up, as for the login.
				val seconds = (result.retryAfter.toMillis() + 999) / 1000
				val status = HttpStatus.TOO_MANY_REQUESTS
				ResponseEntity
					.status(status)
					.contentType(MediaType.APPLICATION_PROBLEM_JSON)
					.header(HttpHeaders.RETRY_AFTER, seconds.toString())
					.body(ProblemDetail.forStatusAndDetail(status, "Too many failed attempts, retry later"))
			}
		}
}

class TotpEnrollmentResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "TOTP secret, base32 without padding (HMAC-SHA1, 6 digits, 30 s)")
	val totpSecret: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "otpauth:// URI of the secret, for the QR code")
	val otpauthUri: String,
) {
	override fun toString(): String = "TotpEnrollmentResponse(******)"
}

class ConfirmTotpEnrollmentRequest(
	@field:NotBlank
	@field:Size(max = 16)
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "Current 6-digit code from the authenticator app", example = "123456")
	val code: String,
) {
	override fun toString(): String = "ConfirmTotpEnrollmentRequest(******)"
}

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
