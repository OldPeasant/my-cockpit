package ch.sonensei.cockpit.newitems

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.mail.compose.ComposeMode
import ch.sonensei.cockpit.mail.compose.MailComposeRoute
import ch.sonensei.cockpit.mail.list.MailSummary
import ch.sonensei.cockpit.mail.read.MailReadRoute
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * The New screen. Its view models come from Hilt, so outside a Hilt activity (the shell's navigation tests, previews)
 * it shows the placeholder instead of failing.
 */
@Composable
fun NewScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        NewRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_new, Icons.Outlined.NewReleases, modifier)
    }
}

/** What is open on top of the list (kept across configuration changes). */
internal sealed interface NewOpen {
    data class Task(
        val id: UUID,
    ) : NewOpen

    data class Mail(
        val id: UUID,
    ) : NewOpen

    fun encode(): String =
        when (this) {
            is Task -> "task:$id"
            is Mail -> "mail:$id"
        }

    companion object {
        fun parse(value: String): NewOpen? {
            val (kind, id) = value.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
            return when (kind) {
                "task" -> Task(uuid)
                "mail" -> Mail(uuid)
                else -> null
            }
        }
    }
}

/** The list, or the task or mail opened from it; back returns to the list. */
@Composable
fun NewRoute(
    modifier: Modifier = Modifier,
    viewModel: NewViewModel = hiltViewModel(),
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var composing by rememberSaveable { mutableStateOf<String?>(null) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Kept here, so the list keeps its position while a task or mail is open on top of it.
    val listState = rememberLazyListState()
    // Reloaded when the screen is shown and when the app comes back to it, so mail that arrived meanwhile appears.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val compose = composing?.let(ComposeMode::parse)
    when (val current = open?.let(NewOpen::parse)) {
        null ->
            NewContent(
                state = state,
                callbacks =
                    NewCallbacks(
                        setFilter = viewModel::setFilter,
                        open = { open = it.encode() },
                        toTask = viewModel::toTask,
                        loadMore = viewModel::loadMore,
                        retry = viewModel::retry,
                        dismissNotice = viewModel::dismissNotice,
                    ),
                modifier = modifier,
                listState = listState,
            )
        is NewOpen.Task -> {
            val task = state.task(current.id)
            // A task processed meanwhile (here or elsewhere) is no longer new: back to the list. Before the Inbox is
            // read (just after a configuration change), the task is simply not shown yet.
            val gone = task == null && state.inboxRefreshedAt != null
            LaunchedEffect(gone) { if (gone) open = null }
            if (task != null) NewTaskView(task, onClose = { open = null }, modifier = modifier)
        }
        is NewOpen.Mail ->
            if (compose != null) {
                MailComposeRoute(mode = compose, onClose = { composing = null }, modifier = modifier)
            } else {
                MailReadRoute(
                    id = current.id,
                    onClose = { open = null },
                    onSeen = viewModel::markSeen,
                    onRemoved = viewModel::removed,
                    onCompose = { composing = it.encode() },
                    modifier = modifier,
                )
            }
    }
}

/** What the list can ask for, so it can be tested without a view model. */
internal data class NewCallbacks(
    val setFilter: (NewFilter) -> Unit = {},
    val open: (NewOpen) -> Unit = {},
    val toTask: (UUID) -> Unit = {},
    val loadMore: () -> Unit = {},
    val retry: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

internal object NewTags {
    const val LIST = "new-list"
    const val EMPTY = "new-empty"
    const val LOADING = "new-loading"
    const val OFFLINE = "new-offline"
    const val ERROR = "new-error"
    const val RETRY = "new-retry"
    const val NOTICE = "new-notice"
    const val LOAD_MORE = "new-load-more"
    const val TASK_VIEW = "new-task-view"

    fun filter(filter: NewFilter) = "new-filter-${filter.name.lowercase()}"

    fun row(row: NewRow) = "new-row:${row.key}"

    fun source(row: NewRow) = "new-source:${row.key}"

    fun toTask(id: UUID) = "new-to-task:$id"
}

/** The source filter, the banners and the merged rows, newest first. */
@Composable
internal fun NewContent(
    state: NewUiState,
    callbacks: NewCallbacks,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemDefaultZone(),
    listState: LazyListState = rememberLazyListState(),
) {
    Column(modifier.fillMaxSize()) {
        Filters(state.filter, callbacks.setFilter)
        if (state.offline) OfflineBanner(state, clock, callbacks.retry)
        if (!state.offline && (state.inboxFailed || state.mailFailed)) ErrorBanner(state, callbacks.retry)
        state.notice?.let { Notice(it, callbacks.dismissNotice) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        when {
            state.rows.isNotEmpty() -> Rows(state, callbacks, listState, clock)
            state.firstLoad ->
                Text(
                    stringResource(R.string.new_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp).testTag(NewTags.LOADING),
                )
            else ->
                Text(
                    stringResource(R.string.new_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag(NewTags.EMPTY),
                )
        }
    }
}

@Composable
private fun Filters(
    selected: NewFilter,
    select: (NewFilter) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NewFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { select(filter) },
                label = {
                    Text(
                        stringResource(
                            when (filter) {
                                NewFilter.ALL -> R.string.new_filter_all
                                NewFilter.INBOX -> R.string.new_filter_inbox
                                NewFilter.MAIL -> R.string.new_filter_mail
                            },
                        ),
                    )
                },
                modifier = Modifier.testTag(NewTags.filter(filter)),
            )
        }
    }
}

@Composable
private fun Rows(
    state: NewUiState,
    callbacks: NewCallbacks,
    listState: LazyListState,
    clock: Clock,
) {
    val today = LocalDate.now(clock)
    LazyColumn(Modifier.fillMaxSize().testTag(NewTags.LIST), state = listState) {
        items(state.rows, key = { it.key }) { row ->
            when (row) {
                is NewRow.Task -> TaskRow(row, today, clock, callbacks.open)
                is NewRow.Mail -> MailRow(row, state, today, clock, callbacks)
            }
            HorizontalDivider()
        }
        // Only the mail part is paged; the Inbox filter shows every task already.
        if (state.moreMail && state.filter != NewFilter.INBOX) {
            item(key = NewTags.LOAD_MORE) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(
                        onClick = callbacks.loadMore,
                        enabled = !state.loadingMore && !state.loading,
                        modifier = Modifier.testTag(NewTags.LOAD_MORE),
                    ) { Text(stringResource(R.string.new_load_more)) }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    row: NewRow.Task,
    today: LocalDate,
    clock: Clock,
    open: (NewOpen) -> Unit,
) {
    RowFrame(row, Icons.Outlined.Inbox, R.string.new_source_inbox, today, clock, onClick = { open(NewOpen.Task(row.task.id)) }) {
        Text(row.task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        row.task.notesMd?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MailRow(
    row: NewRow.Mail,
    state: NewUiState,
    today: LocalDate,
    clock: Clock,
    callbacks: NewCallbacks,
) {
    val mail = row.mail
    RowFrame(row, Icons.Outlined.Mail, R.string.new_source_mail, today, clock, onClick = { callbacks.open(NewOpen.Mail(mail.id)) }) {
        MailText(mail)
        TextButton(
            onClick = { callbacks.toTask(mail.id) },
            enabled = state.canConvert && mail.id !in state.converting,
            modifier = Modifier.testTag(NewTags.toTask(mail.id)),
        ) { Text(stringResource(R.string.new_to_task)) }
    }
}

@Composable
private fun MailText(mail: MailSummary) {
    val unread = stringResource(R.string.mail_unread)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!mail.seen) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = unread },
            )
        }
        Text(
            mail.subject.ifBlank { stringResource(R.string.new_no_subject) },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val from = listOf(mail.sender, mail.snippet).filter { it.isNotBlank() }.joinToString(" – ")
    if (from.isNotEmpty()) {
        Text(
            from,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row: the source marker, the item's [content] and its date; tapping it opens the item. */
@Composable
private fun RowFrame(
    row: NewRow,
    icon: ImageVector,
    sourceLabel: Int,
    today: LocalDate,
    clock: Clock,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)
                .testTag(NewTags.row(row)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(sourceLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f).testTag(NewTags.source(row)),
                )
                row.at?.let {
                    Text(
                        formatDate(it, today, clock),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun OfflineBanner(
    state: NewUiState,
    clock: Clock,
    retry: () -> Unit,
) {
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    val details =
        listOfNotNull(
            state.inboxRefreshedAt?.takeIf { state.inboxOffline }?.let {
                stringResource(R.string.new_offline_inbox_at, format.format(it.atZone(clock.zone)))
            },
            if (state.mailOffline) {
                state.mailLoadedAt?.let { stringResource(R.string.new_offline_mail_at, format.format(it.atZone(clock.zone))) }
                    ?: stringResource(R.string.new_offline_mail_never)
            } else {
                null
            },
        )
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag(NewTags.OFFLINE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                (listOf(stringResource(R.string.new_offline)) + details).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            TextButton(onClick = retry, enabled = !state.loading, modifier = Modifier.testTag(NewTags.RETRY)) {
                Text(stringResource(R.string.new_retry))
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    state: NewUiState,
    retry: () -> Unit,
) {
    val text =
        listOfNotNull(
            stringResource(R.string.new_inbox_failed).takeIf { state.inboxFailed },
            stringResource(R.string.new_mail_failed).takeIf { state.mailFailed },
        ).joinToString(" ")
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(NewTags.ERROR)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            TextButton(onClick = retry, enabled = !state.loading, modifier = Modifier.testTag(NewTags.RETRY)) {
                Text(stringResource(R.string.new_retry))
            }
        }
    }
}

@Composable
private fun Notice(
    notice: NewNotice,
    dismiss: () -> Unit,
) {
    val (text, color) =
        when (notice) {
            is NewNotice.TaskCreated ->
                stringResource(R.string.new_task_created, notice.title) to
                    MaterialTheme.colorScheme.secondaryContainer
            NewNotice.TaskOffline -> stringResource(R.string.new_task_offline) to MaterialTheme.colorScheme.errorContainer
            NewNotice.TaskFailed -> stringResource(R.string.new_task_failed) to MaterialTheme.colorScheme.errorContainer
        }
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag(NewTags.NOTICE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            TextButton(onClick = dismiss) { Text(stringResource(R.string.new_dismiss)) }
        }
    }
}

/** A GTD Inbox item opened from the list, read-only: it is processed in the Inbox. */
@Composable
internal fun NewTaskView(
    task: GtdTask,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemDefaultZone(),
) {
    BackHandler(onBack = onClose)
    Column(modifier.fillMaxSize().testTag(NewTags.TASK_VIEW)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.nav_back))
            }
            Icon(
                Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.new_source_inbox),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(task.title, style = MaterialTheme.typography.headlineSmall)
            createdAt(task.id)?.let {
                val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(it.atZone(clock.zone))
                Text(
                    stringResource(R.string.new_task_created_at, time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.notesMd?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                stringResource(R.string.new_task_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Today's items by time, older ones by date. */
private fun formatDate(
    instant: Instant,
    today: LocalDate,
    clock: Clock,
): String {
    val local = instant.atZone(clock.zone)
    return if (local.toLocalDate() == today) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).format(local)
    }
}
