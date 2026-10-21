package ch.sonensei.cockpit.bookmarks.infra

import ch.sonensei.cockpit.bookmarks.domain.Bookmark
import ch.sonensei.cockpit.core.persistence.SoftDeletableRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query

interface BookmarkRepository :
	SoftDeletableRepository<Bookmark>,
	JpaSpecificationExecutor<Bookmark> {
	/** How a tag is already spelled on any bookmark (also one in the Trash), compared regardless of case; `null` when unused. */
	@Query(value = "select tag from bookmark_tag where lower(tag) = lower(:tag) order by tag limit 1", nativeQuery = true)
	fun findTagSpelling(tag: String): String?

	/** Every tag of the bookmarks not in the Trash with its number of bookmarks, ordered by tag regardless of case. */
	@Query(
		value =
			"select t.tag as tag, count(*) as count from bookmark_tag t join bookmark b on b.id = t.bookmark_id " +
				"where b.deleted_at is null group by t.tag order by lower(t.tag), t.tag",
		nativeQuery = true,
	)
	fun countTags(): List<BookmarkTagCount>
}

/** A tag and its number of bookmarks. */
interface BookmarkTagCount {
	val tag: String
	val count: Long
}
