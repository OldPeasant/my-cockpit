package ch.sonensei.cockpit.calendar.itip

import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventIndexer
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.api.MailContentLoader
import ch.sonensei.cockpit.mail.api.MailReadService
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeUtility
import jakarta.mail.internet.ParseException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Reads the iTIP REPLYs attendees send to invitations of [ItipService] (SPEC.md §6.2) and stores their answers: every
 * message that arrives in an Inbox is looked at (in the background, one at a time), and its `text/calendar` parts with
 * method REPLY update the status of the attendee in the event of a LOCAL calendar it answers.
 *
 * An answer is only taken when the event lists the attendee and the mail comes from the attendee's address, so a
 * stranger cannot change someone else's answer. An answer for the whole series updates the master and its overrides
 * that list the attendee; an answer for one occurrence updates its override, and is ignored when the occurrence has
 * none (the cockpit does not create overrides for answers). Replies that cannot be matched are skipped. When one
 * message holds several answers of an attendee for the same event, only the newest (SEQUENCE, then DTSTAMP) counts.
 * Across messages too: an answer older than the last one taken from the attendee for the same event and occurrence
 * ([ItipReplyStamp]) arrived late and is ignored (RFC 5546 §2.1.5). As a series answer sets the occurrences too, an
 * occurrence answer older than the last series answer is ignored, and a series answer leaves an occurrence alone that
 * holds a newer answer of its own.
 */
