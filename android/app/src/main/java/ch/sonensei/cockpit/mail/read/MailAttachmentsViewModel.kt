package ch.sonensei.cockpit.mail.read

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.FilesRepository
import ch.sonensei.cockpit.files.FilesResult
import ch.sonensei.cockpit.files.FolderCrumb
import ch.sonensei.cockpit.mail.list.MailResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject

/** What the in-app preview can show. */
enum class AttachmentPreviewKind { IMAGE, PDF }

/** An attachment downloaded into the cache and shown in the app. */
data class AttachmentPreview(
    val attachment: MailAttachment,
    val file: File,
    val kind: AttachmentPreviewKind,
)

/**
 * The "Save to Files" folder picker for [attachment]: the drive from the root down to the folder shown ([path], empty
 * at the root) and that folder's subfolders.
 */
data class AttachmentFolderPicker(
    val attachment: MailAttachment,
    val path: List<FolderCrumb> = emptyList(),
    val folders: List<DriveEntry.Folder> = emptyList(),
    val loading: Boolean = false,
    /** The folder could not be listed. */
    val failed: Boolean = false,
)

/** What an attachment action did, shown until the next one. */
sealed interface AttachmentNotice {
    /** [name] was saved on the device where the user chose. */
    data class SavedToDevice(
        val name: String,
    ) : AttachmentNotice

    /**
     * [name] was stored in the drive, in [folder] (the picked folder's path, `null`: `/Attachments/Mail`).
     * [notMoved] is the picked folder it could not be moved to: it then stays in `/Attachments/Mail`.
     */
    data class SavedToFiles(
        val name: String,
        val folder: String? = null,
        val notMoved: String? = null,
    ) : AttachmentNotice

    /** [action] did not work: [offline] when the server or mail server was not reachable, [tooLarge] for the drive's limit. */
    data class Failed(
        val action: AttachmentAction,
        val offline: Boolean = false,
        val tooLarge: Boolean = false,
    ) : AttachmentNotice

    /** No app on the phone opens the attachment. */
    data object NoApp : AttachmentNotice
}

enum class AttachmentAction { OPEN, PREVIEW, SAVE, FILES }

data class MailAttachmentsUiState(
    /** The part being downloaded or stored: the attachment buttons wait for it. */
    val working: String? = null,
    val notice: AttachmentNotice? = null,
    val preview: AttachmentPreview? = null,
    val picker: AttachmentFolderPicker? = null,
)

/** Open [file] (an attachment downloaded into the cache) in another app; [mime] is the mail's type for it, if any. */
data class OpenAttachment(
    val file: File,
    val mime: String?,
)

/**
 * The attachments of the open mail (SPEC.md §7.3): open one in another app, save it on the device, preview images and
 * PDFs in the app, or store it in the drive ("Save to Files", in a folder picked here). One action runs at a time.
 * Device files are touched on [io], never on the main thread.
 */
