package ch.sonensei.cockpit.push

import ch.sonensei.cockpit.core.network.AuthSession
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class PushMessageHandlerTest {
    private var loggedIn = true
    private val session = mockk<AuthSession> { every { isLoggedIn } answers { loggedIn } }
    private val store = FakeInstanceStore(INSTANCE)
    private val sync = RecordingSyncTrigger()
    private val notifier = RecordingMailNotifier()
    private val handler = PushMessageHandler(session, store, sync, notifier)

    private fun handle(
        content: String,
        decrypted: Boolean = true,
        instance: String = INSTANCE,
    ) = handler.handle(content.toByteArray(), decrypted, instance)

    @Test
    fun `a reminder push runs the reminder sync`() {
        val event = handle("""{"kind":"reminder","id":"$ID"}""")

        assertEquals(PushEvent.Reminder(UUID.fromString(ID)), event)
        assertEquals(1, sync.reminderSyncs)
        assertEquals(0, notifier.shown)
    }

    @Test
    fun `a new-mail push shows the new-mail notification`() {
        val event = handle("""{"kind":"mail","id":"$ID"}""")

        assertEquals(PushEvent.Mail(UUID.fromString(ID)), event)
        assertEquals(1, notifier.shown)
        assertEquals(0, sync.reminderSyncs)
    }

    @Test
    fun `fields the app does not know are ignored`() {
        val event = handle("""{"kind":"reminder","id":"$ID","sentAt":"2026-10-26T10:00:00Z"}""")

        assertEquals(PushEvent.Reminder(UUID.fromString(ID)), event)
    }

    @Test
    fun `unknown or garbled messages are ignored`() {
        val ignored =
            listOf(
                """{"kind":"calendar","id":"$ID"}""",
                """{"kind":"reminder","id":"not-a-uuid"}""",
                """{"kind":"reminder"}""",
                """{"id":"$ID"}""",
                """{"kind":"reminder","id":""" + "\"$ID\"",
                """["reminder","$ID"]""",
                "reminder",
                "",
                """{"kind":null,"id":"$ID"}""",
            )

        ignored.forEach { assertNull(it, handle(it)) }
        assertEquals(0, sync.reminderSyncs)
        assertEquals(0, notifier.shown)
    }

    @Test
    fun `bytes that are not UTF-8 are ignored`() {
        val event = handler.handle(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte()), decrypted = true, instance = INSTANCE)

        assertNull(event)
    }

    @Test
    fun `an oversized message is ignored`() {
        val padding = " ".repeat(PushMessageHandler.MAX_PAYLOAD_BYTES)

        assertNull(handle("""{"kind":"reminder","id":"$ID"}$padding"""))
    }

    @Test
    fun `a message of exactly the maximum size is still read, one byte more is ignored`() {
        val message = """{"kind":"mail","id":"$ID"}"""
        val atLimit = message + " ".repeat(PushMessageHandler.MAX_PAYLOAD_BYTES - message.length)

        assertEquals(PushEvent.Mail(UUID.fromString(ID)), handle(atLimit))
        assertNull(handle("$atLimit "))
        assertEquals(1, notifier.shown)
    }

    @Test
    fun `a message without a registration is ignored`() {
        store.instance = null

        assertNull(handle("""{"kind":"mail","id":"$ID"}"""))
        assertEquals(0, notifier.shown)
    }

    @Test
    fun `a message the library could not decrypt is ignored`() {
        assertNull(handle("""{"kind":"reminder","id":"$ID"}""", decrypted = false))
        assertEquals(0, sync.reminderSyncs)
    }

    @Test
    fun `a message for another registration is ignored`() {
        assertNull(handle("""{"kind":"mail","id":"$ID"}""", instance = "an-older-login"))
        assertEquals(0, notifier.shown)
    }

    @Test
    fun `a message after the logout is ignored`() {
        loggedIn = false

        assertNull(handle("""{"kind":"reminder","id":"$ID"}"""))
        assertEquals(0, sync.reminderSyncs)
    }

    private companion object {
        const val INSTANCE = "0b4f7d58-34a2-4b1e-a9b3-6f3a1d2c9e01"
        const val ID = "0192f000-0000-7000-8000-000000000001"
    }
}

class FakeInstanceStore(
    var instance: String? = null,
) : PushInstanceStore {
    var created = 0

    override fun current(): String? = instance

    override fun getOrCreate(): String = instance ?: "instance-${++created}".also { instance = it }

    override fun clear() {
        instance = null
    }
}

class RecordingSyncTrigger : PushSyncTrigger {
    var reminderSyncs = 0

    override fun syncReminders() {
        reminderSyncs++
    }
}

class RecordingMailNotifier : NewMailNotifier {
    var shown = 0
    var dismissed = 0

    override fun show() {
        shown++
    }

    override fun dismiss() {
        dismissed++
    }
}
