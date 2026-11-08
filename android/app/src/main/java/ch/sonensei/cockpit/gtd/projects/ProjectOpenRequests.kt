package ch.sonensei.cockpit.gtd.projects

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Asks the Projects screen to show the detail of a project, from another screen (the weekly review). The request waits
 * until the Projects screen takes it, so it also works when that screen is created only after the request.
 */
object ProjectOpenRequests {
    private val pending = MutableStateFlow<UUID?>(null)

    val requests: StateFlow<UUID?> = pending.asStateFlow()

    fun request(id: UUID) {
        pending.value = id
    }

    /** Takes [id] if it is still the pending request; true for the one caller that gets it. */
    fun take(id: UUID): Boolean = pending.compareAndSet(id, null)
}
