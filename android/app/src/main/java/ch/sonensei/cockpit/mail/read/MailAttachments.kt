package ch.sonensei.cockpit.mail.read

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What the attachment list can ask for, so it can be tested without a view model. */
data class MailAttachmentCallbacks(
    val open: (MailAttachment) -> Unit = {},
    val preview: (MailAttachment) -> Unit = {},
    val saveToDevice: (MailAttachment) -> Unit = {},
    val saveToFiles: (MailAttachment) -> Unit = {},
    val dismissNotice: () -> Unit = {},
    val closePreview: () -> Unit = {},
    /** Opens the previewed attachment in another app, from the file the preview already downloaded. */
    val openPreviewed: () -> Unit = {},
    /** The picker shows [DriveEntry.Folder]. */
    val openFolder: (DriveEntry.Folder) -> Unit = {},
    /** The picker shows the folder at this depth of its path: 0 is the drive root. */
    val goToFolder: (Int) -> Unit = {},
    val saveHere: () -> Unit = {},
    val closePicker: () -> Unit = {},
)

internal object MailAttachmentTags {
    const val NOTICE = "mail-attachment-notice"
    const val PREVIEW_DIALOG = "mail-attachment-preview-dialog"
    const val PREVIEW_CLOSE = "mail-attachment-preview-close"
    const val PREVIEW_OPEN = "mail-attachment-preview-open"
    const val PREVIEW_ERROR = "mail-attachment-preview-error"
    const val PICKER = "mail-attachment-picker"
    const val PICKER_SAVE = "mail-attachment-picker-save"
    const val PICKER_CANCEL = "mail-attachment-picker-cancel"
    const val PICKER_EMPTY = "mail-attachment-picker-empty"
    const val PICKER_FAILED = "mail-attachment-picker-failed"

    fun row(partId: String) = "mail-attachment:$partId"

    fun preview(partId: String) = "mail-attachment-preview:$partId"

    fun more(partId: String) = "mail-attachment-more:$partId"

    fun saveDevice(partId: String) = "mail-attachment-save-device:$partId"

    fun saveFiles(partId: String) = "mail-attachment-save-files:$partId"

    fun working(partId: String) = "mail-attachment-working:$partId"

    fun crumb(depth: Int) = "mail-attachment-crumb:$depth"

    fun folder(name: String) = "mail-attachment-folder:$name"
}

/** Where "Save to Files" stores an attachment unless another folder is picked (the server's auto-managed folder). */
internal const val DEFAULT_ATTACHMENT_FOLDER = "/Attachments/Mail"

/**
 * The attachments of a mail: type icon, name and size; a tap opens one in another app, "Preview" shows images and PDFs
 * here, the menu saves it on the device or in Files. The buttons wait while an action runs ([enabled] `false` or
 * [MailAttachmentsUiState.working]); a long list scrolls on its own, so the mail body keeps its room.
 */
@Composable
internal fun AttachmentList(
    attachments: List<MailAttachment>,
    state: MailAttachmentsUiState,
    callbacks: MailAttachmentCallbacks,
    enabled: Boolean,
) {
    val idle = enabled && state.working == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag(MailReadTags.ATTACHMENTS)) {
        Text(
            stringResource(R.string.mail_read_attachments),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
        )
        Column(Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState())) {
            attachments.forEach { AttachmentRow(it, state.working == it.partId, idle, callbacks) }
        }
        state.notice?.let { AttachmentNoticeLine(it, callbacks.dismissNotice) }
    }
    state.preview?.let { AttachmentPreviewDialog(it, callbacks) }
    state.picker?.let { FolderPickerDialog(it, idle, callbacks) }
}

@Composable
private fun AttachmentRow(
    attachment: MailAttachment,
    working: Boolean,
    enabled: Boolean,
    callbacks: MailAttachmentCallbacks,
) {
    val openLabel = stringResource(R.string.mail_attachment_open, attachment.name)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = openLabel) { callbacks.open(attachment) }
            .padding(start = 12.dp)
            .testTag(MailAttachmentTags.row(attachment.partId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(attachmentIcon(attachment), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            attachment.sizeBytes?.let {
                Text(
                    formatSize(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (working) {
            CircularProgressIndicator(
                Modifier.padding(12.dp).size(24.dp).testTag(MailAttachmentTags.working(attachment.partId)),
                strokeWidth = 2.dp,
            )
        }
        if (previewKind(attachment) != null) {
            IconButton(
                onClick = { callbacks.preview(attachment) },
                enabled = enabled,
                modifier = Modifier.testTag(MailAttachmentTags.preview(attachment.partId)),
            ) { Icon(Icons.Outlined.Visibility, contentDescription = stringResource(R.string.mail_attachment_preview)) }
        }
        AttachmentMenu(attachment, enabled, callbacks)
    }
}

@Composable
private fun AttachmentMenu(
    attachment: MailAttachment,
    enabled: Boolean,
    callbacks: MailAttachmentCallbacks,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(MailAttachmentTags.more(attachment.partId))) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.mail_attachment_more, attachment.name))
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mail_attachment_save_device)) },
                onClick = {
                    open = false
                    callbacks.saveToDevice(attachment)
                },
                modifier = Modifier.testTag(MailAttachmentTags.saveDevice(attachment.partId)),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mail_attachment_save_files)) },
                onClick = {
                    open = false
                    callbacks.saveToFiles(attachment)
                },
                modifier = Modifier.testTag(MailAttachmentTags.saveFiles(attachment.partId)),
            )
        }
    }
}

