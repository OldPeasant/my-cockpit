package ch.sonensei.cockpit.gtd.api

import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.gtd.domain.Change
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * A PATCH body: [request] has the converted values, [body] tells which fields were sent (absent: unchanged, `null`:
 * cleared).
 */
internal class PatchBody<T>(
	private val body: Map<String, Any?>,
	val request: T,
) {
	operator fun contains(name: String): Boolean = name in body

	/** The change of a field that may be cleared, `null` when it was not sent. */
	fun <V> change(
		name: String,
		value: V,
	): Change<V>? = if (name in body) Change(value) else null

	/** The change of a field that cannot be cleared (checked by [parsePatch]), `null` when it was not sent. */
	fun <V : Any> required(
		name: String,
		value: V?,
	): Change<V>? = if (name in body) Change(checkNotNull(value)) else null
}

/**
 * Parses a PATCH body into [type].
 *
 * @throws InvalidRequestException (400) for fields outside [fields], values of the wrong type, or `null` for one of
 *   [notNullable]
 */
internal fun <T> JsonMapper.parsePatch(
	body: Map<String, Any?>,
	type: Class<T>,
	fields: Set<String>,
	notNullable: Set<String>,
): PatchBody<T> {
	val unknown = body.keys - fields
	if (unknown.isNotEmpty()) {
		throw InvalidRequestException(unknown.sorted().map { FieldError(it, "is not a field that PATCH can change") })
	}
	val request =
		try {
			convertValue(body, type)
		} catch (e: JacksonException) {
			val field = e.path.mapNotNull { it.propertyName }.firstOrNull() ?: "body"
			throw InvalidRequestException(field, "has an invalid value")
		}
	val nulls = notNullable.filter { it in body && body[it] == null }
	if (nulls.isNotEmpty()) throw InvalidRequestException(nulls.map { FieldError(it, "must not be null") })
	return PatchBody(body, request)
}
