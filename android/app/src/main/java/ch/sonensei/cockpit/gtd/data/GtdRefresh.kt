package ch.sonensei.cockpit.gtd.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException
import java.time.DateTimeException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the server was unreachable on the last GTD load. Shared by all GTD views: one failed load marks them all
 * offline, the next successful one clears it.
 */
@Singleton
class GtdConnectivity
    @Inject
    constructor() {
        private val state = MutableStateFlow(false)
        val offline: StateFlow<Boolean> = state.asStateFlow()

        /** Loads with [block] and records its outcome; a [RefreshResult.Failed] leaves the flag as it is. */
        internal suspend fun refreshing(block: suspend () -> Unit): RefreshResult {
            val result =
                try {
                    block()
                    RefreshResult.Refreshed
                } catch (_: IOException) {
                    RefreshResult.Offline
                } catch (e: HttpFailure) {
                    if (e.status >= SERVER_ERROR) RefreshResult.Offline else RefreshResult.Failed(e.status)
                } catch (_: SerializationException) {
                    RefreshResult.Failed(null)
                } catch (_: IllegalArgumentException) {
                    RefreshResult.Failed(null)
                } catch (_: DateTimeException) {
                    RefreshResult.Failed(null)
                }
            when (result) {
                RefreshResult.Refreshed -> state.value = false
                RefreshResult.Offline -> state.value = true
                is RefreshResult.Failed -> Unit
            }
            return result
        }

        internal fun reset() {
            state.value = false
        }

        private companion object {
            const val SERVER_ERROR = 500
        }
    }

/** A non-2xx answer; its message carries only the status, never the body. */
internal class HttpFailure(
    val status: Int,
) : Exception("HTTP $status")

/** The body of a successful [response], or [HttpFailure]. */
internal fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw HttpFailure(response.code())
    return body
}

/** Every item of a cursor-paged listing; stops after [MAX_PAGES] pages so a server bug cannot loop forever. */
internal suspend fun <P, T> allPages(
    page: suspend (cursor: String?) -> Response<P>,
    items: (P) -> List<T>,
    nextCursor: (P) -> String?,
): List<T> {
    val all = mutableListOf<T>()
    var cursor: String? = null
    repeat(MAX_PAGES) {
        val body = body(page(cursor))
        all += items(body)
        cursor = nextCursor(body) ?: return all
    }
    return all
}

internal const val PAGE_SIZE = 200
internal const val MAX_PAGES = 50

/** When views reload: once when they are first observed, then on each relevant server change while observed. */
class GtdRefreshTriggers
    @Inject
    constructor(
        private val changes: GtdChangeStream,
        private val scheduler: GtdSyncScheduler,
    ) {
        /**
         * Emits once at once, then for every change of one of [types] and after every (re)connect of the change stream,
         * which does not replay events missed while disconnected. Starting it also makes sure the periodic sync runs.
         */
        fun on(vararg types: String): Flow<Unit> =
            merge(
                flowOf(Unit),
                changes.signals
                    .filter { it is ChangeSignal.Connected || (it is ChangeSignal.Changed && it.type in types) }
                    .map { },
            ).onStart { scheduler.ensureScheduled() }
    }

internal object ChangeTypes {
    const val TASK = "TASK"
    const val PROJECT = "PROJECT"
    const val CONTEXT = "CONTEXT"
}
