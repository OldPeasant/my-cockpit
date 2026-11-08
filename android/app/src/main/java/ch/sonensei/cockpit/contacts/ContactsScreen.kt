package ch.sonensei.cockpit.contacts

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.links.ItemRef
import ch.sonensei.cockpit.core.links.LinkedItemsSection
import ch.sonensei.cockpit.core.links.LinkedItemsViewModel
import ch.sonensei.cockpit.core.links.linksViewModelKey
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchItemScreen
import ch.sonensei.cockpit.search.SearchTarget
import ch.sonensei.cockpit.search.searchTarget
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import okhttp3.HttpUrl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * The Contacts screen. Its view models come from Hilt, so outside a Hilt activity (the shell's navigation tests,
 * previews) it shows the placeholder instead of failing.
 */
@Composable
fun ContactsScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        ContactsRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_contacts, Icons.Outlined.Contacts, modifier)
    }
}

/** The list, or one contact opened from it (kept across configuration changes). */
@Composable
fun ContactsRoute(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val listViewModel: ContactsListViewModel = hiltViewModel()
    if (open == NEW) {
        val editViewModel: ContactEditViewModel = hiltViewModel(key = editViewModelKey(null))
        ContactEditRoute(null, editViewModel, onDone = { saved -> open = saved?.id?.toString() }, modifier = modifier)
        return
    }
    val current = open?.let(UUID::fromString)
    if (current == null) {
        val state by listViewModel.uiState.collectAsStateWithLifecycle()
        // Reloaded each time the list is shown, so a contact changed on the web meanwhile shows up.
        LaunchedEffect(Unit) { listViewModel.refresh() }
        ContactsListContent(
            state = state,
            callbacks =
                ContactsListCallbacks(
                    open = { open = it.toString() },
                    search = listViewModel::search,
                    retry = listViewModel::refresh,
                    create = { open = NEW },
                ),
            modifier = modifier,
        )
    } else {
        ContactDetailRoute(current, onClose = { open = null }, modifier = modifier)
    }
}

private const val NEW = "new"

/** One edit view model per contact (and one for new contacts), so a save or delete outcome never shows on another. */
private fun editViewModelKey(id: UUID?) = "contact-edit:${id ?: NEW}"

/**
 * The form for [contact] (`null`: a new one); [onDone] gets the saved contact, or `null` when cancelled. Back cancels.
 */
