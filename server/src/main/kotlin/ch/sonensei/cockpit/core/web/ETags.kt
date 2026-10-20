package ch.sonensei.cockpit.core.web

import org.springframework.http.ResponseEntity

/**
 * Optimistic locking over HTTP (SPEC.md §4.2): an entity's `version` is its strong `ETag`
 * (`"<version>"`); PATCH and DELETE must send it back in `If-Match`.
 *
 * ```
 * @GetMapping("/{id}") fun get(@PathVariable id: UUID) = service.get(id).let { ETags.ok(it, it.version) }
 * @PatchMapping("/{id}") fun patch(@PathVariable id: UUID, @RequestHeader(IF_MATCH, required = false) ifMatch: String?, ...) {
 *     val current = service.get(id)
 *     ETags.checkIfMatch(ifMatch, current.version)   // 428 when missing, 412 when stale
 *     ...
 * }
 * ```
 */
object ETags {
	/** The `ETag` header value for [version]. */
	fun of(version: Long): String = "\"$version\""

	/** `200 OK` with [body] and the `ETag` of [version]. */
	fun <T : Any> ok(
		body: T,
		version: Long,
	): ResponseEntity<T> = ResponseEntity.ok().eTag(of(version)).body(body)

	/**
	 * Checks an `If-Match` header against the current [version].
	 *
	 * @throws PreconditionRequiredException (428) when the header is missing or blank
	 * @throws PreconditionFailedException (412) when no listed tag matches (`*` matches any version)
	 */
	fun checkIfMatch(
		ifMatch: String?,
		version: Long,
	) {
		if (ifMatch.isNullOrBlank()) throw PreconditionRequiredException()
		val tags = ifMatch.split(',').map { it.trim() }
		// RFC 9110 §13.1.1: If-Match uses strong comparison, so weak tags (W/"...") never match.
		if (tags.none { it == "*" || it == of(version) }) throw PreconditionFailedException()
	}
}
