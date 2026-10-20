package ch.sonensei.cockpit.gtd.api

import ch.sonensei.cockpit.gtd.domain.CaptureCommand
import ch.sonensei.cockpit.gtd.domain.ParsedCapture
import ch.sonensei.cockpit.gtd.domain.TaskList
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.util.UUID

@Schema(
	description =
		"A quick capture. The first line of `text` becomes the title, further lines go in front of `notes`. " +
			"With `parse` (default true) the capture syntax is applied, see `POST /capture/preview`.",
)
data class CaptureRequest(
	@get:Schema(description = "Client-generated UUIDv7 for an idempotent capture (offline queue)")
	val id: UUID? = null,
	@field:NotNull
	@field:Size(max = MAX_CAPTURE_TEXT_LENGTH)
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = MAX_CAPTURE_TEXT_LENGTH)
	val text: String? = null,
	@get:Schema(description = "Markdown notes of the task")
	val notes: String? = null,
	@field:Size(max = MAX_CAPTURE_FILES)
	@get:Schema(description = "Uploaded files to link to the task (at most 50)")
	val fileIds: List<UUID>? = null,
	@get:Schema(description = "Apply the capture syntax (default true); false keeps the text as it is")
	val parse: Boolean? = null,
) {
	fun toCommand() = CaptureCommand(id, checkNotNull(text), notes, fileIds.orEmpty(), parse ?: true)
}

@Schema(description = "A text to preview the capture syntax of")
data class CapturePreviewRequest(
	@field:NotNull
	@field:Size(max = MAX_CAPTURE_TEXT_LENGTH)
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = MAX_CAPTURE_TEXT_LENGTH)
	val text: String? = null,
)

@Schema(description = "A project named with `#`")
data class CaptureProjectResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val title: String,
)

@Schema(description = "A context named with `@`")
data class CaptureContextResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
)

@Schema(
	description =
		"What a capture of the text creates. Syntax (a token starts the text or follows whitespace): `#project` an " +
			"existing project by title (case-insensitive, `-`/`_` for spaces), `@context` an existing context, " +
			"`!today`, `!tomorrow` or `!YYYY-MM-DD` the due date, `^waiting` the WAITING list (`^name`: waiting for name). " +
			"Recognized tokens are removed from the title; unknown ones stay as text.",
)
data class CapturePreviewResponse(
	@get:Schema(description = "The task title; empty when the text has none", requiredMode = RequiredMode.REQUIRED)
	val title: String,
	@get:Schema(description = "The rest of the text, put in front of the notes")
	val extraNotes: String?,
	@get:Schema(description = "INBOX, or WAITING with a `^` token", requiredMode = RequiredMode.REQUIRED)
	val list: TaskList,
	val project: CaptureProjectResponse?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val contexts: List<CaptureContextResponse>,
	@get:Schema(description = "Local date in the user's time zone") val dueDate: LocalDate?,
	@get:Schema(description = "Free text of `^name`") val waitingFor: String?,
) {
	companion object {
		fun of(parsed: ParsedCapture) =
			CapturePreviewResponse(
				title = parsed.title,
				extraNotes = parsed.extraNotes,
				list = parsed.list,
				project = parsed.project?.let { CaptureProjectResponse(it.id, it.title) },
				contexts = parsed.contexts.map { CaptureContextResponse(it.id, it.name) },
				dueDate = parsed.dueDate,
				waitingFor = parsed.waitingFor,
			)
	}
}

/** Longer texts are rejected; the notes have their own limit (the task's). */
const val MAX_CAPTURE_TEXT_LENGTH = 100_000

/** Files per capture. */
const val MAX_CAPTURE_FILES = 50
