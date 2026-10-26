package ch.sonensei.cockpit.push

import ch.sonensei.cockpit.core.network.AuthSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.CharacterCodingException
import java.util.UUID
import javax.inject.Inject

/** What a server push announces; it carries ids only, never item contents. */
sealed interface PushEvent {
    /** A reminder fired: the reminder sync loads the current reminders. */
    data class Reminder(
        val id: UUID,
    ) : PushEvent

    /** New mail arrived in an inbox. */
    data class Mail(
        val id: UUID,
    ) : PushEvent
}

/** The server's push payload: `{"kind":"reminder"|"mail","id":"<uuid>"}`. */
@Serializable
private data class PushPayload(
    @SerialName("kind") val kind: String,
    @SerialName("id") val id: String,
)

private val payloadJson = Json { ignoreUnknownKeys = true }

/** Reads a push payload; anything that is not one (unknown kind, garbled, too big) is null. */
internal fun parsePushEvent(content: ByteArray): PushEvent? {
    if (content.isEmpty() || content.size > PushMessageHandler.MAX_PAYLOAD_BYTES) return null
    return try {
        val payload = payloadJson.decodeFromString<PushPayload>(content.decodeToString(throwOnInvalidSequence = true))
        val id = UUID.fromString(payload.id)
        when (payload.kind) {
            "reminder" -> PushEvent.Reminder(id)
            "mail" -> PushEvent.Mail(id)
            else -> null
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        // Not a UUID, or bytes that are not UTF-8.
        null
    } catch (_: CharacterCodingException) {
        null
    }
}

/** Starts the work a push asks for; the WorkManager-backed one is in [WorkManagerPushSyncTrigger]. */
interface PushSyncTrigger {
    fun syncReminders()
}

/**
 * Reacts to a push: a reminder push runs the reminder sync, a new-mail push shows the new-mail notification. Pushes
 * that were not decrypted (anyone who learns the endpoint can post plain text to it), that belong to another
 * registration, arrive while logged out, or are unknown or garbled are ignored.
 */
class PushMessageHandler
    @Inject
    constructor(
        private val session: AuthSession,
        private val store: PushInstanceStore,
        private val sync: PushSyncTrigger,
        private val mailNotifier: NewMailNotifier,
    ) {
        /** Returns the event acted on, or null if the push was ignored. */
        fun handle(
            content: ByteArray,
            decrypted: Boolean,
            instance: String,
        ): PushEvent? {
            if (!decrypted || !session.isLoggedIn || instance != store.current()) return null
            val event = parsePushEvent(content) ?: return null
            when (event) {
                is PushEvent.Reminder -> sync.syncReminders()
                is PushEvent.Mail -> mailNotifier.show()
            }
            return event
        }

        internal companion object {
            /** The server sends about 60 bytes; a distributor delivers at most 4 KiB. */
            const val MAX_PAYLOAD_BYTES = 4096
        }
    }
