package ch.sonensei.cockpit.mail.search

import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.mail.api.MailMessageSummaryResponse
import ch.sonensei.cockpit.mail.api.toSummary
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Mail search (SPEC.md §7.3, §7.4): over cached headers and bodies, optionally on the mail server too. */
@RestController
@RequestMapping("/api/v1/mail")
@Tag(name = "Mail search", description = "Searching cached mail, and on request the mail servers (IMAP SEARCH)")
class MailSearchController(
	private val search: MailSearchService,
) {
	@GetMapping("/search", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "searchMail",
		summary = "Search mail, newest first",
		description =
			"Every word of `q` must match the start of a word of a cached message's subject, sender, recipients or " +
				"cached body (exact or English-stemmed form). `folderId` searches that folder, `accountId` alone all " +
				"folders of the account, neither all folders of all accounts. With `server=true` the folders are " +
				"also searched on the mail server (IMAP SEARCH: substrings of headers and body, also mail older than " +
				"the sync window); found messages are cached and returned with the cached hits, at most `limit` per " +
				"folder. An account whose server fails is listed in `server.failures`; the search still answers. A " +
				"query without letters or digits finds nothing.",
	)
	@ApiResponse(responseCode = "200", description = "The matching messages, newest first")
	@ApiResponse(
		responseCode = "400",
		description = "Blank or too long `q`, `limit` out of range, or a folder of another account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such account or folder",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun search(
		@Parameter(
			description = "Search text",
			schema = Schema(type = "string", minLength = 1, maxLength = MailSearchService.MAX_QUERY_LENGTH),
		)
		@RequestParam
		q: String,
		@RequestParam(required = false) accountId: UUID?,
		@RequestParam(required = false) folderId: UUID?,
		@Parameter(description = "Also search on the mail server (slower)")
		@RequestParam(required = false, defaultValue = "false")
		server: Boolean,
		@Parameter(
			description = "Maximum number of messages, 1..${MailSearchService.MAX_LIMIT}",
			schema =
				Schema(
					type = "integer",
					format = "int32",
					defaultValue = "${MailSearchService.DEFAULT_LIMIT}",
					minimum = "1",
					maximum = "${MailSearchService.MAX_LIMIT}",
				),
		)
		@RequestParam(required = false)
		limit: Int?,
	): MailSearchResponse {
		val query = q.trim()
		if (query.isEmpty()) throw InvalidRequestException("q", "must not be blank")
		if (query.length > MailSearchService.MAX_QUERY_LENGTH) {
			throw InvalidRequestException("q", "must be at most ${MailSearchService.MAX_QUERY_LENGTH} characters")
		}
		val effectiveLimit = limit ?: MailSearchService.DEFAULT_LIMIT
		if (effectiveLimit !in 1..MailSearchService.MAX_LIMIT) {
			throw InvalidRequestException("limit", "must be between 1 and ${MailSearchService.MAX_LIMIT}")
		}
		val result = search.search(query, MailSearchScope(accountId, folderId), server, effectiveLimit)
		return MailSearchResponse(
			items = result.messages.map { it.toSummary(1, if (it.seen) 0 else 1) },
			hasMore = result.hasMore,
			server =
				result.server?.let { summary ->
					MailServerSearchResponse(
						searchedFolders = summary.searchedFolders,
						loaded = summary.loaded,
						truncated = summary.truncated,
						failures = summary.failures.map { MailServerSearchFailureResponse(it.accountId, it.error, it.error.message) },
					)
				},
		)
	}

	private companion object {
		const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		const val PROBLEM = "#/components/schemas/ProblemDetail"
	}
}

data class MailSearchResponse(
	@field:Schema(description = "Newest (arrival) first")
	val items: List<MailMessageSummaryResponse>,
	@field:Schema(description = "More messages match than `limit`")
	val hasMore: Boolean,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "`null` without `server=true`")
	val server: MailServerSearchResponse?,
)

@Schema(description = "What the search on the mail servers did")
data class MailServerSearchResponse(
	@field:Schema(description = "Folders searched on the server")
	val searchedFolders: Int,
	@field:Schema(description = "Found messages that were not cached and now are")
	val loaded: Int,
	@field:Schema(description = "A folder had more matches than `limit`; only its most recent ones are included")
	val truncated: Boolean,
	@field:Schema(description = "Accounts whose server could not be searched")
	val failures: List<MailServerSearchFailureResponse>,
)

data class MailServerSearchFailureResponse(
	val accountId: UUID,
	val errorCode: MailConnectionError,
	@field:Schema(description = "A fixed message for the error code")
	val message: String,
)
