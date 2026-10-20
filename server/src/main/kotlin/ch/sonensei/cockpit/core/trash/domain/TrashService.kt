package ch.sonensei.cockpit.core.trash.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** An item in the Trash with its type and the time it will be purged automatically. */
data class TrashEntry(
	val type: ItemType,
	val item: TrashedItem,
) {
	val ref: ItemRef get() = ItemRef(type, item.id)
	val purgeAt: Instant get() = item.deletedAt.plus(TrashService.RETENTION)
}

/**
 * The generic Trash (SPEC.md §4.2) over every module's [TrashContributor]: one list of all deleted items, restore and
 * permanent delete. Items deleted more than [RETENTION] ago are purged by [TrashPurgeJob].
 */
@Service
@Transactional
class TrashService(
	private val contributors: TrashContributorRegistry,
	private val events: ApplicationEventPublisher,
	private val changes: ChangeEventPublisher,
	transactions: PlatformTransactionManager,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * One read-only transaction per contributor listing: a contributor that fails (e.g. an SQL error, which aborts a
	 * Postgres transaction, or a repository marking the transaction rollback-only) only rolls back its own listing.
	 */
	private val listingTransaction =
		TransactionTemplate(transactions).apply {
			propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
			isReadOnly = true
		}

	/**
	 * One page of the Trash, most recently deleted first (then type, then id descending). A contributor that fails is
	 * left out and logged, so one broken module does not hide the rest of the Trash.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	fun list(request: CursorRequest): CursorPage<TrashEntry> {
		val after = request.position?.let(::parsePosition)
		val fetched =
			entries()
				.asSequence()
				.sortedWith(ORDER)
				.filter { after == null || ORDER.compare(it, after) > 0 }
				.take(request.limit + 1)
				.toList()
		return request.page(fetched, ::positionOf)
	}

	/**
	 * Every item in the Trash, unordered; contributors that fail are left out. Each contributor lists in its own
	 * transaction ([listingTransaction]), so a failing one cannot break the others or this call.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	fun entries(): List<TrashEntry> =
		contributors.all().flatMap { contributor ->
			try {
				listingTransaction
					.execute { contributor.listDeleted() }
					.orEmpty()
					.map { TrashEntry(contributor.type, it) }
			} catch (e: RuntimeException) {
				log.warn("TrashContributor for {} failed to list deleted items", contributor.type, e)
				emptyList()
			}
		}

	/**
	 * Takes the item out of the Trash and announces it as `UPDATED`.
	 *
	 * @throws NotFoundException (404) when no such item is in the Trash or its type has no Trash
	 */
	fun restore(ref: ItemRef) {
		if (!contributorOf(ref).restore(ref.id)) throw notInTrash()
		changes.publish(ref.type, ref.id, ChangeOp.UPDATED)
	}

	/**
	 * Deletes the item permanently, removes its links ([ItemDeletedEvent]) and announces it as `DELETED`, all in one
	 * transaction.
	 *
	 * @throws NotFoundException (404) when no such item is in the Trash or its type has no Trash
	 */
	fun purge(ref: ItemRef) {
		if (!contributorOf(ref).purge(ref.id)) throw notInTrash()
		events.publishEvent(ItemDeletedEvent(ref))
		changes.publish(ref.type, ref.id, ChangeOp.DELETED)
	}

	private fun contributorOf(ref: ItemRef): TrashContributor = contributors.find(ref.type) ?: throw notInTrash()

	private fun notInTrash() = NotFoundException("Item not found in the Trash")

	private fun positionOf(entry: TrashEntry): String = "${entry.item.deletedAt}|${entry.type.name}|${entry.item.id}"

	/** The cursor as a sort key: a stand-in entry with the last item's deletedAt, type and id. */
	private fun parsePosition(position: String): TrashEntry {
		val parts = position.split('|')
		val entry =
			runCatching {
				require(parts.size == 3)
				val id = UUID.fromString(parts[2])
				TrashEntry(ItemType.valueOf(parts[1]), TrashedItem(id, "", Instant.parse(parts[0]), icon = ""))
			}.getOrNull()
		return entry ?: throw InvalidRequestException("cursor", "is not a valid cursor")
	}

	companion object {
		/** How long an item stays in the Trash before it is purged (SPEC.md §4.2). */
		val RETENTION: Duration = Duration.ofDays(30)

		private val ORDER: Comparator<TrashEntry> =
			compareByDescending<TrashEntry> { it.item.deletedAt }
				.thenBy { it.type }
				.thenByDescending { it.item.id }
	}
}
