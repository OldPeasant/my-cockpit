package ch.sonensei.cockpit.bookmarks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R

internal object ReaderTags {
    const val READER = "bookmarks-reader"
    const val BACK = "bookmarks-reader-back"
    const val TITLE = "bookmarks-reader-title"
    const val ORIGINAL = "bookmarks-reader-original"
    const val TEXT = "bookmarks-reader-text"
    const val EMPTY = "bookmarks-reader-empty"
    const val ERROR = "bookmarks-reader-error"
    const val RETRY = "bookmarks-reader-retry"
    const val SAVE_COPY = "bookmarks-reader-save-copy"
    const val TRUNCATED = "bookmarks-reader-truncated"
    const val LOADING = "bookmarks-reader-loading"
    const val READ = "bookmarks-reader-read"
    const val READ_LATER = "bookmarks-reader-read-later"
    const val INBOX = "bookmarks-reader-inbox"
}

/**
 * The readable copy the server saved of a bookmarked page (SPEC.md §8.4), shown as text: the server stores it as
 * sanitized HTML without scripts, styles or images, and it is never loaded into a web view. Links in it open in the
 * browser; back closes the reader.
 */
@Composable
internal fun BookmarkReader(
    reading: ReaderState,
    busy: Boolean,
    callbacks: BookmarksCallbacks,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = callbacks.closeReader)
    val bookmark = reading.bookmark
    Column(modifier.fillMaxSize().testTag(ReaderTags.READER)) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.closeReader, modifier = Modifier.testTag(ReaderTags.BACK)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bookmarks_reader_back))
            }
            Text(
                bookmark.displayTitle,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(ReaderTags.TITLE),
            )
        }
        if (reading.loading || reading.saving) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag(ReaderTags.LOADING))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = { callbacks.open(bookmark) }, modifier = Modifier.testTag(ReaderTags.ORIGINAL)) {
                Text(stringResource(R.string.bookmarks_reader_original, bookmark.site), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ReaderActions(bookmark, busy, callbacks)
            ReaderBody(reading, callbacks)
        }
    }
}

@Composable
private fun ReaderActions(
    bookmark: Bookmark,
    busy: Boolean,
    callbacks: BookmarksCallbacks,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { callbacks.toggleRead(bookmark) }, enabled = !busy, modifier = Modifier.testTag(ReaderTags.READ)) {
            Text(stringResource(if (bookmark.read) R.string.bookmarks_mark_unread else R.string.bookmarks_mark_read), maxLines = 1)
        }
        OutlinedButton(
            onClick = { callbacks.toggleReadLater(bookmark) },
            enabled = !busy,
            modifier = Modifier.testTag(ReaderTags.READ_LATER),
        ) {
            Text(
                stringResource(if (bookmark.readLater) R.string.bookmarks_read_later_remove else R.string.bookmarks_read_later_add),
                maxLines = 1,
            )
        }
    }
    OutlinedButton(onClick = { callbacks.toInbox(bookmark) }, enabled = !busy, modifier = Modifier.testTag(ReaderTags.INBOX)) {
        Text(stringResource(R.string.bookmarks_to_inbox), maxLines = 1)
    }
}

@Composable
private fun ReaderBody(
    reading: ReaderState,
    callbacks: BookmarksCallbacks,
) {
    val failure = reading.failure
    val snapshot = reading.snapshot
    when {
        failure != null -> {
            Text(
                stringResource(
                    when (failure) {
                        ReaderFailure.OFFLINE -> R.string.bookmarks_reader_offline
                        ReaderFailure.FAILED -> R.string.bookmarks_reader_failed
                        ReaderFailure.UNREADABLE -> R.string.bookmarks_reader_unreadable
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(ReaderTags.ERROR),
            )
            if (failure == ReaderFailure.UNREADABLE) {
                if (snapshot != null) SnapshotText(snapshot, callbacks)
            } else {
                TextButton(onClick = callbacks.retryReader, enabled = !reading.loading, modifier = Modifier.testTag(ReaderTags.RETRY)) {
                    Text(stringResource(R.string.bookmarks_retry))
                }
            }
        }
        reading.loading -> Unit
        snapshot != null -> {
            SnapshotText(snapshot, callbacks)
            SaveCopyButton(reading, R.string.bookmarks_reader_save_again, callbacks)
        }
        else -> {
            Text(
                stringResource(R.string.bookmarks_reader_no_copy),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ReaderTags.EMPTY),
            )
            SaveCopyButton(reading, R.string.bookmarks_reader_save, callbacks)
        }
    }
}

@Composable
private fun SaveCopyButton(
    reading: ReaderState,
    label: Int,
    callbacks: BookmarksCallbacks,
) {
    Button(onClick = callbacks.saveSnapshot, enabled = !reading.saving, modifier = Modifier.testTag(ReaderTags.SAVE_COPY)) {
        Text(stringResource(label))
    }
}

@Composable
private fun SnapshotText(
    snapshot: BookmarkSnapshot,
    callbacks: BookmarksCallbacks,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text =
        remember(snapshot.html, linkColor) {
            AnnotatedString.fromHtml(
                snapshot.html,
                linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                linkInteractionListener = { link -> (link as? LinkAnnotation.Url)?.url?.let(callbacks.openLink) },
            )
        }
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(ReaderTags.TEXT))
    if (snapshot.truncated) {
        Text(
            stringResource(R.string.bookmarks_reader_truncated),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ReaderTags.TRUNCATED),
        )
    }
}
