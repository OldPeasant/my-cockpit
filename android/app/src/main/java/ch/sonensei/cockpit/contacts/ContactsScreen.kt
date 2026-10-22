package ch.sonensei.cockpit.contacts

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
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Search
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
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
                ),
            modifier = modifier,
        )
    } else {
        val viewModel: ContactDetailViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        LaunchedEffect(current) { viewModel.open(current) }
        BackHandler { open = null }
        val context = LocalContext.current
        val noApp = stringResource(R.string.contacts_no_app)
        val launch = { intent: android.content.Intent ->
            if (!ContactIntents.launch(context, intent)) Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
        }
        ContactDetailContent(
            state = state,
            callbacks =
                ContactDetailCallbacks(
                    back = { open = null },
                    call = { launch(ContactIntents.dial(it)) },
                    mail = { launch(ContactIntents.mail(it)) },
                    retry = viewModel::refresh,
                ),
            modifier = modifier,
        )
    }
}

/** What the list can ask for, so it can be tested without a view model. */
data class ContactsListCallbacks(
    val open: (UUID) -> Unit = {},
    val search: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

/** What the detail can ask for: [call] gets a phone number, [mail] an email address. */
data class ContactDetailCallbacks(
    val back: () -> Unit = {},
    val call: (String) -> Unit = {},
    val mail: (String) -> Unit = {},
    val retry: () -> Unit = {},
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
    Column(modifier.fillMaxSize()) {
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
            LazyColumn(Modifier.fillMaxSize().testTag(ContactsTags.LIST), contentPadding = PaddingValues(bottom = 24.dp)) {
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
) {
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.back, modifier = Modifier.testTag(ContactsTags.BACK)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.contacts_back))
            }
        }
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
            state.related?.let { related(it) }
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

private fun LazyListScope.related(related: ContactRelated) {
    if (related.waitingFor.isEmpty() && related.mails.isEmpty() && related.events.isEmpty() && related.links.isEmpty()) {
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
            RelatedRow(task.id, task.title, task.waitingSince?.let { stringResource(R.string.contacts_waiting_since, formatDate(it)) })
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
            )
        }
    }
    if (related.events.isNotEmpty()) {
        section(R.string.contacts_events)
        items(related.events, key = { "e${it.eventId}${it.start}${it.startDate}" }) { event ->
            RelatedRow(event.eventId, event.title, event.start?.let(::formatInstant) ?: event.startDate?.let(::formatDate))
        }
    }
    if (related.links.isNotEmpty()) {
        section(R.string.contacts_links)
        items(related.links, key = { "l${it.id}" }) { link ->
            val title = link.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.contacts_untitled)
            val subtitle =
                if (link.available) {
                    listOfNotNull(typeName(link.type), link.subtitle?.takeIf { it.isNotBlank() }).joinToString(", ")
                } else {
                    stringResource(R.string.contacts_link_deleted)
                }
            RelatedRow(link.id, title, subtitle)
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
) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(ContactsTags.related(id)),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.takeIf { it.isNotEmpty() }?.let { Label(it) }
    }
}

/** `JOURNAL_ENTRY` → `Journal entry`. */
internal fun typeName(type: String): String = type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun formatDate(date: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)

private fun formatInstant(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))
