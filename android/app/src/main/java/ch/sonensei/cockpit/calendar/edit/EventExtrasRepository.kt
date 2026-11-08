package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.apis.ContactsApi
import ch.sonensei.cockpit.core.network.generated.apis.MailAccountsApi
import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.models.CalendarResponse
import ch.sonensei.cockpit.core.network.generated.models.CreateLinkRequest
import ch.sonensei.cockpit.core.network.generated.models.CreateNoteRequest
import ch.sonensei.cockpit.core.network.generated.models.CreateTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.ItemRefDto
import ch.sonensei.cockpit.core.network.generated.models.LinkListResponse
import ch.sonensei.cockpit.core.network.generated.models.LinkResponse
import ch.sonensei.cockpit.core.network.generated.models.SendInvitationsRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The links of an item; its own interface so the editor doesn't depend on who provides the generated one. */
interface EventLinksApi {
    @GET("api/v1/links")
    suspend fun listLinks(
        @Query("type") type: String,
        @Query("id") id: UUID,
    ): Response<LinkListResponse>

    @POST("api/v1/links")
    suspend fun createLink(
        @Body body: CreateLinkRequest,
    ): Response<LinkResponse>
}

/** A mail account invitations can be sent over; its address is the organizer. */
data class MailAccount(
    val id: UUID,
    val name: String,
    val email: String,
)

/** What an invitation mail does (iTIP, RFC 5546). */
enum class InvitationMethod { REQUEST, CANCEL }

/** What kind of item a link points to (the API's item types). */
enum class LinkedType { TASK, PROJECT, NOTE, EVENT, EMAIL, FILE, FOLDER, CONTACT, BOOKMARK, JOURNAL_ENTRY, HABIT, OTHER }

/** The other end of a link of the event. */
data class LinkedItem(
    val linkId: UUID,
    val type: LinkedType,
    val itemId: UUID,
    /** `null` when the item no longer exists (or can't be shown). */
    val title: String?,
    val subtitle: String?,
)

/** What "Create task / note from event" ended with. */
sealed interface LinkedCreateResult {
    /** Created and linked to the event. */
    data class Created(
        val title: String,
    ) : LinkedCreateResult

    /** Created, but the link failed: the item exists without it. */
    data class NotLinked(
        val title: String,
    ) : LinkedCreateResult

    /** Not created. */
    data object Failed : LinkedCreateResult
}

/**
 * What the event editor needs besides the event itself (SPEC.md §6.2, §6.3): which calendars are local (only their
 * guests are edited here), contact suggestions for guests, invitations by mail, and the event's linked items.
 */
@Singleton
class EventExtrasRepository
    @Inject
    constructor(
        private val calendars: CalendarsApi,
        private val contacts: ContactsApi,
        private val mailAccounts: MailAccountsApi,
        private val tasks: TasksApi,
        private val notes: NotesApi,
        private val links: EventLinksApi,
    ) {
        /** The ids of the calendars of the local source (writable ones): providers own the guests of the others. */
        suspend fun localCalendars(): CalendarResult<Set<UUID>> =
            calendarCall {
                body(calendars.listCalendars())
                    .items
                    .filter { it.sourceType == CalendarResponse.SourceType.LOCAL && !it.readOnly }
                    .map { it.id }
                    .toSet()
            }

        /**
         * The ids of the writable Google and Microsoft calendars: their events' invitations are answered at the provider
         * (SPEC.md §6.2).
         */
        suspend fun invitationCalendars(): CalendarResult<Set<UUID>> =
            calendarCall {
                body(calendars.listCalendars())
                    .items
                    .filter { it.sourceType in INVITATION_SOURCES && !it.readOnly }
                    .map { it.id }
                    .toSet()
            }

        /** Contacts and correspondents matching [query] that have an address. */
        suspend fun suggestions(query: String): CalendarResult<List<Attendee>> =
            calendarCall {
                body(contacts.autocompleteContacts(query, SUGGESTIONS))
                    .mapNotNull { s -> s.email?.takeIf { it.isNotBlank() }?.let { Attendee(it, s.name?.ifBlank { null }) } }
                    .distinctBy { it.email.lowercase() }
            }

        suspend fun mailAccounts(): CalendarResult<List<MailAccount>> =
            calendarCall { body(mailAccounts.listMailAccounts()).items.map { MailAccount(it.id, it.displayName, it.emailAddress) } }

        /** The user's own addresses (of the mail accounts), lower case: their attendee carries the user's answer. */
        suspend fun ownAddresses(): CalendarResult<Set<String>> =
            calendarCall { body(mailAccounts.listMailAccounts()).items.map { it.emailAddress.lowercase() }.toSet() }

        /**
         * Sends [method] for [eventId] over [accountId] to [recipients] (`null`: every guest but the organizer); the
         * result is the number of addresses the mail went to.
         */
        suspend fun sendInvitations(
            eventId: UUID,
            method: InvitationMethod,
            accountId: UUID,
            recipients: List<String>? = null,
        ): CalendarResult<Int> =
            calendarCall {
                val request =
                    SendInvitationsRequest(
                        accountId = accountId,
                        method = SendInvitationsRequest.Method.valueOf(method.name),
                        recipients = recipients,
                    )
                body(calendars.sendEventInvitations(eventId, request)).recipients.size
            }

        /** The items linked to the event [eventId], either direction. */
        suspend fun links(eventId: UUID): CalendarResult<List<LinkedItem>> =
            calendarCall {
                body(links.listLinks(ItemRefDto.Type.EVENT.value, eventId)).items.map { link ->
                    val other = if (link.direction == LinkResponse.Direction.OUTGOING) link.target else link.source
                    LinkedItem(
                        linkId = link.id,
                        type = LinkedType.entries.firstOrNull { it.name == other.type.name } ?: LinkedType.OTHER,
                        itemId = other.id,
                        title = other.title.takeIf { other.available },
                        subtitle = other.subtitle.takeIf { other.available },
                    )
                }
            }

        /** A new Inbox task titled [title], linked to the event [eventId]. */
        suspend fun createTask(
            title: String,
            eventId: UUID,
        ): LinkedCreateResult {
            val created =
                calendarCall { body(tasks.createTask(CreateTaskRequest(title = title, list = CreateTaskRequest.List.INBOX))).id }
            return linkCreated(created, ItemRefDto.Type.TASK, title, eventId)
        }

        /** A new note titled [title], linked to the event [eventId]. */
        suspend fun createNote(
            title: String,
            eventId: UUID,
        ): LinkedCreateResult {
            val created = calendarCall { body(notes.createNote(CreateNoteRequest(title = title))).id }
            return linkCreated(created, ItemRefDto.Type.NOTE, title, eventId)
        }

        private suspend fun linkCreated(
            created: CalendarResult<UUID>,
            type: ItemRefDto.Type,
            title: String,
            eventId: UUID,
        ): LinkedCreateResult {
            val id = (created as? CalendarResult.Ok)?.value ?: return LinkedCreateResult.Failed
            val linked =
                calendarCall { body(links.createLink(CreateLinkRequest(ItemRefDto(id, type), ItemRefDto(eventId, ItemRefDto.Type.EVENT)))) }
            return if (linked is CalendarResult.Ok) LinkedCreateResult.Created(title) else LinkedCreateResult.NotLinked(title)
        }

        private companion object {
            const val SUGGESTIONS = 8
            val INVITATION_SOURCES = setOf(CalendarResponse.SourceType.GOOGLE, CalendarResponse.SourceType.MICROSOFT)
        }
    }
