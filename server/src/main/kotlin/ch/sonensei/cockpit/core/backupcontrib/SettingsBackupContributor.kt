package ch.sonensei.cockpit.core.backupcontrib

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.json
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * `data/settings.json`: the settings the owner changed (SPEC.md §4.7), as stored; settings at their default are not
 * stored, so a restore on a newer version picks up its defaults. Mirror: `readable/settings.md`.
 */
@Component
class SettingsBackupContributor(
	private val jdbc: JdbcTemplate,
	private val jsonMapper: JsonMapper,
) : BackupContributor {
	override val id = ID

	override val dependsOn = setOf(UserBackupContributor.ID)

	override fun export(writer: BackupDataWriter) {
		settings()?.let { writer.json(SETTINGS, it) }
	}

	override fun readable(writer: BackupReadableWriter) {
		val overrides =
			settings()
				?.overrides
				?.properties()
				?.sortedBy { it.key }
				.orEmpty()
		writer.text(
			"settings.md",
			buildString {
				appendLine("# Settings")
				appendLine()
				if (overrides.isEmpty()) {
					appendLine("All settings are at their defaults.")
				} else {
					appendLine("Settings changed from their defaults:")
					appendLine()
					appendLine("| Setting | Value |")
					appendLine("| --- | --- |")
					overrides.forEach { (key, value) -> appendLine("| ${key.markdownCell()} | ${value.toString().markdownCell()} |") }
				}
			},
		)
	}

	override fun import(reader: BackupDataReader) {
		val settings = reader.json<SettingsBackup>(SETTINGS) ?: return
		jdbc.update(
			"insert into user_settings (id, created_at, updated_at, version, overrides) values (?, ?, ?, ?, ?::jsonb)",
			settings.id,
			settings.createdAt.toTimestamp(),
			settings.updatedAt.toTimestamp(),
			settings.version,
			jsonMapper.writeValueAsString(settings.overrides),
		)
	}

	private fun settings(): SettingsBackup? =
		jdbc
			.query("select * from user_settings order by id") { rs, _ ->
				SettingsBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					overrides = jsonMapper.readTree(rs.getString("overrides")),
				)
			}.singleOrNull()

	companion object {
		const val ID = "settings"
		const val SETTINGS = "settings"
	}
}

/** The settings row in `data/settings.json`. [overrides] is the JSON object `{key: value}` of changed settings. */
data class SettingsBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	/** The ETag of the settings document. */
	val version: Long,
	val overrides: JsonNode,
)
