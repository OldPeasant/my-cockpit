package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A user-defined context tag such as `@home` (SPEC.md §5.1). Names are unique regardless of case. */
@Entity
@Table(name = "context")
class Context(
	@Column(name = "name", nullable = false)
	var name: String,
	/** A CSS color, e.g. `#3366ff`. */
	@Column(name = "color")
	var color: String? = null,
	@Column(name = "position", nullable = false)
	var position: Int = 0,
	id: UUID? = null,
) : BaseEntity(id)