/** The icon for [attachment]'s type: its content type, else its file extension. */
internal fun attachmentIcon(attachment: MailAttachment): ImageVector {
    val type =
        attachment.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
    val extension = attachment.name.substringAfterLast('.', "").lowercase()
    return when {
        type.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg") ->
            Icons.Outlined.Photo
        type == "application/pdf" || extension == "pdf" -> Icons.Outlined.PictureAsPdf
        type.startsWith("audio/") || extension in setOf("mp3", "m4a", "wav", "ogg", "flac") -> Icons.Outlined.AudioFile
        type.startsWith("video/") || extension in setOf("mp4", "mov", "mkv", "webm", "avi") -> Icons.Outlined.VideoFile
        type.contains("zip") || type.contains("compressed") || extension in setOf("zip", "7z", "rar", "gz", "tar") ->
            Icons.Outlined.FolderZip
        type.startsWith("text/") || extension in setOf("txt", "csv", "md", "doc", "docx", "odt", "rtf") -> Icons.Outlined.Description
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}

@Composable
private fun AttachmentNoticeLine(
    notice: AttachmentNotice,
    dismiss: () -> Unit,
) {
    val text = noticeText(notice)
    val good = notice is AttachmentNotice.SavedToDevice || (notice is AttachmentNotice.SavedToFiles && notice.notMoved == null)
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).testTag(MailAttachmentTags.NOTICE),
        )
        IconButton(onClick = dismiss) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.mail_attachment_dismiss))
        }
    }
}

@Composable
private fun noticeText(notice: AttachmentNotice): String =
    when (notice) {
        is AttachmentNotice.SavedToDevice -> stringResource(R.string.mail_attachment_saved_device, notice.name)
        is AttachmentNotice.SavedToFiles ->
            if (notice.notMoved != null) {
                stringResource(R.string.mail_attachment_not_moved, notice.name, DEFAULT_ATTACHMENT_FOLDER, notice.notMoved)
            } else {
                stringResource(R.string.mail_attachment_saved_files, notice.name, notice.folder ?: DEFAULT_ATTACHMENT_FOLDER)
            }
        AttachmentNotice.NoApp -> stringResource(R.string.mail_attachment_no_app)
        is AttachmentNotice.Failed -> {
            val failed =
                when (notice.action) {
                    AttachmentAction.OPEN -> stringResource(R.string.mail_attachment_open_failed)
                    AttachmentAction.PREVIEW -> stringResource(R.string.mail_attachment_preview_failed)
                    AttachmentAction.SAVE -> stringResource(R.string.mail_attachment_save_failed)
                    AttachmentAction.FILES -> stringResource(R.string.mail_attachment_files_failed)
                }
            val reason =
                when {
                    notice.offline -> stringResource(R.string.mail_attachment_offline)
                    notice.tooLarge -> stringResource(R.string.mail_attachment_too_large)
                    else -> null
                }
            if (reason == null) failed else "$failed $reason"
        }
    }

/** The in-app preview: the image, or the pages of the PDF, full screen; it can also hand the file to another app. */
@Composable
private fun AttachmentPreviewDialog(
    preview: AttachmentPreview,
    callbacks: MailAttachmentCallbacks,
) {
    Dialog(onDismissRequest = callbacks.closePreview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag(MailAttachmentTags.PREVIEW_DIALOG)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = callbacks.closePreview, modifier = Modifier.testTag(MailAttachmentTags.PREVIEW_CLOSE)) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.mail_attachment_close_preview))
                    }
                    Text(
                        preview.attachment.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = callbacks.openPreviewed,
                        modifier = Modifier.testTag(MailAttachmentTags.PREVIEW_OPEN),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.mail_attachment_open_in_app),
                        )
                    }
                }
                when (preview.kind) {
                    AttachmentPreviewKind.IMAGE -> ImagePreview(preview.file)
                    AttachmentPreviewKind.PDF -> PdfPreview(preview.file)
                }
            }
        }
    }
}

