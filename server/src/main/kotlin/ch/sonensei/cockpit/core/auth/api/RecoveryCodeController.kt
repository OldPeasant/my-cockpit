package ch.sonensei.cockpit.core.auth.api

import ch.sonensei.cockpit.core.auth.domain.RecoveryCodeResult
import ch.sonensei.cockpit.core.auth.domain.RecoveryCodeService
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

/** Regenerating the recovery codes of the logged-in owner (SPEC.md §4.1); the new codes are returned only once. */
@RestController
@RequestMapping(SecurityConfiguration.AUTH_PATH)
@Tag(name = "Auth", description = "Web login sessions and device tokens")
class RecoveryCodeController(
	private val recoveryCodes: RecoveryCodeService,
) {
	@PostMapping("/recovery-codes", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "regenerateRecoveryCodes",
		summary = "Replace all recovery codes with new ones",
		description =
			"Needs a current TOTP code and shares the login throttle. Returns 10 new single-use codes exactly once; " +
				"all previous codes, used or not, stop working.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "New recovery codes",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryCodesResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Missing or invalid TOTP code",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	@ApiResponse(
		responseCode = "409",
		description = "Setup is not complete: there is no active account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "429",
		description = "Too many failed attempts; retry after the given number of seconds",
		headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait", schema = Schema(type = "integer"))],
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun regenerate(
		@Valid @RequestBody request: RegenerateRecoveryCodesRequest,
	): ResponseEntity<Any> =
		when (val result = recoveryCodes.regenerate(request.totp.trim())) {
			is RecoveryCodeResult.Regenerated -> {
				ResponseEntity
					.ok()
					.cacheControl(CacheControl.noStore())
					.body(RecoveryCodesResponse(result.codes))
			}

			RecoveryCodeResult.InvalidTotp -> {
				throw InvalidRequestException("totp", "is not a valid code")
			}

			is RecoveryCodeResult.Throttled -> {
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

class RegenerateRecoveryCodesRequest(
	@field:NotBlank
	@field:Size(max = 16)
	@field:Schema(description = "Current 6-digit code from the authenticator app", example = "123456", requiredMode = RequiredMode.REQUIRED)
	val totp: String,
) {
	override fun toString(): String = "RegenerateRecoveryCodesRequest(******)"
}

class RecoveryCodesResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "10 single-use recovery codes, shown only now")
	val recoveryCodes: List<String>,
) {
	override fun toString(): String = "RecoveryCodesResponse(******)"
}

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
