package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.core.network.generated.apis.ContactsApi
import ch.sonensei.cockpit.core.network.generated.models.ContactRequest
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.time.DateTimeException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ContactsModule {
    @Provides
    fun contactsApi(retrofit: Retrofit): ContactsApi = retrofit.create(ContactsApi::class.java)
}

/** The outcome of a load. */
sealed interface ContactsResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ContactsResult<T>

    /** No connection (or the server is down). */
    data object Offline : ContactsResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : ContactsResult<Nothing>
}

/** Contacts on the server (SPEC.md §8.3): no local cache, every screen loads what it shows. */
@Singleton
class ContactsRepository
    @Inject
    constructor(
        private val api: ContactsApi,
    ) {
        /** Every contact matching [query] (blank: all), in the server's order: by display name. */
        suspend fun list(query: String?): ContactsResult<List<Contact>> =
            load {
                val q = query?.trim()?.takeIf { it.isNotEmpty() }
                val all = mutableListOf<Contact>()
                var cursor: String? = null
                for (page in 1..MAX_PAGES) {
                    val response = body(api.listContacts(q = q, cursor = cursor, limit = PAGE_SIZE))
                    response.items.mapTo(all) { it.toContact() }
                    cursor = response.nextCursor ?: break
                }
                all.distinctBy { it.id }
            }

        suspend fun get(id: UUID): ContactsResult<Contact> = load { body(api.getContact(id)).toContact() }

        /** The contact's Waiting For tasks, recent mails and upcoming events (its links are in the linked-items panel). */
        suspend fun related(id: UUID): ContactsResult<ContactRelated> = load { body(api.getContactRelated(id)).toRelated() }

        /** Creates a LOCAL contact. */
        suspend fun create(request: ContactRequest): ContactsResult<Contact> = load { body(api.createContact(request)).toContact() }

        /** Replaces [base]'s fields with [request]; a change made elsewhere meanwhile fails with 409 or 412. */
        suspend fun update(
            base: Contact,
            request: ContactRequest,
        ): ContactsResult<Contact> = load { body(api.updateContact(base.id, request, etag(base.version))).toContact() }

        /** Moves [base] to the Trash. */
        suspend fun delete(base: Contact): ContactsResult<Unit> =
            load {
                val response = api.deleteContact(base.id, etag(base.version))
                if (!response.isSuccessful) throw ContactsHttpFailure(response.code())
            }

        private suspend fun <T> load(block: suspend () -> T): ContactsResult<T> =
            try {
                ContactsResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                ContactsResult.Offline
            } catch (e: ContactsHttpFailure) {
                if (e.status >= SERVER_ERROR) ContactsResult.Offline else ContactsResult.Failed(e.status)
            } catch (_: SerializationException) {
                ContactsResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                ContactsResult.Failed(null)
            } catch (_: DateTimeException) {
                ContactsResult.Failed(null)
            }

        private companion object {
            const val PAGE_SIZE = 200
            const val MAX_PAGES = 50
            const val SERVER_ERROR = 500
        }
    }

/** A non-2xx answer; its message carries only the status, never the body. */
private class ContactsHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun etag(version: Long) = "\"$version\""

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw ContactsHttpFailure(response.code())
    return body
}
