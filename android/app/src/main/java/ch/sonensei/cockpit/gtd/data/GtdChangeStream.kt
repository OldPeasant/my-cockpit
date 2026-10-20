package ch.sonensei.cockpit.gtd.data

import ch.sonensei.cockpit.core.network.PLACEHOLDER_BASE_URL
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.core.network.generated.models.ChangeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** What the server's change stream (`GET /api/v1/events`) tells the GTD views. */
sealed interface ChangeSignal {
    /** The stream opened on its first attempt after it started; the views load on start anyway. */
    data object Connected : ChangeSignal

    /**
     * The stream is open again after a refusal, a drop or the server ending it; changes made meanwhile are not replayed,
     * so views reload.
     */
    data object Reconnected : ChangeSignal

    /** An item of [type] (e.g. `TASK`) changed. */
    data class Changed(
        val type: String,
    ) : ChangeSignal
}

/**
 * The server's change notifications for the foreground refresh: one Server-Sent Events connection while anything
 * collects [signals] (the screens collect only while visible), closed 5 s after the last collector leaves. A dropped or
 * refused connection is retried with backoff; the server ends every stream after 15 minutes, which reconnects at once.
 */
@Singleton
class GtdChangeStream internal constructor(
    client: OkHttpClient,
    private val baseUrl: String,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val retryDelaysMs: List<Long>,
    stopTimeoutMs: Long = STOP_TIMEOUT_MS,
) {
    @Inject
    constructor(client: OkHttpClient) : this(
        client,
        // Retrofit's placeholder: the server URL interceptor points it at the configured server.
        PLACEHOLDER_BASE_URL,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        Dispatchers.IO,
        DEFAULT_RETRY_DELAYS_MS,
    )

    // Heartbeats arrive every 25 s; a silent connection is dead after twice that plus a margin.
    private val streamClient = client.newBuilder().readTimeout(Duration.ofSeconds(READ_TIMEOUT_S)).build()

    val signals: SharedFlow<ChangeSignal> =
        channelFlow { connectLoop() }
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMs))

    private suspend fun ProducerScope<ChangeSignal>.connectLoop() {
        var failures = 0
        var first = true
        while (isActive) {
            val ended =
                try {
                    readStream(if (first) ChangeSignal.Connected else ChangeSignal.Reconnected)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Offline, refused (401 after a failed refresh, 5xx) or a broken body: try again later. Not logged:
                    // the exception may carry user data.
                    false
                }
            first = false
            failures = if (ended) 0 else failures + 1
            delay(if (ended) retryDelaysMs.first() else retryDelaysMs[minOf(failures, retryDelaysMs.size) - 1])
        }
    }

    /** Reads one connection (sending [opened] once open) until the server ends it; throws if it cannot be opened or breaks. */
    private suspend fun ProducerScope<ChangeSignal>.readStream(opened: ChangeSignal) {
        val call =
            streamClient.newCall(
                Request
                    .Builder()
                    .url(baseUrl + EVENTS_PATH)
                    .header("Accept", "text/event-stream")
                    .build(),
            )
        // Cancelling the collector cancels the call, which unblocks the read below.
        val canceller =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        try {
            withContext(ioDispatcher) { call.execute() }.use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                send(opened)
                val source = response.body.source()
                val parser = SseParser()
                while (true) {
                    val line = withContext(ioDispatcher) { source.readUtf8Line() } ?: break
                    parser.feed(line)?.let { event -> toSignal(event)?.let { send(it) } }
                }
            }
        } finally {
            canceller.cancel()
        }
    }

    private fun toSignal(event: SseEvent): ChangeSignal? {
        if (event.name != CHANGE_EVENT) return null
        return try {
            ChangeSignal.Changed(Serializer.kotlinxSerializationJson.decodeFromString<ChangeEvent>(event.data).type)
        } catch (_: Exception) {
            // An event this client does not understand is skipped, not fatal for the stream.
            null
        }
    }

    internal companion object {
        const val EVENTS_PATH = "api/v1/events"
        const val CHANGE_EVENT = "change"
        const val READ_TIMEOUT_S = 60L
        const val STOP_TIMEOUT_MS = 5_000L
        val DEFAULT_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    }
}

internal data class SseEvent(
    val name: String,
    val data: String,
)

/** The `text/event-stream` format, line by line: `event:` and `data:` fields, comments, a blank line ends an event. */
internal class SseParser {
    private var name: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feeds one line (without its line break); returns the event the line completes, if any. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> name = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val event = if (hasData) SseEvent(name ?: "message", data.toString()) else null
        name = null
        data.setLength(0)
        hasData = false
        return event
    }
}