@Composable
fun ContactEditRoute(
    contact: Contact?,
    viewModel: ContactEditViewModel,
    onDone: (Contact?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept across configuration changes: a recreated screen continues the same form instead of starting it again.
    val session = rememberSaveable(contact?.id) { UUID.randomUUID().toString() }
    LaunchedEffect(session) { viewModel.start(contact, session) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Only this form's save closes it: a value left over from an earlier form arrives before start() clears it.
    val saved = state.saved?.takeIf { state.session == session }
    LaunchedEffect(saved) {
        if (saved != null) {
            viewModel.finished()
            onDone(saved)
        }
    }
    val cancel = {
        viewModel.cancel()
        onDone(null)
    }
    BackHandler(enabled = !state.saving, onBack = cancel)
    ContactEditContent(
        state = state,
        callbacks = ContactEditCallbacks(change = viewModel::change, save = viewModel::save, cancel = cancel),
        modifier = modifier,
    )
}

/**
 * One contact with its linked-items panel; tapping a related or linked item opens it here (tasks, mails, notes,
 * contacts in their Android views, other items such as events in the web app). Back returns to the contact.
 */
@Composable
fun ContactDetailRoute(
    id: UUID,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactDetailViewModel = hiltViewModel(),
    /** Without one, and outside a Hilt activity (screen tests with a fake contact view model), there is no Edit or Delete. */
    editViewModel: ContactEditViewModel? = null,
) {
    var opened by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(id) { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hilt = LocalActivity.current is GeneratedComponentManagerHolder
    val editor = editViewModel ?: if (hilt) hiltViewModel<ContactEditViewModel>(key = editViewModelKey(id)) else null
    val editState = editor?.uiState?.collectAsStateWithLifecycle()?.value ?: ContactEditUiState()
    val shown = state.contact
    if (editing && shown != null && editor != null) {
        ContactEditRoute(shown, editor, onDone = { saved ->
            editing = false
            if (saved != null) viewModel.refresh()
        }, modifier = modifier)
        return
    }
    val context = LocalContext.current
    val trashed = shown?.let { stringResource(R.string.contacts_trashed, it.displayName) }
    LaunchedEffect(editState.deleted) {
        if (editState.deleted) {
            editor?.finished()
            trashed?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            onClose()
        }
    }
    // The contact was changed elsewhere: reload it, so the next Delete goes against its current version.
    LaunchedEffect(editState.error) {
        if (editState.error == ContactSaveError.CONFLICT) viewModel.refresh()
    }
    val item = opened?.split(':')?.let { (type, itemId) -> type to UUID.fromString(itemId) }
    if (item != null) {
        BackHandler { opened = null }
        SearchItemScreen(item.first, item.second, onClose = { opened = null }, modifier = modifier)
        return
    }
    // Without Hilt (screen tests with a fake contact view model) there is no links panel; events then open without a server.
    val links: LinkedItemsViewModel? =
        if (hilt) {
            hiltViewModel(key = linksViewModelKey(ItemRef("CONTACT", id)))
        } else {
            null
        }
    val server =
        links
            ?.uiState
            ?.collectAsStateWithLifecycle()
            ?.value
            ?.server
    LaunchedEffect(id) { viewModel.open(id) }
    BackHandler(onBack = onClose)
    val noApp = stringResource(R.string.contacts_no_app)
    val noBrowser = stringResource(R.string.links_no_browser)
    val launch = { intent: Intent ->
        if (!ContactIntents.launch(context, intent)) Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
    }
    val openTarget = { target: SearchTarget ->
        when (target) {
            is SearchTarget.InApp -> opened = "${target.type}:${target.id}"
            is SearchTarget.Web ->
                if (!ContactIntents.launch(context, Intent(Intent.ACTION_VIEW, target.url.toUri()))) {
                    Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
                }
            // The contacts screen has no navigation to other screens; such items have a web link instead.
            is SearchTarget.Screen -> Unit
        }
    }
    ContactDetailContent(
        state = state,
        callbacks =
            ContactDetailCallbacks(
                back = onClose,
                call = { launch(ContactIntents.dial(it)) },
                mail = { launch(ContactIntents.mail(it)) },
                retry = viewModel::refresh,
                openRelated = { type, itemId -> openTarget(relatedTarget(type, itemId, server)) },
                edit = editor?.let { { editing = true } },
                delete = editor?.let { { confirmDelete = true } },
            ),
        busy = editState.saving,
        actionError = editState.error,
        modifier = modifier,
        linksPanel = { links?.let { LinkedItemsSection(ItemRef("CONTACT", id), open = openTarget, viewModel = it) } },
    )
    if (confirmDelete && shown != null && editor != null) {
        DeleteDialog(shown.displayName, onConfirm = {
            confirmDelete = false
            editor.delete(shown)
        }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun DeleteDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_delete_title)) },
        text = { Text(stringResource(R.string.contacts_delete_text, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(ContactsTags.CONFIRM_DELETE)) {
                Text(stringResource(R.string.contacts_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_cancel)) } },
    )
}

/** Where a related row leads: tasks and mails in-app, events in the web app's calendar. */
internal fun relatedTarget(
    type: String,
    id: UUID,
    server: HttpUrl?,
): SearchTarget =
    when (type) {
        "EVENT" -> searchTarget(SearchHit(id, type, "", null, "/calendar/events/$id"), server)
        else -> SearchTarget.InApp(type, id)
    }

/** What the list can ask for, so it can be tested without a view model. */
data class ContactsListCallbacks(
    val open: (UUID) -> Unit = {},
    val search: (String) -> Unit = {},
    val retry: () -> Unit = {},
    /** Opens the form for a new contact; without it there is no New button. */
    val create: (() -> Unit)? = null,
)

/** What the detail can ask for: [call] gets a phone number, [mail] an email address. */
data class ContactDetailCallbacks(
    val back: () -> Unit = {},
    val call: (String) -> Unit = {},
    val mail: (String) -> Unit = {},
    val retry: () -> Unit = {},
    /**
     * Opens a related item (waiting-for task `TASK`, mail `EMAIL`, event `EVENT`) by its type and id; without it the
     * related rows are not tappable.
     */
    val openRelated: ((String, UUID) -> Unit)? = null,
    /** Opens the edit form; without it (and [delete]) there are no edit actions. */
    val edit: (() -> Unit)? = null,
    /** Asks to move the contact to the Trash. */
    val delete: (() -> Unit)? = null,
)

internal object ContactsTags {
    const val SEARCH = "contacts-search"
    const val CLEAR = "contacts-clear"
    const val LIST = "contacts-list"
    const val EMPTY = "contacts-empty"
    const val MESSAGE = "contacts-message"
    const val RETRY = "contacts-retry"
    const val BACK = "contact-back"
    const val DETAIL = "contact-detail"
    const val CALL = "contact-call"
    const val MAIL = "contact-mail"
    const val RELATED_MESSAGE = "contact-related-message"
    const val NOTHING_RELATED = "contact-nothing-related"
    const val NEW = "contacts-new"
    const val EDIT = "contact-edit"
    const val DELETE = "contact-delete"
    const val CONFIRM_DELETE = "contact-confirm-delete"
    const val SOURCE = "contact-source"
    const val ACTION_ERROR = "contact-action-error"

    fun contact(id: UUID) = "contacts-contact:$id"

    fun phone(number: String) = "contact-phone:$number"

    fun email(address: String) = "contact-email:$address"

    fun related(id: UUID) = "contact-related:$id"
}

@Composable
fun ContactsListContent(
    state: ContactsListUiState,
    callbacks: ContactsListCallbacks,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        ContactsList(state, callbacks)
        callbacks.create?.let { create ->
            ExtendedFloatingActionButton(
                onClick = create,
                icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.contacts_new)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(ContactsTags.NEW),
            )
        }
    }
}

