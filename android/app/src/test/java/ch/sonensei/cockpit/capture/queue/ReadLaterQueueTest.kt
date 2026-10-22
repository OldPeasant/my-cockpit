package ch.sonensei.cockpit.capture.queue

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

fun bookmarkJson(url: String): String =
    """{"id":"${fileId(9)}","url":"$url","displayTitle":"Page","tags":[],"readLater":true,"metadataStatus":"PENDING",""" +
        """"version":1,"createdAt":"2026-10-20T10:00:00Z","updatedAt":"2026-10-20T10:00:00Z"}"""

/** Read-later bookmarks go through the offline capture queue (SPEC.md §8.4, §9.3). */
@RunWith(AndroidJUnit4::class)
class ReadLaterQueueTest {
    private val server = MockWebServer()
    private lateinit var q: QueueHarness

    @Before
    fun setUp() {
        server.start()
        q = QueueHarness(server, clock = TickingClock())
    }

    @After
    fun tearDown() {
        q.close()
        server.close()
    }

    private fun take(): RecordedRequest? = server.takeRequest(0, TimeUnit.SECONDS)

    @Test
    fun `read later is stored offline first and counted as pending`() =
        runBlocking {
            q.queue.readLater(" https://example.org/a ", "  A page ")

            val stored = q.dao.sendableBookmarks().single()
            assertEquals("https://example.org/a", stored.url)
            assertEquals("A page", stored.title)
            assertEquals(1, q.scheduler.scheduled)
            assertEquals(
                1,
                q.queue.status
                    .first()
                    .pendingCount,
            )
            assertNull(take())
        }

    @Test
    fun `a URL the server always rejects is refused and nothing is queued`() =
        runBlocking {
            for (url in listOf(
                "example.org",
                "ftp://example.org",
                "https://",
                "https://exa mple.org",
                "https://e.org/" + "a".repeat(2_048),
                "https://example.org:abc/",
                "https://example.org:123456/",
                "https://user@/",
                "https://[::1/",
                "https://[::1]x/",
                "https://exa\$mple.org/",
            )) {
                try {
                    q.queue.readLater(url)
                    fail("accepted $url")
                } catch (_: IllegalArgumentException) {
                }
            }
            assertTrue(q.dao.sendableBookmarks().isEmpty())
            assertEquals(0, q.scheduler.scheduled)
        }

    @Test
    fun `URLs with an authority the server accepts are queued`() =
        runBlocking {
            val urls =
                listOf(
                    "https://example.org",
                    "http://example.org:8080/a?b=c#d",
                    "https://user:secret@example.org/",
                    "https://[2001:db8::1]:443/",
                    "https://my_site.example.org./",
                    "https://bücher.example/",
                )
            for (url in urls) q.queue.readLater(url)
            assertEquals(urls, q.dao.sendableBookmarks().map { it.url })
        }

    @Test
    fun `the length limit counts the URL as the server encodes it`() {
        val base = "https://example.org/" // 20 characters; the server writes each "é" as %C3%A9 (6 characters).
        assertTrue(CaptureQueue.isBookmarkUrl(base + "é".repeat(338))) // 2048 once encoded
        assertFalse(CaptureQueue.isBookmarkUrl(base + "é".repeat(338) + "a"))
        // Valid escapes stay as they are, so an already encoded URL of 2048 characters is accepted.
        assertTrue(CaptureQueue.isBookmarkUrl(base + "%C3%A9".repeat(338)))
        // An IDN host counts in its longer ASCII form.
        val idnHost = "https://bücher.example/" // xn--bcher-kva.example: 7 characters longer
        assertTrue(CaptureQueue.isBookmarkUrl(idnHost + "a".repeat(2048 - idnHost.length - 7)))
        assertFalse(CaptureQueue.isBookmarkUrl(idnHost + "a".repeat(2048 - idnHost.length - 6)))
        // A second "#" in the fragment is encoded (%23).
        assertFalse(CaptureQueue.isBookmarkUrl(base + "a".repeat(2025) + "#a#"))
        assertTrue(CaptureQueue.isBookmarkUrl(base + "a".repeat(2025) + "#ab"))
    }

    @Test
    fun `a 2xx answer that cannot be read still counts as sent, so the bookmark is not saved twice`() =
        runBlocking {
            q.queue.readLater("https://example.org/a")
            server.enqueue(answer(201, bookmarkJson("https://example.org/a").replace("\"PENDING\"", "\"SOMETHING_NEW\"")))

            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertEquals(BOOKMARKS, take()!!.url.encodedPath)
            assertTrue(q.dao.sendableBookmarks().isEmpty())
            assertEquals(
                0,
                q.queue.status
                    .first()
                    .pendingCount,
            )

            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertNull(take())
        }

    @Test
    fun `a 2xx answer with a date-time or id the client cannot parse still counts as sent`() =
        runBlocking {
            // The generated adapters throw DateTimeParseException and IllegalArgumentException here, which
            // kotlinx.serialization does not wrap in a SerializationException.
            val answers =
                listOf(
                    bookmarkJson("https://example.org/a").replace("\"createdAt\":\"2026-10-20T10:00:00Z\"", "\"createdAt\":\"yesterday\""),
                    bookmarkJson("https://example.org/a").replace("\"id\":\"${fileId(9)}\"", "\"id\":\"not-a-uuid\""),
                )
            for (body in answers) {
                q.queue.readLater("https://example.org/a")
                server.enqueue(answer(201, body))

                assertEquals(SendResult.DONE, q.uploader.sendAll())
                assertEquals(BOOKMARKS, take()!!.url.encodedPath)
                assertTrue(q.dao.sendableBookmarks().isEmpty())
                assertEquals(
                    0,
                    q.queue.status
                        .first()
                        .pendingCount,
                )

                assertEquals(SendResult.DONE, q.uploader.sendAll())
                assertNull(take())
            }
        }

