package ch.sonensei.cockpit.core.items.infra

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface LinkRepository : JpaRepository<Link, UUID> {
	/** Links of an item in both directions, oldest first. */
	@Query(
		"""select l from Link l
		where (l.sourceType = :type and l.sourceId = :id) or (l.targetType = :type and l.targetId = :id)
		order by l.createdAt, l.id""",
	)
	fun findTouching(
		@Param("type") type: ItemType,
		@Param("id") id: UUID,
	): List<Link>

	fun findBySourceTypeAndSourceIdAndOrigin(
		sourceType: ItemType,
		sourceId: UUID,
		origin: LinkOrigin,
	): List<Link>

	// No clearAutomatically: this runs in the publisher's transaction and must not detach the entities it loaded.
	@Modifying(flushAutomatically = true)
	@Query(
		"""delete from Link l
		where (l.sourceType = :type and l.sourceId = :id) or (l.targetType = :type and l.targetId = :id)""",
	)
	fun deleteTouching(
		@Param("type") type: ItemType,
		@Param("id") id: UUID,
	): Int
}
