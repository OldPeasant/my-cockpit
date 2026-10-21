package ch.sonensei.cockpit.capture.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** What another app shared with us, turned into a capture draft: a title line, optional notes and the files. */
data class SharedContent(
    val title: String,
    val notes: String?,
    val uris: List<Uri>,
)

/** Reads `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intents (SPEC.md §4.5). */
object ShareIntentParser {
    /**
     * The draft for [intent], or null if it is no share or carries nothing to capture.
     *
     * Text becomes the title (first line) and the notes (the rest). If the sender also gives a subject (a browser
     * sharing a page: subject = page title, text = URL), the subject is the title and the text goes to the notes.
     * Only `content:` URIs are accepted: a `file:` URI would let any app make us read our own private files.
     */
    fun parse(intent: Intent): SharedContent? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
        val text = sharedText(intent)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }
        val uris = sharedUris(intent)
        if (text == null && subject == null && uris.isEmpty()) return null
        val (title, notes) =
            when {
                subject != null && text != null && text != subject -> subject.singleLine() to text
                text != null -> text.substringBefore('\n').trim() to text.substringAfter('\n', "").trim().takeIf { it.isNotEmpty() }
                else -> subject.orEmpty().singleLine() to null
            }
        return SharedContent(title, notes, uris)
    }

    private fun sharedText(intent: Intent): String? {
        val texts =
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
                ?: listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        return texts
            .map { it.toString().trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .takeIf { it.isNotEmpty() }
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val extras =
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
        val clip = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        return (extras + clip).filter { it.scheme == ContentResolver.SCHEME_CONTENT }.distinct()
    }

    private fun String.singleLine(): String = replace(Regex("\\s*\\n\\s*"), " ").trim()
}
