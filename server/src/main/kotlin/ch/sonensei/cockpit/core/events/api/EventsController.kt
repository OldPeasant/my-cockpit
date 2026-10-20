package ch.sonensei.cockpit.core.events.api

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.infra.SseEventHub
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/** Change notifications for connected clients as Server-Sent Events (SPEC.md §2). */
@RestController
@Tag(name = "Events", description = "Change notifications (Server-Sent Events)")
class EventsController(
	private val hub: SseEventHub,
) {
	@GetMapping(PATH, produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	@Operation(
		operationId = "streamEvents",
		summary = "Stream change notifications",
		description =
			"A Server-Sent Events stream that stays open. Each committed change arrives as an event named `" +
				SseEventHub.EVENT_NAME + "` whose data is a `ChangeEvent` JSON object; clients refetch the item. " +
				"Comment lines (`:heartbeat`) arrive every 25 seconds and carry no data. Events are not replayed: " +
				"after a reconnect, clients refetch what they show.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The event stream; each event's data is a ChangeEvent",
		content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(implementation = ChangeEvent::class))],
	)
	@ApiResponse(responseCode = "401", description = "Not logged in", content = [Content()])
	fun stream(): SseEmitter = hub.subscribe()

	companion object {
		const val PATH = "/api/v1/events"
	}
}
