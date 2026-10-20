package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.ErrorResponseException
import java.util.UUID

/**
 * Writes local edits of events in synced calendars through to their provider (SPEC.md §6.2: remote is the source of
 * truth, no offline edits). The event service [record]s every event it creates, changes or deletes; just before its
 * transaction commits, the recorded events of synced calendars go to the adapter, and the local copies take the
 * provider's answer (etag, UID, normalized fields). Only when every write succeeded does the local change commit.
 *
 * - The provider has a newer version (412): the transaction rolls back, the calendar is fetched again once it did, and
 *   the client gets 409 "changed remotely, please retry".
 * - The provider fails otherwise: the transaction rolls back and the client gets 502.
 *
 * Writes are ordered so that a series exists remotely before its overrides: removed overrides, then created or changed
 * series and single events, then created or changed overrides, then deleted series.
 */
@Component
class CalendarWriteThrough(
	private val sources: CalendarSourceRepository,
	private val calendars: CalendarRepository,
	private val events: CalendarEventRepository,
	private val registry: CalendarProviderRegistry,
	private val sync: CalendarSyncService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Whether events of calendar [calendarId] are synced with a provider (and so changed through it). */
	fun isSynced(calendarId: UUID): Boolean = sourceOf(calendarId)?.let { registry.supports(it.type) } == true

	/**
	 * Notes that [event] was created, changed or deleted ([ChangeOp.DELETED]: for good, the entity is not used again) in
	 * the current transaction. Events of local calendars are ignored. Without a transaction the write happens now.
	 */
	fun record(
		event: CalendarEvent,
		op: ChangeOp,
	) {
		if (!isSynced(event.calendarId)) return
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			Batch().also { it.add(event, op) }.push()
			return
		}
		val batch =
			TransactionSynchronizationManager.getResource(this) as Batch?
				?: Batch().also {
					TransactionSynchronizationManager.bindResource(this, it)
					TransactionSynchronizationManager.registerSynchronization(it)
				}
		batch.add(event, op)
	}

	private fun sourceOf(calendarId: UUID): CalendarSource? =
		calendars.findById(calendarId).orElse(null)?.let { sources.findById(it.sourceId).orElse(null) }?.takeIf {
			it.type != CalendarSourceType.LOCAL
		}

	private class Pending(
		val event: CalendarEvent,
		var op: ChangeOp,
	)

	/** The recorded writes of one transaction. */
	private inner class Batch : TransactionSynchronization {
		private val pending = LinkedHashMap<UUID, Pending>()
		private var refetch: UUID? = null

		fun add(
			event: CalendarEvent,
			op: ChangeOp,
		) {
			val previous = pending[event.id]
			when {
				previous == null -> pending[event.id] = Pending(event, op)
				// Never written: nothing to delete remotely.
				previous.op == ChangeOp.CREATED && op == ChangeOp.DELETED -> pending.remove(event.id)
				previous.op == ChangeOp.CREATED -> Unit
				else -> previous.op = op
			}
		}

		override fun beforeCommit(readOnly: Boolean) = push()

		override fun afterCompletion(status: Int) {
			TransactionSynchronizationManager.unbindResourceIfPossible(this@CalendarWriteThrough)
			refetch?.let { if (status != TransactionSynchronization.STATUS_COMMITTED) sync.refetch(it) }
		}

		fun push() {
			val all = pending.values.toList()
			pending.clear()
			val (deleted, written) = all.partition { it.op == ChangeOp.DELETED }
			// Deleting a series removes its overrides at the provider as well.
			val deletedSeries = deleted.filter { !it.event.isOverride }.map { it.event.id }.toSet()
			val ordered =
				deleted.filter { it.event.isOverride && it.event.parentEventId !in deletedSeries } +
					written.filter { !it.event.isOverride } +
					written.filter { it.event.isOverride } +
					deleted.filter { !it.event.isOverride }
			ordered.forEach { write(it) }
		}

		private fun write(pending: Pending) {
			val event = pending.event
			val calendar = calendars.findById(event.calendarId).orElseThrow()
			val source = sources.findById(calendar.sourceId).orElseThrow()
			val adapter = registry.find(source.type) ?: throw ConflictException("Events of this calendar cannot be changed")
			val calendarExternalId = calendar.externalId ?: throw ConflictException("The calendar is not synced yet")
			val master = if (event.isOverride) events.findById(event.parentEventId!!).orElseThrow() else null
			try {
				if (pending.op == ChangeOp.DELETED) {
					// An event or override that never reached the provider has nothing to delete there.
					val uid = event.externalId ?: return
					val etag = adapter.deleteEvent(source, calendarExternalId, RemoteEventKey(uid, event.recurrenceId), event.externalEtag)
					if (master != null && adapter.seriesShareEtag && etag != null) shareEtag(master, etag)
					return
				}
				val stored =
					if (master != null) {
						val uid = master.externalId ?: throw ConflictException("The series is not synced yet")
						if (event.externalId == null) {
							adapter.createEvent(source, calendarExternalId, event.toRemote(uid))
						} else {
							adapter.updateEvent(source, calendarExternalId, event.toRemote(uid), event.externalEtag)
						}
					} else {
						val uid = event.externalId
						if (uid == null) {
							adapter.createEvent(source, calendarExternalId, event.toRemote(event.id.toString()))
						} else {
							adapter.updateEvent(source, calendarExternalId, event.toRemote(uid), event.externalEtag)
						}
					}
				event.copyFrom(stored)
				if (adapter.seriesShareEtag && stored.etag != null && (master != null || event.isRecurring)) {
					shareEtag(master ?: event, stored.etag)
				}
			} catch (e: RemoteEventChangedException) {
				refetch = calendar.id
				throw ConflictException("The event was changed remotely; the cockpit now shows the new version, please retry")
			} catch (e: CalendarProviderException) {
				log.warn("Writing an event of calendar {} to the provider failed: {}", calendar.id, e.message)
				throw providerFailed(e)
			}
		}

		private fun shareEtag(
			master: CalendarEvent,
			etag: String,
		) {
			master.externalEtag = etag
			events.findByParentEventIdOrderByIdAsc(master.id).forEach { it.externalEtag = etag }
		}
	}

	private fun providerFailed(e: CalendarProviderException) =
		ErrorResponseException(
			HttpStatus.BAD_GATEWAY,
			ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "The calendar provider failed: ${e.message}").apply {
				title = "Calendar provider error"
			},
			e,
		)
}
