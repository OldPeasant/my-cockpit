package ch.sonensei.cockpit.mail.compose

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import java.util.UUID

/**
 * Compose, reply or forward ([mode]) as a full screen of the Mail tab. Back keeps the draft; [onClose] runs once it is
 * sent, discarded or left.
 */
@Composable
fun MailComposeRoute(
    mode: ComposeMode,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MailComposeViewModel = hiltViewModel(),
) {
    // The compose this route opened: the view model is shared, so a state of an earlier compose (closed, maybe even
    // with the same mode) must neither show here nor close this one.
    var session by remember(viewModel, mode) { mutableStateOf<Int?>(null) }
    LaunchedEffect(viewModel, mode) { session = viewModel.start(mode) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.attach(uris.map { describePicked(resolver, it) })
        }
    BackHandler(onBack = viewModel::leave)
    // Until start answered, an open compose of the same mode is the one start keeps (configuration change).
    val own = if (session == null) state.mode == mode && !state.ended else state.session == session
    val closed = own && state.ended
    LaunchedEffect(closed) { if (closed) onClose() }
    MailComposeContent(
        state = if (own) state else MailComposeUiState(mode = mode),
        callbacks =
            MailComposeCallbacks(
                back = viewModel::leave,
                abandon = viewModel::abandon,
                retry = viewModel::retry,
                account = viewModel::selectAccount,
                to = viewModel::setTo,
                cc = viewModel::setCc,
                bcc = viewModel::setBcc,
                showCcBcc = viewModel::showCcBcc,
                subject = viewModel::setSubject,
                body = viewModel::setBody,
                format = viewModel::format,
                attach = { picker.launch(arrayOf("*/*")) },
                removeAttachment = viewModel::removeAttachment,
                save = viewModel::save,
                send = viewModel::send,
                discard = viewModel::discard,
            ),
        modifier = modifier,
    )
}

/** What the compose screen can ask for, so it can be tested without a view model. */
data class MailComposeCallbacks(
    val back: () -> Unit = {},
    val abandon: () -> Unit = {},
    val retry: () -> Unit = {},
    val account: (UUID) -> Unit = {},
    val to: (String) -> Unit = {},
    val cc: (String) -> Unit = {},
    val bcc: (String) -> Unit = {},
    val showCcBcc: () -> Unit = {},
    val subject: (String) -> Unit = {},
    val body: (TextFieldValue) -> Unit = {},
    val format: (TextFormat) -> Unit = {},
    val attach: () -> Unit = {},
    val removeAttachment: (String) -> Unit = {},
    val save: () -> Unit = {},
    val send: () -> Unit = {},
    val discard: () -> Unit = {},
)

internal object MailComposeTags {
    const val BACK = "mail-compose-back"
    const val TITLE = "mail-compose-title"
    const val SEND = "mail-compose-send"
    const val ATTACH = "mail-compose-attach"
    const val MENU = "mail-compose-menu"
    const val SAVE = "mail-compose-save"
    const val DISCARD = "mail-compose-discard"
    const val STATUS = "mail-compose-status"
    const val PROBLEM = "mail-compose-problem"
    const val RETRY = "mail-compose-retry"
    const val ABANDON = "mail-compose-abandon"
    const val ACCOUNT = "mail-compose-account"
    const val CC_BCC = "mail-compose-cc-bcc"
    const val SUBJECT = "mail-compose-subject"
    const val BODY = "mail-compose-body"
    const val QUOTED = "mail-compose-quoted"
    const val UPLOADING = "mail-compose-uploading"

    fun field(field: RecipientField) = "mail-compose-field:$field"

    fun format(format: TextFormat) = "mail-compose-format:$format"

    fun account(id: UUID) = "mail-compose-account:$id"

    fun attachment(partId: String) = "mail-compose-attachment:$partId"
}

/** Toolbar (back, attach, send, more), draft status, From, recipients, subject, formatting buttons, body, quote. */
@Composable
fun MailComposeContent(
    state: MailComposeUiState,
    callbacks: MailComposeCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Toolbar(state, callbacks)
        val busy = state.phase == ComposePhase.STARTING && state.problem == null || state.phase == ComposePhase.SENDING
        if (busy || state.draft == DraftState.SAVING || state.uploading.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }
        Status(state)
        state.problem?.let { Problem(it, state, callbacks) }
        if (state.leaveBlocked) {
            TextButton(onClick = callbacks.abandon, modifier = Modifier.padding(horizontal = 8.dp).testTag(MailComposeTags.ABANDON)) {
                Text(stringResource(R.string.mail_compose_leave_unsaved))
            }
        }
        if (state.phase != ComposePhase.STARTING) Form(state, callbacks)
    }
}

