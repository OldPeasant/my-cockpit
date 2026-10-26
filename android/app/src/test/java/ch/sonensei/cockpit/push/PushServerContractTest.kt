package ch.sonensei.cockpit.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Pins the app to the payloads the server sends: `PushMessage.toJson()` in the server's `core.push.domain` writes
 * `{"kind":"<wire>","id":"<uuid>"}` for every `PushKind` (`reminder`, `mail`), with UUIDv7 ids. If either side
 * changes the format, this test and the server's `PushDispatcherTests` must change together.
 */
class PushServerContractTest {
    @Test
    fun `every kind the server sends is understood`() {
        val serverPayloads =
            mapOf(
                """{"kind":"reminder","id":"$REMINDER_ID"}""" to PushEvent.Reminder(UUID.fromString(REMINDER_ID)),
                """{"kind":"mail","id":"$MAIL_ID"}""" to PushEvent.Mail(UUID.fromString(MAIL_ID)),
            )

        serverPayloads.forEach { (payload, expected) -> assertEquals(payload, expected, parsePushEvent(payload.toByteArray())) }
    }

    @Test
    fun `the largest payload the server can send fits the app's size limit`() {
        val longest = """{"kind":"reminder","id":"$REMINDER_ID"}""".toByteArray()

        assertTrue(longest.size <= PushMessageHandler.MAX_PAYLOAD_BYTES)
    }

    private companion object {
        // Fictitious UUIDv7 ids, as the server generates them.
        const val REMINDER_ID = "019a2b3c-4d5e-7f60-8a1b-2c3d4e5f6a7b"
        const val MAIL_ID = "019a2b3c-4d5e-7f60-9b2c-3d4e5f6a7b8c"
    }
}