@Composable
private fun ContactsList(
    state: ContactsListUiState,
    callbacks: ContactsListCallbacks,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = callbacks.search,
            placeholder = { Text(stringResource(R.string.contacts_search)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { callbacks.search("") }, modifier = Modifier.testTag(ContactsTags.CLEAR)) {
                        Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.contacts_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(ContactsTags.SEARCH),
        )
        state.message?.let { Banner(it, R.string.contacts_offline, R.string.contacts_failed, state.loading, callbacks.retry) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))
        if (state.contacts.isEmpty() && state.loaded) {
            Text(
                state.query
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { stringResource(R.string.contacts_empty_search, it) }
                    ?: stringResource(R.string.contacts_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp).testTag(ContactsTags.EMPTY),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag(ContactsTags.LIST), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(state.contacts, key = { it.id }) { contact ->
                    ContactRow(contact, onClick = { callbacks.open(contact.id) })
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(ContactsTags.contact(contact.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(contact.displayName, size = 40)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(contact.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val secondary = listOfNotNull(contact.organization, contact.emails.firstOrNull()?.value ?: contact.phones.firstOrNull()?.value)
            if (secondary.isNotEmpty()) {
                Text(
                    secondary.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The contact's initials on a tonal circle. */
@Composable
private fun Monogram(
    name: String,
    size: Int,
) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            style = if (size > 48) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Up to two initials: first and last word (`Anna Muster` → `AM`); `?` for a name without letters. */
internal fun initials(name: String): String {
    val words = name.split(' ', '-', '.').filter { w -> w.any { it.isLetterOrDigit() } }
    val picks = listOfNotNull(words.firstOrNull(), words.drop(1).lastOrNull())
    return picks.joinToString("") { w -> w.first { it.isLetterOrDigit() }.uppercase() }.ifEmpty { "?" }
}

@Composable
private fun Banner(
    message: ContactsMessage,
    offline: Int,
    failed: Int,
    loading: Boolean,
    onRetry: () -> Unit,
    tag: String = ContactsTags.MESSAGE,
) {
    val (text, color) =
        when (message) {
            ContactsMessage.OFFLINE -> offline to MaterialTheme.colorScheme.secondaryContainer
            ContactsMessage.FAILED -> failed to MaterialTheme.colorScheme.errorContainer
        }
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            )
            TextButton(onClick = onRetry, enabled = !loading, modifier = Modifier.testTag(ContactsTags.RETRY)) {
                Text(stringResource(R.string.contacts_retry))
            }
        }
    }
}

@Composable
fun ContactDetailContent(
    state: ContactDetailUiState,
    callbacks: ContactDetailCallbacks,
    modifier: Modifier = Modifier,
    /** The linked-items panel below the related items (the routes pass [ch.sonensei.cockpit.core.links.LinkedItemsSection]). */
    linksPanel: @Composable () -> Unit = {},
    /** A save or delete is running: the edit actions are disabled. */
    busy: Boolean = false,
    /** Why the last delete did not work. */
    actionError: ContactSaveError? = null,
) {
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.back, modifier = Modifier.testTag(ContactsTags.BACK)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.contacts_back))
            }
            Spacer(Modifier.weight(1f))
            if (state.contact != null) {
                callbacks.edit?.let {
                    IconButton(onClick = it, enabled = !busy, modifier = Modifier.testTag(ContactsTags.EDIT)) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.contacts_edit))
                    }
                }
                callbacks.delete?.let {
                    IconButton(onClick = it, enabled = !busy, modifier = Modifier.testTag(ContactsTags.DELETE)) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.contacts_delete))
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        actionError?.let { ErrorBanner(deleteErrorText(it, state.contact), ContactsTags.ACTION_ERROR) }
        val contact = state.contact
        if (contact == null) {
            state.message?.let { Banner(it, R.string.contacts_offline, R.string.contacts_not_loaded, state.loading, callbacks.retry) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize().testTag(ContactsTags.DETAIL), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Header(contact, callbacks) }
            details(contact, callbacks)
            state.relatedMessage?.let { message ->
                item {
                    // Items from an earlier load stay shown, so the banner then says they may be out of date.
                    val stale = state.related != null
                    Banner(
                        message,
                        if (stale) R.string.contacts_related_offline_stale else R.string.contacts_related_offline,
                        if (stale) R.string.contacts_related_failed_stale else R.string.contacts_related_failed,
                        state.loading,
                        callbacks.retry,
                        tag = ContactsTags.RELATED_MESSAGE,
                    )
                }
            }
            state.related?.let { related(it, callbacks.openRelated) }
            item(key = "links") { linksPanel() }
        }
    }
}

