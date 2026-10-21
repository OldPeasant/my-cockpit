package ch.sonensei.cockpit.gtd.convert.domain

import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.api.EventChanges
import ch.sonensei.cockpit.calendar.api.FieldChange
import ch.sonensei.cockpit.calendar.api.TimingInput
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.gtd.domain.TaskService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * "To calendar" in inbox processing (SPEC.md §5.3): converts a task into a calendar event, in one transaction.
 *
 * The event gets the task's title and its notes as description, in [calendarId] (default: the default calendar) at the
 * requested times. Links are carried over as for [TaskToNoteService]: each existing item linked to the task is linked to
 * the event as a manual link with the same direction and label. The task goes to the Trash with its own links.
 */
@Service
@Transactional
class TaskToEventService(
	private val tasks: TaskService,
	private val events: CalendarEventService,
	private val links: LinkService,
	private val resolvers: ItemResolverRegistry,
) {
	/**
	 * Converts the task [id] into an event and returns the event. [checkVersion] sees the task's current version before
	 * anything changes (If-Match).
	 *
	 * @throws ch.sonensei.cockpit.core.web.NotFoundException (404) when there is no such task or it is in the Trash
	 * @throws ch.sonensei.cockpit.core.web.InvalidRequestException (400) for invalid times, an unknown calendar or a task
	 *   whose content is not a valid event
	 * @throws ch.sonensei.cockpit.core.web.ConflictException (409) when the calendar is not writable
	 */
	fun convert(
		id: UUID,
		calendarId: UUID?,
		timing: TimingInput,
		checkVersion: (Long) -> Unit,
	): CalendarEvent {
		val task = tasks.get(id)
		val taskRef = ItemRef(ItemType.TASK, task.id)
		val linked = links.linksOf(taskRef).map { it.link }
		tasks.delete(id, checkVersion)
		val event =
			events
				.create(
					null,
					EventChanges(
						calendarId = calendarId?.let(::FieldChange),
						title = FieldChange(task.title),
						description = task.notesMd?.takeIf { it.isNotBlank() }?.let(::FieldChange),
						timing = timing,
					),
				).event
		val eventRef = ItemRef(ItemType.EVENT, event.id)
		linked.forEach { link ->
			val fromTask = link.source == taskRef
			val other = if (fromTask) link.target else link.source
			// LinkService.create would reject it, and its exception would mark this transaction for rollback.
			if (!exists(other)) return@forEach
			if (fromTask) links.create(eventRef, other, link.label) else links.create(other, eventRef, link.label)
		}
		return event
	}

	/** Whether [ref] exists; an item of a type without a resolver counts as existing, as for [LinkService.create]. */
	private fun exists(ref: ItemRef): Boolean = resolvers.find(ref.type)?.exists(ref.id) ?: true
}
