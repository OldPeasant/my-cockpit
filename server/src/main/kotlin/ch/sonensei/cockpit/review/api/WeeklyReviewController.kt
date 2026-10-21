package ch.sonensei.cockpit.review.api

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.gtd.api.ProjectResponse
import ch.sonensei.cockpit.gtd.api.TaskResponse
import ch.sonensei.cockpit.review.domain.ReviewDataService
import ch.sonensei.cockpit.review.domain.WeeklyReview
import ch.sonensei.cockpit.review.domain.WeeklyReviewService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/**
 * The weekly review wizard (SPEC.md §8.6): a resumable review with its current step, and the data each step shows.
 * Review responses carry the review's `ETag`; moving and completing need it in `If-Match`. The step data endpoints
 * (`/steps/...`) always return the current data; the client acts on the items through the GTD and calendar APIs.
 */
@RestController
@RequestMapping("/api/v1/weekly-reviews")
@Tag(name = "Weekly review", description = "The resumable weekly review and the data of its steps")
class WeeklyReviewController(
	private val reviews: WeeklyReviewService,
	private val data: ReviewDataService,
) {
	@GetMapping("/status", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "getWeeklyReviewStatus",
		summary = "Open review and last completion",
		description = "The open review (to resume it), and when the last review was completed (\"Last weekly review: N days ago\").",
	)
	@ApiResponse(responseCode = "200", description = "The status")
	fun status(): WeeklyReviewStatusResponse {
		val status = reviews.status()
		return WeeklyReviewStatusResponse(status.current?.let(WeeklyReviewResponse::of), status.lastCompletedAt, status.daysSinceLast)
	}

	@PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "startWeeklyReview",
		summary = "Start or resume a weekly review",
		description = "Starts a review at its first step (201), or returns the open review unchanged (200): at most one is open.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Started",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the review, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = WeeklyReviewResponse::class))],
	)
	@ReviewOkResponse
	fun start(): ResponseEntity<WeeklyReviewResponse> {
		val started = reviews.start()
		if (!started.created) return respond(started.review)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(started.review.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(started.review.version))
			.body(WeeklyReviewResponse.of(started.review))
	}

	@GetMapping("/current", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getCurrentWeeklyReview", summary = "The open review")
	@ReviewOkResponse
	@ReviewNotFoundResponse
	fun current(): ResponseEntity<WeeklyReviewResponse> = respond(reviews.current())

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getWeeklyReview", summary = "One review")
	@ReviewOkResponse
	@ReviewNotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<WeeklyReviewResponse> = respond(reviews.get(id))

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "moveWeeklyReview",
		summary = "Save the step of the open review",
		description = "Any step, also an earlier one; a completed review cannot change (409).",
	)
	@ReviewOkResponse
	@ReviewBadRequestResponse
	@ReviewNotFoundResponse
	@ReviewIfMatchResponses
	fun move(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the review") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: MoveWeeklyReviewRequest,
	): ResponseEntity<WeeklyReviewResponse> {
		val step = body.step ?: throw InvalidRequestException("step", "must not be null")
		return respond(reviews.moveTo(id, step) { ETags.checkIfMatch(ifMatch, it) })
	}

	@PostMapping("/{id}/complete", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "completeWeeklyReview",
		summary = "Complete the open review",
		description = "Afterwards a new review can be started; completing a completed review is a 409.",
	)
	@ReviewOkResponse
	@ReviewNotFoundResponse
	@ReviewIfMatchResponses
	fun complete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the review") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<WeeklyReviewResponse> = respond(reviews.complete(id) { ETags.checkIfMatch(ifMatch, it) })

	@GetMapping("/steps/past-events", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "getReviewPastEvents",
		summary = "Step 2: the past calendar",
		description = "Occurrences of the 7 days before today (visible calendars), by start.",
	)
	@ApiResponse(responseCode = "200", description = "The occurrences")
	fun pastEvents(): ReviewEventsResponse = ReviewEventsResponse.of(data.pastEvents())

	@GetMapping("/steps/upcoming-events", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "getReviewUpcomingEvents",
		summary = "Step 3: the upcoming calendar",
		description = "Occurrences of today and the 13 days after it (visible calendars), by start.",
	)
	@ApiResponse(responseCode = "200", description = "The occurrences")
	fun upcomingEvents(): ReviewEventsResponse = ReviewEventsResponse.of(data.upcomingEvents())

	@GetMapping("/steps/waiting-for", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "getReviewWaitingFor",
		summary = "Step 4: Waiting For",
		description = "Open Waiting For tasks, waiting longest first (tasks without `waitingSince` last).",
	)
	@ApiResponse(responseCode = "200", description = "The tasks")
	fun waitingFor(): ReviewTaskListResponse = ReviewTaskListResponse(data.waitingFor().map(TaskResponse::of))

	@GetMapping("/steps/projects", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "getReviewProjects",
		summary = "Step 5: projects",
		description = "Active projects, stalled ones first, each group by title.",
	)
	@ApiResponse(responseCode = "200", description = "The projects")
	fun projects(): ReviewProjectListResponse = ReviewProjectListResponse(data.projects().map(ProjectResponse::of))

	@GetMapping("/steps/stale-next-actions", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "getReviewStaleNextActions",
		summary = "Step 6: stale next actions",
		description = "Open Next actions not changed for more than `olderThanDays` days, least recently changed first.",
	)
	@ApiResponse(responseCode = "200", description = "The tasks")
	@ReviewBadRequestResponse
	fun staleNextActions(
		@Parameter(description = "Days without a change (1 to 3650)")
		@RequestParam(required = false, defaultValue = "${ReviewDataService.STALE_DAYS}")
		olderThanDays: Int,
	): ReviewTaskListResponse {
		if (olderThanDays !in 1..ReviewDataService.MAX_STALE_DAYS) {
			throw InvalidRequestException("olderThanDays", "must be between 1 and ${ReviewDataService.MAX_STALE_DAYS}")
		}
		return ReviewTaskListResponse(data.staleNextActions(olderThanDays).map(TaskResponse::of))
	}

	@GetMapping("/steps/someday", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "getReviewSomeday",
		summary = "Step 7: Someday/Maybe",
		description = "Tasks on the Someday list and projects with status SOMEDAY.",
	)
	@ApiResponse(responseCode = "200", description = "The tasks and projects")
	fun someday(): ReviewSomedayResponse = ReviewSomedayResponse.of(data.someday())

	private fun respond(review: WeeklyReview): ResponseEntity<WeeklyReviewResponse> = ETags.ok(WeeklyReviewResponse.of(review), review.version)
}

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

/** `200` with a review and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The review with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the review, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = WeeklyReviewResponse::class))],
)
annotation class ReviewOkResponse

/** `400` Problem Details response of the weekly review endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "400",
	description = "Invalid request",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class ReviewBadRequestResponse

/** `404` Problem Details response of the weekly review endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "404",
	description = "No such review",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class ReviewNotFoundResponse

/** The responses (409, 412, 428) of a change that requires `If-Match`. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "409",
	description = "Already completed, or changed concurrently",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "412",
	description = "If-Match does not match the current ETag",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "428",
	description = "If-Match is missing",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class ReviewIfMatchResponses
