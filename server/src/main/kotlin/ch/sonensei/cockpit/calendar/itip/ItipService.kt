package ch.sonensei.cockpit.calendar.itip

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.compose.MailSender
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailIdentityService
import ch.sonensei.cockpit.mail.model.MailAccount
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID

/** What was sent: the method and the addresses it went to. */
data class SentInvitation(
	val method: ItipMethod,
	val recipients: List<String>,
)

/**
 * Sends invitations for events of LOCAL calendars by mail (SPEC.md §6.2, iTIP over iMIP, RFC 6047): a REQUEST invites
 * the attendees or tells them about a change, a CANCEL calls the event off. The mail goes out over a mail account of
 * the user, whose address (its default identity, else the account's) is the organizer, so replies arrive there (see
 * [ItipReplyProcessor]).
 *
 * A series master is sent with its overrides, an override on its own. Events of synced calendars are not sent: their
 * provider invites the attendees itself.
 */
@Service
class ItipService(
	private val events: CalendarEventRepository,
	private val calendars: CalendarRepository,
	private val sources: CalendarSourceRepository,
	private val accounts: MailAccountService,
	private val identities: MailIdentityService,
	private val sender: MailSender,
	private val settings: SettingsService,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {
	private val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }
	private val session: Session = Session.getInstance(Properties())

	/** The mail to send, read in one transaction. */
	private class Invitation(
		val account: MailAccount,
		val organizer: ItipOrganizer,
		val recipients: List<String>,
		val subject: String,
		val text: String,
		val calendar: String,
	)

	/**
	 * Sends [method] for event [id] over mail account [accountId]: to [recipients] (default: every attendee but the
	 * organizer). A REQUEST only goes to attendees of the event; a CANCEL may also go to people that are no longer
	 * attendees (they are told they are no longer invited), and also for an event in the Trash.
	 *
	 * @throws NotFoundException (404) when there is no such event
	 * @throws InvalidRequestException (400) for an unknown mail account or recipients that are not allowed
	 * @throws ConflictException (409) when the event is not in a LOCAL calendar or there is nobody to send to
	 * @throws ch.sonensei.cockpit.mail.compose.MailSendRejectedException when the SMTP server refused the message
	 * @throws ch.sonensei.cockpit.mail.imap.MailConnectionException when the SMTP server failed
	 */
	fun send(
		id: UUID,
		method: ItipMethod,
		accountId: UUID,
		recipients: List<String>?,
	): SentInvitation {
		val invitation = readTx.execute { invitation(id, method, accountId, recipients) }!!
		val mime = MimeMessage(session)
		mime.setFrom(InternetAddress(invitation.organizer.email, invitation.organizer.name, Charsets.UTF_8.name()))
		mime.setRecipients(Message.RecipientType.TO, invitation.recipients.map { InternetAddress(it) }.toTypedArray())
		mime.setSubject(invitation.subject, Charsets.UTF_8.name())
		mime.sentDate = Date.from(clock.instant())
		val alternative = MimeMultipart("alternative")
		alternative.addBodyPart(MimeBodyPart().apply { setText(invitation.text, Charsets.UTF_8.name()) })
		alternative.addBodyPart(
			MimeBodyPart().apply { setContent(invitation.calendar, "text/calendar; charset=UTF-8; method=${method.name}") },
		)
		mime.setContent(alternative)
		mime.saveChanges()
		sender.send(invitation.account, mime)
		return SentInvitation(method, invitation.recipients)
	}

	private fun invitation(
		id: UUID,
		method: ItipMethod,
		accountId: UUID,
		requested: List<String>?,
	): Invitation {
		val event = events.findById(id).orElse(null) ?: throw NotFoundException("Event not found")
		val master = event.parentEventId?.let { events.findById(it).orElseThrow() }
		val trashed = event.isDeleted || master?.isDeleted == true
		if (trashed && method == ItipMethod.REQUEST) throw NotFoundException("Event not found")
		val calendar = calendars.findById(event.calendarId).orElseThrow()
		if (sources.findById(calendar.sourceId).orElseThrow().type != CalendarSourceType.LOCAL) {
			throw ConflictException("Invitations are only sent for events of local calendars; synced calendars invite through their provider")
		}
		val account =
			try {
				accounts.get(accountId)
			} catch (e: NotFoundException) {
				throw InvalidRequestException("accountId", "is no mail account")
			}
		val identity = identities.list(account.id).firstOrNull { it.isDefault }
		val organizer = ItipOrganizer(identity?.email ?: account.emailAddress, identity?.name ?: account.displayName)
		val attendees = event.attendees.map { it.email }
		val recipients =
			if (requested == null) {
				attendees.filterNot { it.equals(organizer.email, ignoreCase = true) }
			} else {
				requested.forEach { address ->
					if (!isAddress(address)) throw InvalidRequestException("recipients", "contains an invalid address: $address")
					if (method == ItipMethod.REQUEST && attendees.none { it.equals(address, ignoreCase = true) }) {
						throw InvalidRequestException("recipients", "may only name attendees of the event for a REQUEST")
					}
				}
				requested.distinctBy { it.lowercase() }
			}
		if (recipients.isEmpty()) throw ConflictException("The event has no attendees to send the invitation to")
		val content = if (event.isOverride || !event.isRecurring) listOf(event) else listOf(event) + overrides(event)
		val uninvited = recipients.filter { address -> attendees.none { it.equals(address, ignoreCase = true) } }
		val text =
			ItipMessages.build(
				method,
				content,
				master,
				organizer,
				clock.instant(),
				attendeesOnly = uninvited.takeIf { method == ItipMethod.CANCEL && it.isNotEmpty() && !trashed },
			)
		val prefix = if (method == ItipMethod.REQUEST) "Invitation" else "Cancelled"
		return Invitation(account, organizer, recipients, "$prefix: ${event.title}", summary(method, event), text)
	}

	/** The live overrides of series [master]; cancelled occurrences are in its EXDATEs. */
	private fun overrides(master: CalendarEvent): List<CalendarEvent> = events.findByParentEventIdOrderByIdAsc(master.id).filter { !it.isDeleted }

	/** The plain-text part, for mail clients that do not show the invitation. */
	private fun summary(
		method: ItipMethod,
		event: CalendarEvent,
	): String {
		val zone = settings.timeZone()
		return buildString {
			appendLine(if (method == ItipMethod.REQUEST) "You are invited to: ${event.title}" else "This event was cancelled: ${event.title}")
			appendLine("When: ${describe(Timing.of(event), zone)}${if (event.isRecurring) " (recurring)" else ""}")
			event.location?.let { appendLine("Where: $it") }
			event.conferenceUrl?.let { appendLine("Join: $it") }
			event.description?.let { append('\n').appendLine(it) }
		}
	}

	private fun describe(
		timing: Timing,
		zone: ZoneId,
	): String =
		when (timing) {
			is Timing.AllDay -> {
				val last = timing.endDate.minusDays(1)
				if (last == timing.startDate) DATE.format(timing.startDate) else "${DATE.format(timing.startDate)} – ${DATE.format(last)}"
			}
			is Timing.Timed -> "${DATE_TIME.format(timing.start.atZone(zone))} – ${DATE_TIME.format(timing.end.atZone(zone))} (${zone.id})"
		}

	private fun isAddress(address: String): Boolean =
		runCatching { InternetAddress(address, true).address == address && address.contains('@') }.getOrDefault(false)

	private companion object {
		val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.ENGLISH)
		val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.ENGLISH)
	}
}