/** What a preview could render: `null` while it loads, an empty list when it cannot show the file. */
private typealias Pages = List<Bitmap>?

@Composable
private fun ImagePreview(file: File) {
    val pages by produceState<Pages>(null, file) { value = withContext(Dispatchers.IO) { listOfNotNull(decodeImage(file)) } }
    PreviewPages(pages)
}

@Composable
private fun PdfPreview(file: File) {
    // null while the document opens; it is closed when the preview leaves.
    val document by produceState<PdfDocument?>(null, file) {
        var opened: PdfDocument? = null
        try {
            withContext(Dispatchers.IO) { opened = PdfDocument.open(file) }
            value = opened
            awaitCancellation()
        } finally {
            opened?.close()
        }
    }
    when (val pdf = document) {
        null -> PreviewPages(null)
        PdfDocument.UNREADABLE -> PreviewPages(emptyList())
        else ->
            // Pages are rendered one at a time as they scroll into view and recycled when they leave it.
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pdf.pageRatios.size) { index -> PdfPage(pdf, index) }
            }
    }
}

@Composable
private fun PdfPage(
    pdf: PdfDocument,
    index: Int,
) {
    val description = stringResource(R.string.mail_attachment_page, index + 1, pdf.pageRatios.size)
    // null while it renders; a page that cannot be rendered (no memory for it) shows a note, the others still show.
    val page by produceState<PdfPageImage?>(null, pdf, index) {
        var bitmap: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { bitmap = pdf.render(index) }
            value = bitmap?.let { PdfPageImage.Rendered(it) } ?: PdfPageImage.Failed
            awaitCancellation()
        } finally {
            bitmap?.recycle()
        }
    }
    // The page keeps its shape while it renders, so the list composes only the pages in view.
    Box(
        Modifier.fillMaxWidth().aspectRatio(pdf.pageRatios[index]),
        contentAlignment = Alignment.Center,
    ) {
        when (val shown = page) {
            null -> CircularProgressIndicator()
            PdfPageImage.Failed ->
                Text(stringResource(R.string.mail_attachment_cannot_show), modifier = Modifier.padding(16.dp))
            is PdfPageImage.Rendered ->
                Image(
                    shown.bitmap.asImageBitmap(),
                    contentDescription = description,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxSize(),
                )
        }
    }
}

private sealed interface PdfPageImage {
    class Rendered(
        val bitmap: Bitmap,
    ) : PdfPageImage

    data object Failed : PdfPageImage
}

@Composable
private fun PreviewPages(pages: Pages) {
    when {
        pages == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        pages.isEmpty() ->
            Text(
                stringResource(R.string.mail_attachment_cannot_show),
                modifier = Modifier.padding(16.dp).testTag(MailAttachmentTags.PREVIEW_ERROR),
            )
        else ->
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pages.size) { index ->
                    Image(
                        pages[index].asImageBitmap(),
                        contentDescription = stringResource(R.string.mail_attachment_page, index + 1, pages.size),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
    }
}

/** The image in [file], scaled down to at most [MAX_PREVIEW_PX] on its longer side; `null` when it cannot be decoded. */
private fun decodeImage(file: File): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PREVIEW_PX) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

/**
 * An open PDF whose first [MAX_PDF_PAGES] pages are rendered on demand, one at a time ([PdfRenderer] allows only one
 * open page). [pageRatios] is each page's width / height. [close] can come from the main thread while a page renders:
 * the document is then closed as soon as that page is done, without blocking the caller.
 */
