package ch.sonensei.cockpit.core.auth.api

import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.domain.LoginCredentials
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.infra.SecurityConfiguration
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Web login (SPEC.md §4.1): [login] sets the session cookie, [logout] ends the session, [me] tells the SPA who is
 * logged in. Login and logout are public but, like every state-changing request, need the CSRF header.
 */
@RestController
@RequestMapping(SecurityConfiguration.AUTH_PATH)
@Tag(name = "Auth", description = "Web login sessions and device tokens")
class AuthController(
	private val loginService: LoginService,
	private val cookies: SessionCookies,
) {
	@PostMapping("/login", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "login",
		summary = "Log in with password and a TOTP or recovery code",
		description =
			"Sends exactly one of `totp` and `recoveryCode`; a recovery code works only once. On success the session " +
				"cookie is set: persistent for a sliding 30 days with `rememberDevice`, a browser-session cookie " +
				"otherwise. After 5 consecutive failures further attempts are blocked for an exponentially growing time.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "Logged in, session cookie set",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MeResponse::class))],
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
	fun login(
		@Valid @RequestBody request: LoginRequest,
		httpRequest: HttpServletRequest,
		response: HttpServletResponse,
	): ResponseEntity<Any> {
		val totp = request.totp?.takeIf { it.isNotBlank() }
		val recoveryCode = request.recoveryCode?.takeIf { it.isNotBlank() }
		if ((totp == null) == (recoveryCode == null)) {
			throw InvalidRequestException("totp", "exactly one of totp and recoveryCode is required")
		}
		val credentials = LoginCredentials(request.username, request.password, totp, recoveryCode, request.rememberDevice)
		return when (val result = loginService.login(credentials)) {
			is LoginResult.Success -> {
				cookies.write(response, result.grant.token, result.grant.rememberDevice)
				cookies.rotateCsrfToken(httpRequest, response)
				ResponseEntity.ok(MeResponse(result.grant.principal.username))
			}

			LoginResult.InvalidCredentials -> {
				problem(HttpStatus.UNAUTHORIZED, "Invalid credentials")
			}

			is LoginResult.Throttled -> {
				// Whole seconds, rounded up, so a client waiting exactly this long is never rejected again.
				val seconds = (result.retryAfter.toMillis() + 999) / 1000
				problem(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts, retry later", seconds)
			}
		}
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "logout", summary = "End the current session and clear its cookie")
	@ApiResponse(responseCode = "204", description = "Logged out (also when there was no session)")
	fun logout(
		request: HttpServletRequest,
		response: HttpServletResponse,
	) {
		cookies.read(request)?.let(loginService::logout)
		cookies.clear(response)
		cookies.rotateCsrfToken(request, response)
	}

	@GetMapping("/me", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "getCurrentUser", summary = "The logged-in user")
	@ApiResponse(responseCode = "200", description = "Logged in")
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	fun me(authentication: Authentication): MeResponse =
		MeResponse((authentication.principal as? CockpitPrincipal)?.username ?: authentication.name)

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

class LoginRequest(
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
	@field:Schema(description = "Keep the session for a sliding 30 days instead of the browser session")
	val rememberDevice: Boolean = false,
) {
	override fun toString(): String = "LoginRequest(username=$username, ******)"
}

data class MeResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val username: String,
)

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