@HiltViewModel
class MailAttachmentsViewModel internal constructor(
    private val attachments: MailAttachmentRepository,
    private val files: FilesRepository,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(attachments: MailAttachmentRepository, files: FilesRepository) : this(attachments, files, Dispatchers.IO)

    private val state = MutableStateFlow(MailAttachmentsUiState())
    val uiState: StateFlow<MailAttachmentsUiState> = state.asStateFlow()

    private val openChannel = Channel<OpenAttachment>(Channel.BUFFERED)

    /** Attachments to open in another app, once downloaded. */
    val opens: Flow<OpenAttachment> = openChannel.receiveAsFlow()

    private var messageId: UUID? = null
    private var job: Job? = null
    private var pickerJob: Job? = null

    /** A "save as" answer that arrived before its mail was shown (the app was restored while the picker was open). */
    private var waitingSave: WaitingSave? = null

    /** The mail whose attachments are shown, `null`: none. */
    val shown: UUID? get() = messageId

    /**
     * The attachments shown are those of [id] (`null`: no mail); what ran for another mail is dropped. A save waiting
     * for [id] starts now; one for another mail is discarded.
     */
    fun show(id: UUID?) {
        if (id == messageId) return
        messageId = id
        job?.cancel()
        pickerJob?.cancel()
        state.value = MailAttachmentsUiState()
        val waiting = waitingSave?.takeIf { id != null } ?: return
        waitingSave = null
        if (waiting.mail == id) {
            startSave(id, waiting.attachment, waiting.target, waiting.discard)
        } else {
            discardLater(waiting.discard)
            saveDropped()
        }
    }

    /**
     * Downloads [attachment] into the cache file [target] chooses (given the mail's id, so each mail has its own), then
     * asks the screen to open it.
     */
    fun open(
        attachment: MailAttachment,
        target: (UUID) -> File,
    ) = toCache(attachment, AttachmentAction.OPEN, target) { file ->
        openChannel.send(OpenAttachment(file, attachment.contentType))
    }

    /** Opens the attachment shown in the preview in another app, from the file already downloaded for it. */
    fun openPreviewed() {
        val preview = state.value.preview ?: return
        state.update { it.copy(preview = null) }
        openChannel.trySend(OpenAttachment(preview.file, preview.attachment.contentType))
    }

    /**
     * Downloads [attachment] into the cache file [target] chooses (given the mail's id) and shows it in the app (images
     * and PDFs).
     */
    fun preview(
        attachment: MailAttachment,
        target: (UUID) -> File,
    ) {
        val kind = previewKind(attachment) ?: return
        toCache(attachment, AttachmentAction.PREVIEW, target) { file ->
            state.update { it.copy(preview = AttachmentPreview(attachment, file, kind)) }
        }
    }

    fun closePreview() = state.update { it.copy(preview = null) }

    /** The screen could not open the attachment: no app takes it. */
    fun noApp() = state.update { it.copy(notice = AttachmentNotice.NoApp) }

    fun dismissNotice() = state.update { it.copy(notice = null) }

    /**
     * Downloads [attachment] of [mail] (`null`: the mail shown) into the document [target] opens (the user picked
     * where); a failed download, or a save ignored because another action runs, calls [discard] so no empty copy stays
     * there. When no mail is shown yet (the app was restored while the picker was open), the save waits for [mail] to be
     * shown; a save for another mail than the one shown fails and says so.
     */
    fun save(
        attachment: MailAttachment,
        target: () -> OutputStream?,
        mail: UUID? = null,
        discard: () -> Unit = {},
    ) {
        val shownId = messageId
        val id = mail ?: shownId
        when {
            id == null -> {
                discardLater(discard)
                saveDropped()
            }
            shownId == null -> {
                waitingSave?.let { discardLater(it.discard) }
                waitingSave = WaitingSave(id, attachment, target, discard)
            }
            id != shownId -> {
                discardLater(discard)
                saveDropped()
            }
            busy() -> discardLater(discard)
            else -> startSave(id, attachment, target, discard)
        }
    }

    override fun onCleared() {
        // Not in viewModelScope: that is cancelled now.
        waitingSave?.let { waiting -> CoroutineScope(io).launch { runCatching { waiting.discard() } } }
        waitingSave = null
    }

    /** A save that could not run: other actions keep running, the user is told. */
    private fun saveDropped() = state.update { it.copy(notice = AttachmentNotice.Failed(AttachmentAction.SAVE)) }

    private fun discardLater(discard: () -> Unit) {
        viewModelScope.launch(io) { runCatching { discard() } }
    }

    private fun startSave(
        id: UUID,
        attachment: MailAttachment,
        target: () -> OutputStream?,
        discard: () -> Unit,
    ) {
        start(attachment) {
            var ok = false
            try {
                val result = attachments.download(id, attachment.partId, target)
                ok = result is MailResult.Ok
                finish(if (ok) AttachmentNotice.SavedToDevice(attachment.name) else result.failed(AttachmentAction.SAVE))
            } finally {
                if (!ok) withContext(NonCancellable + io) { runCatching { discard() } }
            }
        }
    }

    /** "Save to Files": opens the folder picker for [attachment] at the drive root. */
    fun pickFolder(attachment: MailAttachment) {
        if (messageId == null || busy()) return
        state.update { it.copy(picker = AttachmentFolderPicker(attachment), notice = null) }
        listFolders()
    }

    /** The picker shows [folder]. */
    fun openFolder(folder: DriveEntry.Folder) = showFolder { it + FolderCrumb(folder.id, folder.name) }

    /** The picker shows the folder at [depth] of its path: 0 is the drive root. */
    fun goToFolder(depth: Int) = showFolder { it.take(depth.coerceAtLeast(0)) }

    fun closePicker() {
        pickerJob?.cancel()
        state.update { it.copy(picker = null) }
    }

    /**
     * Stores the picked attachment in the folder the picker shows; at the drive root it goes to the default
     * `/Attachments/Mail`.
     */
    fun saveToFiles() {
        val id = messageId ?: return
        val picker = state.value.picker ?: return
        if (busy()) return
        pickerJob?.cancel()
        state.update { it.copy(picker = null) }
        val target = picker.path.lastOrNull()
        val folder = picker.path.takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "/") { it.name }
        start(picker.attachment) {
            val notice =
                when (val result = attachments.saveToFiles(id, picker.attachment.partId, target?.id)) {
                    is MailResult.Ok ->
                        if (target == null || result.value.folderId == target.id) {
                            AttachmentNotice.SavedToFiles(result.value.name, folder)
                        } else {
                            AttachmentNotice.SavedToFiles(result.value.name, notMoved = folder)
                        }
                    else -> result.failed(AttachmentAction.FILES)
                }
            finish(notice)
        }
    }

    private fun showFolder(path: (List<FolderCrumb>) -> List<FolderCrumb>) {
        val picker = state.value.picker ?: return
        state.update { it.copy(picker = picker.copy(path = path(picker.path), folders = emptyList(), failed = false)) }
        listFolders()
    }

    private fun listFolders() {
        val picker = state.value.picker ?: return
        val folder = picker.path.lastOrNull()?.id
        pickerJob?.cancel()
        state.update { it.copy(picker = picker.copy(loading = true, failed = false)) }
        pickerJob =
            viewModelScope.launch {
                val result = files.list(folder)
                state.update { s ->
                    val shown = s.picker?.takeIf { it.path.lastOrNull()?.id == folder } ?: return@update s
                    s.copy(
                        picker =
                            shown.copy(
                                folders = (result as? FilesResult.Ok)?.value.orEmpty().filterIsInstance<DriveEntry.Folder>(),
                                loading = false,
                                failed = result !is FilesResult.Ok,
                            ),
                    )
                }
            }
    }

    private fun toCache(
        attachment: MailAttachment,
        action: AttachmentAction,
        target: (UUID) -> File,
        done: suspend (File) -> Unit,
    ) {
        val id = messageId ?: return
        if (busy()) return
        start(attachment) {
            var cache: File? = null
            var ok = false
            try {
                val result =
                    attachments.download(id, attachment.partId) {
                        target(id).also { cache = it }.let(::openCache)
                    }
                ok = result is MailResult.Ok
                finish(if (ok) null else result.failed(action))
                if (ok) done(checkNotNull(cache))
            } finally {
                if (!ok) withContext(NonCancellable + io) { runCatching { cache?.delete() } }
            }
        }
    }

    private fun busy() = job?.isActive == true

    private fun start(
        attachment: MailAttachment,
        block: suspend () -> Unit,
    ) {
        state.update { it.copy(working = attachment.partId, notice = null, preview = null) }
        job = viewModelScope.launch { block() }
    }

    private fun finish(notice: AttachmentNotice?) = state.update { it.copy(working = null, notice = notice) }
}