private class PdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor?,
    private val renderer: PdfRenderer?,
    val pageRatios: List<Float>,
) {
    private val lock = ReentrantLock()

    @Volatile private var closing = false
    private var closed = false

    /** Page [index], [PDF_PAGE_PX] wide; `null` when it cannot be rendered (e.g. no memory for it) or the document closed. */
    fun render(index: Int): Bitmap? {
        val bitmap =
            lock.withLock {
                if (closing || renderer == null) return@withLock null
                var rendered: Bitmap? = null
                try {
                    renderer.openPage(index).use { page ->
                        val height = (PDF_PAGE_PX / pageRatios[index]).toInt().coerceIn(1, MAX_PDF_PAGE_HEIGHT_PX)
                        createBitmap(PDF_PAGE_PX, height).also {
                            rendered = it
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                } catch (_: OutOfMemoryError) {
                    rendered?.recycle()
                    null
                } catch (_: RuntimeException) {
                    rendered?.recycle()
                    null
                }
            }
        if (closing) closeIfIdle()
        return bitmap
    }

    fun close() {
        closing = true
        closeIfIdle()
    }

    private fun closeIfIdle() {
        if (!lock.tryLock()) return
        try {
            if (!closed) {
                closed = true
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
            }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** A file that is not a readable PDF. */
        val UNREADABLE = PdfDocument(null, null, emptyList())

        /** Opens the PDF in [file] and reads its page sizes; [UNREADABLE] when it cannot be opened or has no pages. */
        fun open(file: File): PdfDocument {
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            return try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor)
                val ratios =
                    (0 until minOf(renderer.pageCount, MAX_PDF_PAGES)).map { index ->
                        renderer.openPage(index).use { page ->
                            (page.width.toFloat() / page.height.coerceAtLeast(1)).coerceIn(MIN_PAGE_RATIO, MAX_PAGE_RATIO)
                        }
                    }
                if (ratios.isEmpty()) error("The PDF has no pages")
                PdfDocument(descriptor, renderer, ratios)
            } catch (_: Exception) {
                closeQuietly(renderer, descriptor)
                UNREADABLE
            } catch (_: LinkageError) {
                // A platform without a working PDF renderer: the user can still open the file in another app.
                closeQuietly(renderer, descriptor)
                UNREADABLE
            }
        }

        private fun closeQuietly(
            renderer: PdfRenderer?,
            descriptor: ParcelFileDescriptor?,
        ) {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }
}

private const val MAX_PREVIEW_PX = 2048
private const val PDF_PAGE_PX = 1080
private const val MAX_PDF_PAGES = 30

/** A page is at most twice the preview limit tall: about 17.7 MB at [PDF_PAGE_PX] wide, and only pages in view are kept. */
private const val MAX_PDF_PAGE_HEIGHT_PX = MAX_PREVIEW_PX * 2

/** The shapes a page is shown with: no taller than [MAX_PDF_PAGE_HEIGHT_PX] at full width, and no flatter than 4:1. */
private val MIN_PAGE_RATIO = PDF_PAGE_PX.toFloat() / MAX_PDF_PAGE_HEIGHT_PX
private const val MAX_PAGE_RATIO = 4f

/** "Save to Files": the drive's folders, from the root down; "Save here" stores the attachment in the folder shown. */
@Composable
private fun FolderPickerDialog(
    picker: AttachmentFolderPicker,
    enabled: Boolean,
    callbacks: MailAttachmentCallbacks,
) {
    AlertDialog(
        onDismissRequest = callbacks.closePicker,
        modifier = Modifier.testTag(MailAttachmentTags.PICKER),
        title = { Text(stringResource(R.string.mail_attachment_pick_title, picker.attachment.name)) },
        text = {
            Column {
                Crumbs(picker, callbacks.goToFolder)
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    when {
                        picker.loading -> CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                        picker.failed ->
                            Text(
                                stringResource(R.string.mail_attachment_folders_failed),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 8.dp).testTag(MailAttachmentTags.PICKER_FAILED),
                            )
                        picker.folders.isEmpty() ->
                            Text(
                                stringResource(R.string.mail_attachment_no_folders),
                                modifier = Modifier.padding(vertical = 8.dp).testTag(MailAttachmentTags.PICKER_EMPTY),
                            )
                        else ->
                            picker.folders.forEach { folder ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { callbacks.openFolder(folder) }
                                        .padding(vertical = 10.dp)
                                        .testTag(MailAttachmentTags.folder(folder.name)),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = callbacks.saveHere, enabled = enabled, modifier = Modifier.testTag(MailAttachmentTags.PICKER_SAVE)) {
                Text(
                    if (picker.path.isEmpty()) {
                        stringResource(R.string.mail_attachment_save_default, DEFAULT_ATTACHMENT_FOLDER)
                    } else {
                        stringResource(R.string.mail_attachment_save_here)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.closePicker, modifier = Modifier.testTag(MailAttachmentTags.PICKER_CANCEL)) {
                Text(stringResource(R.string.mail_attachment_cancel))
            }
        },
    )
}

/** "Files › Projects › 2026": each step goes back to that folder. */
@Composable
private fun Crumbs(
    picker: AttachmentFolderPicker,
    goTo: (Int) -> Unit,
) {
    val names = listOf(stringResource(R.string.mail_attachment_drive)) + picker.path.map { it.name }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { depth, name ->
            if (depth > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = { goTo(depth) },
                enabled = depth < names.lastIndex,
                modifier = Modifier.testTag(MailAttachmentTags.crumb(depth)),
            ) { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
