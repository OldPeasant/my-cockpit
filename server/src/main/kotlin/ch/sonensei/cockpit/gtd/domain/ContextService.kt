package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.TaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Contexts (`@home`, `@phone`; SPEC.md §5.1), in the user's order. Names are trimmed and unique regardless of case;
 * colors are CSS hex colors. Changes are announced as [CONTEXT] change events. Deleting a context removes it from its
 * tasks (each announced as `UPDATED`, with a new version).
 */
@Service
@Transactional
class ContextService(
	private val contexts: ContextRepository,
	private val tasks: TaskRepository,
	private val changes: ChangeEventPublisher,
) {
	@Transactional(readOnly = true)
	fun list(): List<Context> = contexts.findAllByOrderByPositionAscIdAsc()

	@Transactional(readOnly = true)
	fun get(id: UUID): Context = load(id)

	/**
	 * Creates a context, by default at the end.
	 *
	 * @throws ConflictException (409) when a context with this name exists
	 */
	fun create(
		name: String,
		color: String?,
		position: Int?,
	): Context {
		val context = Context(name.trim(), color, position ?: ((contexts.maxPosition() ?: -1) + 1))
		validate(context)
		checkNameFree(context.name, null)
		val saved = contexts.saveAndFlush(context)
		changes.publish(CONTEXT, saved.id, ChangeOp.CREATED)
		return saved
	}

	/**
	 * Changes the given fields; [color] is a [Change] because `null` clears it.
	 *
	 * @throws ConflictException (409) when another context has the new name
	 */
	fun update(
		id: UUID,
		name: String?,
		color: Change<String?>?,
		position: Int?,
		checkVersion: (Long) -> Unit,
	): Context {
		val context = load(id)
		checkVersion(context.version)
		// Before the change: the query's auto-flush would otherwise write a duplicate name into the unique index.
		name?.let { checkNameFree(it.trim(), id) }
		name?.let { context.name = it.trim() }
		color?.let { context.color = it.value }
		position?.let { context.position = it }
		validate(context)
		val saved = contexts.saveAndFlush(context)
		changes.publish(CONTEXT, saved.id, ChangeOp.UPDATED)
		return saved
	}

	/**
	 * Puts the contexts in the order of [ids], which must list every context exactly once.
	 *
	 * @throws InvalidRequestException (400) when [ids] is not exactly the set of all contexts
	 */
	fun order(ids: List<UUID>): List<Context> {
		val all = contexts.findAllByOrderByPositionAscIdAsc()
		checkPermutation(ids, all.map { it.id })
		val byId = all.associateBy { it.id }
		ids.forEachIndexed { position, id ->
			val context = byId.getValue(id)
			if (context.position != position) {
				context.position = position
				contexts.save(context)
				changes.publish(CONTEXT, id, ChangeOp.UPDATED)
			}
		}
		contexts.flush()
		return list()
	}

	/** Deletes the context and removes it from every task that has it (also those in the Trash). */
	fun delete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	) {
		val context = load(id)
		checkVersion(context.version)
		tasks.findAllWithContext(id).forEach { task ->
			task.contextIds.remove(id)
			tasks.save(task)
			if (!task.isDeleted) changes.publish(ItemType.TASK, task.id, ChangeOp.UPDATED)
		}
		tasks.flush()
		contexts.delete(context)
		contexts.flush()
		changes.publish(CONTEXT, id, ChangeOp.DELETED)
	}

	private fun load(id: UUID): Context = contexts.findById(id).orElse(null) ?: throw NotFoundException("Context not found")

	private fun validate(context: Context) {
		val errors = mutableListOf<FieldError>()
		if (context.name.isBlank()) errors += FieldError("name", "must not be blank")
		if (context.name.length > MAX_NAME_LENGTH) errors += FieldError("name", "must be at most $MAX_NAME_LENGTH characters")
		context.color?.let { if (!COLOR.matches(it)) errors += FieldError("color", "must be a hex color like #3366ff") }
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	private fun checkNameFree(
		name: String,
		id: UUID?,
	) {
		val existing = contexts.findByNameIgnoreCase(name)
		if (existing != null && existing.id != id) throw ConflictException("A context with this name exists")
	}

	companion object {
		/** Change event type of contexts (not an item type: contexts are not linkable items). */
		const val CONTEXT = "CONTEXT"
		const val MAX_NAME_LENGTH = 100
		private val COLOR = Regex("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})")
	}
}
