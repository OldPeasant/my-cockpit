package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.FilesRepository
import ch.sonensei.cockpit.files.FilesResult
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.mailBody
import ch.sonensei.cockpit.mail.list.mailCall
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A suggestion for a recipient field: a contact ([contactId] set) or a recent correspondent. */
data class RecipientSuggestion(
    val address: String,
    val name: String?,
    val contactId: UUID? = null,
)

/** What compose looks up while editing: recipient suggestions and the cockpit Files to attach. */
interface ComposeLookup {
    /** Contacts first, then recent correspondents, as the server merges them; each address once. */
    suspend fun suggestions(query: String): MailResult<List<RecipientSuggestion>>

    /** Folder [folderId] of the drive (`null`: the root), folders first. */
    suspend fun folder(folderId: UUID?): MailResult<List<DriveEntry>>

    /** Looks up nothing (tests and previews). */
    object None : ComposeLookup {
        override suspend fun suggestions(query: String): MailResult<List<RecipientSuggestion>> = MailResult.Ok(emptyList())

        override suspend fun folder(folderId: UUID?): MailResult<List<DriveEntry>> = MailResult.Ok(emptyList())
    }
}

/** Suggestions from `GET /api/v1/contacts/autocomplete`, folders from the Files feature's repository. */
@Singleton
class RemoteComposeLookup
    @Inject
    constructor(
        private val api: MailComposeApi,
        private val files: FilesRepository,
    ) : ComposeLookup {
        override suspend fun suggestions(query: String): MailResult<List<RecipientSuggestion>> {
            if (query.isBlank()) return MailResult.Ok(emptyList())
            return mailCall {
                val seen = mutableSetOf<String>()
                mailBody(api.autocomplete(query.trim(), SUGGESTION_LIMIT)).mapNotNull { item ->
                    val address = item.email?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    if (!seen.add(address.lowercase())) return@mapNotNull null
                    RecipientSuggestion(
                        address = address,
                        name = item.name?.trim()?.takeIf { it.isNotEmpty() },
                        contactId = item.contactId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                    )
                }
            }
        }

        override suspend fun folder(folderId: UUID?): MailResult<List<DriveEntry>> =
            when (val result = files.list(folderId)) {
                is FilesResult.Ok -> MailResult.Ok(result.value)
                FilesResult.Offline -> MailResult.Offline
                is FilesResult.Failed -> MailResult.Failed(result.status)
            }

        internal companion object {
            const val SUGGESTION_LIMIT = 8
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ComposeLookupModule {
    @Binds
    abstract fun composeLookup(lookup: RemoteComposeLookup): ComposeLookup
}
