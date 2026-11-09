package ch.sonensei.cockpit.files

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.File

internal object PreviewTags {
    const val PREVIEW = "files-preview"
    const val CLOSE = "files-preview-close"
    const val NAME = "files-preview-name"
    const val POSITION = "files-preview-position"
    const val OPEN_WITH = "files-preview-open-with"
    const val SAVE = "files-preview-save"
    const val PREVIOUS = "files-preview-previous"
    const val NEXT = "files-preview-next"
    const val LOADING = "files-preview-loading"
    const val IMAGE = "files-preview-image"
    const val TEXT = "files-preview-text"
    const val TRUNCATED = "files-preview-truncated"
    const val UNSUPPORTED = "files-preview-unsupported"
    const val PROBLEM = "files-preview-problem"
    const val RETRY = "files-preview-retry"
    const val PDF_PAGE = "files-preview-pdf-page"
    const val PDF_PAGE_NUMBER = "files-preview-pdf-page-number"
    const val PDF_PREVIOUS_PAGE = "files-preview-pdf-previous"
    const val PDF_NEXT_PAGE = "files-preview-pdf-next"
    const val PDF_FAILED = "files-preview-pdf-failed"
    const val DOWNLOADING = "files-preview-downloading"
    const val LINKS_TOGGLE = "files-preview-links-toggle"
    const val LINKS = "files-preview-links"
}

/**
 * A file shown in the app, over the list (SPEC.md §8.2): images with pinch and double-tap zoom, PDFs page by page,
 * the start of text files; other types offer to open them in another app. The bar at the bottom names the files before
 * and after it in the folder. [downloading]: this file is being downloaded (a spinner replaces Save and "Open with");
 * [busy]: any file is, so Save and "Open with" are disabled, as in the list (one download at a time).
 * [linksPanel]: the file's linked items (SPEC.md §4.3), shown instead of the content while toggled on; `null` hides
 * the toggle.
 */
@Composable
internal fun FilePreviewView(
    preview: FilePreview,
    downloading: Boolean,
    busy: Boolean,
    downloadMessage: FilesMessage?,
    callbacks: FilesCallbacks,
    imageLoader: ImageLoader?,
    pdfOpener: (File) -> PdfPages?,
    linksPanel: (@Composable (DriveEntry.File) -> Unit)? = null,
) {
    var showLinks by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().testTag(PreviewTags.PREVIEW)) {
        Column(Modifier.fillMaxSize()) {
            PreviewTopBar(preview, downloading, busy, callbacks, linksShown = showLinks.takeIf { linksPanel != null }) {
                showLinks = !showLinks
            }
            downloadMessage?.let {
                Text(
                    stringResource(if (it == FilesMessage.OFFLINE) R.string.files_download_offline else R.string.files_download_failed),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            if (showLinks && linksPanel != null) {
                // Keyed by file, so stepping to the next file shows its links.
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .testTag(PreviewTags.LINKS),
                ) {
                    key(preview.file.id) { linksPanel(preview.file) }
                }
            } else {
                PreviewContentBox(preview, busy, callbacks, imageLoader, pdfOpener)
            }
            HorizontalDivider()
            NeighbourBar(preview, callbacks.previewStep)
        }
    }
}

@Composable
private fun ColumnScope.PreviewContentBox(
    preview: FilePreview,
    busy: Boolean,
    callbacks: FilesCallbacks,
    imageLoader: ImageLoader?,
    pdfOpener: (File) -> PdfPages?,
) {
    val file = preview.file
    Box(
        Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        // A new file starts unzoomed, on its first page.
        key(file.id) {
            when (val content = preview.content) {
                PreviewContent.Loading ->
                    CircularProgressIndicator(Modifier.testTag(PreviewTags.LOADING))
                PreviewContent.Image -> ImagePreview(file, imageLoader, callbacks)
                is PreviewContent.Pdf -> PdfPreview(content.file, pdfOpener, busy, callbacks, file)
                is PreviewContent.Text -> TextPreview(content)
                PreviewContent.Unsupported ->
                    Notice(file, R.string.files_preview_unsupported, PreviewTags.UNSUPPORTED, error = false) {
                        OpenWithButton(file, busy, callbacks)
                    }
                is PreviewContent.Problem -> {
                    val offline = content.message == FilesMessage.OFFLINE
                    val text = if (offline) R.string.files_preview_offline else R.string.files_preview_failed
                    Notice(file, text, PreviewTags.PROBLEM, error = true) {
                        FilledTonalButton(onClick = callbacks.retryPreview, modifier = Modifier.testTag(PreviewTags.RETRY)) {
                            Text(stringResource(R.string.files_retry))
                        }
                    }
                }
            }
        }
    }
}

