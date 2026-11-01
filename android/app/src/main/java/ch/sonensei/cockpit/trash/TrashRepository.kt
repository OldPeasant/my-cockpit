package ch.sonensei.cockpit.trash

import ch.sonensei.cockpit.core.network.generated.apis.TrashApi
import ch.sonensei.cockpit.gtd.data.GtdSync
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.time.DateTimeException
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TrashModule {
    @Provides
    fun trashApi(retrofit: Retrofit): TrashApi = retrofit.create(TrashApi::class.java)
}

/** The outcome of a load or a change. */
sealed interface TrashResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : TrashResult<T>

    /** No connection (or the server is down): nothing changed. */
    data object Offline : TrashResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : TrashResult<Nothing>
}

/** Reloads the app's cached lists an item type appears in, so a restored item shows up there at once. */
fun interface RestoredItemLists {
    suspend fun reload(type: String)
}

/**
 * The Trash (SPEC.md §4.2) on the server: no local cache, the screen loads what it shows. The lists reloaded after a
 * restore are reloaded in [appScope], so the reload neither delays the restore nor stops when the screen is left.
 */
@Singleton
class TrashRepository internal constructor(
    private val api: TrashApi,
    private val lists: RestoredItemLists,
    private val appScope: CoroutineScope,
) {
    internal constructor(api: TrashApi, gtd: GtdSync, appScope: CoroutineScope) : this(
        api,
        RestoredItemLists { type -> if (type in GTD_TYPES) gtd.refreshAll() },
        appScope,
    )

    @Inject
    constructor(api: TrashApi, gtd: GtdSync) : this(api, gtd, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** Every deleted item, most recently deleted first (the server's order). */
    suspend fun list(): TrashResult<List<TrashItem>> =
        load {
            val all = mutableListOf<TrashItem>()
            var cursor: String? = null
            for (page in 1..MAX_PAGES) {
                val response = body(api.listTrash(cursor = cursor, limit = PAGE_SIZE))
                response.items.mapTo(all) { it.toTrashItem() }
                cursor = response.nextCursor ?: break
            }
            all.distinctBy { it.key }
        }

    /**
     * Takes [item] out of the Trash. Returns as soon as the server restored it; its own list is reloaded afterwards,
     * in the background, where the app caches it.
     */
    suspend fun restore(item: TrashItem): TrashResult<Unit> {
        val result = load { expectSuccess(api.restoreTrashItem(item.type, item.id)) }
        if (result is TrashResult.Ok) {
            // The restore itself worked: a reload that fails only delays the item until the next sync.
            appScope.launch { load { lists.reload(item.type) } }
        }
        return result
    }

    /** Deletes [item] for good. */
    suspend fun purge(item: TrashItem): TrashResult<Unit> = load { expectSuccess(api.purgeTrashItem(item.type, item.id)) }

    private suspend fun <T> load(block: suspend () -> T): TrashResult<T> =
        try {
            TrashResult.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            TrashResult.Offline
        } catch (e: TrashHttpFailure) {
            if (e.status >= SERVER_ERROR) TrashResult.Offline else TrashResult.Failed(e.status)
        } catch (_: SerializationException) {
            TrashResult.Failed(null)
        } catch (_: IllegalArgumentException) {
            TrashResult.Failed(null)
        } catch (_: DateTimeException) {
            TrashResult.Failed(null)
        }

    companion object {
        const val NOT_FOUND = 404

        /** Item types whose lists the app keeps in its GTD cache. */
        internal val GTD_TYPES = setOf("TASK", "PROJECT")
        internal const val PAGE_SIZE = 200
        internal const val MAX_PAGES = 50
        private const val SERVER_ERROR = 500
    }
}

/** A non-2xx answer; its message carries only the status, never the body. */
private class TrashHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun expectSuccess(response: Response<*>) {
    if (!response.isSuccessful) throw TrashHttpFailure(response.code())
}

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw TrashHttpFailure(response.code())
    return body
}
