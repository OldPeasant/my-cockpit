package ch.sonensei.cockpit.gtd.data

import android.database.sqlite.SQLiteException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException
import java.time.DateTimeException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the server was unreachable on the last GTD load. Shared by all GTD views: one failed load marks them all
 * offline, the next successful one clears it. It also orders the loads and cache writes of all views (see [refreshing]
 * with a view key, and [clearing]).
 */
@Singleton
class GtdConnectivity
    @Inject
    constructor() {
        private val state = MutableStateFlow(false)
        val offline: StateFlow<Boolean> = state.asStateFlow()

        /** Per view key, why its last finished load failed (a view whose last load worked or was offline has no entry). */
        private val failures = MutableStateFlow<Map<String, RefreshResult.Failed>>(emptyMap())

        /** One lock per cached view: its loads run one after another, so the load that started last writes last. */
        private val viewLocks = ConcurrentHashMap<String, Mutex>()

        /** Cache writes and the logout clear exclude each other; [clears] counts the clears (guarded by it). */
        private val writeLock = Mutex()
        private var clears = 0L

        /**
         * Why the last load of [view] failed, or `null` when it worked, could not reach the server ([offline] says so) or
         * is running again (a retry), so a screen shows its loading state instead of the old error meanwhile.
         */
        fun failure(view: String): Flow<RefreshResult.Failed?> = failures.map { it[view] }.distinctUntilChanged()

        /**
         * Loads [view] with [load] and, if that worked, stores the answer with [store]. Loads of the same view never
         * overlap, and an answer whose load started before the last [clearing] (a logout) is dropped as
         * [RefreshResult.Failed], so it cannot bring the previous session's data back. The outcome is kept as the
         * view's [failure].
         */
        internal suspend fun <T> refreshing(
            view: String,
            load: suspend () -> T,
            store: suspend (T) -> Unit,
        ): RefreshResult =
            viewLocks.getOrPut(view) { Mutex() }.withLock {
                val clearsAtStart = writeLock.withLock { clears }
                failures.update { it - view }
                val result =
                    refreshing {
                        val loaded = load()
                        writeLock.withLock {
                            if (clears != clearsAtStart) throw SessionEnded()
                            store(loaded)
                        }
                    }
                // A load that outlived a logout must not show its failure in the next session.
                if (result is RefreshResult.Failed) {
                    writeLock.withLock { if (clears == clearsAtStart) failures.update { it + (view to result) } }
                }
                result
            }

        /** Runs [clear] (dropping the cached data) while no cache write runs, then resets the offline flag and the failures. */
        internal suspend fun clearing(clear: suspend () -> Unit) {
            writeLock.withLock {
                clears++
                clear()
                failures.value = emptyMap()
            }
            reset()
        }

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
                } catch (_: SQLiteException) {
                    // The cache could not be written (e.g. the device is full); the transaction left it as it was.
                    RefreshResult.Failed(null)
                } catch (_: SessionEnded) {
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

/** The session ended (logout) while a load was running; its answer is not stored. */
private class SessionEnded : Exception("session ended")

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
         * Emits once at once, then for every change of one of [types] and after every reconnect of the change stream,
         * which does not replay events missed while disconnected. The stream's first connection does not emit: the load
         * at once covers it, and it still happens when the stream cannot connect (offline). Starting it also makes sure
         * the periodic sync runs.
         */
        fun on(vararg types: String): Flow<Unit> =
            merge(
                flowOf(Unit),
                changes.signals
                    .filter { it is ChangeSignal.Reconnected || (it is ChangeSignal.Changed && it.type in types) }
                    .map { },
            ).onStart { scheduler.ensureScheduled() }
    }

internal object ChangeTypes {
    const val TASK = "TASK"
    const val PROJECT = "PROJECT"
    const val CONTEXT = "CONTEXT"
}