/** [linksShown]: whether the linked items are shown, `null` without a links panel (no toggle). */
@Composable
private fun PreviewTopBar(
    preview: FilePreview,
    downloading: Boolean,
    busy: Boolean,
    callbacks: FilesCallbacks,
    linksShown: Boolean?,
    toggleLinks: () -> Unit,
) {
    val file = preview.file
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = callbacks.closePreview, modifier = Modifier.testTag(PreviewTags.CLOSE)) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.files_preview_close))
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.testTag(PreviewTags.NAME),
            )
            Text(
                stringResource(R.string.files_preview_position, preview.index + 1, preview.files.size) + ", " + formatSize(file.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(PreviewTags.POSITION),
            )
        }
        if (linksShown != null) {
            IconButton(onClick = toggleLinks, modifier = Modifier.testTag(PreviewTags.LINKS_TOGGLE)) {
                Icon(
                    Icons.Outlined.Link,
                    contentDescription = stringResource(if (linksShown) R.string.files_links_hide else R.string.files_links_show),
                    tint = if (linksShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (downloading) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp).testTag(PreviewTags.DOWNLOADING), strokeWidth = 2.dp)
        } else {
            // Another file is downloading: a save would be dropped after the "save as" picker created its document.
            IconButton(onClick = { callbacks.saveFile(file) }, enabled = !busy, modifier = Modifier.testTag(PreviewTags.SAVE)) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.files_save))
            }
            IconButton(onClick = { callbacks.openFile(file) }, enabled = !busy, modifier = Modifier.testTag(PreviewTags.OPEN_WITH)) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.files_open))
            }
        }
    }
}

/** Previous and next file of the folder, by name, so it is clear where each goes. */
@Composable
private fun NeighbourBar(
    preview: FilePreview,
    step: (Int) -> Unit,
) {
    val previous = preview.files.getOrNull(preview.index - 1)
    val next = preview.files.getOrNull(preview.index + 1)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { step(-1) },
            enabled = previous != null,
            modifier = Modifier.weight(1f).testTag(PreviewTags.PREVIOUS),
        ) {
            Icon(Icons.AutoMirrored.Outlined.NavigateBefore, contentDescription = stringResource(R.string.files_preview_previous))
            Text(
                previous?.name.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        TextButton(
            onClick = { step(1) },
            enabled = next != null,
            modifier = Modifier.weight(1f).testTag(PreviewTags.NEXT),
        ) {
            Text(
                next?.name.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            )
            Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = stringResource(R.string.files_preview_next))
        }
    }
}

@Composable
private fun OpenWithButton(
    file: DriveEntry.File,
    busy: Boolean,
    callbacks: FilesCallbacks,
) = FilledTonalButton(onClick = { callbacks.openFile(file) }, enabled = !busy) {
    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
    Text(stringResource(R.string.files_open), modifier = Modifier.padding(start = 8.dp))
}

/** The file's type tile, a sentence, and what can be done about it. */
@Composable
private fun Notice(
    file: DriveEntry.File,
    text: Int,
    tag: String,
    error: Boolean,
    action: @Composable () -> Unit,
) {
    Column(
        Modifier.widthIn(max = 360.dp).padding(24.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (error) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp),
            )
        } else {
            KindTile(fileKind(file.mime), size = 72.dp)
        }
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        action()
    }
}

/** The image from the server, fitted to the screen and zoomable; without a loader (tests) the type tile instead. */
@Composable
private fun ImagePreview(
    file: DriveEntry.File,
    imageLoader: ImageLoader?,
    callbacks: FilesCallbacks,
) {
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        Notice(file, R.string.files_preview_image_failed, PreviewTags.PROBLEM, error = true) {
            // Retry keeps the preview's state (an image), so the image itself has to be loaded again here.
            val retry = {
                failed = false
                callbacks.retryPreview()
            }
            FilledTonalButton(onClick = retry, modifier = Modifier.testTag(PreviewTags.RETRY)) {
                Text(stringResource(R.string.files_retry))
            }
        }
        return
    }
    Zoomable(Modifier.fillMaxSize().testTag(PreviewTags.IMAGE)) {
        if (imageLoader == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { KindTile(FileKind.IMAGE, size = 72.dp) }
        } else {
            AsyncImage(
                model = previewUrl(file.id),
                contentDescription = file.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The start of a text file, selectable, in a monospaced face so code and tables keep their columns. */
@Composable
private fun TextPreview(content: PreviewContent.Text) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (content.truncated) {
            Text(
                stringResource(R.string.files_preview_truncated),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag(PreviewTags.TRUNCATED),
            )
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                content.text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(16.dp).testTag(PreviewTags.TEXT),
            )
        }
    }
}