@Component
class ItipReplyProcessor(
	private val mail: MailReadService,
	private val loader: MailContentLoader,
	private val events: CalendarEventRepository,
	private val calendars: CalendarRepository,
	private val sources: CalendarSourceRepository,
	private val stamps: ItipReplyStampRepository,
	private val indexer: CalendarEventIndexer,
	private val changes: ChangeEventPublisher,
	transactionManager: PlatformTransactionManager,
) : DisposableBean {
	private val log = LoggerFactory.getLogger(javaClass)
	private val tx = TransactionTemplate(transactionManager)

	private val executor =
		ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE_SIZE)) { runnable ->
			Thread(runnable, "itip-replies").apply { isDaemon = true }
		}

	/** A new message: queued to be looked at, after the transaction that stored it committed. */
	@TransactionalEventListener(fallbackExecution = true)
	fun onChange(event: ChangeEvent) {
		if (event.type != ItemType.EMAIL.name || event.op != ChangeOp.CREATED) return
		try {
			executor.execute { safely(event.id) }
		} catch (e: RejectedExecutionException) {
			if (!executor.isShutdown) log.warn("Message {}: not checked for an invitation reply, the queue is full", event.id)
		}
	}

	private fun safely(messageId: UUID) {
		try {
			process(messageId)
		} catch (e: NotFoundException) {
			log.debug("Message {} is gone before it was checked for an invitation reply", messageId)
		} catch (e: MailConnectionException) {
			log.info("Message {}: not checked for an invitation reply, the mail server failed ({})", messageId, e.javaClass.simpleName)
		} catch (e: RuntimeException) {
			log.warn("Message {}: checking for an invitation reply failed ({})", messageId, e.javaClass.name)
			log.debug("Invitation reply failure", e)
		}
	}

	/**
	 * Applies the iTIP REPLYs in message [messageId] if it is in an Inbox, and returns the ids of the events that
	 * changed.
	 *
	 * @throws NotFoundException when the message is no longer on the server
	 * @throws MailConnectionException when the mail server cannot be reached or fails
	 */
	fun process(messageId: UUID): List<UUID> {
		val message = mail.inInbox(messageId) ?: return emptyList()
		val senders = message.from.map { it.address.lowercase() }.toSet()
		if (senders.isEmpty()) return emptyList()
		val calendars = loader.withRemoteMessage(message) { calendarParts(it) }
		val replies =
			calendars.flatMap { text ->
				try {
					ItipMessages.replies(text)
				} catch (e: IllegalArgumentException) {
					emptyList()
				}
			}
		// Several answers of one attendee for the same event in one message: only the newest counts.
		val newest =
			replies
				.filter { it.email.lowercase() in senders }
				.groupBy { it.key }
				.values
				.map { it.maxWith(ItipReply.ORDER) }
		val changed = newest.flatMap { reply -> applyRetrying(reply) }.distinct()
		if (changed.isNotEmpty()) log.info("Message {}: invitation reply updated {} event(s)", messageId, changed.size)
		return changed
	}

	/**
	 * [apply]s [reply] in its own transaction; when a concurrent change of the event wins (optimistic locking), it is
	 * tried again on the reloaded event, up to [MAX_ATTEMPTS] times in all.
	 */
	private fun applyRetrying(reply: ItipReply): List<UUID> {
		var attempt = 1
		while (true) {
			try {
				return tx.execute { apply(reply) }!!
			} catch (e: OptimisticLockingFailureException) {
				if (attempt >= MAX_ATTEMPTS) throw e
				attempt++
			}
		}
	}

	/** Stores [reply] and returns the ids of the events whose attendee status changed. */
	private fun apply(reply: ItipReply): List<UUID> {
		val master = ItipMessages.eventId(reply.uid)?.let { events.findById(it).orElse(null) } ?: return emptyList()
		if (master.isOverride || master.isDeleted || !isLocal(master)) return emptyList()
		val targets =
			if (reply.recurrenceId == null) {
				listOf(master) + events.findByParentEventIdOrderByIdAsc(master.id).filter { !it.isDeleted }
			} else {
				events.findByParentEventIdOrderByIdAsc(master.id).filter { !it.isDeleted && it.recurrenceId == reply.recurrenceId }
			}
		// The answer counts only from someone the event (or the answered occurrence) invites.
		val invited = targets.firstOrNull()?.attendees?.any { it.email.equals(reply.email, ignoreCase = true) } ?: false
		if (!invited) return emptyList()
		if (!takeStamp(master, reply)) return emptyList()
		// A series answer leaves an occurrence alone that was answered later on its own.
		val updated = if (reply.recurrenceId == null) targets.filter { !answeredLater(master, it, reply) } else targets
		return updated.mapNotNull { event -> update(event, reply)?.id }
	}

	/**
	 * Records [reply] as the newest answer of its attendee for [master] and its occurrence; false (nothing recorded)
	 * when an answer taken before is newer. An answer for one occurrence is also too old when it is older than the last
	 * answer taken for the whole series, which set that occurrence too.
	 */
	private fun takeStamp(
		master: CalendarEvent,
		reply: ItipReply,
	): Boolean {
		val last = stamps.find(master.id, reply.recurrenceId, reply.email)
		val series = if (reply.recurrenceId == null) null else stamps.find(master.id, null, reply.email)
		if (isOlder(reply, last) || isOlder(reply, series)) {
			log.debug("Event {}: ignored an invitation reply older than the last one taken", master.id)
			return false
		}
		if (last == null) {
			val occurrence = reply.recurrenceId
			stamps.save(ItipReplyStamp(master.id, occurrence?.at, occurrence?.date, reply.email.lowercase(), reply.sequence, reply.stamp))
			return true
		}
		last.sequence = reply.sequence
		last.stamp = reply.stamp
		stamps.save(last)
		return true
	}

	/** Whether override [event] of [master] holds an answer of the attendee for its occurrence newer than series [reply]. */
	private fun answeredLater(
		master: CalendarEvent,
		event: CalendarEvent,
		reply: ItipReply,
	): Boolean {
		val occurrence = event.recurrenceId ?: return false
		return isOlder(reply, stamps.find(master.id, occurrence, reply.email))
	}

	/** Whether [reply] is strictly older than the answer [stamp] records (never when there is none). */
	private fun isOlder(
		reply: ItipReply,
		stamp: ItipReplyStamp?,
	): Boolean = stamp != null && ItipReply.ORDER.compare(reply, reply.copy(sequence = stamp.sequence, stamp = stamp.stamp)) < 0

	private fun update(
		event: CalendarEvent,
		reply: ItipReply,
	): CalendarEvent? {
		val attendee = event.attendees.firstOrNull { it.email.equals(reply.email, ignoreCase = true) } ?: return null
		if (attendee.status == reply.status) return null
		event.attendees = event.attendees.map { if (it === attendee) it.copy(status = reply.status) else it }
		val saved = events.saveAndFlush(event)
		indexer.index(saved)
		changes.publish(ItemType.EVENT, saved.id, ChangeOp.UPDATED)
		return saved
	}

	private fun isLocal(event: CalendarEvent): Boolean {
		val calendar = calendars.findById(event.calendarId).orElse(null) ?: return false
		return sources.findById(calendar.sourceId).orElse(null)?.type == CalendarSourceType.LOCAL
	}

	/** The text of the `text/calendar` parts of [part] (at most [MAX_CALENDAR_BYTES] each). */
	private fun calendarParts(part: Part): List<String> {
		if (part.isMimeType("multipart/*")) {
			val multipart = part.content as? Multipart ?: return emptyList()
			return (0 until multipart.count).flatMap { calendarParts(multipart.getBodyPart(it)) }
		}
		if (!part.isMimeType("text/calendar") && !part.isMimeType("application/ics")) return emptyList()
		val bytes = part.inputStream.use { it.readNBytes(MAX_CALENDAR_BYTES) }
		return listOf(String(bytes, charset(part)))
	}

	private fun charset(part: Part): Charset =
		try {
			ContentType(part.contentType).getParameter("charset")?.let { Charset.forName(MimeUtility.javaCharset(it)) }
		} catch (_: ParseException) {
			null
		} catch (_: IllegalArgumentException) {
			null
		} ?: Charsets.UTF_8

	override fun destroy() {
		executor.shutdownNow()
	}

	private companion object {
		const val QUEUE_SIZE = 1000
		const val MAX_ATTEMPTS = 3
		const val MAX_CALENDAR_BYTES = 1024 * 1024
	}
}