@Composable
private fun Toolbar(
    state: MailComposeUiState,
    callbacks: MailComposeCallbacks,
) {
    var menu by remember { mutableStateOf(false) }
    val title =
        when (state.mode) {
            is ComposeMode.Reply -> R.string.mail_compose_title_reply
            is ComposeMode.ReplyAll -> R.string.mail_compose_title_reply_all
            is ComposeMode.Forward -> R.string.mail_compose_title_forward
            is ComposeMode.New, null -> R.string.mail_compose_title_new
        }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = callbacks.back, modifier = Modifier.testTag(MailComposeTags.BACK)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.mail_compose_back))
        }
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(MailComposeTags.TITLE),
        )
        IconButton(onClick = callbacks.attach, enabled = state.editable, modifier = Modifier.testTag(MailComposeTags.ATTACH)) {
            Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(R.string.mail_compose_attach))
        }
        IconButton(
            onClick = callbacks.send,
            enabled = state.editable && state.uploading.isEmpty(),
            modifier = Modifier.testTag(MailComposeTags.SEND),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(R.string.mail_compose_send),
                tint =
                    if (state.editable &&
                        state.uploading.isEmpty()
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(MailComposeTags.MENU)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.mail_compose_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mail_compose_save)) },
                    enabled = state.editable,
                    onClick = {
                        menu = false
                        callbacks.save()
                    },
                    modifier = Modifier.testTag(MailComposeTags.SAVE),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mail_compose_discard)) },
                    enabled = (state.phase == ComposePhase.EDITING || state.phase == ComposePhase.STARTING) && !state.leaving,
                    onClick = {
                        menu = false
                        callbacks.discard()
                    },
                    modifier = Modifier.testTag(MailComposeTags.DISCARD),
                )
            }
        }
    }
}

@Composable
private fun Status(state: MailComposeUiState) {
    val text =
        when {
            state.phase == ComposePhase.STARTING -> R.string.mail_compose_starting
            state.phase == ComposePhase.SENDING -> R.string.mail_compose_sending
            state.phase == ComposePhase.SENT -> R.string.mail_compose_sent
            state.draft == DraftState.SAVING -> R.string.mail_compose_saving
            state.draft == DraftState.SAVED -> R.string.mail_compose_saved
            state.draft == DraftState.UNSAVED -> R.string.mail_compose_unsaved
            else -> null
        } ?: return
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).testTag(MailComposeTags.STATUS),
    )
}

@Composable
private fun Problem(
    problem: ComposeProblem,
    state: MailComposeUiState,
    callbacks: MailComposeCallbacks,
) {
    val text =
        when (problem) {
            ComposeProblem.START_FAILED -> R.string.mail_compose_start_failed
            ComposeProblem.OFFLINE -> R.string.mail_compose_offline
            ComposeProblem.SAVE_FAILED -> R.string.mail_compose_save_failed
            ComposeProblem.INVALID_ADDRESS -> R.string.mail_compose_invalid_address
            ComposeProblem.NO_ACCOUNT -> R.string.mail_compose_no_account
            ComposeProblem.NO_RECIPIENT -> R.string.mail_compose_no_recipient
            ComposeProblem.REJECTED -> R.string.mail_compose_rejected
            ComposeProblem.SEND_FAILED -> R.string.mail_compose_send_failed
            ComposeProblem.ATTACH_FAILED -> R.string.mail_compose_attach_failed
            ComposeProblem.TOO_LARGE -> R.string.mail_compose_too_large
            ComposeProblem.DISCARD_FAILED -> R.string.mail_compose_discard_failed
        }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).testTag(MailComposeTags.PROBLEM),
        )
        if (state.phase == ComposePhase.STARTING || state.accounts.isEmpty() && state.mode is ComposeMode.New) {
            TextButton(onClick = callbacks.retry, modifier = Modifier.testTag(MailComposeTags.RETRY)) {
                Text(stringResource(R.string.mail_retry))
            }
        }
    }
}

