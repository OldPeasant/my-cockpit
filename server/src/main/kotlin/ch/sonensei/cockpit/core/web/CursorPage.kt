package ch.sonensei.cockpit.core.web

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64

/**
 * One page of a cursor-paginated list (SPEC.md §4.2). [nextCursor] is opaque to clients and is
 * `null` on the last page; clients pass it back as `?cursor=`.
 */
data class CursorPage<T>(
	val items: List<T>,
	val nextCursor: String?,
)

/**
 * Parsed `?cursor=&limit=` parameters. Controllers take both as optional `@RequestParam`s (so they
 * appear in the OpenAPI contract) and call [CursorRequest.of].
 *
 * The cursor is a base64url-encoded string chosen by the module (typically the keyset of the last
 * item, e.g. `"<sortKey>|<id>"`); [position] is the decoded value, `null` for the first page.
 */
data class CursorRequest(
	val position: String?,
	val limit: Int,
) {
	/**
	 * Builds a page from up to `limit + 1` [fetched] rows (fetching one extra row tells whether a
	 * next page exists); [positionOf] gives the keyset of an item for the next cursor.
	 */
	fun <T> page(
		fetched: List<T>,
		positionOf: (T) -> String,
	): CursorPage<T> {
		val items = fetched.take(limit)
		val next = if (fetched.size > limit) encodeCursor(positionOf(items.last())) else null
		return CursorPage(items, next)
	}

	companion object {
		const val DEFAULT_LIMIT = 50
		const val MAX_LIMIT = 200

		/** @throws InvalidRequestException (400) for a malformed cursor or a limit outside 1..[MAX_LIMIT] */
		fun of(
			cursor: String?,
			limit: Int?,
		): CursorRequest {
			val effectiveLimit = limit ?: DEFAULT_LIMIT
			if (effectiveLimit !in 1..MAX_LIMIT) {
				throw InvalidRequestException("limit", "must be between 1 and $MAX_LIMIT")
			}
			val position = cursor?.takeIf { it.isNotEmpty() }?.let(::decodeCursor)
			return CursorRequest(position, effectiveLimit)
		}

		fun encodeCursor(position: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(position.toByteArray(Charsets.UTF_8))

		private fun decodeCursor(cursor: String): String {
			val bytes =
				try {
					Base64.getUrlDecoder().decode(cursor)
				} catch (_: IllegalArgumentException) {
					throw InvalidRequestException("cursor", "is not a valid cursor")
				}
			return try {
				Charsets.UTF_8
					.newDecoder()
					.decode(ByteBuffer.wrap(bytes))
					.toString()
			} catch (_: CharacterCodingException) {
				throw InvalidRequestException("cursor", "is not a valid cursor")
			}
		}
	}
}
