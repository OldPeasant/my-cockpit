package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * One calendar of a [CalendarSource] (SPEC.md §6.1). [color] is a CSS hex color (`#3366ff`); hidden calendars
 * ([visible] false) are not shown in the views; at most one calendar is the [isDefault] one for new events (the
 * database rejects a second). [externalId]/[externalEtag] identify the provider's calendar (null for LOCAL).
 */
@Entity
@Table(name = "calendar")
class Calendar(
	@Column(name = "source_id", nullable = false, updatable = false)
	val sourceId: UUID,
	@Column(name = "name", nullable = false)
	var name: String,
	id: UUID? = null,
) : BaseEntity(id) {
	@Column(name = "color")
	var color: String? = null

	@Column(name = "visible", nullable = false)
	var visible: Boolean = true

	@Column(name = "read_only", nullable = false)
	var readOnly: Boolean = false

	@Column(name = "is_default", nullable = false)
	var isDefault: Boolean = false

	@Column(name = "external_id")
	var externalId: String? = null

	@Column(name = "external_etag")
	var externalEtag: String? = null
}
