package ch.sonensei.cockpit.capture.queue

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.generated.apis.CaptureApi
import ch.sonensei.cockpit.core.network.generated.apis.FilesApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import io.mockk.every
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Fictitious ids, readable in failures. */
fun fileId(n: Int): String = "00000000-0000-7000-9000-%012d".format(n)

fun fileJson(
    n: Int,
    name: String,
): String =
    """{"id":"${fileId(n)}","name":"$name","mime":"text/plain","sha256":"00","size":5,"version":1,""" +
        """"createdAt":"2026-10-20T10:00:00Z","updatedAt":"2026-10-20T10:00:00Z"}"""

fun captureTaskJson(id: String): String =
    """{"id":"$id","title":"Captured","list":"INBOX","position":1,"version":1,"contextIds":[],""" +
        """"createdAt":"2026-10-20T10:00:00Z","updatedAt":"2026-10-20T10:00:00Z"}"""

fun answer(
    code: Int,
    body: String = "",
): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

class RecordingScheduler : CaptureSyncScheduler {
    @Volatile var scheduled = 0

    override fun schedule() {
        scheduled++
    }
}

/** The queue, its uploader and an attachment store in a fresh folder, against [server]; [close] releases them. */
class QueueHarness(
    server: MockWebServer,
    loggedIn: Boolean = true,
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val database: CaptureQueueDatabase =
        Room
            .inMemoryDatabaseBuilder(context, CaptureQueueDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    val dao: CaptureQueueDao = database.dao()
    val root = File(context.filesDir, "capture-test-${System.nanoTime()}")
    val store = AttachmentStore(root, context.contentResolver)
    val scheduler = RecordingScheduler()
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)
    val session = mockk<AuthSession> { every { isLoggedIn } returns loggedIn }
    val queue = CaptureQueue(dao, store, scheduler, clock)
    private val retrofit =
        Retrofit
            .Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
            .build()
    val uploader =
        CaptureUploader(session, dao, store, retrofit.create(CaptureApi::class.java), retrofit.create(FilesApi::class.java), clock)

    /** A source file outside the store, as a picked attachment would be. */
    fun source(
        name: String,
        content: String,
    ): Uri {
        val dir = File(context.cacheDir, "sources-${System.nanoTime()}").apply { mkdirs() }
        return Uri.fromFile(File(dir, name).apply { writeText(content) })
    }

    override fun close() {
        database.close()
        root.deleteRecursively()
    }
}

const val CAPTURE = "/api/v1/capture"
const val FILES = "/api/v1/files"
