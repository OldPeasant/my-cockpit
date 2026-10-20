package ch.sonensei.cockpit.core.items.api

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkEnd
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.domain.ResolvedLink
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.Instant
import java.util.UUID

/** Links between items of any module (SPEC.md §4.3), for the "Linked items" panel of every detail view. */
@RestController
@RequestMapping("/api/v1/links")
@Tag(name = "Links", description = "Links between items of any type")
class LinkController(
	private val links: LinkService,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listLinks",
		summary = "The links of an item, in both directions",
		description =
			"Each linked item appears once: when several links connect the two items (a manual link, inline links " +
				"in either direction), the manual one is returned, else the oldest. Oldest first by the returned link. " +
				"Each end is resolved by its module; an end whose item no longer exists or whose type has no resolver " +
				"yet is returned with `available: false` and only its type and id.",
	)
	@ApiResponse(responseCode = "200", description = "The item's links (empty when it has none or does not exist)")
	@ApiResponse(
		responseCode = "400",
		description = "Missing or invalid type or id",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun list(
		@Parameter(description = "Type of the item") @RequestParam type: ItemType,
		@Parameter(description = "Id of the item") @RequestParam id: UUID,
	): LinkListResponse {
		val item = ItemRef(type, id)
		return LinkListResponse(links.linksOf(item).map { it.toResponse(item) })
	}

	@PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "createLink",
		summary = "Link two items",
		description =
			"The link is shown on both items. Linking an item to itself, to an item that does not exist, or a pair that " +
				"already has a manual link (in either direction) is rejected; inline links of the pair do not block it.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Created; the response is the link as seen from the source",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = LinkResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid request, self-link, or an end that does not exist",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The items already have a manual link",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun create(
		@Valid @RequestBody request: CreateLinkRequest,
	): ResponseEntity<LinkResponse> {
		val source = request.source!!.toRef()
		val created = links.create(source, request.target!!.toRef(), request.label)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(created.link.id)
				.toUri()
		return ResponseEntity.created(location).body(created.toResponse(source))
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "deleteLink",
		summary = "Remove a manual link",
		description =
			"Removes a link with origin `MANUAL` from both items. A link with origin `SYNC` (e.g. an inline link in a " +
				"note) is rejected with 409: the next save of its source would recreate it, so it is removed by editing " +
				"the source. Links never change, so no `If-Match` is needed.",
	)
	@ApiResponse(responseCode = "204", description = "Removed")
	@ApiResponse(
		responseCode = "404",
		description = "No such link",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The link has origin `SYNC`; remove it in its source",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun delete(
		@PathVariable id: UUID,
	) = links.delete(id)
}

// Nullable fields so a missing value reaches Bean Validation (400 with field errors) instead of failing in Jackson.
@Schema(description = "Reference to an item of any type")
data class ItemRefDto(
	@field:NotNull
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val type: ItemType?,
	@field:NotNull
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID?,
) {
	fun toRef(): ItemRef = ItemRef(type!!, id!!)
}

class CreateLinkRequest(
	@field:NotNull
	@field:Valid
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val source: ItemRefDto?,
	@field:NotNull
	@field:Valid
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val target: ItemRefDto?,
	@field:Size(max = LinkService.MAX_LABEL_LENGTH)
	@field:Schema(description = "Optional short label, e.g. \"origin\"", maxLength = LinkService.MAX_LABEL_LENGTH)
	val label: String? = null,
)

enum class LinkDirection {
	/** The queried item is the link's source. */
	OUTGOING,

	/** The queried item is the link's target. */
	INCOMING,
}

@Schema(description = "One end of a link. Without `available`, only type and id are set.")
data class LinkEndResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val type: ItemType,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(
		description = "False when the item no longer exists or its type has no resolver",
		requiredMode = RequiredMode.REQUIRED,
	)
	val available: Boolean,
	val title: String? = null,
	val subtitle: String? = null,
	@field:Schema(description = "Material Symbols icon name")
	val icon: String? = null,
	@field:Schema(description = "Client route of the item's detail view, e.g. /tasks/{id}")
	val deepLink: String? = null,
)

data class LinkResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val source: LinkEndResponse,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val target: LinkEndResponse,
	@field:Schema(description = "Direction relative to the queried item", requiredMode = RequiredMode.REQUIRED)
	val direction: LinkDirection,
	val label: String? = null,
	@field:Schema(
		description =
			"MANUAL: created via this API, removable with DELETE. SYNC: owned by its source (e.g. an inline link in a " +
				"note), removed by editing the source",
		requiredMode = RequiredMode.REQUIRED,
	)
	val origin: LinkOrigin,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val createdAt: Instant,
)

data class LinkListResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val items: List<LinkResponse>,
)

private fun ResolvedLink.toResponse(perspective: ItemRef) =
	LinkResponse(
		id = link.id,
		source = source.toResponse(),
		target = target.toResponse(),
		direction = if (link.source == perspective) LinkDirection.OUTGOING else LinkDirection.INCOMING,
		label = link.label,
		origin = link.origin,
		createdAt = checkNotNull(link.createdAt),
	)

private fun LinkEnd.toResponse() =
	LinkEndResponse(
		type = ref.type,
		id = ref.id,
		available = available,
		title = item?.title,
		subtitle = item?.subtitle,
		icon = item?.icon,
		deepLink = item?.deepLink,
	)

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
