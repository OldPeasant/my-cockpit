package ch.sonensei.cockpit.core.auth.api

import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.infra.SecurityConfiguration
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * First-run setup (SPEC.md §4.1): public, and only usable while no active account exists. Once setup is complete,
 * [start] and [confirmTotp] answer 409 and [status] reports `setupRequired: false`.
 */
@RestController
@RequestMapping(SecurityConfiguration.SETUP_PATH)
@Tag(name = "Setup", description = "First-run creation of the owner account")
class SetupController(
	private val setup: SetupService,
) {
	@GetMapping("/status", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "getSetupStatus", summary = "Whether the first-run setup still has to be completed")
	fun status(): SetupStatusResponse = SetupStatusResponse(setup.isSetupRequired())

	@PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(
		operationId = "startSetup",
		summary = "Create the owner account and start the TOTP enrollment",
		description =
			"Returns the TOTP secret and recovery codes exactly once. Calling it again before the TOTP is confirmed " +
				"replaces the pending account.",
	)
	@ApiResponse(responseCode = "201", description = "Account created, TOTP confirmation pending")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid username or password",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "Setup is already complete",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun start(
		@Valid @RequestBody request: SetupRequest,
	): SetupResponse {
		val enrollment = setup.start(request.username, request.password)
		return SetupResponse(enrollment.totpSecret, enrollment.otpauthUri, enrollment.recoveryCodes)
	}

	@PostMapping("/confirm-totp")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "confirmSetupTotp", summary = "Activate the account with a code from the authenticator app")
	@ApiResponse(responseCode = "204", description = "Setup complete, the account is active")
	@ApiResponse(
		responseCode = "400",
		description = "The code is not valid for the TOTP secret",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "Setup is already complete, or was not started",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun confirmTotp(
		@Valid @RequestBody request: ConfirmTotpRequest,
	) {
		setup.confirmTotp(request.code)
	}
}

data class SetupStatusResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val setupRequired: Boolean,
)

class SetupRequest(
	@field:NotBlank
	@field:Size(min = 1, max = 64)
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	@field:Pattern(regexp = "[\\p{L}\\p{N}._@-]+", message = "may only contain letters, digits and . _ @ -")
	val username: String,
	@field:Size(min = 12, max = 256)
	@field:Schema(format = "password", minLength = 12, maxLength = 256, requiredMode = RequiredMode.REQUIRED)
	val password: String,
) {
	override fun toString(): String = "SetupRequest(username=$username, password=******)"
}

class SetupResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "TOTP secret, base32 without padding (HMAC-SHA1, 6 digits, 30 s)")
	val totpSecret: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "otpauth:// URI of the secret, for the QR code")
	val otpauthUri: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "10 single-use recovery codes, shown only now")
	val recoveryCodes: List<String>,
) {
	override fun toString(): String = "SetupResponse(******)"
}

data class ConfirmTotpRequest(
	@field:NotBlank
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "Current 6-digit code from the authenticator app", example = "123456")
	val code: String,
)

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
