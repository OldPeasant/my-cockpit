package ch.sonensei.cockpit.mail.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Why the list may be outdated, or what did not work. */
enum class MailListMessage { OFFLINE, FAILED, ARCHIVE_FAILED, DELETE_FAILED }

data class MailListUiState(
    val mailbox: Mailbox = Mailbox.Unified,
    val accounts: List<MailAccountFolders> = emptyList(),
    val messages: List<MailSummary> = emptyList(),
    /** The cursor of the page after the loaded ones; `null` when everything is loaded. */
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** The mailbox was loaded at least once. */
    val loaded: Boolean = false,
    val message: MailListMessage? = null,
) {
    val folder: MailFolder?
        get() =
            (mailbox as? Mailbox.Folder)?.let { box ->
                accounts.firstNotNullOfOrNull { a ->
                    a.folders.firstOrNull {
                        it.id ==
                            box.folderId
                    }
                }
            }

    /** Archiving a mail that is already in the Archive does nothing: the swipe is not offered there. */
    val canArchive: Boolean get() = folder?.role != FolderRole.ARCHIVE

    /** Names the account of each row in the unified Inbox when there is more than one. */
    val showAccounts: Boolean get() = mailbox == Mailbox.Unified && accounts.size > 1

    fun account(id: UUID?): MailAccount? = accounts.firstOrNull { it.account.id == id }?.account
}

/**
 * The unified Inbox or one folder, loaded page by page as it is scrolled, with the swipe actions archive and delete.
 * A swiped mail leaves the list at once and comes back if the server could not move it.
 */
@HiltViewModel
class MailListViewModel
    @Inject
    constructor(
        private val repository: MailRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(MailListUiState())
        val uiState: StateFlow<MailListUiState> = state.asStateFlow()

        private var loadJob: Job? = null

        /** Loads the accounts and the first page of the current mailbox again. */
        fun refresh() = load(state.value.mailbox)

        /** Shows [mailbox] from its newest mail. */
        fun select(mailbox: Mailbox) {
            if (mailbox == state.value.mailbox && state.value.loaded) return
            state.update { it.copy(mailbox = mailbox, messages = emptyList(), nextCursor = null, loaded = false) }
            load(mailbox)
        }

        /** The list was scrolled near its end: appends the next page. */
        fun loadMore() {
            val current = state.value
            val cursor = current.nextCursor ?: return
            if (current.loading || current.loadingMore) return
            state.update { it.copy(loadingMore = true) }
            loadJob =
                viewModelScope.launch {
                    val result = repository.messages(current.mailbox, cursor)
                    state.update {
                        when (result) {
                            is MailResult.Ok ->
                                it.copy(
                                    messages = (it.messages + result.value.items).distinctBy { m -> m.id },
                                    nextCursor = result.value.nextCursor,
                                    loadingMore = false,
                                    message = null,
                                )
                            MailResult.Offline -> it.copy(loadingMore = false, message = MailListMessage.OFFLINE)
                            is MailResult.Failed -> it.copy(loadingMore = false, message = MailListMessage.FAILED)
                        }
                    }
                }
        }

        /** Archives or deletes [id]: it leaves the list now, and returns to its place if the server refuses. */
        fun act(
            id: UUID,
            action: MailListAction,
        ) {
            val index = state.value.messages.indexOfFirst { it.id == id }
            if (index < 0) return
            if (action == MailListAction.ARCHIVE && !state.value.canArchive) return
            val mail = state.value.messages[index]
            val mailbox = state.value.mailbox
            state.update { s -> s.copy(messages = s.messages.filterNot { it.id == id }) }
            viewModelScope.launch {
                val done = (repository.act(id, action) as? MailResult.Ok)?.value == true
                if (done) {
                    // A reload that overtook the call may have brought the mail back.
                    state.update { s -> s.copy(messages = s.messages.filterNot { it.id == id }) }
                } else {
                    state.update { s ->
                        val messages =
                            // Another mailbox shown meanwhile never gets the mail; a reload of this one may have it already.
                            if (s.mailbox != mailbox || s.messages.any { it.id == id }) {
                                s.messages
                            } else {
                                s.messages.toMutableList().apply {
                                    add(
                                        minOf(index, size),
                                        mail,
                                    )
                                }
                            }
                        s.copy(
                            messages = messages,
                            message =
                                if (action ==
                                    MailListAction.ARCHIVE
                                ) {
                                    MailListMessage.ARCHIVE_FAILED
                                } else {
                                    MailListMessage.DELETE_FAILED
                                },
                        )
                    }
                }
            }
        }

        /** The read view opened [id] (and marks it read on the server). */
        fun markSeen(id: UUID) = state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) it.copy(seen = true) else it }) }

        /** The read view archived or deleted [id]. */
        fun removed(id: UUID) = state.update { s -> s.copy(messages = s.messages.filterNot { it.id == id }) }

        /** Loads the accounts and the first page of [mailbox]; a load still running is dropped. */
        private fun load(mailbox: Mailbox) {
            loadJob?.cancel()
            state.update { it.copy(loading = true, loadingMore = false) }
            loadJob =
                viewModelScope.launch {
                    val accounts = repository.accounts()
                    val result = repository.messages(mailbox, null)
                    state.update { current ->
                        val withAccounts = (accounts as? MailResult.Ok)?.let { current.copy(accounts = it.value) } ?: current
                        when (result) {
                            is MailResult.Ok -> {
                                val (messages, cursor) = mergeFirstPage(current, mailbox, result.value)
                                withAccounts.copy(
                                    messages = messages,
                                    nextCursor = cursor,
                                    loading = false,
                                    loaded = true,
                                    message = null,
                                )
                            }
                            MailResult.Offline -> withAccounts.copy(loading = false, message = MailListMessage.OFFLINE)
                            is MailResult.Failed -> withAccounts.copy(loading = false, message = MailListMessage.FAILED)
                        }
                    }
                }
        }

        /**
         * The mails and cursor after [page], the newest mails of [mailbox]. Reloading the mailbox shown keeps the pages
         * already loaded behind it (from the page's last mail on), so a refresh does not lose the scroll position.
         */
        private fun mergeFirstPage(
            current: MailListUiState,
            mailbox: Mailbox,
            page: MailPage,
        ): Pair<List<MailSummary>, String?> {
            val last = page.items.lastOrNull()
            val at = last?.let { l -> current.messages.indexOfFirst { it.id == l.id } } ?: -1
            if (current.mailbox != mailbox || !current.loaded || page.nextCursor == null || at < 0) {
                return page.items to page.nextCursor
            }
            val ids = page.items.mapTo(HashSet()) { it.id }
            val older = current.messages.drop(at + 1).filterNot { it.id in ids }
            return if (older.isEmpty()) page.items to page.nextCursor else (page.items + older) to current.nextCursor
        }
    }
