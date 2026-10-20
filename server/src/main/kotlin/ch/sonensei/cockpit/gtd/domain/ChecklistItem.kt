package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A simple sub-step inside a task; deleted together with its task. */
@Entity
@Table(name = "checklist_item")
class ChecklistItem(
	@Column(name = "task_id", nullable = false, updatable = false)
	val taskId: UUID,
	@Column(name = "text", nullable = false)
	var text: String,
	@Column(name = "done", nullable = false)
	var done: Boolean = false,
	@Column(name = "position", nullable = false)
	var position: Int = 0,
	id: UUID? = null,
) : BaseEntity(id)
