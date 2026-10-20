package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.gtd.infra.ChecklistItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The checklist of a task (simple sub-steps). Only tasks that are not in the Trash have a checklist here; every change
 * announces the task as `UPDATED`. Items have their own version (ETag), checked by `checkVersion` like [TaskService].
 */
@Service
@Transactional
class ChecklistService(
	private val items: ChecklistItemRepository,
	private val tasks: TaskService,
) {
	/** The task's checklist in order. */
	@Transactional(readOnly = true)
	fun list(taskId: UUID): List<ChecklistItem> {
		tasks.requireActive(taskId)
		return items.findByTaskIdOrderByPositionAscIdAsc(taskId)
	}

	/** Adds an item at the end of the checklist. */
	fun add(
		taskId: UUID,
		text: String,
		done: Boolean,
	): ChecklistItem {
		val task = tasks.requireActive(taskId)
		val item = ChecklistItem(taskId, text, done, (items.maxPosition(taskId) ?: -1) + 1)
		validate(item)
		val saved = items.saveAndFlush(item)
		tasks.changed(task)
		return saved
	}

	fun update(
		taskId: UUID,
		itemId: UUID,
		changes: ChecklistItemChanges,
		checkVersion: (Long) -> Unit,
	): ChecklistItem {
		val task = tasks.requireActive(taskId)
		val item = load(taskId, itemId)
		checkVersion(item.version)
		changes.text?.let { item.text = it }
		changes.done?.let { item.done = it }
		changes.position?.let { item.position = it }
		validate(item)
		val saved = items.saveAndFlush(item)
		tasks.changed(task)
		return saved
	}

	fun delete(
		taskId: UUID,
		itemId: UUID,
		checkVersion: (Long) -> Unit,
	) {
		val task = tasks.requireActive(taskId)
		val item = load(taskId, itemId)
		checkVersion(item.version)
		items.delete(item)
		tasks.changed(task)
	}

	private fun load(
		taskId: UUID,
		itemId: UUID,
	): ChecklistItem = items.findByIdAndTaskId(itemId, taskId) ?: throw NotFoundException("Checklist item not found")

	private fun validate(item: ChecklistItem) {
		val errors = mutableListOf<FieldError>()
		if (item.text.isBlank()) errors += FieldError("text", "must not be blank")
		if (item.text.length > MAX_TEXT_LENGTH) errors += FieldError("text", "must be at most $MAX_TEXT_LENGTH characters")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	companion object {
		const val MAX_TEXT_LENGTH = 1_000
	}
}
