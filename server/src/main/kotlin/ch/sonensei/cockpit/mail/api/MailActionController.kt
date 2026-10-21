package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Triage actions on messages (SPEC.md §7.3, §7.4). */
@RestController
@RequestMapping("/api/v1/mail")
@Tag(name = "Mail messages")
class MailActionController(
	private val actions: MailActionService,
) {
	@PostMapping("/messages/actions", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "applyMailAction",
		summary = "Mark, flag, move, archive, delete or report messages as spam",
		description =
			"Applies `action` on the mail server and in the cache. `MOVE` needs `targetFolderId` (a folder of the " +
				"messages' account); `ARCHIVE`, `DELETE` and `SPAM` move to the account's Archive, Trash or Spam " +
				"folder; `DELETE` of a message in Trash deletes it permanently. A moved message keeps its id when " +
				"the server reports its new UID. Each id succeeds or fails on its own: the response has one result " +
				"per distinct id in request order, with an `errorCode` for a failure (`NOT_FOUND`, " +
				"`NO_ROLE_FOLDER`, `OTHER_ACCOUNT`, `REJECTED` or a mail server error such as `CONNECTION_FAILED` " +
				"or `POOL_EXHAUSTED`).",
	)
	@ApiResponse(responseCode = "200", description = "One result per id; also when some or all of them failed")
	@ApiResponse(
		responseCode = "400",
		description = "No or too many ids, unknown action, `targetFolderId` missing for MOVE or given for another action",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such target folder",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun apply(
		@Valid @RequestBody request: MailActionRequest,
	): MailActionResponse {
		val ids = checkNotNull(request.ids)
		if (ids.any { it == null }) throw InvalidRequestException("ids", "must not contain null")
		val outcomes = actions.apply(ids.filterNotNull(), checkNotNull(request.action), request.targetFolderId)
		return MailActionResponse(outcomes.map { MailActionResultResponse(it.id, it.ok, it.errorCode, it.detail) })
	}

	private companion object {
		const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		const val PROBLEM = "#/components/schemas/ProblemDetail"
	}
}

// Nullable fields so a missing value reaches Bean Validation (400 with field errors) instead of failing in Jackson.
class MailActionRequest(
	@field:NotEmpty
	@field:Size(max = MAX_IDS)
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "Message ids, at most $MAX_IDS")
	val ids: List<UUID?>?,
	@field:NotNull
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val action: MailAction?,
	@field:Schema(description = "Required for MOVE, not allowed for other actions")
	val targetFolderId: UUID? = null,
) {
	companion object {
		const val MAX_IDS = 1000
	}
}

data class MailActionResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "One result per distinct requested id, in request order")
	val results: List<MailActionResultResponse>,
)

data class MailActionResultResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "The requested id")
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val ok: Boolean,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, types = ["string", "null"], description = "Why it failed; `null` on success")
	val errorCode: String?,
	@field:Schema(
		requiredMode = RequiredMode.REQUIRED,
		types = ["string", "null"],
		description = "A message for the failure, safe to show; `null` on success",
	)
	val detail: String?,
)
