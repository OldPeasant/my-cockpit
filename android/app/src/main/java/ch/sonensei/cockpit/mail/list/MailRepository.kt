package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.apis.MailAccountsApi
import ch.sonensei.cockpit.core.network.generated.models.CreateMailFolderRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Mail accounts, folders and message lists on the server (SPEC.md §7.4): no local cache, the list loads what it shows. */
@Singleton
class MailRepository
    @Inject
    constructor(
        private val api: MailApi,
        private val accountsApi: MailAccountsApi,
    ) {
        /** Every account by its label (email address), with its selectable folders. */
        suspend fun accounts(): MailResult<List<MailAccountFolders>> =
            mailCall {
                val accounts = mailBody(api.accounts()).items.map { it.toAccount() }.sortedBy { it.label.lowercase() }
                coroutineScope {
                    accounts
                        .map { account ->
                            async {
                                val folders =
                                    mailBody(api.folders(account.id.toString()))
                                        .items
                                        .filter { it.selectable }
                                        .map { it.toFolder() }
                                        .sortedWith(folderOrder)
                                MailAccountFolders(account, folders)
                            }
                        }.awaitAll()
                }
            }

        /** One page of [mailbox], newest first, after [cursor] (`null`: the first page). */
        suspend fun messages(
            mailbox: Mailbox,
            cursor: String?,
        ): MailResult<MailPage> =
            mailCall {
                val folder = mailbox as? Mailbox.Folder
                val page =
                    mailBody(
                        api.messages(
                            accountId = folder?.accountId?.toString(),
                            folderId = folder?.folderId?.toString(),
                            cursor = cursor,
                            limit = PAGE_SIZE,
                        ),
                    )
                MailPage(page.items.map { it.toSummary() }, page.nextCursor?.takeIf { it.isNotEmpty() })
            }

        /**
         * The mail matching [query] in [mailbox] (the unified Inbox searches all folders of all accounts), newest first;
         * [server] also searches on the mail servers (IMAP SEARCH), which is slower and finds older mail.
         */
        suspend fun search(
            query: String,
            mailbox: Mailbox,
            server: Boolean,
        ): MailResult<MailSearchResult> =
            mailCall {
                val folder = mailbox as? Mailbox.Folder
                val result =
                    mailBody(
                        api.search(
                            query = query,
                            accountId = folder?.accountId?.toString(),
                            folderId = folder?.folderId?.toString(),
                            server = server.takeIf { it },
                            limit = PAGE_SIZE,
                        ),
                    )
                MailSearchResult(
                    items = result.items.map { it.toSummary() },
                    hasMore = result.hasMore || result.server?.truncated == true,
                    failedAccounts =
                        result.server
                            ?.failures
                            .orEmpty()
                            .mapNotNull { f -> f.accountId?.let { runCatching { UUID.fromString(it) }.getOrNull() } }
                            .distinct(),
                )
            }

        /**
         * Caches up to [LOAD_OLDER_LIMIT] messages of [folder] older than the sync window from the mail server (SPEC.md
         * §7.2); the result tells how many were added.
         */
        suspend fun loadOlder(folder: Mailbox.Folder): MailResult<Int> =
            mailCall { mailBody(api.loadOlder(folder.folderId.toString(), LOAD_OLDER_LIMIT)).loaded }

        /** The newest [wanted] mails of [mailbox] (fewer if it has fewer), read page by page, with the cursor after them. */
        suspend fun messagesUpTo(
            mailbox: Mailbox,
            wanted: Int,
        ): MailResult<MailPage> =
            mailCall {
                val folder = mailbox as? Mailbox.Folder
                val items = LinkedHashMap<UUID, MailSummary>()
                var cursor: String? = null
                do {
                    val limit = (wanted - items.size).coerceIn(1, MAX_PAGE_SIZE)
                    val page =
                        mailBody(api.messages(folder?.accountId?.toString(), folder?.folderId?.toString(), cursor, limit))
                    page.items.map { it.toSummary() }.forEach { items.putIfAbsent(it.id, it) }
                    cursor = page.nextCursor?.takeIf { it.isNotEmpty() }
                } while (cursor != null && items.size < wanted && page.items.isNotEmpty())
                MailPage(items.values.toList(), cursor)
            }

        /** Archives or deletes [id] on the mail server; `Ok(false)` when the server could not do it for this message. */
        suspend fun act(
            id: UUID,
            action: MailListAction,
        ): MailResult<Boolean> = act(id, action.wire)

        /** Marks [id] read; failures are ignored by callers, the list shows the mail server's state on the next load. */
        suspend fun markRead(id: UUID): MailResult<Boolean> = act(id, "READ")

        /**
         * Applies [action] to [ids] (moving them to [target] for MOVE), at most [MAX_ACTION_IDS] per request. Each mail
         * succeeds or fails on its own: the result holds the ids the server did it for. A request that fails as a whole
         * fails the call, also when an earlier batch went through (the list then reloads anyway).
         */
        suspend fun actAll(
            ids: List<UUID>,
            action: MailListAction,
            target: UUID? = null,
        ): MailResult<Set<UUID>> =
            mailCall {
                val done = HashSet<UUID>()
                ids.distinct().chunked(MAX_ACTION_IDS).forEach { batch ->
                    val request = MailActionRequestDto(action.wire, batch.map { it.toString() }, target?.toString())
                    val ok = mailBody(api.act(request)).results.filter { it.ok }.mapTo(HashSet()) { it.id }
                    batch.filterTo(done) { it.toString() in ok }
                }
                done
            }

        /**
         * "Next action & archive" (SPEC.md §7.3): one Next action per mail of [ids], linked to it, then the mails are
         * archived, at most [MAX_TASK_IDS] per request. A failed archive keeps its task ([MailNextAction.archived]
         * false); a request that fails as a whole (e.g. a mail that is gone) fails the call, nothing was created for
         * its batch.
         */
        suspend fun toNextActions(ids: List<UUID>): MailResult<List<MailNextAction>> =
            mailCall {
                ids.distinct().chunked(MAX_TASK_IDS).flatMap { batch ->
                    mailBody(api.toTasks(MailToTasksRequestDto(batch.map { it.toString() }))).results.map {
                        MailNextAction(
                            mailId = UUID.fromString(it.id),
                            taskId = UUID.fromString(requireNotNull(it.task) { "no task" }.id),
                            title = it.task.title.orEmpty(),
                            archived = it.archived,
                            archiveErrorCode = it.archiveErrorCode?.takeUnless { _ -> it.archived },
                        )
                    }
                }
            }

        private suspend fun act(
            id: UUID,
            action: String,
        ): MailResult<Boolean> =
            mailCall {
                val results = mailBody(api.act(MailActionRequestDto(action, listOf(id.toString())))).results
                results.firstOrNull { it.id == id.toString() }?.ok == true
            }

        /** Creates the folder [name] in [parent] (or at the top level of [accountId]) on the mail server. */
        suspend fun createFolder(
            accountId: UUID,
            parent: MailFolder?,
            name: String,
        ): MailResult<MailFolder> =
            mailCall {
                mailBody(accountsApi.createMailFolder(accountId, CreateMailFolderRequest(name = name, parentId = parent?.id))).toFolder()
            }

        /** Renames [folder] on the mail server; a `Failed(412)` means it changed since the list was loaded. */
        suspend fun renameFolder(
            folder: MailFolder,
            name: String,
        ): MailResult<MailFolder> =
            mailCall {
                mailBody(
                    api.renameFolder(folder.accountId.toString(), folder.id.toString(), etag(folder.version), RenameMailFolderDto(name)),
                ).toFolder()
            }

        /** Deletes [folder] with its subfolders and their mail on the mail server. */
        suspend fun deleteFolder(folder: MailFolder): MailResult<Unit> =
            mailCall {
                val response = accountsApi.deleteMailFolder(folder.accountId, folder.id, etag(folder.version))
                if (!response.isSuccessful) throw MailHttpFailure(response.code())
            }

        internal companion object {
            const val PAGE_SIZE = 50

            /** The largest page the server returns. */
            const val MAX_PAGE_SIZE = 200

            /** Messages one "load older from server" caches (the server's default). */
            const val LOAD_OLDER_LIMIT = 100

            /** The most ids the server takes in one action request. */
            const val MAX_ACTION_IDS = 1000

            /** The server takes at most 100 mails per "Next action & archive" request. */
            const val MAX_TASK_IDS = 100
        }
    }

/** Runs a mail call: no connection or a 5xx answer is [MailResult.Offline], a refusal or unreadable answer [MailResult.Failed]. */
internal suspend fun <T> mailCall(block: suspend () -> T): MailResult<T> =
    try {
        MailResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        MailResult.Offline
    } catch (e: MailHttpFailure) {
        if (e.status >= SERVER_ERROR) MailResult.Offline else MailResult.Failed(e.status)
    } catch (_: SerializationException) {
        MailResult.Failed(null)
    } catch (_: IllegalArgumentException) {
        MailResult.Failed(null)
    }

private const val SERVER_ERROR = 500

private fun etag(version: Long) = "\"$version\""

/** A non-2xx answer; its message carries only the status, never the body. */
internal class MailHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

internal fun <T> mailBody(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw MailHttpFailure(response.code())
    return body
}
