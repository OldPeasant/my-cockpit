package ch.sonensei.cockpit.core.settings.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * The owner's changed settings (single row): [overrides] is a JSON object `{key: value}`, read and written by
 * [SettingsService]. Its `version` is the ETag of the settings document.
 */
@Entity
@Table(name = "user_settings")
class UserSettings(
	@Column(name = "overrides", nullable = false)
	@JdbcTypeCode(SqlTypes.JSON)
	var overrides: String = "{}",
) : BaseEntity()