    @Test
    fun `a blank title is left to the server and a long one is shortened`() =
        runBlocking {
            q.queue.readLater("https://example.org/a", " ")
            q.queue.readLater("https://example.org/b", "t".repeat(600))

            val (blank, long) = q.dao.sendableBookmarks()
            assertNull(blank.title)
            assertEquals(CaptureQueue.MAX_TITLE_LENGTH, long.title!!.length)
        }

    @Test
    fun `the job sends it as a read-later bookmark after the captures and removes it`() =
        runBlocking {
            q.queue.readLater("https://example.org/a", "A page")
            val captureId = q.queue.capture("Call plumber")
            server.enqueue(answer(201, captureTaskJson(captureId.toString())))
            server.enqueue(answer(201, bookmarkJson("https://example.org/a")))

            assertEquals(SendResult.DONE, q.uploader.sendAll())

            assertEquals(CAPTURE, take()!!.url.encodedPath)
            val bookmark = take()!!
            assertEquals(BOOKMARKS, bookmark.url.encodedPath)
            val body = Json.parseToJsonElement(bookmark.body!!.utf8()).jsonObject
            assertEquals("https://example.org/a", body["url"]!!.jsonPrimitive.content)
            assertEquals("A page", body["title"]!!.jsonPrimitive.content)
            assertEquals("true", body["readLater"]!!.jsonPrimitive.content)
            assertTrue(q.dao.sendableBookmarks().isEmpty())
            assertEquals(
                0,
                q.queue.status
                    .first()
                    .pendingCount,
            )
        }

    @Test
    fun `offline the bookmark stays queued with the error and is sent on the next run`() =
        runBlocking {
            q.queue.readLater("https://example.org/a")
            server.close()

            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            val status = q.queue.status.first()
            assertEquals(1, status.pendingCount)
            assertEquals(CaptureUploader.OFFLINE, status.lastError)
        }

    @Test
    fun `logged out nothing is sent and the bookmark waits`() =
        runBlocking {
            val loggedOut = QueueHarness(server, loggedIn = false)
            try {
                loggedOut.queue.readLater("https://example.org/a")
                assertEquals(SendResult.RETRY, loggedOut.uploader.sendAll())
                assertNull(take())
                assertEquals(
                    CaptureUploader.NOT_LOGGED_IN,
                    loggedOut.queue.status
                        .first()
                        .lastError,
                )
            } finally {
                loggedOut.close()
            }
        }

    @Test
    fun `a bookmark the server rejects is kept but no longer sent, a server error is retried`() =
        runBlocking {
            q.queue.readLater("https://example.org/rejected")
            server.enqueue(answer(400, """{"title":"Bad Request","status":400}"""))
            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertTrue(q.dao.sendableBookmarks().isEmpty())
            assertEquals(
                1,
                q.queue.status
                    .first()
                    .pendingCount,
            )

            q.queue.readLater("https://example.org/later")
            server.enqueue(answer(503))
            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            assertEquals(1, q.dao.sendableBookmarks().size)
            assertFalse(
                q.queue.status
                    .first()
                    .lastError
                    .isNullOrEmpty(),
            )
        }

    /**
     * A queue written by the previous app version (schema 1, built from its committed export) opens with schema 2: its
     * captures are kept and read-later bookmarks can be queued. Room's MigrationTestHelper cannot read the exported
     * schemas under Robolectric, so the old database is built here.
     */
    @Test
    fun `version 1 queues migrate to version 2 with their captures kept`() =
        runBlocking {
            val context: Context = ApplicationProvider.getApplicationContext()
            val file = context.getDatabasePath(MIGRATION_DB).apply { parentFile!!.mkdirs() }
            val schema =
                Json
                    .parseToJsonElement(File("schemas/${CaptureQueueDatabase::class.java.name}/1.json").readText())
                    .jsonObject["database"]!!
                    .jsonObject
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                for (entity in schema["entities"]!!.jsonArray) {
                    val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
                    db.execSQL(
                        entity.jsonObject["createSql"]!!
                            .jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table),
                    )
                }
                schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL(
                    "INSERT INTO pending_capture (id, text, notes, createdAtEpochMs, lastError, lastAttemptAtEpochMs, rejected) " +
                        "VALUES ('${fileId(1)}', 'Kept', NULL, 1, NULL, NULL, 0)",
                )
                db.version = 1
            }

            val migrated = Room.databaseBuilder(context, CaptureQueueDatabase::class.java, MIGRATION_DB).build()
            try {
                val dao = migrated.dao()
                assertEquals(
                    "Kept",
                    dao
                        .sendable()
                        .single()
                        .capture.text,
                )
                dao.insertBookmark(PendingBookmarkEntity(fileId(2), "https://example.org/a", null, 2, null, null))
                assertEquals(2, dao.observeStatus().first().pending)
            } finally {
                migrated.close()
                context.deleteDatabase(MIGRATION_DB)
            }
        }

    private companion object {
        const val MIGRATION_DB = "capture-queue-migration-test"
    }
}