@Composable
private fun Header(
    contact: Contact,
    callbacks: ContactDetailCallbacks,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Monogram(contact.displayName, size = 88)
        Text(contact.displayName, style = MaterialTheme.typography.headlineSmall)
        val role = listOfNotNull(contact.title, contact.organization).joinToString(", ")
        if (role.isNotEmpty()) {
            Text(role, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (contact.source == "LOCAL") {
                stringResource(R.string.contacts_source_local)
            } else {
                stringResource(R.string.contacts_source_synced, sourceName(contact.source))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ContactsTags.SOURCE),
        )
        val phone = contact.phones.firstOrNull()
        val email = contact.emails.firstOrNull()
        if (phone != null || email != null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (phone != null) {
                    FilledTonalButton(onClick = { callbacks.call(phone.value) }, modifier = Modifier.testTag(ContactsTags.CALL)) {
                        Icon(Icons.Outlined.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.contacts_call), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (email != null) {
                    FilledTonalButton(onClick = { callbacks.mail(email.value) }, modifier = Modifier.testTag(ContactsTags.MAIL)) {
                        Icon(Icons.Outlined.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.contacts_email), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.details(
    contact: Contact,
    callbacks: ContactDetailCallbacks,
) {
    if (contact.phones.isNotEmpty()) {
        section(R.string.contacts_phones)
        items(contact.phones) { phone ->
            ActionRow(phone, R.string.contacts_call_number, Icons.Outlined.Call, ContactsTags.phone(phone.value)) {
                callbacks.call(phone.value)
            }
        }
    }
    if (contact.emails.isNotEmpty()) {
        section(R.string.contacts_emails)
        items(contact.emails) { email ->
            ActionRow(email, R.string.contacts_email_address, Icons.Outlined.Email, ContactsTags.email(email.value)) {
                callbacks.mail(email.value)
            }
        }
    }
    if (contact.addresses.isNotEmpty()) {
        section(R.string.contacts_addresses)
        items(contact.addresses) { InfoRow(it.value, it.label) }
    }
    contact.birthday?.let { birthday ->
        section(R.string.contacts_birthday)
        item { InfoRow(formatDate(birthday), null) }
    }
    contact.notesMd?.let { notes ->
        section(R.string.contacts_notes)
        item { InfoRow(notes, null) }
    }
}

private fun LazyListScope.related(
    related: ContactRelated,
    open: ((String, UUID) -> Unit)?,
) {
    if (related.waitingFor.isEmpty() && related.mails.isEmpty() && related.events.isEmpty()) {
        item {
            Text(
                stringResource(R.string.contacts_nothing_related),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).testTag(ContactsTags.NOTHING_RELATED),
            )
        }
        return
    }
    if (related.waitingFor.isNotEmpty()) {
        section(R.string.contacts_waiting_for)
        items(related.waitingFor, key = { "w${it.id}" }) { task ->
            RelatedRow(
                task.id,
                task.title,
                task.waitingSince?.let { stringResource(R.string.contacts_waiting_since, formatDate(it)) },
                open?.let { { it("TASK", task.id) } },
            )
        }
    }
    if (related.mails.isNotEmpty()) {
        section(R.string.contacts_mails)
        items(related.mails, key = { "m${it.id}" }) { mail ->
            val at = mail.at?.let(::formatInstant).orEmpty()
            RelatedRow(
                mail.id,
                mail.subject ?: stringResource(R.string.contacts_no_subject),
                stringResource(if (mail.fromContact) R.string.contacts_mail_from else R.string.contacts_mail_to, at).trimEnd(',', ' '),
                open?.let { { it("EMAIL", mail.id) } },
            )
        }
    }
    if (related.events.isNotEmpty()) {
        section(R.string.contacts_events)
        items(related.events, key = { "e${it.eventId}${it.start}${it.startDate}" }) { event ->
            RelatedRow(
                event.eventId,
                event.title,
                event.start?.let(::formatInstant) ?: event.startDate?.let(::formatDate),
                open?.let { { it("EVENT", event.eventId) } },
            )
        }
    }
}

private fun LazyListScope.section(title: Int) {
    item {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ActionRow(
    value: LabeledValue,
    action: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    onClick: () -> Unit,
) {
    val description = stringResource(action, value.value)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(value.value, style = MaterialTheme.typography.bodyLarge)
            value.label?.takeIf { it.isNotBlank() }?.let { Label(it) }
        }
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun InfoRow(
    value: String,
    label: String?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(value, style = MaterialTheme.typography.bodyLarge)
        label?.takeIf { it.isNotBlank() }?.let { Label(it) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RelatedRow(
    id: UUID,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(ContactsTags.related(id)),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.takeIf { it.isNotEmpty() }?.let { Label(it) }
    }
}

/** A failed delete: a refused one says where to change the contact instead. */
@Composable
private fun deleteErrorText(
    error: ContactSaveError,
    contact: Contact?,
): String =
    when (error) {
        ContactSaveError.FAILED -> stringResource(R.string.contacts_delete_failed)
        ContactSaveError.CONFLICT -> stringResource(R.string.contacts_delete_conflict)
        else -> saveErrorText(error, contact)
    }

/** `JOURNAL_ENTRY` → `Journal entry`. */
internal fun typeName(type: String): String = type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun formatDate(date: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)

private fun formatInstant(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))