private class WaitingSave(
    val mail: UUID,
    val attachment: MailAttachment,
    val target: () -> OutputStream?,
    val discard: () -> Unit,
)

private const val TOO_LARGE = 413

private fun MailResult<*>.failed(action: AttachmentAction) =
    AttachmentNotice.Failed(
        action,
        offline = this == MailResult.Offline,
        tooLarge = (this as? MailResult.Failed)?.status == TOO_LARGE,
    )

private fun openCache(file: File): OutputStream? =
    try {
        FileOutputStream(file)
    } catch (_: IOException) {
        null
    }

private val PREVIEW_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** What the in-app preview shows [attachment] as; `null` when it cannot show it (open it in another app instead). */
internal fun previewKind(attachment: MailAttachment): AttachmentPreviewKind? {
    val type =
        attachment.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
    val extension = attachment.name.substringAfterLast('.', "").lowercase()
    return when {
        type in PREVIEW_IMAGES -> AttachmentPreviewKind.IMAGE
        type == "application/pdf" -> AttachmentPreviewKind.PDF
        type != null && type != "application/octet-stream" -> null
        extension in IMAGE_EXTENSIONS -> AttachmentPreviewKind.IMAGE
        extension == "pdf" -> AttachmentPreviewKind.PDF
        else -> null
    }
}
