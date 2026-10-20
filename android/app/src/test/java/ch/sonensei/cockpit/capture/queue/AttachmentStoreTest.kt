package ch.sonensei.cockpit.capture.queue

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** A shared document as a picker or share sheet hands it over: a `content://` uri with provider metadata. */
class FakeDocumentProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "ch.sonensei.cockpit.test.documents"
        val uri: Uri = Uri.parse("content://$AUTHORITY/doc/42")

        lateinit var file: File
        var displayName: String? = null
        var mime: String? = null
        var hasRow = true
        var queryFails = false
        var noCursor = false
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (queryFails) throw SecurityException("no metadata for you")
        if (noCursor) return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { if (hasRow) addRow(arrayOf(displayName)) }
    }

    override fun getType(uri: Uri): String? = mime

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

@RunWith(AndroidJUnit4::class)
class AttachmentStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var q: QueueHarness

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeDocumentProvider::class.java, FakeDocumentProvider.AUTHORITY)
        FakeDocumentProvider.file =
            File(context.cacheDir, "doc-${System.nanoTime()}").apply { writeText("scanned receipt") }
        FakeDocumentProvider.displayName = "Receipt Oct 2026.pdf"
        FakeDocumentProvider.mime = "application/pdf"
        FakeDocumentProvider.hasRow = true
        FakeDocumentProvider.queryFails = false
        FakeDocumentProvider.noCursor = false
        server.start()
        q = QueueHarness(server)
        server.close()
    }

    @After
    fun tearDown() {
        q.close()
        FakeDocumentProvider.file.delete()
    }

    private fun copy() = runBlocking { q.store.copy("capture-1", 0, FakeDocumentProvider.uri) }

    @Test
    fun `a content uri is copied with the provider's display name and mime type`() {
        val copy = copy()

        assertEquals("Receipt Oct 2026.pdf", copy.name)
        assertEquals("application/pdf", copy.mime)
        assertEquals("scanned receipt", copy.file.readText())
        assertEquals(File(File(q.root, "capture-1"), "0"), copy.file)
    }

    @Test
    fun `without provider metadata the name falls back to the position and the mime to octet-stream`() {
        FakeDocumentProvider.displayName = null
        FakeDocumentProvider.mime = null

        val copy = copy()

        assertEquals("attachment-1", copy.name)
        assertEquals(AttachmentStore.DEFAULT_MIME, copy.mime)
    }

    @Test
    fun `a blank display name, an empty or missing cursor or a failing query fall back to the position name`() {
        FakeDocumentProvider.displayName = "   "
        assertEquals("attachment-1", copy().name)

        FakeDocumentProvider.hasRow = false
        assertEquals("attachment-1", copy().name)

        FakeDocumentProvider.noCursor = true
        assertEquals("attachment-1", copy().name)

        FakeDocumentProvider.queryFails = true
        val copy = copy()
        assertEquals("attachment-1", copy.name)
        assertEquals("scanned receipt", copy.file.readText())
    }

    @Test
    fun `a queued content attachment keeps the provider name and mime`() =
        runBlocking {
            val id = q.queue.capture("Shared", null, listOf(CaptureAttachment(FakeDocumentProvider.uri)))

            val attachment = q.dao.attachments(id.toString()).single()
            assertEquals("Receipt Oct 2026.pdf", attachment.name)
            assertEquals("application/pdf", attachment.mime)
            assertEquals("scanned receipt", File(attachment.localPath).readText())
        }

    @Test
    fun `a content uri that cannot be opened queues nothing`() =
        runBlocking {
            FakeDocumentProvider.file.delete()
            try {
                q.queue.capture(
                    "Gone",
                    null,
                    listOf(CaptureAttachment(q.source("a.txt", "a")), CaptureAttachment(FakeDocumentProvider.uri)),
                )
                fail("expected an exception")
            } catch (_: FileNotFoundException) {
            }

            assertTrue(q.dao.sendable().isEmpty())
            assertFalse(q.root.exists() && q.root.listFiles()!!.isNotEmpty())
            assertEquals(0, q.scheduler.scheduled)
        }

    @Test
    fun `a failing database write removes the copies and schedules nothing`() =
        runBlocking {
            val failingDao =
                mockk<CaptureQueueDao>(relaxed = true) {
                    coEvery { insert(any(), any()) } throws SQLiteFullException("disk full")
                }
            val queue = CaptureQueue(failingDao, q.store, q.scheduler, q.clock)
            try {
                queue.capture("No db", null, listOf(CaptureAttachment(q.source("a.txt", "a"))))
                fail("expected the insert to fail")
            } catch (_: SQLiteFullException) {
            }

            assertFalse(q.root.exists() && q.root.listFiles()!!.isNotEmpty())
            assertEquals(0, q.scheduler.scheduled)
        }

    @Test
    fun `an unusable storage folder queues nothing`() =
        runBlocking {
            // A plain file where the queue's folder should be: no capture folder can be created under it.
            q.root.parentFile!!.mkdirs()
            q.root.writeText("not a folder")
            try {
                q.queue.capture("No storage", null, listOf(CaptureAttachment(q.source("a.txt", "a"))))
                fail("expected an IOException")
            } catch (_: IOException) {
            }

            assertTrue(q.dao.sendable().isEmpty())
            assertEquals(0, q.scheduler.scheduled)
        }

    @Test
    fun `deleting copies of an unknown capture is fine`() =
        runBlocking {
            q.store.delete("never-queued")
            assertFalse(File(q.root, "never-queued").exists())
        }
}
