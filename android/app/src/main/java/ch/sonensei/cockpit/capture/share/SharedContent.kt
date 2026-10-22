package ch.sonensei.cockpit.capture.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import ch.sonensei.cockpit.capture.queue.CaptureQueue

/**
 * What another app shared with us, turned into a capture draft: a title line, optional notes and the files. [url] is
 * the shared web page, if any: such a share can also go to the read-later list (SPEC.md §8.4).
 */
data class SharedContent(
    val title: String,
    val notes: String?,
    val uris: List<Uri>,
    val url: String? = null,
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
        // Files cannot become a bookmark, so a share with files only goes to the Inbox.
        val url = if (uris.isEmpty()) firstUrl(text) ?: firstUrl(subject) else null
        return SharedContent(title, notes, uris, url)
    }

    /**
     * The first http(s) URL in [text], without the punctuation a sentence puts after it. A closing `)` stays when the
     * URL opened it (`https://en.wikipedia.org/wiki/Bern_(city)`).
     */
    private fun firstUrl(text: String?): String? {
        var url = text?.let { URL_CANDIDATE.find(it)?.value } ?: return null
        while (url.isNotEmpty()) {
            val last = url.last()
            val unbalanced = last == ')' && url.count { it == ')' } > url.count { it == '(' }
            if (last in TRAILING_PUNCTUATION || unbalanced) url = url.dropLast(1) else break
        }
        return url.takeIf(CaptureQueue::isBookmarkUrl)
    }

    private const val TRAILING_PUNCTUATION = ".,;:!?]}>\"'"

    private val URL_CANDIDATE = Regex("""(?i)\bhttps?://\S+""")

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
