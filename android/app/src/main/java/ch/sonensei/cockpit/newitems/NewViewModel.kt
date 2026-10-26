package ch.sonensei.cockpit.newitems

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.MailSummary
import ch.sonensei.cockpit.mail.list.Mailbox
import ch.sonensei.cockpit.mail.read.MailGtdAction
import ch.sonensei.cockpit.mail.read.MailReadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** The outcome of "→ Inbox task", shown until dismissed. */
sealed interface NewNotice {
    data class TaskCreated(
        val title: String,
    ) : NewNotice

    /** The server could not be reached; nothing was created. */
    data object TaskOffline : NewNotice

    data object TaskFailed : NewNotice
}

data class NewUiState(
    val filter: NewFilter = NewFilter.ALL,
    /** The rows of [filter], newest first. */
    val rows: List<NewRow> = emptyList(),
    /** The whole GTD Inbox, whatever [filter] shows or holds back. */
    val inbox: List<GtdTask> = emptyList(),
    /** The GTD Inbox is the cached copy from [inboxRefreshedAt]: the server is unreachable. */
    val inboxOffline: Boolean = false,
    /** When the shown Inbox was loaded; `null` while it was never loaded. */
    val inboxRefreshedAt: Instant? = null,
    /** The server refused the last Inbox load although it was reachable. */
    val inboxFailed: Boolean = false,
    /** The mails are the ones loaded at [mailLoadedAt] (or none): the server is unreachable. */
    val mailOffline: Boolean = false,
    /** The server refused the last mail load although it was reachable. */
    val mailFailed: Boolean = false,
    val mailLoadedAt: Instant? = null,
    /** The first page of mail is loading. */
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** Older mails can be loaded. */
    val moreMail: Boolean = false,
    /** The mails "→ Inbox task" runs for. */
    val converting: Set<UUID> = emptySet(),
    val notice: NewNotice? = null,
) {
    val offline: Boolean get() = inboxOffline || mailOffline

    /** Offline the mail part is read-only: "→ Inbox task" needs the server. */
    val canConvert: Boolean get() = !mailOffline

    /** Nothing to show yet because both parts are still loading for the first time. */
    val firstLoad: Boolean get() = rows.isEmpty() && (loading || (inboxRefreshedAt == null && !inboxOffline && !inboxFailed))

    /** The Inbox task [id]; `null` once it left the Inbox (it was processed). */
    fun task(id: UUID): GtdTask? = inbox.firstOrNull { it.id == id }
}

/**
 * The New view (SPEC.md §5.3): the GTD Inbox (from the offline cache, reloaded while shown) and the unified mail inbox
 * (page by page) in one list, filtered by source. The filter is remembered across visits.
 */
