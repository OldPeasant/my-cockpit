package ch.sonensei.cockpit.backup.api

import ch.sonensei.cockpit.backup.domain.BackupJob
import ch.sonensei.cockpit.backup.domain.BackupJobService
import ch.sonensei.cockpit.backup.domain.BackupJobState
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.Instant
import java.util.UUID

/** Backups on demand (SPEC.md §10.1): start, follow, download. */
@RestController
@RequestMapping("/api/v1/backup")
@Tag(name = "Backup", description = "Create and download backups")
class BackupController(
	private val jobs: BackupJobService,
) {
	@PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "startBackup",
		summary = "Start a backup",
		description =
			"Builds the backup zip in the background. Progress arrives on `GET /api/v1/events` as change events of " +
				"type `BACKUP` with the job's id; refetch the job on each. When its state is `COMPLETED`, download the " +
				"zip. Starting a new backup discards earlier finished ones.",
	)
	@ApiResponse(responseCode = "202", description = "Started; `Location` points to the job")
	@ApiResponse(
		responseCode = "409",
		description = "Another backup is running",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun start(): ResponseEntity<BackupJobResponse> {
		val job = jobs.start()
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(job.id)
				.toUri()
		return ResponseEntity.accepted().location(location).body(job.toResponse())
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "getBackup", summary = "State and progress of a backup")
	@ApiResponse(responseCode = "200", description = "The backup job")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid id",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such backup (unknown, or discarded by a newer one)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun get(
		@Parameter(description = "Id of the backup job") @PathVariable id: UUID,
	): BackupJobResponse = jobs.get(id).toResponse()

	@GetMapping("/{id}/download", produces = [ZIP, PROBLEM_JSON])
	@Operation(
		operationId = "downloadBackup",
		summary = "Download a completed backup",
		description =
			"The zip (format version 1): `README.md`, `manifest.json` (formatVersion, appVersion, createdAt, counts, " +
				"sha256 per file), `data/` (canonical JSON, used for restore), `files/` (blobs), `readable/` (mirrors).",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The backup zip",
		content = [Content(mediaType = ZIP, schema = Schema(type = "string", format = "binary"))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid id",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such backup",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The backup is still running or failed",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun download(
		@Parameter(description = "Id of the backup job") @PathVariable id: UUID,
	): ResponseEntity<Resource> {
		val (job, zip) = jobs.zip(id)
		return ResponseEntity
			.ok()
			.contentType(MediaType.parseMediaType(ZIP))
			.header(
				HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition
					.attachment()
					.filename(job.fileName!!)
					.build()
					.toString(),
			).body(FileSystemResource(zip))
	}
}

@Schema(description = "A backup job")
data class BackupJobResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val state: BackupJobState,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val createdAt: Instant,
	@field:Schema(description = "What the backup is doing, e.g. `gtd: data`", requiredMode = RequiredMode.REQUIRED)
	val step: String,
	@field:Schema(description = "Steps finished", requiredMode = RequiredMode.REQUIRED)
	val stepsDone: Int,
	@field:Schema(description = "Steps in total", requiredMode = RequiredMode.REQUIRED)
	val stepsTotal: Int,
	@field:Schema(description = "When the backup completed or failed")
	val finishedAt: Instant? = null,
	@field:Schema(description = "File name of the zip, once completed", example = "cockpit-backup-2026-10-20T1530Z.zip")
	val fileName: String? = null,
	@field:Schema(description = "Size of the zip in bytes, once completed")
	val size: Long? = null,
)

private fun BackupJob.toResponse() =
	BackupJobResponse(
		id = id,
		state = state,
		createdAt = createdAt,
		step = step,
		stepsDone = stepsDone,
		stepsTotal = stepsTotal,
		finishedAt = finishedAt,
		fileName = fileName,
		size = size,
	)

private const val ZIP = "application/zip"
private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
