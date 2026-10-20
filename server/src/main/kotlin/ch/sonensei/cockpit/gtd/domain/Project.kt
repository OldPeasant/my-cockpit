package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.persistence.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A GTD project (SPEC.md §5.2): its tasks are the [Task]s with its id as `projectId`, ordered by position;
 * [nextTaskId] is the designated next step. [areaId] references an [Area].
 */
@Entity
@Table(name = "project")
class Project(
	@Column(name = "title", nullable = false)
	var title: String,
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	var status: ProjectStatus = ProjectStatus.ACTIVE,
	id: UUID? = null,
) : SoftDeletableEntity(id) {
	@Column(name = "description_md")
	var descriptionMd: String? = null

	@Column(name = "area_id")
	var areaId: UUID? = null

	@Column(name = "next_task_id")
	var nextTaskId: UUID? = null

	@Column(name = "due_date")
	var dueDate: LocalDate? = null

	@Column(name = "review_date")
	var reviewDate: LocalDate? = null

	@Column(name = "completed_at")
	var completedAt: Instant? = null
}