@HiltViewModel
class NewViewModel internal constructor(
    private val tasks: TaskRepository,
    private val mail: MailRepository,
    private val reader: MailReadRepository,
    private val cache: NewMailCache,
    private val filterStore: NewFilterStore,
    private val clock: Clock,
) : ViewModel() {
    @Inject
    constructor(
        tasks: TaskRepository,
        mail: MailRepository,
        reader: MailReadRepository,
        cache: NewMailCache,
        filterStore: NewFilterStore,
    ) : this(tasks, mail, reader, cache, filterStore, Clock.systemUTC())

    private data class MailPart(
        val messages: List<MailSummary> = emptyList(),
        val nextCursor: String? = null,
        val loadedAt: Instant? = null,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val offline: Boolean = false,
        val failed: Boolean = false,
        val converting: Set<UUID> = emptySet(),
        val notice: NewNotice? = null,
    )

    private val filter = MutableStateFlow(filterStore.read())

    /** The session this view model belongs to: after a logout, nothing it loaded goes into the cache any more. */
    private val session = cache.session()
    private val mailPart =
        MutableStateFlow(cache.last?.let { MailPart(it.messages, it.nextCursor, it.loadedAt) } ?: MailPart())
    private var loadJob: Job? = null

    val uiState: StateFlow<NewUiState> =
        combine(tasks.observe(TaskView.INBOX), mailPart, filter) { inbox, m, f ->
            NewUiState(
                filter = f,
                // Tasks are held back only while the next mail page can actually be loaded.
                rows = mergeNew(inbox.data, m.messages, m.nextCursor != null && !m.offline && !m.failed, f),
                inbox = inbox.data,
                inboxOffline = inbox.offline,
                inboxRefreshedAt = inbox.refreshedAt,
                inboxFailed = inbox.failure != null,
                mailOffline = m.offline,
                mailFailed = m.failed,
                mailLoadedAt = m.loadedAt,
                loading = m.loading,
                loadingMore = m.loadingMore,
                moreMail = m.nextCursor != null,
                converting = m.converting,
                notice = m.notice,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NewUiState(filter = filter.value))

    fun setFilter(value: NewFilter) {
        if (value == filter.value) return
        filter.value = value
        filterStore.write(value)
    }

    /** Loads the newest mails again; the pages already loaded behind them stay. */
    fun refresh() {
        loadJob?.cancel()
        mailPart.update { it.copy(loading = true, loadingMore = false) }
        loadJob =
            viewModelScope.launch {
                val result = mail.messages(Mailbox.Unified, null)
                mailPart.update { m ->
                    when (result) {
                        is MailResult.Ok -> {
                            val page = result.value
                            val ids = page.items.mapTo(HashSet()) { it.id }
                            val oldest = page.items.lastOrNull()?.let { last -> m.messages.indexOfFirst { it.id == last.id } } ?: -1
                            // Keeps the pages behind the first one when the new page ends inside them.
                            val (messages, cursor) =
                                if (page.nextCursor != null && oldest >= 0) {
                                    (page.items + m.messages.drop(oldest + 1).filterNot { it.id in ids }) to m.nextCursor
                                } else {
                                    page.items to page.nextCursor
                                }
                            m.copy(
                                messages = messages,
                                nextCursor = cursor,
                                loadedAt = clock.instant(),
                                loading = false,
                                offline = false,
                                failed = false,
                            )
                        }
                        MailResult.Offline -> m.copy(loading = false, offline = true, failed = false)
                        is MailResult.Failed -> m.copy(loading = false, offline = false, failed = true)
                    }
                }
                remember()
            }
    }

    /** Reloads both parts (the error banner's retry). */
    fun retry() {
        viewModelScope.launch { tasks.refresh(TaskView.INBOX) }
        refresh()
    }

    /** Appends the next page of mail. */
    fun loadMore() {
        val m = mailPart.value
        val cursor = m.nextCursor ?: return
        if (m.loading || m.loadingMore) return
        mailPart.update { it.copy(loadingMore = true) }
        loadJob =
            viewModelScope.launch {
                val result = mail.messages(Mailbox.Unified, cursor)
                mailPart.update {
                    when (result) {
                        is MailResult.Ok ->
                            it.copy(
                                messages = (it.messages + result.value.items).distinctBy { mail -> mail.id },
                                nextCursor = result.value.nextCursor,
                                loadingMore = false,
                                offline = false,
                                failed = false,
                            )
                        MailResult.Offline -> it.copy(loadingMore = false, offline = true)
                        is MailResult.Failed -> it.copy(loadingMore = false, failed = true)
                    }
                }
                remember()
            }
    }

    /**
     * Creates an Inbox task from mail [id], linked to it (SPEC.md §7.3); the mail stays. Not offered offline; a second
     * tap while it runs does nothing.
     */
    fun toTask(id: UUID) {
        val m = mailPart.value
        if (m.offline || id in m.converting || m.messages.none { it.id == id }) return
        mailPart.update { it.copy(converting = it.converting + id, notice = null) }
        viewModelScope.launch {
            val result = reader.gtd(id, MailGtdAction.TASK)
            val notice =
                when (result) {
                    is MailResult.Ok -> NewNotice.TaskCreated(result.value)
                    MailResult.Offline -> NewNotice.TaskOffline
                    is MailResult.Failed -> NewNotice.TaskFailed
                }
            mailPart.update { it.copy(converting = it.converting - id, notice = notice) }
            // The task shows at once, before its change event arrives.
            if (result is MailResult.Ok) tasks.refresh(TaskView.INBOX)
        }
    }

    fun dismissNotice() = mailPart.update { it.copy(notice = null) }

    /** The read view opened [id] (and marked it read). */
    fun markSeen(id: UUID) = updateMessages { list -> list.map { if (it.id == id) it.copy(seen = true) else it } }

    /** The read view archived or deleted [id]: it left the inbox. */
    fun removed(id: UUID) = updateMessages { list -> list.filterNot { it.id == id } }

    private fun updateMessages(change: (List<MailSummary>) -> List<MailSummary>) {
        mailPart.update { it.copy(messages = change(it.messages)) }
        remember()
    }

    /** Keeps what was loaded for the offline view; nothing is kept before the first successful load. */
    private fun remember() {
        val m = mailPart.value
        val loadedAt = m.loadedAt ?: return
        cache.store(session, CachedMail(m.messages, m.nextCursor, loadedAt))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
