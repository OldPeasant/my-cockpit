package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.gtd.infra.AreaRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Areas of focus (SPEC.md §5.1), in the user's order. Changes are announced as [AREA] change events. Deleting an area
 * (no Trash: it holds no content of its own) takes its projects out of it.
 */
@Service
@Transactional
class AreaService(
	private val areas: AreaRepository,
	private val projects: ProjectRepository,
	private val projectService: ProjectService,
	private val changes: ChangeEventPublisher,
) {
	@Transactional(readOnly = true)
	fun list(): List<Area> = areas.findAllByOrderByPositionAscIdAsc()

	@Transactional(readOnly = true)
	fun get(id: UUID): Area = load(id)

	/** Creates an area, by default at the end. */
	fun create(
		title: String,
		position: Int?,
	): Area {
		val area = Area(title, position ?: ((areas.maxPosition() ?: -1) + 1))
		validate(area)
		val saved = areas.saveAndFlush(area)
		changes.publish(AREA, saved.id, ChangeOp.CREATED)
		return saved
	}

	fun update(
		id: UUID,
		title: String?,
		position: Int?,
		checkVersion: (Long) -> Unit,
	): Area {
		val area = load(id)
		checkVersion(area.version)
		title?.let { area.title = it }
		position?.let { area.position = it }
		validate(area)
		val saved = areas.saveAndFlush(area)
		changes.publish(AREA, saved.id, ChangeOp.UPDATED)
		return saved
	}

	/**
	 * Puts the areas in the order of [ids], which must list every area exactly once.
	 *
	 * @throws InvalidRequestException (400) when [ids] is not exactly the set of all areas
	 */
	fun order(ids: List<UUID>): List<Area> {
		val all = areas.findAllByOrderByPositionAscIdAsc()
		checkPermutation(ids, all.map { it.id })
		val byId = all.associateBy { it.id }
		ids.forEachIndexed { position, id ->
			val area = byId.getValue(id)
			if (area.position != position) {
				area.position = position
				areas.save(area)
				changes.publish(AREA, id, ChangeOp.UPDATED)
			}
		}
		areas.flush()
		return list()
	}

	/** Deletes the area; its projects (also those in the Trash) no longer have an area. */
	fun delete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	) {
		val area = load(id)
		checkVersion(area.version)
		projects.findByAreaId(id).forEach { project ->
			project.areaId = null
			projects.save(project)
			if (!project.isDeleted) projectService.changed(project)
		}
		projects.flush()
		areas.delete(area)
		areas.flush()
		changes.publish(AREA, id, ChangeOp.DELETED)
	}

	private fun load(id: UUID): Area = areas.findById(id).orElse(null) ?: throw NotFoundException("Area not found")

	private fun validate(area: Area) {
		val errors = mutableListOf<FieldError>()
		if (area.title.isBlank()) errors += FieldError("title", "must not be blank")
		if (area.title.length > MAX_TITLE_LENGTH) errors += FieldError("title", "must be at most $MAX_TITLE_LENGTH characters")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	companion object {
		/** Change event type of areas (not an item type: areas are not linkable items). */
		const val AREA = "AREA"
		const val MAX_TITLE_LENGTH = 200
	}
}

/**
 * Checks that [ids] lists each of [all] exactly once (a full reorder).
 *
 * @throws InvalidRequestException (400) for duplicates, unknown or missing ids
 */
internal fun checkPermutation(
	ids: List<UUID>,
	all: List<UUID>,
) {
	val errors = mutableListOf<FieldError>()
	if (ids.toSet().size != ids.size) errors += FieldError("ids", "contains duplicates")
	if (!all.containsAll(ids)) errors += FieldError("ids", "contains an unknown id")
	if (!ids.containsAll(all)) errors += FieldError("ids", "must list every item")
	if (errors.isNotEmpty()) throw InvalidRequestException(errors)
}
