package ch.sonensei.cockpit.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why the shown list may be outdated. */
enum class TrashMessage { OFFLINE, FAILED }

/** Why a restore or delete did not happen. */
enum class TrashFailure { OFFLINE, FAILED }

/** A short note at the bottom of the screen. */
sealed interface TrashSnackbar {
    /** Tells two notes with the same content apart (the view model numbers them). */
    val id: Long

    data class Restored(
        val item: TrashItem,
        override val id: Long,
    ) : TrashSnackbar

    data class Purged(
        val item: TrashItem,
        override val id: Long,
    ) : TrashSnackbar

    data class Failed(
        val item: TrashItem,
        val failure: TrashFailure,
        override val id: Long,
    ) : TrashSnackbar
}

data class TrashUiState(
    /** The deleted items, most recently deleted first. */
    val items: List<TrashItem> = emptyList(),
    /** A load is running. */
    val loading: Boolean = false,
    /** The running load was started by pulling the list down. */
    val refreshing: Boolean = false,
    /** The list was loaded once. */
    val loaded: Boolean = false,
    val message: TrashMessage? = null,
    /** Keys ([TrashItem.key]) of the items with a restore or delete running. */
    val busy: Set<String> = emptySet(),
    /** The item whose permanent deletion waits for the user's confirmation. */
    val confirmingPurge: TrashItem? = null,
    val snackbar: TrashSnackbar? = null,
)

/** The Trash (SPEC.md §4.2): deleted items of all types; restore them, or delete them for good after a confirmation. */
@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val trash: TrashRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(TrashUiState())
        val uiState: StateFlow<TrashUiState> = state.asStateFlow()

        private var loadJob: Job? = null
        private var snackbars = 0L

        /** Loads the Trash; a load still running is dropped. */
        fun refresh(pulled: Boolean = false) {
            loadJob?.cancel()
            state.update { it.copy(loading = true, refreshing = pulled) }
            loadJob =
                viewModelScope.launch {
                    val result = trash.list()
                    ensureActive()
                    state.update {
                        it.copy(
                            items = (result as? TrashResult.Ok)?.value ?: it.items,
                            loading = false,
                            refreshing = false,
                            loaded = it.loaded || result is TrashResult.Ok,
                            message =
                                when (result) {
                                    is TrashResult.Ok -> null
                                    TrashResult.Offline -> TrashMessage.OFFLINE
                                    is TrashResult.Failed -> TrashMessage.FAILED
                                },
                        )
                    }
                }
        }

        /** Takes [item] out of the Trash: it is back in its own list. */
        fun restore(item: TrashItem) = run(item, { TrashSnackbar.Restored(item, it) }) { trash.restore(item) }

        /** Asks for confirmation before [item] is deleted for good. */
        fun askPurge(item: TrashItem) = state.update { it.copy(confirmingPurge = item) }

        fun cancelPurge() = state.update { it.copy(confirmingPurge = null) }

        /** Deletes the item waiting for confirmation for good. */
        fun confirmPurge() {
            val item = state.value.confirmingPurge ?: return
            state.update { it.copy(confirmingPurge = null) }
            run(item, { TrashSnackbar.Purged(item, it) }) { trash.purge(item) }
        }

        /** The snackbar was shown and is gone; a newer one stays. */
        fun dismiss(snackbar: TrashSnackbar) = state.update { if (it.snackbar == snackbar) it.copy(snackbar = null) else it }

        /**
         * Runs a change of [item], one per item at a time. The item leaves the list when the change worked, or when it
         * is no longer in the Trash (404: restored or deleted elsewhere).
         */
        private fun run(
            item: TrashItem,
            done: (Long) -> TrashSnackbar,
            action: suspend () -> TrashResult<Unit>,
        ) {
            if (item.key in state.value.busy) return
            state.update { it.copy(busy = it.busy + item.key) }
            viewModelScope.launch {
                val result =
                    try {
                        action()
                    } finally {
                        state.update { it.copy(busy = it.busy - item.key) }
                    }
                when {
                    result is TrashResult.Ok -> {
                        drop(item)
                        show(done(++snackbars))
                    }
                    (result as? TrashResult.Failed)?.status == TrashRepository.NOT_FOUND -> drop(item)
                    else -> {
                        val failure = if (result == TrashResult.Offline) TrashFailure.OFFLINE else TrashFailure.FAILED
                        show(TrashSnackbar.Failed(item, failure, ++snackbars))
                    }
                }
            }
        }

        /**
         * Removes [item] from the list. A load that started before the change would bring it back: it is replaced by a
         * new load.
         */
        private fun drop(item: TrashItem) {
            state.update { it.copy(items = it.items.filterNot { i -> i.key == item.key }) }
            if (loadJob?.isActive == true) refresh(pulled = state.value.refreshing)
        }

        private fun show(snackbar: TrashSnackbar) = state.update { it.copy(snackbar = snackbar) }
    }