/** Opening the PDF: not yet, open, or it cannot be shown. */
private sealed interface PdfState {
    data object Opening : PdfState

    data class Open(
        val pages: PdfPages,
    ) : PdfState

    data object Failed : PdfState
}

/** A PDF page by page, as white paper on the stage, zoomable; the bar below turns the pages. */
@Composable
private fun PdfPreview(
    pdf: File,
    opener: (File) -> PdfPages?,
    busy: Boolean,
    callbacks: FilesCallbacks,
    file: DriveEntry.File,
) {
    val state by produceState<PdfState>(PdfState.Opening, pdf) {
        // Kept inside the non-cancellable block: a PDF opened while the preview moves on is still closed again.
        var pages: PdfPages? = null
        try {
            withContext(NonCancellable + Dispatchers.IO) { pages = opener(pdf) }
            val opened = pages
            value = if (opened == null || opened.pageCount <= 0) PdfState.Failed else PdfState.Open(opened)
            awaitCancellation()
        } finally {
            // Off the main thread: closing waits for a page that is still rendering.
            withContext(NonCancellable + Dispatchers.IO) { pages?.close() }
        }
    }
    when (val s = state) {
        PdfState.Opening -> CircularProgressIndicator(Modifier.testTag(PreviewTags.LOADING))
        PdfState.Failed ->
            Notice(file, R.string.files_preview_pdf_failed, PreviewTags.PDF_FAILED, error = true) {
                OpenWithButton(file, busy, callbacks)
            }
        is PdfState.Open -> PdfPagesView(s.pages)
    }
}

@Composable
private fun PdfPagesView(pages: PdfPages) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val count = pages.pageCount
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            // Rendered sharper than the screen, so zooming in stays readable; capped to keep the bitmap small.
            val width = with(density) { (maxWidth - 32.dp).roundToPx() }.times(2).coerceIn(1, MAX_RENDER_WIDTH)
            val bitmap by produceState<Bitmap?>(null, pages, page, width) {
                value = withContext(Dispatchers.IO) { pages.render(page, width) }
            }
            Zoomable(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    bitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            contentDescription = stringResource(R.string.files_preview_page, page + 1, count),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.shadow(4.dp, RoundedCornerShape(2.dp)).testTag(PreviewTags.PDF_PAGE),
                        )
                    } ?: CircularProgressIndicator(Modifier.testTag(PreviewTags.LOADING))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.testTag(PreviewTags.PDF_PREVIOUS_PAGE)) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = stringResource(R.string.files_preview_previous_page))
            }
            Text(
                stringResource(R.string.files_preview_page, page + 1, count),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp).testTag(PreviewTags.PDF_PAGE_NUMBER),
            )
            IconButton(onClick = { page++ }, enabled = page < count - 1, modifier = Modifier.testTag(PreviewTags.PDF_NEXT_PAGE)) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(R.string.files_preview_next_page))
            }
        }
    }
}

private const val MAX_RENDER_WIDTH = 2048

/** Pinch to zoom (up to [MAX_ZOOM]) and pan while zoomed; a double tap zooms in or back out. */
@Composable
private fun Zoomable(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                        offset = Offset.Zero
                    },
                )
            }.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = zoomed(scale, zoom)
                    offset = panned(offset, pan, scale, Size(size.width.toFloat(), size.height.toFloat()))
                }
            }.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) { content() }
}

/** The scale after a pinch by [zoom]: never smaller than the fitted view, at most [MAX_ZOOM]. */
internal fun zoomed(
    scale: Float,
    zoom: Float,
): Float = (scale * zoom).coerceIn(1f, MAX_ZOOM)

/** The offset after panning by [pan] at [scale]: the zoomed content always covers the view of [size]. */
internal fun panned(
    offset: Offset,
    pan: Offset,
    scale: Float,
    size: Size,
): Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    val moved = offset + pan
    return Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
}

internal const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
