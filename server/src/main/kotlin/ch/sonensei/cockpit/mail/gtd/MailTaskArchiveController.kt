package ch.sonensei.cockpit.mail.gtd

import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.gtd.domain.TaskList
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Mail → Next action & archive (SPEC.md §7.3, §7.4): the GTD clarify step for one or more mails in one call. */
@RestController
@RequestMapping("/api/v1/mail/messages")
@Tag(name = "Mail messages")
class MailTaskArchiveController(
	private val service: MailGtdService,
) {
	@PostMapping("/to-tasks", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "mailToTasks",
		summary = "Create a task per message (Next by default) and archive the messages",
		description =
			"Per message a task in `list` (`NEXT` when omitted; `INBOX` and `SOMEDAY` are allowed too), titled with the " +
				"subject and linked to the message as `to-task` does; then, unless `archive` is false, the messages move " +
				"to their account's Archive folder like the `ARCHIVE` action. Every id is checked first: an unknown id " +
				"fails the request with 404 and creates nothing. Archiving is per message, after the tasks exist: a " +
				"failed archive keeps its task and is reported in its result (`archiveErrorCode` as in the actions " +
				"endpoint, e.g. `NO_ROLE_FOLDER`). The link keeps resolving to the message after the move. Ids of the " +
				"same message get one task; there is one result per distinct id, in request order.",
	)
	@ApiResponse(responseCode = "200", description = "One result per distinct id; also when archiving failed for some or all")
	@ApiResponse(
		responseCode = "400",
		description = "No, too many or malformed ids, or a list other than NEXT, INBOX or SOMEDAY",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "A message does not exist or is no longer on the server; nothing was created",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun toTasks(
		@Valid @RequestBody request: MailToTasksRequest,
	): MailToTasksResponse {
		val ids = checkNotNull(request.ids)
		if (ids.any { it == null }) throw InvalidRequestException("ids", "must not contain null")
		val results = service.toTasks(ids.filterNotNull(), request.list ?: TaskList.NEXT, request.archive ?: true)
		return MailToTasksResponse(
			results.map { result ->
				MailToTaskResultResponse(
					id = result.requestedId,
					messageId = result.task.messageId,
					task = MailCreatedTaskResponse(result.task.item.id, result.task.title, result.list),
					linkId = result.task.linkId,
					archived = result.archive?.ok ?: false,
					archiveErrorCode = result.archive?.errorCode,
					archiveDetail = result.archive?.detail,
				)
			},
		)
	}

	private companion object {
		const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		const val PROBLEM = "#/components/schemas/ProblemDetail"
	}
}

// Nullable fields so a missing value reaches Bean Validation (400 with field errors) instead of failing in Jackson.
class MailToTasksRequest(
	@field:NotEmpty
	@field:Size(max = MAX_IDS)
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "Message ids, at most $MAX_IDS")
	val ids: List<UUID?>?,
	@field:Schema(description = "The list of the new tasks: NEXT (default), INBOX or SOMEDAY")
	val list: TaskList? = null,
	@field:Schema(description = "Archive the messages after creating the tasks (default true)")
	val archive: Boolean? = null,
) {
	companion object {
		const val MAX_IDS = 100
	}
}

data class MailToTasksResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "One result per distinct requested id, in request order")
	val results: List<MailToTaskResultResponse>,
)

data class MailToTaskResultResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "The requested id")
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "The message linked (its current copy)")
	val messageId: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val task: MailCreatedTaskResponse,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "The link between the task and the message")
	val linkId: UUID,
	@field:Schema(
		requiredMode = RequiredMode.REQUIRED,
		description = "Whether the message is in the Archive now; false when not asked to archive",
	)
	val archived: Boolean,
	@field:Schema(
		requiredMode = RequiredMode.REQUIRED,
		types = ["string", "null"],
		description = "Why archiving failed (`NO_ROLE_FOLDER`, `NOT_FOUND`, `REJECTED` or a mail server error); `null` otherwise",
	)
	val archiveErrorCode: String?,
	@field:Schema(
		requiredMode = RequiredMode.REQUIRED,
		types = ["string", "null"],
		description = "A message for the archive failure, safe to show; `null` otherwise",
	)
	val archiveDetail: String?,
)

data class MailCreatedTaskResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val title: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val list: TaskList,
)
