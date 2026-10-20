package ch.sonensei.cockpit.core.items.domain

import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** One end of a link, with its display data when its resolver knows the item; otherwise it is `unavailable`. */
data class LinkEnd(
	val ref: ItemRef,
	val item: ResolvedItem?,
) {
	val available: Boolean get() = item != null
}

/** A link with both ends resolved. */
data class ResolvedLink(
	val link: Link,
	val source: LinkEnd,
	val target: LinkEnd,
)

/**
 * The link system (SPEC.md §4.3): manual links ([create], [delete]), links owned by their source ([sync]), and
 * listing an item's links in both directions with each end resolved ([linksOf]).
 */
@Service
@Transactional
class LinkService(
	private val links: LinkRepository,
	private val resolvers: ItemResolverRegistry,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Links [source] and [target] manually. An end whose resolver says it does not exist is rejected; an end without a
	 * resolver is accepted (its module may not exist yet) and shown as unavailable.
	 *
	 * @throws InvalidRequestException (400) for a self-link, an unknown item or a too long label
	 * @throws ConflictException (409) when the two items already have a manual link, in either direction; [SYNC][LinkOrigin.SYNC]
	 * links of the pair do not block a manual one
	 */
	fun create(
		source: ItemRef,
		target: ItemRef,
		label: String?,
	): ResolvedLink {
		if (source == target) throw InvalidRequestException("target", "must differ from source")
		val normalizedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
		if (normalizedLabel != null && normalizedLabel.length > MAX_LABEL_LENGTH) {
			throw InvalidRequestException("label", "must be at most $MAX_LABEL_LENGTH characters")
		}
		requireKnown("source", source)
		requireKnown("target", target)
		if (hasManualLink(source, target)) throw ConflictException("The items are already linked")
		val link =
			try {
				links.saveAndFlush(Link(source, target, normalizedLabel, LinkOrigin.MANUAL))
			} catch (_: DataIntegrityViolationException) {
				// A concurrent request linked the same pair between the check and the insert.
				throw ConflictException("The items are already linked")
			}
		return resolve(link)
	}

	/**
	 * Deletes a [MANUAL][LinkOrigin.MANUAL] link.
	 *
	 * @throws NotFoundException (404) when no such link exists
	 * @throws ConflictException (409) for a [SYNC][LinkOrigin.SYNC] link: the next [sync] of its source would recreate
	 * it, so it is removed by editing the source (e.g. the inline link in the note)
	 */
	fun delete(id: UUID) {
		val link = links.findById(id).orElseThrow { NotFoundException("Link not found") }
		if (link.origin == LinkOrigin.SYNC) {
			throw ConflictException("The link is part of its source's content; remove it there")
		}
		links.delete(link)
	}

	/**
	 * The links of [item] in both directions, each end resolved. Each linked item appears once: when several links
	 * connect [item] to it (a manual link, sync links in either direction), the manual link is shown, else the oldest.
	 * Ordered oldest first by the shown link (created at, then id).
	 */
	@Transactional(readOnly = true)
	fun linksOf(item: ItemRef): List<ResolvedLink> {
		// findTouching is ordered oldest first, so the index of the shown link gives the order.
		val touching = links.findTouching(item.type, item.id).withIndex()
		val shown =
			touching
				.groupBy { (_, link) -> if (link.source == item) link.target else link.source }
				.values
				.map { group -> group.firstOrNull { it.value.origin == LinkOrigin.MANUAL } ?: group.first() }
				.sortedBy { it.index }
		val cache = HashMap<ItemRef, LinkEnd>()
		return shown.map { (_, link) ->
			ResolvedLink(
				link,
				cache.getOrPut(link.source) { end(link.source) },
				cache.getOrPut(link.target) { end(link.target) },
			)
		}
	}

	/**
	 * Replaces the links [source] owns (origin SYNC, e.g. the inline `[[type:id]]` links of a note): afterwards the
	 * SYNC links from [source] are exactly [targets] without [source] itself. Stale ones are deleted, missing ones
	 * added, unchanged ones kept. This is independent of other links: manual links and links in the other direction
	 * (e.g. the target's own inline link back) are neither touched nor considered, so a pair stays linked while
	 * either end still wants it. Targets are not checked against their resolvers: an inline link to a missing item is
	 * kept and shown as unavailable.
	 */
	fun sync(
		source: ItemRef,
		targets: Collection<ItemRef>,
	) {
		val wanted = targets.toSet() - source
		val owned = links.findBySourceTypeAndSourceIdAndOrigin(source.type, source.id, LinkOrigin.SYNC)
		val stale = owned.filter { it.target !in wanted }
		if (stale.isNotEmpty()) {
			links.deleteAll(stale)
			links.flush()
		}
		val kept = owned.mapTo(HashSet()) { it.target }
		val missing = wanted.filter { it !in kept }
		links.saveAll(missing.map { Link(source, it, null, LinkOrigin.SYNC) })
		links.flush()
	}

	/** Removes every link of a permanently deleted item, in the publisher's transaction. */
	@EventListener
	fun onItemDeleted(event: ItemDeletedEvent) {
		links.deleteTouching(event.item.type, event.item.id)
	}

	private fun hasManualLink(
		a: ItemRef,
		b: ItemRef,
	): Boolean = links.findTouching(a.type, a.id).any { it.origin == LinkOrigin.MANUAL && it.connects(a, b) }

	private fun requireKnown(
		field: String,
		ref: ItemRef,
	) {
		val resolver = resolvers.find(ref.type) ?: return
		if (!resolver.exists(ref.id)) throw InvalidRequestException(field, "does not exist")
	}

	private fun resolve(link: Link) = ResolvedLink(link, end(link.source), end(link.target))

	private fun end(ref: ItemRef): LinkEnd {
		val resolver = resolvers.find(ref.type) ?: return LinkEnd(ref, null)
		val item =
			try {
				resolver.resolve(ref.id)
			} catch (e: RuntimeException) {
				// One broken module must not break the panel of every item linked to it.
				log.warn("ItemResolver for {} failed for {}", ref.type, ref.id, e)
				null
			}
		return LinkEnd(ref, item)
	}

	companion object {
		const val MAX_LABEL_LENGTH = 200
	}
}
