package ch.sonensei.cockpit.core.auth.passkey.api

import ch.sonensei.cockpit.core.auth.api.MeResponse
import ch.sonensei.cockpit.core.auth.api.TokenResponse
import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenResult
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.infra.SecurityConfiguration
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.passkey.domain.PasskeyService
import ch.sonensei.cockpit.core.auth.passkey.domain.RegistrationStartResult
import ch.sonensei.cockpit.core.web.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * Passkeys (SPEC.md §4.1). Each ceremony has two steps: the `options` call returns a `ceremonyId` and the WebAuthn
 * options in their JSON form (`PublicKeyCredential.parseCreationOptionsFromJSON()` / `parseRequestOptionsFromJSON()`
 * in the browser, the `requestJson` of Android's Credential Manager); the client then sends the resulting
 * `PublicKeyCredential` as JSON (`credential.toJSON()`, Credential Manager's `registrationResponseJson` /
 * `authenticationResponseJson`) together with the `ceremonyId`.
 *
 * Managing passkeys needs a login (web session or device token). Logging in with a passkey is public: the web login
 * sets the session cookie and needs the CSRF header like the password login; the token login is for the Android app
 * and needs none.
 */
@RestController
@RequestMapping(SecurityConfiguration.PASSKEYS_PATH)
@Tag(name = "Passkeys", description = "WebAuthn passkeys: registration, login and management")
class PasskeyController(
	private val passkeys: PasskeyService,
	private val relyingParties: PasskeyRelyingParties,
	private val cookies: SessionCookies,
	private val jsonMapper: JsonMapper,
) {
	@PostMapping("/registration/options", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "startPasskeyRegistration",
		summary = "Start adding a passkey; needs a current TOTP code",
		description =
			"Returns the options for `navigator.credentials.create()` (discoverable credential, user verification " +
				"required). Wrong TOTP codes count against the login throttle.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "Registration started; finish it within `publicKey.timeout` milliseconds",
		content = [
			Content(
				mediaType = MediaType.APPLICATION_JSON_VALUE,
				schema = Schema(implementation = PasskeyRegistrationOptionsResponse::class),
			),
		],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Missing TOTP code",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	@ApiResponse(
		responseCode = "403",
		description = "Wrong TOTP code",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "429",
		description = "Too many failed attempts; retry after the given number of seconds",
		headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait", schema = Schema(type = "integer"))],
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun startRegistration(
		@Valid @RequestBody body: PasskeyRegistrationOptionsRequest,
		authentication: Authentication,
		request: HttpServletRequest,
	): ResponseEntity<Any> =
		when (val result = passkeys.startRegistration(principal(authentication), body.totp, relyingParties.resolve(request))) {
			is RegistrationStartResult.Started -> noStore(PasskeyRegistrationOptionsResponse.of(result.options))
			RegistrationStartResult.InvalidTotp -> problem(HttpStatus.FORBIDDEN, "Invalid TOTP code")
			is RegistrationStartResult.Throttled -> throttled(result.retryAfter)
		}

	@PostMapping("/registration", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(
		operationId = "finishPasskeyRegistration",
		summary = "Finish adding a passkey with the authenticator's response",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Passkey added",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = PasskeyResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Unknown or expired ceremony, or a response that does not verify",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	@ApiResponse(
		responseCode = "409",
		description = "This passkey is already registered",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun finishRegistration(
		@Valid @RequestBody body: PasskeyRegistrationRequest,
		authentication: Authentication,
	): PasskeyResponse =
		PasskeyResponse.of(
			passkeys.finishRegistration(
				principal(authentication),
				body.ceremonyId,
				body.name.trim(),
				jsonMapper.writeValueAsString(body.credential),
			),
		)

	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listPasskeys", summary = "Registered passkeys, oldest first")
	@ApiResponse(responseCode = "200", description = "The passkeys")
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	fun list(): List<PasskeyResponse> = passkeys.list().map(PasskeyResponse::of)

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deletePasskey", summary = "Delete a passkey; it can no longer be used to log in")
	@ApiResponse(responseCode = "204", description = "Deleted")
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	@ApiResponse(
		responseCode = "404",
		description = "No such passkey",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun delete(
		@PathVariable id: UUID,
	) {
		if (!passkeys.delete(id)) throw NotFoundException("Passkey not found")
	}

	@PostMapping("/authentication/options", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "startPasskeyAuthentication",
		summary = "Start a login with a passkey",
		description = "Returns the options for `navigator.credentials.get()`. Public; no CSRF token is needed.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "Authentication started; finish it within `publicKey.timeout` milliseconds",
		content = [
			Content(
				mediaType = MediaType.APPLICATION_JSON_VALUE,
				schema = Schema(implementation = PasskeyAuthenticationOptionsResponse::class),
			),
		],
	)
	fun startAuthentication(request: HttpServletRequest): ResponseEntity<Any> =
		noStore(PasskeyAuthenticationOptionsResponse.of(passkeys.startAuthentication(relyingParties.resolve(request))))

	@PostMapping("/login", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "loginWithPasskey",
		summary = "Log in to the web app with a passkey",
		description =
			"Sets the session cookie like the password login and is throttled together with it. Needs the CSRF header.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "Logged in, session cookie set",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MeResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Missing fields",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "401",
		description = "Unknown or expired ceremony, unknown passkey, or a response that does not verify",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "429",
		description = "Too many failed attempts; retry after the given number of seconds",
		headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait", schema = Schema(type = "integer"))],
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun login(
		@Valid @RequestBody body: PasskeyLoginRequest,
		request: HttpServletRequest,
		response: HttpServletResponse,
	): ResponseEntity<Any> =
		when (val result = passkeys.login(body.ceremonyId, jsonMapper.writeValueAsString(body.credential), body.rememberDevice)) {
			is LoginResult.Success -> {
				cookies.write(response, result.grant.token, result.grant.rememberDevice)
				cookies.rotateCsrfToken(request, response)
				ResponseEntity.ok(MeResponse.of(result.grant.principal))
			}

			else -> {
				rejected(result)
			}
		}

	@PostMapping("/token", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "issueDeviceTokenWithPasskey",
		summary = "Log a device in with a passkey and get its access and refresh tokens",
		description = "Like `issueDeviceToken`, with a passkey instead of password and TOTP. No CSRF token is needed.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "Device registered",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TokenResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Missing fields",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "401",
		description = "Unknown or expired ceremony, unknown passkey, or a response that does not verify",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "403",
		description = "Valid passkey, but TOTP must be set up again in the web app first (account restored from a backup)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "429",
		description = "Too many failed attempts; retry after the given number of seconds",
		headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait", schema = Schema(type = "integer"))],
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun issueToken(
		@Valid @RequestBody body: PasskeyTokenRequest,
	): ResponseEntity<Any> =
		when (val result = passkeys.issueToken(body.ceremonyId, jsonMapper.writeValueAsString(body.credential), body.deviceName.trim())) {
			is DeviceTokenResult.Issued -> noStore(TokenResponse.of(result.tokens))
			is DeviceTokenResult.Rejected -> rejected(result.result)
		}

	private fun principal(authentication: Authentication): CockpitPrincipal =
		authentication.principal as? CockpitPrincipal ?: throw IllegalStateException("Unexpected principal type")

	private fun noStore(body: Any): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

	private fun rejected(result: LoginResult): ResponseEntity<Any> =
		when (result) {
			is LoginResult.Throttled -> throttled(result.retryAfter)
			LoginResult.TotpEnrollmentRequired -> problem(HttpStatus.FORBIDDEN, "TOTP must be set up again in the web app first")
			else -> problem(HttpStatus.UNAUTHORIZED, "Invalid credentials")
		}

	private fun throttled(retryAfter: Duration): ResponseEntity<Any> {
		// Whole seconds, rounded up, as for the password login.
		val seconds = (retryAfter.toMillis() + 999) / 1000
		return problem(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts, retry later", seconds)
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

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
private const val PROBLEM = "#/components/schemas/ProblemDetail"
