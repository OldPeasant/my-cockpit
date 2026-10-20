package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** An area of focus (Work, Family, Health): optional grouping of projects (SPEC.md §5.1). */
@Entity
@Table(name = "area")
class Area(
	@Column(name = "title", nullable = false)
	var title: String,
	@Column(name = "position", nullable = false)
	var position: Int = 0,
	id: UUID? = null,
) : BaseEntity(id)
