package ch.sonensei.cockpit.core.settings.domain

import ch.sonensei.cockpit.core.settings.infra.UserSettingsRepository
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Key-value user settings with typed defaults (SPEC.md §4.7).
 *
 * Every module reads settings here ([get], [timeZone]) and registers its own keys with defaults: as
 * [SettingDefinition] beans (collected at startup) or with [register]. The core keys are in [CoreSettings].
 * Only changed values are stored; a key that was never set, was reset (`null`) or holds a value its definition no
 * longer accepts reads as its default. After a change a [SettingsChangedEvent] is published.
 */
@Service
class SettingsService(
	private val repository: UserSettingsRepository,
	private val jsonMapper: JsonMapper,
	private val events: ApplicationEventPublisher,
	definitionBeans: ObjectProvider<SettingDefinition<*>>,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val definitions = ConcurrentHashMap<String, SettingDefinition<*>>()

	init {
		CoreSettings.ALL.forEach(::register)
		definitionBeans.orderedStream().forEach(::register)
	}

	/**
	 * Registers a setting so that it is returned, validated and stored. Registering the same definition again is a
	 * no-op.
	 *
	 * @throws IllegalArgumentException when another definition already uses the key
	 */
	fun register(definition: SettingDefinition<*>) {
		val existing = definitions.putIfAbsent(definition.key, definition)
		require(existing == null || existing === definition) { "Setting ${definition.key} is already registered" }
	}

	/** All registered settings, sorted by key. */
	fun definitions(): List<SettingDefinition<*>> = definitions.values.sortedBy { it.key }

	/** The current value of a registered setting (its default when unset). */
	@Transactional(readOnly = true)
	fun <T : Any> get(definition: SettingDefinition<T>): T {
		require(definitions[definition.key] === definition) { "Setting ${definition.key} is not registered" }
		return valueOf(definition, readOverrides(repository.findFirstBy()))
	}

	/** The owner's time zone (SPEC.md §4.2), for local dates and daily jobs. */
	fun timeZone(): ZoneId = ZoneId.of(get(CoreSettings.TIME_ZONE))

	/** Every registered setting with its current value, and the version to use as ETag. */
	@Transactional(readOnly = true)
	fun current(): SettingsSnapshot = snapshot(repository.findFirstBy())

	/**
	 * Changes settings: each entry of [changes] sets a key to a new value, `null` resets it to its default. Nothing is
	 * stored unless every key is registered and every value valid. [checkVersion] gets the current version before the
	 * change, to check a client's `If-Match`.
	 *
	 * @throws InvalidRequestException (400) listing every unknown key and invalid value
	 * @throws ConflictException (409) when the settings were created concurrently
	 */
	@Transactional
	fun update(
		changes: Map<String, Any?>,
		checkVersion: (Long) -> Unit = {},
	): SettingsSnapshot {
		val parsed = validate(changes)
		val existing = repository.findFirstBy()
		checkVersion(existing?.version ?: 0)
		val settings = existing ?: create()
		val overrides = readOverrides(settings).toMutableMap()
		val changed = mutableSetOf<String>()
		var dirty = false
		for ((key, value) in parsed) {
			val definition = definitions.getValue(key)
			if (valueOf(definition, overrides) == (value ?: definition.default)) {
				// Resetting a stored value its definition no longer accepts cleans it up without changing the value.
				if (value == null && overrides.containsKey(key)) {
					overrides.remove(key)
					dirty = true
				}
				continue
			}
			if (value == null) overrides.remove(key) else overrides[key] = value
			changed += key
			dirty = true
		}
		// Reassigned only on a real change: Postgres returns jsonb reformatted, so rewriting the same document would
		// still make the entity dirty and bump its version (the ETag).
		if (dirty) settings.overrides = jsonMapper.writeValueAsString(overrides.toSortedMap())
		val saved = repository.saveAndFlush(settings)
		if (changed.isNotEmpty()) {
			log.debug("Settings changed: {}", changed.sorted())
			events.publishEvent(SettingsChangedEvent(changed.toSet()))
		}
		return snapshot(saved)
	}

	private fun validate(changes: Map<String, Any?>): Map<String, Any?> {
		val errors = mutableListOf<FieldError>()
		val parsed = mutableMapOf<String, Any?>()
		for ((key, value) in changes) {
			val definition = definitions[key]
			if (definition == null) {
				errors += FieldError(key, "is not a known setting")
				continue
			}
			val valid = value?.let(definition.type::parse)
			if (value != null && valid == null) errors += FieldError(key, "must be ${definition.type.expected}") else parsed[key] = valid
		}
		if (errors.isNotEmpty()) throw InvalidRequestException(errors.sortedBy { it.field })
		return parsed
	}

	/** Creates the single row in its own insert, so that its first change already gets a new version (ETag). */
	private fun create(): UserSettings =
		try {
			repository.saveAndFlush(UserSettings())
		} catch (ex: DataIntegrityViolationException) {
			throw ConflictException()
		}

	private fun snapshot(settings: UserSettings?): SettingsSnapshot {
		val overrides = readOverrides(settings)
		val values = definitions().associate { it.key to valueOf(it, overrides) }
		return SettingsSnapshot(values, settings?.version ?: 0)
	}

	private fun readOverrides(settings: UserSettings?): Map<String, Any?> =
		settings?.let { jsonMapper.readValue<Map<String, Any?>>(it.overrides) } ?: emptyMap()

	private fun <T : Any> valueOf(
		definition: SettingDefinition<T>,
		overrides: Map<String, Any?>,
	): T = overrides[definition.key]?.let(definition.type::parse) ?: definition.default
}

/** All settings with their current values, and the [version] that is their ETag. */
data class SettingsSnapshot(
	val values: Map<String, Any>,
	val version: Long,
)

/** Published after settings changed, with the keys whose value changed. */
data class SettingsChangedEvent(
	val keys: Set<String>,
)
