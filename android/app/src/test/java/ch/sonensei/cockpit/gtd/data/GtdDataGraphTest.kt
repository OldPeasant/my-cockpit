package ch.sonensei.cockpit.gtd.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.auth.SessionCache
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.core.network.TokenStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.inject.Inject

/** The production Hilt graph of gtd.data: everything the screens and the worker need is provided. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class GtdDataGraphTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var tasks: TaskRepository

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var contexts: ContextRepository

    @Inject lateinit var sync: GtdSync

    @Inject lateinit var connectivity: GtdConnectivity

    @Inject lateinit var scheduler: GtdSyncScheduler

    @Inject lateinit var changes: GtdChangeStream

    @Inject lateinit var caches: Set<@JvmSuppressWildcards SessionCache>

    @Inject lateinit var serverUrls: ServerUrlRepository

    @Inject lateinit var tokenStore: TokenStore

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val eventAuthorizations = mutableListOf<String?>()

    @Before
    fun setUp() {
        hilt.inject()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.url.encodedPath != "/cockpit/api/v1/events") return MockResponse.Builder().code(404).build()
                    synchronized(eventAuthorizations) { eventAuthorizations += request.headers["Authorization"] }
                    return MockResponse
                        .Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/event-stream")
                        .body(CHANGE_EVENT)
                        .build()
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the graph provides the repositories, the sync and the WorkManager scheduler`() {
        assertNotNull(tasks)
        assertNotNull(projects)
        assertNotNull(contexts)
        assertNotNull(sync)
        assertTrue(scheduler is WorkManagerGtdSyncScheduler)
        val entryPoint = EntryPointAccessors.fromApplication(context, GtdSyncEntryPoint::class.java)
        assertNotNull(entryPoint.gtdSync())
    }

    @Test
    fun `the offline flag is one shared singleton`() {
        val entryPoint = EntryPointAccessors.fromApplication(context, GtdDataGraphEntryPoint::class.java)

        assertSame(connectivity, entryPoint.connectivity())
        assertSame(tasks, entryPoint.tasks())
    }

    @Test
    fun `the session caches cleared on logout include the gtd cache`() =
        runBlocking {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            connectivity.refreshing { throw IOException("unreachable") }
            assertTrue(connectivity.offline.value)

            caches.forEach { it.clear() }

            // Only the GTD cache resets the shared offline flag.
            assertFalse(connectivity.offline.value)
        }

    @Test
    fun `the worker created by WorkManager's default factory gets its sync from the graph`() =
        runBlocking {
            // Logged out: refreshAll returns at once without network, which proves the entry point resolved GtdSync.
            val worker = TestListenableWorkerBuilder<GtdSyncWorker>(context).build()

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }

    @Test
    fun `the change stream uses the configured server and the access token`() =
        runBlocking {
            serverUrls.setServerUrl(server.url("/cockpit").toString())
            tokenStore.save(AuthTokens("graph-access", "graph-refresh"))

            val signals = withTimeout(10_000) { changes.signals.take(2).toList() }

            assertEquals(listOf(ChangeSignal.Connected, ChangeSignal.Changed("TASK")), signals)
            assertEquals("Bearer graph-access", synchronized(eventAuthorizations) { eventAuthorizations.first() })
        }

    private companion object {
        val CHANGE_EVENT =
            "event: change\n" +
                "data: {\"type\":\"TASK\",\"id\":\"${uuid(1)}\",\"op\":\"DELETED\",\"at\":\"2026-10-20T10:00:00Z\"}\n\n"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GtdDataGraphEntryPoint {
    fun connectivity(): GtdConnectivity

    fun tasks(): TaskRepository
}
