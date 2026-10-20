package ch.sonensei.cockpit.gtd.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** Fictitious ids, readable in failures. */
fun uuid(n: Int): String = "00000000-0000-7000-8000-%012d".format(n)

fun taskJson(
    n: Int,
    title: String,
    list: String = "INBOX",
    extra: String = "",
    contextIds: List<String> = emptyList(),
): String =
    """{"id":"${uuid(n)}","title":"$title","list":"$list","position":$n,"version":1,""" +
        """"contextIds":[${contextIds.joinToString(",") { "\"$it\"" }}],""" +
        """"createdAt":"2026-10-01T08:00:00Z","updatedAt":"2026-10-01T08:00:00Z"$extra}"""

fun taskPage(
    vararg tasks: String,
    nextCursor: String? = null,
): String = """{"items":[${tasks.joinToString(",")}]${nextCursor?.let { ""","nextCursor":"$it"""" } ?: ""}}"""

fun projectJson(
    n: Int,
    title: String,
    status: String = "ACTIVE",
): String =
    """{"id":"${uuid(n)}","title":"$title","status":"$status","stalled":false,"version":2,""" +
        """"createdAt":"2026-10-01T08:00:00Z","updatedAt":"2026-10-01T08:00:00Z"}"""

fun contextJson(
    n: Int,
    name: String,
): String = """{"id":"${uuid(n)}","name":"$name","position":$n,"version":1,"color":"#3366ff"}"""

fun json(body: String): MockResponse =
    MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

fun status(code: Int): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .build()

/**
 * Answers API calls from a queue per path (`/api/v1/tasks?list=INBOX` is keyed by its path plus query) and the event
 * stream from [events]; an unknown request gets a 404.
 */
class RoutingDispatcher : Dispatcher() {
    private val routes = mutableMapOf<String, ConcurrentLinkedQueue<MockResponse>>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile var events: () -> MockResponse = { status(503) }

    /** Runs on the server thread after an API call's answer was taken from its queue, e.g. to hold it back. */
    @Volatile var beforeAnswer: (RecordedRequest) -> Unit = {}

    fun enqueue(
        key: String,
        response: MockResponse,
    ) {
        synchronized(routes) { routes.getOrPut(key) { ConcurrentLinkedQueue() } }.add(response)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.url.encodedPath
        if (path == "/api/v1/events") return events()
        val key = listOfNotNull(path, request.url.queryParameter("list"), request.url.queryParameter("due")?.lowercase()).joinToString("|")
        val response = synchronized(routes) { routes[key] }?.poll() ?: status(404)
        beforeAnswer(request)
        return response
    }
}

/** A complete data layer against [server]; [close] releases it. */
class GtdHarness(
    val server: MockWebServer,
    val dispatcher: RoutingDispatcher,
    retryDelaysMs: List<Long> = listOf(50L),
    stopTimeoutMs: Long = GtdChangeStream.STOP_TIMEOUT_MS,
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val database: GtdCacheDatabase =
        Room
            .inMemoryDatabaseBuilder(context, GtdCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    val dao: GtdCacheDao = database.dao()
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)
    val connectivity = GtdConnectivity()
    val scheduler = FakeScheduler()
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val changes = GtdChangeStream(client, server.url("/").toString(), scope, Dispatchers.IO, retryDelaysMs, stopTimeoutMs)
    val triggers = GtdRefreshTriggers(changes, scheduler)
    private val retrofit =
        Retrofit
            .Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
            .build()
    val tasks = TaskRepository(retrofit.create(TasksApi::class.java), dao, connectivity, triggers, clock)
    val projects = ProjectRepository(retrofit.create(ProjectsApi::class.java), dao, connectivity, triggers, clock)
    val contexts = ContextRepository(retrofit.create(ProjectsApi::class.java), dao, connectivity, triggers, clock)

    override fun close() {
        scope.cancel()
        database.close()
    }
}

class FakeScheduler : GtdSyncScheduler {
    @Volatile var scheduled = 0

    @Volatile var cancelled = 0

    override fun ensureScheduled() {
        scheduled++
    }

    override fun cancel() {
        cancelled++
    }
}

const val TASKS = "/api/v1/tasks"
const val PROJECTS = "/api/v1/projects"
const val CONTEXTS = "/api/v1/contexts"