@Composable
private fun Form(
    state: MailComposeUiState,
    callbacks: MailComposeCallbacks,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        From(state, callbacks.account)
        HorizontalDivider()
        RecipientRow(R.string.mail_compose_to, state.to, RecipientField.TO, state, callbacks.to) {
            if (!state.showCcBcc) {
                TextButton(onClick = callbacks.showCcBcc, modifier = Modifier.testTag(MailComposeTags.CC_BCC)) {
                    Text(stringResource(R.string.mail_compose_cc_bcc))
                }
            }
        }
        if (state.showCcBcc) {
            RecipientRow(R.string.mail_compose_cc, state.cc, RecipientField.CC, state, callbacks.cc)
            RecipientRow(R.string.mail_compose_bcc, state.bcc, RecipientField.BCC, state, callbacks.bcc)
        }
        LabeledRow(R.string.mail_compose_subject, isError = false) {
            PlainField(
                value = state.subject,
                onValueChange = callbacks.subject,
                enabled = state.editable,
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.testTag(MailComposeTags.SUBJECT),
            )
        }
        Attachments(state, callbacks.removeAttachment)
        FormatBar(state.editable, callbacks.format)
        val bodyLabel = stringResource(R.string.mail_compose_body)
        BasicTextField(
            value = state.body,
            onValueChange = callbacks.body,
            enabled = state.editable,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
                    .padding(16.dp)
                    .semantics { contentDescription = bodyLabel }
                    .testTag(MailComposeTags.BODY),
            decorationBox = { inner ->
                Box {
                    if (state.body.text.isEmpty()) {
                        Text(bodyLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                    }
                    inner()
                }
            },
        )
        if (state.quotedText.isNotBlank()) Quoted(state.quotedText)
    }
}

@Composable
private fun From(
    state: MailComposeUiState,
    select: (UUID) -> Unit,
) {
    val account = state.accounts.firstOrNull { it.id == state.accountId }
    if (account == null && state.accounts.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val canPick = !state.accountLocked && state.accounts.size > 1 && state.editable
    LabeledRow(R.string.mail_compose_from, isError = false) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { expanded = true }, enabled = canPick, modifier = Modifier.testTag(MailComposeTags.ACCOUNT)) {
                Text(
                    account
                        ?.let {
                            if (it.address.isNotBlank() &&
                                it.address != it.name
                            ) {
                                "${it.name} <${it.address}>"
                            } else {
                                it.name
                            }
                        }.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (canPick) Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.accounts.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(a.name) },
                        onClick = {
                            expanded = false
                            select(a.id)
                        },
                        modifier = Modifier.testTag(MailComposeTags.account(a.id)),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecipientRow(
    label: Int,
    value: String,
    field: RecipientField,
    state: MailComposeUiState,
    onChange: (String) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val isError = field in state.invalid
    LabeledRow(label, isError) {
        PlainField(
            value = value,
            onValueChange = onChange,
            enabled = state.editable,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.weight(1f).testTag(MailComposeTags.field(field)),
        )
        trailing()
    }
}

/** A row of the header form: a fixed-width label, then the field; an invalid one shows its label in the error color. */
@Composable
private fun LabeledRow(
    label: Int,
    isError: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp),
            )
            content()
        }
        HorizontalDivider(color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    keyboard: KeyboardOptions,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = keyboard,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun Attachments(
    state: MailComposeUiState,
    remove: (String) -> Unit,
) {
    val listed = state.listedAttachments
    if (listed.isEmpty() && state.uploading.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val removeLabel = stringResource(R.string.mail_compose_remove_attachment)
        listed.forEach { attachment ->
            InputChip(
                selected = false,
                onClick = { if (state.editable) remove(attachment.partId) },
                enabled = state.editable,
                label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "$removeLabel ${attachment.name}") },
                modifier = Modifier.testTag(MailComposeTags.attachment(attachment.partId)),
            )
        }
        state.uploading.forEach { name ->
            Text(
                stringResource(R.string.mail_compose_uploading, name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp).testTag(MailComposeTags.UPLOADING),
            )
        }
    }
}

@Composable
private fun FormatBar(
    enabled: Boolean,
    format: (TextFormat) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FormatButton(Icons.Outlined.FormatBold, R.string.mail_compose_bold, TextFormat.BOLD, enabled, format)
        FormatButton(Icons.Outlined.FormatItalic, R.string.mail_compose_italic, TextFormat.ITALIC, enabled, format)
        FormatButton(Icons.AutoMirrored.Outlined.FormatListBulleted, R.string.mail_compose_bullets, TextFormat.BULLET, enabled, format)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FormatButton(
    icon: ImageVector,
    label: Int,
    format: TextFormat,
    enabled: Boolean,
    onFormat: (TextFormat) -> Unit,
) {
    IconButton(onClick = { onFormat(format) }, enabled = enabled, modifier = Modifier.testTag(MailComposeTags.format(format))) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** The original of a reply or forward, kept below the body as the server quoted it; shown on request. */
@Composable
private fun Quoted(text: String) {
    var shown by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        TextButton(onClick = { shown = !shown }, modifier = Modifier.testTag(MailComposeTags.QUOTED)) {
            Text(stringResource(if (shown) R.string.mail_compose_hide_quoted else R.string.mail_compose_show_quoted))
        }
        if (shown) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
            )
        }
    }
}
