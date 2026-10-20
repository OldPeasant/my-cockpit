package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A quick capture (SPEC.md §4.5): the text, optional notes and files to attach, and whether to parse the syntax. */
data class CaptureCommand(
	val id: UUID?,
	val text: String,
	val notes: String? = null,
	val fileIds: List<UUID> = emptyList(),
	val parse: Boolean = true,
)

/**
 * Quick capture (SPEC.md §4.5, §9.3): turns a text into a task, using the light syntax of [CaptureParser], and links
 * the attached files to it. Idempotent by the client-chosen id like [TaskService.create].
 */
@Service
@Transactional
class CaptureService(
	private val tasks: TaskService,
	private val projects: ProjectRepository,
	private val contexts: ContextRepository,
	private val links: LinkService,
	private val resolvers: ItemResolverRegistry,
	private val settings: SettingsService,
	private val clock: Clock,
) {
	/** What capturing [text] with parsing would create. */
	@Transactional(readOnly = true)
	fun preview(text: String): ParsedCapture = parse(text)

	/**
	 * Creates the captured task (INBOX, or WAITING with `^`). The rest of a multi-line text goes in front of the notes.
	 * A repeated [CaptureCommand.id] returns the existing task unchanged, without linking files again.
	 *
	 * @throws InvalidRequestException (400) for a text without a title, an unknown file or invalid task fields
	 * @throws ch.sonensei.cockpit.core.web.ConflictException (409) when the id belongs to a task in the Trash
	 */
	fun capture(command: CaptureCommand): CreatedTask {
		command.id?.let { id -> tasks.existingCreate(id)?.let { return it } }
		val parsed = if (command.parse) parse(command.text) else CaptureParser.plain(command.text)
		if (parsed.title.isEmpty()) throw InvalidRequestException("text", "must contain a title")
		val fileIds = command.fileIds.distinct()
		val fileResolver = resolvers.find(ItemType.FILE)
		if (fileResolver != null && fileIds.any { !fileResolver.exists(it) }) {
			throw InvalidRequestException("fileIds", "contains an unknown file")
		}
		val notes = listOfNotNull(parsed.extraNotes, command.notes?.takeIf { it.isNotBlank() }).joinToString("\n\n")
		if (notes.length > TaskService.MAX_NOTES_LENGTH) {
			throw InvalidRequestException("notes", "must be at most ${TaskService.MAX_NOTES_LENGTH} characters, with the text after its first line")
		}
		val fields =
			TaskChanges(
				title = Change(parsed.title),
				notesMd = Change(notes.ifEmpty { null }),
				projectId = parsed.project?.let { Change(it.id) },
				contextIds = Change(parsed.contexts.mapTo(LinkedHashSet()) { it.id }),
				dueDate = parsed.dueDate?.let { Change(it) },
				waitingForText = parsed.waitingFor?.let { Change(it) },
			)
		val result = tasks.create(command.id, parsed.list, fields)
		val task = ItemRef(ItemType.TASK, result.task.id)
		fileIds.forEach { links.create(task, ItemRef(ItemType.FILE, it), null) }
		return result
	}

	/** The answer to a repeated capture with [id] after a concurrent capture with it won the insert, see [TaskService.existingCreate]. */
	@Transactional(readOnly = true)
	fun existing(id: UUID): CreatedTask? = tasks.existingCreate(id)

	private fun parse(text: String): ParsedCapture {
		// ACTIVE projects first, then on hold and someday; completed and dropped ones are not offered.
		val candidates =
			projects
				.findByStatusInAndDeletedAtIsNullOrderByIdAsc(CAPTURE_PROJECT_STATUSES)
				.sortedBy { CAPTURE_PROJECT_STATUSES.indexOf(it.status) }
				.map { CaptureProject(it.id, it.title) }
		val knownContexts = contexts.findAllByOrderByPositionAscIdAsc().map { CaptureContext(it.id, it.name) }
		return CaptureParser.parse(text, today(), candidates, knownContexts)
	}

	private fun today(): LocalDate = LocalDate.now(clock.withZone(settings.timeZone()))

	private companion object {
		val CAPTURE_PROJECT_STATUSES = listOf(ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD, ProjectStatus.SOMEDAY)
	}
}
