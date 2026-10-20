package ch.sonensei.cockpit.core.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.NoRepositoryBean
import java.util.UUID

/**
 * Base repository for [SoftDeletableEntity] types. Normal reads use the `Active` methods, which exclude
 * soft-deleted rows; the inherited `findById`/`findAll` still see everything (Trash, restore, backup).
 * Derived queries in module repositories add `DeletedAtIsNull` themselves.
 */
@NoRepositoryBean
interface SoftDeletableRepository<T : SoftDeletableEntity> : JpaRepository<T, UUID> {
	@Query("select e from #{#entityName} e where e.id = :id and e.deletedAt is null")
	fun findActiveById(id: UUID): T?

	@Query("select e from #{#entityName} e where e.deletedAt is null order by e.id")
	fun findAllActive(): List<T>

	@Query("select e from #{#entityName} e where e.deletedAt is not null order by e.deletedAt desc")
	fun findAllDeleted(): List<T>
}
