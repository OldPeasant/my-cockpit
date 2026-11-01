package ch.sonensei.cockpit.mail.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import java.time.Clock
import java.time.LocalDate

/**
 * The search box in place of the mailbox picker, and what the search found in the usual mail rows. Back or the close
 * button returns to the mailbox. A search can be run on the mail server too (with the text in the box); accounts whose
 * server failed are named, and a hint says when only the newest results are listed. [listState] scrolls the results.
 */
@Composable
internal fun MailSearchContent(
    state: MailListUiState,
    search: MailSearchState,
    callbacks: MailListCallbacks,
    listState: LazyListState,
    clock: Clock,
) {
    BackHandler(onBack = callbacks.search.close)
    var text by rememberSaveable { mutableStateOf(search.query) }
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        SearchBox(state, search, text, { text = it.take(MAX_QUERY) }, callbacks.search)
        search.message?.let { ListMessage(it, callbacks.search.retry) }
        if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        if (search.canSearchServer) {
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    callbacks.search.server(text)
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.padding(horizontal = 16.dp).testTag(MailListTags.SEARCH_SERVER),
            ) {
                Text(stringResource(R.string.mail_search_on_server))
            }
        }
        if (search.failedAccounts.isNotEmpty()) {
            val unknown = stringResource(R.string.mail_search_unknown_account)
            val names = search.failedAccounts.joinToString(", ") { id -> state.account(id)?.label ?: unknown }
            Note(stringResource(R.string.mail_search_server_failed, names), MailListTags.SEARCH_FAILURES, error = true)
        }
        if (search.hasMore && !search.loading) Note(stringResource(R.string.mail_search_has_more), MailListTags.SEARCH_HAS_MORE)
        when {
            search.loaded && search.results.isEmpty() && !search.loading ->
                Text(
                    stringResource(R.string.mail_search_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag(MailListTags.SEARCH_EMPTY),
                )
            else -> Results(state, search, callbacks, listState, clock)
        }
    }
}

@Composable
private fun SearchBox(
    state: MailListUiState,
    search: MailSearchState,
    text: String,
    onText: (String) -> Unit,
    callbacks: SearchCallbacks,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val submit = {
        if (text.isNotBlank()) {
            focusManager.clearFocus()
            callbacks.submit(text)
        }
    }
    val placeholder =
        when (search.mailbox) {
            Mailbox.Unified -> stringResource(R.string.mail_search_all)
            is Mailbox.Folder ->
                state.folder?.takeIf { it.id == search.mailbox.folderId }?.let { stringResource(R.string.mail_search_in, folderLabel(it)) }
                    ?: stringResource(R.string.mail_search)
        }
    // A new, empty search box takes the focus so the keyboard opens.
    LaunchedEffect(Unit) { if (search.query.isEmpty()) focus.requestFocus() }
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = callbacks.close, modifier = Modifier.testTag(MailListTags.SEARCH_CLOSE)) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.mail_search_close))
        }
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            trailingIcon = {
                IconButton(onClick = submit, enabled = text.isNotBlank(), modifier = Modifier.testTag(MailListTags.SEARCH_SUBMIT)) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.mail_search_submit))
                }
            },
            modifier = Modifier.weight(1f).focusRequester(focus).testTag(MailListTags.SEARCH_FIELD),
        )
    }
}

@Composable
private fun Note(
    text: String,
    tag: String,
    error: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag),
    )
}

@Composable
private fun Results(
    state: MailListUiState,
    search: MailSearchState,
    callbacks: MailListCallbacks,
    listState: LazyListState,
    clock: Clock,
) {
    val today = LocalDate.now(clock)
    // The bottom padding keeps the last row clear of the compose button.
    LazyColumn(
        Modifier.fillMaxSize().testTag(MailListTags.SEARCH_RESULTS),
        state = listState,
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        items(search.results, key = { it.id }) { mail ->
            MailRow(mail, state.account(mail.accountId)?.takeIf { state.showSearchAccounts }, today, clock, callbacks.open)
            HorizontalDivider()
        }
    }
}
