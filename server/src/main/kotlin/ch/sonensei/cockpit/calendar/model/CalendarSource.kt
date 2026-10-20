package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

/**
 * An account or feed that provides [Calendar]s (SPEC.md §6.1). [config] is a JSON object with the provider's
 * non-secret settings (e.g. `{"url": "..."}` for CalDAV and ICS feeds); [credentials] is its secret (OAuth refresh
 * token, CalDAV password), stored encrypted and never serialized in clear. LOCAL sources have no credentials.
 */
@Entity
@Table(name = "calendar_source")
class CalendarSource(
	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false)
	val type: CalendarSourceType,
	@Column(name = "name", nullable = false)
	var name: String,
	id: UUID? = null,
) : BaseEntity(id) {
	@Column(name = "config", nullable = false)
	@JdbcTypeCode(SqlTypes.JSON)
	var config: String = "{}"

	@Column(name = "credentials")
	var credentials: EncryptedSecret? = null
}
