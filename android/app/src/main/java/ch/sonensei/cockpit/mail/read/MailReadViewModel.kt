package ch.sonensei.cockpit.mail.read

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.mail.list.MailListAction
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Why the message is not shown. */
enum class MailReadError { OFFLINE, FAILED, GONE }

/** The outcome of an action, shown below the toolbar until the next one. */
sealed interface MailReadNotice {
    data class Created(
        val action: MailGtdAction,
        val title: String,
    ) : MailReadNotice

    data class GtdFailed(
        val action: MailGtdAction,
    ) : MailReadNotice

    data class ActionFailed(
        val action: MailListAction,
    ) : MailReadNotice
}

data class MailReadUiState(
    val message: MailMessage? = null,
    val loading: Boolean = false,
    val error: MailReadError? = null,
    /** An action is running: the buttons wait for it. */
    val busy: Boolean = false,
    val notice: MailReadNotice? = null,
    /** The message was archived or deleted: the view closes. */
    val removed: Boolean = false,
)

/** One message (SPEC.md §7.3): opening it marks it read; archive, delete, "→ Inbox task" and "→ Waiting For". */
@HiltViewModel
class MailReadViewModel
    @Inject
    constructor(
        private val reader: MailReadRepository,
        private val mail: MailRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(MailReadUiState())
        val uiState: StateFlow<MailReadUiState> = state.asStateFlow()

        private var id: UUID? = null

        /** Loads [messageId] (once; again with [retry]). */
        fun open(messageId: UUID) {
            if (id == messageId) return
            id = messageId
            load()
        }

        fun retry() = load()

        fun gtd(action: MailGtdAction) =
            run { messageId ->
                val result = reader.gtd(messageId, action)
                state.update {
                    it.copy(
                        notice =
                            if (result is MailResult.Ok) {
                                MailReadNotice.Created(
                                    action,
                                    result.value,
                                )
                            } else {
                                MailReadNotice.GtdFailed(action)
                            },
                    )
                }
            }

        fun act(action: MailListAction) =
            run { messageId ->
                val done = (mail.act(messageId, action) as? MailResult.Ok)?.value == true
                state.update { if (done) it.copy(removed = true) else it.copy(notice = MailReadNotice.ActionFailed(action)) }
            }

        private fun run(block: suspend (UUID) -> Unit) {
            val messageId = state.value.message?.id ?: return
            if (state.value.busy) return
            state.update { it.copy(busy = true, notice = null) }
            viewModelScope.launch {
                block(messageId)
                state.update { it.copy(busy = false) }
            }
        }

        private fun load() {
            val messageId = id ?: return
            state.update { it.copy(loading = true, error = null) }
            viewModelScope.launch {
                when (val result = reader.message(messageId)) {
                    is MailResult.Ok -> {
                        state.update { it.copy(message = result.value, loading = false) }
                        // Read state is the mail server's: a failure shows on the next load.
                        if (!result.value.seen) mail.markRead(result.value.id)
                    }
                    MailResult.Offline -> state.update { it.copy(loading = false, error = MailReadError.OFFLINE) }
                    is MailResult.Failed ->
                        state.update {
                            it.copy(loading = false, error = if (result.status == NOT_FOUND) MailReadError.GONE else MailReadError.FAILED)
                        }
                }
            }
        }

        private companion object {
            const val NOT_FOUND = 404
        }
    }
