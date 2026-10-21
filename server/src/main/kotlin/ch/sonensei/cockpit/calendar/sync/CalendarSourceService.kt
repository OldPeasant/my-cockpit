package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

/** What a new external source is made of; which fields apply depends on [type]. */
data class NewCalendarSource(
	val type: CalendarSourceType,
	val name: String? = null,
	val url: String? = null,
	val username: String? = null,
	val password: String? = null,
	val oauthConnectionId: UUID? = null,
)

/** The created (or, for an already linked OAuth connection, the existing) source. */
data class CreatedCalendarSource(
	val source: CalendarSource,
	val created: Boolean,
)

/**
 * Adds external calendar sources (SPEC.md §6.1): CalDAV accounts (URL, user, password stored encrypted), ICS feeds
 * (URL) and Google/Microsoft accounts (an OAuth connection of that provider). The config keys match the adapters'
 * (`url`, `username`, `connectionId`). The scheduler syncs a new source at its next tick.
 */
@Service
class CalendarSourceService(
	private val sources: CalendarSourceRepository,
	private val tokens: OAuthTokenProvider,
	private val changes: ChangeEventPublisher,
	private val json: JsonMapper,
) {
	@Transactional
	fun create(request: NewCalendarSource): CreatedCalendarSource {
		val errors = mutableListOf<FieldError>()
		val name = request.name?.trim()?.takeIf { it.isNotEmpty() }
		if (name != null && name.length > MAX_NAME_LENGTH) errors += FieldError("name", "must be at most $MAX_NAME_LENGTH characters")
		val config = json.createObjectNode()
		var secret: String? = null
		var defaultName: String? = null
		when (request.type) {
			CalendarSourceType.LOCAL -> errors += FieldError("type", "local calendars are created with POST /api/v1/calendar/calendars")
			CalendarSourceType.CALDAV -> {
				val url = checkUrl(request.url, setOf("http", "https"), errors)
				val username = request.username?.trim()?.takeIf { it.isNotEmpty() }
				if (username == null) errors += FieldError("username", "is required for CALDAV sources")
				if (request.password.isNullOrEmpty()) errors += FieldError("password", "is required for CALDAV sources")
				if (request.oauthConnectionId != null) errors += FieldError("oauthConnectionId", "must be empty for CALDAV sources")
				url?.let { config.put(URL, it.toString()) }
				username?.let { config.put(USERNAME, it) }
				secret = request.password
				defaultName = url?.host
			}
			CalendarSourceType.ICS_FEED -> {
				val url = checkUrl(request.url, setOf("http", "https", "webcal", "webcals"), errors)
				if (request.username != null) errors += FieldError("username", "must be empty for ICS_FEED sources")
				if (request.password != null) errors += FieldError("password", "must be empty for ICS_FEED sources")
				if (request.oauthConnectionId != null) errors += FieldError("oauthConnectionId", "must be empty for ICS_FEED sources")
				url?.let { config.put(URL, it.toString()) }
				defaultName = url?.host
			}
			CalendarSourceType.GOOGLE, CalendarSourceType.MICROSOFT -> {
				val provider = if (request.type == CalendarSourceType.GOOGLE) OAuthProvider.GOOGLE else OAuthProvider.MICROSOFT
				if (request.url != null) errors += FieldError("url", "must be empty for ${request.type} sources")
				if (request.username != null) errors += FieldError("username", "must be empty for ${request.type} sources")
				if (request.password != null) errors += FieldError("password", "must be empty for ${request.type} sources")
				val connection = request.oauthConnectionId?.let(tokens::connection)
				when {
					request.oauthConnectionId == null -> errors += FieldError("oauthConnectionId", "is required for ${request.type} sources")
					connection == null || connection.provider != provider ->
						errors += FieldError("oauthConnectionId", "is not a $provider connection")
					else -> {
						if (errors.isEmpty()) linked(request.type, connection.id)?.let { return CreatedCalendarSource(it, created = false) }
						config.put(CONNECTION_ID, connection.id.toString())
						defaultName = connection.email ?: connection.displayName ?: provider.name.lowercase().replaceFirstChar { it.uppercase() }
					}
				}
			}
		}
		if (errors.isNotEmpty()) throw InvalidRequestException(errors.sortedBy { it.field })
		val source =
			CalendarSource(request.type, name ?: defaultName!!.take(MAX_NAME_LENGTH)).apply {
				this.config = json.writeValueAsString(config)
				credentials = secret?.let(::EncryptedSecret)
			}
		val saved = sources.saveAndFlush(source)
		changes.publish(CalendarSyncService.SOURCE_CHANGE_TYPE, saved.id, ChangeOp.CREATED)
		return CreatedCalendarSource(saved, created = true)
	}

	/** The source of [type] already using OAuth connection [connectionId], so connecting an account twice adds it once. */
	private fun linked(
		type: CalendarSourceType,
		connectionId: UUID,
	): CalendarSource? =
		sources.findAllByOrderByIdAsc().firstOrNull { source ->
			source.type == type &&
				runCatching { json.readTree(source.config).get(CONNECTION_ID)?.asString() }.getOrNull() == connectionId.toString()
		}

	private fun checkUrl(
		value: String?,
		schemes: Set<String>,
		errors: MutableList<FieldError>,
	): URI? {
		val text = value?.trim()?.takeIf { it.isNotEmpty() }
		if (text == null) {
			errors += FieldError("url", "is required")
			return null
		}
		val uri =
			try {
				URI(text)
			} catch (e: URISyntaxException) {
				null
			}
		if (uri == null || uri.host == null || uri.scheme?.lowercase() !in schemes) {
			errors += FieldError("url", "must be an absolute ${schemes.joinToString("/")} URL")
			return null
		}
		if (uri.rawUserInfo != null) {
			errors += FieldError("url", "must not contain credentials")
			return null
		}
		return uri
	}

	companion object {
		/** Config keys, as read by the CalDAV, ICS, Google and Microsoft adapters. */
		const val URL = "url"
		const val USERNAME = "username"
		const val CONNECTION_ID = "connectionId"

		const val MAX_NAME_LENGTH = 200
	}
}
