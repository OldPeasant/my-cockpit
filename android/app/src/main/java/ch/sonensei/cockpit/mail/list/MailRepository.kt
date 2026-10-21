package ch.sonensei.cockpit.mail.list

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
    ) {
        /** Every account by name, with its selectable folders. */
        suspend fun accounts(): MailResult<List<MailAccountFolders>> =
            mailCall {
                val accounts = mailBody(api.accounts()).items.map { it.toAccount() }.sortedBy { it.name.lowercase() }
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

        /** Archives or deletes [id] on the mail server; `Ok(false)` when the server could not do it for this message. */
        suspend fun act(
            id: UUID,
            action: MailListAction,
        ): MailResult<Boolean> = act(id, action.wire)

        /** Marks [id] read; failures are ignored by callers, the list shows the mail server's state on the next load. */
        suspend fun markRead(id: UUID): MailResult<Boolean> = act(id, "READ")

        private suspend fun act(
            id: UUID,
            action: String,
        ): MailResult<Boolean> =
            mailCall {
                val results = mailBody(api.act(MailActionRequestDto(action, listOf(id.toString())))).results
                results.firstOrNull { it.id == id.toString() }?.ok == true
            }

        internal companion object {
            const val PAGE_SIZE = 50
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

/** A non-2xx answer; its message carries only the status, never the body. */
internal class MailHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

internal fun <T> mailBody(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw MailHttpFailure(response.code())
    return body
}
