package ch.sonensei.cockpit.calendar.invitations

import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventIndexer
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.sync.CalendarProviderAdapter
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.CalendarProviderRegistry
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.calendar.sync.InvitationNotRespondableException
import ch.sonensei.cockpit.calendar.sync.RemoteEventChangedException
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import ch.sonensei.cockpit.calendar.sync.copyFrom
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.NotFoundException
import org.hibernate.Hibernate
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.ErrorResponseException
import java.util.UUID

/** The user's answer to an invitation. */
enum class InvitationResponse(
	val status: AttendeeStatus,
) {
	ACCEPTED(AttendeeStatus.ACCEPTED),
	DECLINED(AttendeeStatus.DECLINED),
	TENTATIVE(AttendeeStatus.TENTATIVE),
}

/**
 * Answers invitations to events of Google and Microsoft calendars (SPEC.md §6.2) through the provider's adapter
 * ([CalendarProviderAdapter.respondToInvitation]); the local copy then takes the provider's version of the event, with
 * the user's attendee status. A series is answered as a whole, an override for its occurrence; after answering a series
 * its calendar is fetched again, so that its overrides show the answer too.
 *
 * The provider is called outside any transaction. As with the write-through of edits, the provider having another
 * version gives 409 "changed remotely" (the calendar is fetched again), other provider failures 502, and nothing changes
 * locally.
 */
@Service
class InvitationService(
	private val events: CalendarEventRepository,
	private val calendars: CalendarRepository,
	private val sources: CalendarSourceRepository,
	private val registry: CalendarProviderRegistry,
	private val sync: CalendarSyncService,
	private val indexer: CalendarEventIndexer,
	private val changes: ChangeEventPublisher,
	transactionManager: PlatformTransactionManager,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val tx = TransactionTemplate(transactionManager)
	private val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }

	/** What the provider needs to answer the invitation to one event. */
	private class Target(
		val calendarId: UUID,
		val calendarExternalId: String,
		val source: CalendarSource,
		val adapter: CalendarProviderAdapter,
		val key: RemoteEventKey,
		val etag: String?,
	)

	/**
	 * Answers the invitation to event [id] (a series master, an override or a single event) with [response] and returns
	 * the event as stored now. [checkVersion] sees the event's version first (If-Match).
	 *
	 * @throws NotFoundException (404) when there is no such event or it is in the Trash
	 * @throws ConflictException (409) when the event's calendar is not a Google or Microsoft calendar, is read-only or is
	 *   not synced yet, the user is not invited to the event or organizes it, or the event changed at the provider
	 */
	fun respond(
		id: UUID,
		response: InvitationResponse,
		checkVersion: (Long) -> Unit,
	): CalendarEvent {
		val target = readTx.execute { target(id, checkVersion) }!!
		val stored =
			try {
				target.adapter.respondToInvitation(target.source, target.calendarExternalId, target.key, response.status, target.etag)
			} catch (e: InvitationNotRespondableException) {
				throw ConflictException(e.message!!)
			} catch (e: RemoteEventChangedException) {
				sync.refetch(target.calendarId)
				throw ConflictException("The event was changed remotely; the cockpit now shows the new version, please retry")
			} catch (e: CalendarProviderException) {
				log.warn("Answering an invitation in calendar {} failed at the provider: {}", target.calendarId, e.message)
				throw providerFailed(e)
			}
		val event =
			tx.execute {
				val event = load(id)
				try {
					event.copyFrom(stored)
				} catch (e: IllegalArgumentException) {
					throw providerFailed(CalendarProviderException("The provider answered with an invalid event: ${e.message}", e))
				}
				val saved = events.saveAndFlush(event)
				indexer.index(saved)
				changes.publish(ItemType.EVENT, saved.id, ChangeOp.UPDATED)
				saved.also { Hibernate.initialize(it.exdates) }
			}!!
		if (event.isRecurring) sync.refetch(target.calendarId)
		return event
	}

	private fun target(
		id: UUID,
		checkVersion: (Long) -> Unit,
	): Target {
		val event = load(id)
		checkVersion(event.version)
		val calendar = calendars.findById(event.calendarId).orElseThrow()
		val source = sources.findById(calendar.sourceId).orElseThrow()
		val adapter =
			registry.find(source.type)?.takeIf { it.respondsToInvitations }
				?: throw ConflictException("Invitations can only be answered for events of Google and Microsoft calendars")
		if (calendar.readOnly) throw ConflictException("The calendar is read-only")
		val calendarExternalId = calendar.externalId ?: throw ConflictException("The calendar is not synced yet")
		val uid = if (event.isOverride) events.findById(event.parentEventId!!).orElseThrow().externalId else event.externalId
		uid ?: throw ConflictException("The event is not synced yet")
		return Target(calendar.id, calendarExternalId, source, adapter, RemoteEventKey(uid, event.recurrenceId), event.externalEtag)
	}

	/** An event that is not in the Trash; an override shares its series master's Trash state. */
	private fun load(id: UUID): CalendarEvent =
		events
			.findActiveById(id)
			?.takeIf { !it.isOverride || events.findActiveById(it.parentEventId!!) != null }
			?: throw NotFoundException("Event not found")

	private fun providerFailed(e: CalendarProviderException) =
		ErrorResponseException(
			HttpStatus.BAD_GATEWAY,
			ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "The calendar provider failed: ${e.message}").apply {
				title = "Calendar provider error"
			},
			e,
		)
}
