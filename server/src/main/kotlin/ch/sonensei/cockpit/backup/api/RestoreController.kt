package ch.sonensei.cockpit.backup.api

import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.PreRestoreBackupFailedException
import ch.sonensei.cockpit.backup.domain.RestoreFailedException
import ch.sonensei.cockpit.backup.domain.RestoreReport
import ch.sonensei.cockpit.backup.domain.RestoreResult
import ch.sonensei.cockpit.backup.domain.RestoreService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as RequestBodyDoc

/** Restore from a backup zip (SPEC.md §10.1): upload for a validation report, then confirm. */
@RestController
@RequestMapping("/api/v1/restore")
@Tag(name = "Backup", description = "Create and download backups")
class RestoreController(
	private val restores: RestoreService,
) {
	@PostMapping(consumes = [ZIP, OCTET_STREAM], produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "uploadRestore",
		summary = "Upload a backup and validate it",
		description =
			"Stores the backup zip and returns its validation report; no data changes. A valid backup gets an `id` to " +
				"confirm the restore with; an invalid one (`valid: false`, see `problems`) is discarded. A new upload " +
				"discards the previous one.",
		requestBody =
			RequestBodyDoc(
				required = true,
				content = [Content(mediaType = ZIP, schema = Schema(type = "string", format = "binary"))],
			),
	)
	@ApiResponse(responseCode = "200", description = "The validation report")
	fun upload(request: HttpServletRequest): RestoreReportResponse = restores.upload(request.inputStream).toResponse()

	@PostMapping("/{id}/confirm", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "confirmRestore",
		summary = "Replace all data with an uploaded backup",
		description =
			"Saves the current data as a backup to `pre-restore/` in the data directory, then replaces all data with " +
				"the upload, in one transaction: on failure nothing changes. Sessions are replaced too, so the client " +
				"signs in again afterwards. The upload is used up, also when the restore fails, but not by a wrong " +
				"passphrase. With the backup's `passphrase` its secrets are restored (and the pre-restore backup is " +
				"made with it too); without, accounts must be reconnected and TOTP is set up again on the next " +
				"login. The body is optional.",
	)
	@ApiResponse(responseCode = "200", description = "Restored")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid id",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such upload (unknown, invalid, discarded by a newer one or used up)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "A backup is running",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "422",
		description =
			"The passphrase is wrong, or the backup's data could not be imported; nothing changed. `problems` lists " +
				"why, if known.",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "500",
		description = "The current data could not be backed up to `pre-restore/` first; nothing changed",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun confirm(
		@Parameter(description = "Id of the upload, from its validation report") @PathVariable id: UUID,
		@Valid @RequestBody(required = false) request: ConfirmRestoreRequest?,
	): RestoreResultResponse = restores.confirm(id, request?.passphrase?.takeIf { it.isNotEmpty() }).toResponse()

	@ExceptionHandler(InvalidBackupException::class)
	fun handleInvalid(ex: InvalidBackupException): ResponseEntity<ProblemDetail> = unprocessable(ex.problems)

	@ExceptionHandler(RestoreFailedException::class)
	fun handleFailed(ex: RestoreFailedException): ResponseEntity<ProblemDetail> = unprocessable(emptyList())

	@ExceptionHandler(PreRestoreBackupFailedException::class)
	fun handlePreRestoreFailed(ex: PreRestoreBackupFailedException): ResponseEntity<ProblemDetail> =
		ResponseEntity
			.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.message))

	private fun unprocessable(problems: List<String>): ResponseEntity<ProblemDetail> {
		val problem =
			ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "The backup could not be restored; nothing was changed")
		problem.setProperty("problems", problems)
		return ResponseEntity
			.status(HttpStatus.UNPROCESSABLE_CONTENT)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(problem)
	}
}

@Schema(description = "Options of a restore")
class ConfirmRestoreRequest(
	@field:Size(max = PASSPHRASE_MAX)
	@field:Schema(description = "The backup's passphrase, to restore its secrets", format = "password", maxLength = PASSPHRASE_MAX)
	val passphrase: String? = null,
) {
	override fun toString(): String = "ConfirmRestoreRequest(******)"
}

@Schema(description = "Validation report of an uploaded backup")
data class RestoreReportResponse(
	@field:Schema(description = "Id to confirm the restore with; only for a valid backup")
	val id: UUID?,
	@field:Schema(description = "Whether the backup can be restored", requiredMode = RequiredMode.REQUIRED)
	val valid: Boolean,
	@field:Schema(description = "Format version from the manifest, if readable")
	val formatVersion: Int?,
	@field:Schema(description = "Version of My Cockpit that created the backup, if readable")
	val appVersion: String?,
	@field:Schema(description = "When the backup was created, if readable")
	val createdAt: Instant?,
	@field:Schema(description = "Records per data name, e.g. `tasks`", requiredMode = RequiredMode.REQUIRED)
	val counts: Map<String, Long>,
	@field:Schema(
		description = "Whether the backup holds encrypted secrets: confirm with its passphrase to restore them",
		requiredMode = RequiredMode.REQUIRED,
	)
	val hasSecrets: Boolean,
	@field:Schema(
		description = "Why it cannot be restored: not a zip, wrong format version, sha256 mismatches, ...",
		requiredMode = RequiredMode.REQUIRED,
	)
	val problems: List<String>,
	@field:Schema(description = "What to know before confirming; no obstacle to the restore", requiredMode = RequiredMode.REQUIRED)
	val warnings: List<String>,
)

@Schema(description = "A completed restore")
data class RestoreResultResponse(
	@field:Schema(description = "Records restored per data name", requiredMode = RequiredMode.REQUIRED)
	val counts: Map<String, Long>,
	@field:Schema(
		description = "Backup of the replaced data, relative to the data directory",
		example = "pre-restore/cockpit-backup-2026-10-20T1530Z.zip",
		requiredMode = RequiredMode.REQUIRED,
	)
	val preRestoreBackup: String,
)

private fun RestoreReport.toResponse() = RestoreReportResponse(id, valid, formatVersion, appVersion, createdAt, counts, hasSecrets, problems, warnings)

private fun RestoreResult.toResponse() = RestoreResultResponse(counts, preRestoreBackup)

private const val ZIP = "application/zip"
private const val OCTET_STREAM = MediaType.APPLICATION_OCTET_STREAM_VALUE
private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
