package ch.sonensei.cockpit.core.events.domain

/**
 * A message for the connected clients that is not a [ChangeEvent], e.g. a fired reminder: sent over
 * `GET /api/v1/events` as SSE event [name] with [data] as JSON. Unlike a change event it may carry display data (such as
 * an item title), never note or mail bodies.
 *
 * Publish it as a Spring application event (e.g. with `ApplicationEventPublisher`); like a [ChangeEvent] it reaches
 * clients only after the publishing transaction commits.
 */
data class ClientNotification(
	val name: String,
	val data: Any,
) {
	init {
		require(name.isNotBlank() && name != "change") { "name must be set and must not be the change event's" }
	}
}
