package ch.sonensei.cockpit.core.persistence

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Transient
import jakarta.persistence.Version
import org.hibernate.Hibernate
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.domain.Persistable
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * Base class of every entity (SPEC.md §4.2). Tables declare the matching columns:
 * `id uuid primary key, created_at timestamptz not null, updated_at timestamptz not null, version bigint not null`.
 *
 * - `id`: UUIDv7, generated here, or taken from the client for idempotent creates (must be a UUIDv7).
 * - `createdAt`/`updatedAt`: set on persist/update from the injectable [java.time.Clock] (see [ClockConfiguration]).
 * - `version`: optimistic locking; a stale update fails with Spring's `OptimisticLockingFailureException`.
 *
 * Implements [Persistable] so `save()` of an entity with an assigned id inserts directly instead of merging.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseEntity(
	id: UUID? = null,
) : Persistable<UUID> {
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private var id: UUID = id ?: UuidV7.generate()

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant? = null
		protected set

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant? = null
		protected set

	@Version
	@Column(name = "version", nullable = false)
	var version: Long = 0
		protected set

	@Transient
	private var new: Boolean = true

	init {
		require(UuidV7.isUuidV7(this.id)) { "Entity ids must be UUIDv7" }
	}

	override fun getId(): UUID = id

	override fun isNew(): Boolean = new

	@PostLoad
	@PostPersist
	protected fun markPersisted() {
		new = false
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is BaseEntity) return false
		return Hibernate.getClass(this) == Hibernate.getClass(other) && id == other.getId()
	}

	override fun hashCode(): Int = id.hashCode()

	override fun toString(): String = "${Hibernate.getClass(this).simpleName}(id=$id)"
}
