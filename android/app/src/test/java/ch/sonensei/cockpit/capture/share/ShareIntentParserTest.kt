package ch.sonensei.cockpit.capture.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareIntentParserTest {
    private fun send(
        type: String,
        block: Intent.() -> Unit = {},
    ) = Intent(Intent.ACTION_SEND).setType(type).apply(block)

    private fun image(n: Int): Uri = Uri.parse("content://com.example.gallery/images/$n")

    @Test
    fun `plain text becomes the title, further lines the notes`() {
        val content =
            ShareIntentParser.parse(
                send("text/plain") { putExtra(Intent.EXTRA_TEXT, "  Call plumber\nkitchen sink\nbefore Friday ") },
            )

        assertEquals(SharedContent("Call plumber", "kitchen sink\nbefore Friday", emptyList()), content)
    }

    @Test
    fun `single-line text has no notes`() {
        assertEquals(
            SharedContent("Buy milk", null, emptyList()),
            ShareIntentParser.parse(send("text/plain") { putExtra(Intent.EXTRA_TEXT, "Buy milk") }),
        )
    }

    @Test
    fun `a shared URL with a page title uses the title and keeps the URL in the notes`() {
        val content =
            ShareIntentParser.parse(
                send("text/plain") {
                    putExtra(Intent.EXTRA_SUBJECT, "Holiday ideas\nfor 2027")
                    putExtra(Intent.EXTRA_TEXT, "https://example.org/holiday")
                },
            )

        assertEquals(SharedContent("Holiday ideas for 2027", "https://example.org/holiday", emptyList()), content)
    }

    @Test
    fun `with a subject, multi-line text goes to the notes in full`() {
        assertEquals(
            SharedContent("Recipe", "Pancakes\nhttps://example.org/pancakes", emptyList()),
            ShareIntentParser.parse(
                send("text/plain") {
                    putExtra(Intent.EXTRA_SUBJECT, "Recipe")
                    putExtra(Intent.EXTRA_TEXT, "Pancakes\nhttps://example.org/pancakes")
                },
            ),
        )
    }

    @Test
    fun `multiple images given only as clip data are attached`() {
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").apply {
                clipData = ClipData.newRawUri("", image(1)).apply { addItem(ClipData.Item(image(2))) }
            }

        assertEquals(SharedContent("", null, listOf(image(1), image(2))), ShareIntentParser.parse(intent))
    }

    @Test
    fun `a bare URL becomes the title`() {
        assertEquals(
            SharedContent("https://example.org/a?b=c", null, emptyList()),
            ShareIntentParser.parse(send("text/plain") { putExtra(Intent.EXTRA_TEXT, "https://example.org/a?b=c") }),
        )
    }

    @Test
    fun `subject equal to the text is not repeated in the notes`() {
        assertEquals(
            SharedContent("Same", null, emptyList()),
            ShareIntentParser.parse(
                send("text/plain") {
                    putExtra(Intent.EXTRA_SUBJECT, "Same")
                    putExtra(Intent.EXTRA_TEXT, "Same")
                },
            ),
        )
    }

    @Test
    fun `a subject alone becomes a single-line title`() {
        assertEquals(
            SharedContent("Meeting notes Q4", null, emptyList()),
            ShareIntentParser.parse(send("text/plain") { putExtra(Intent.EXTRA_SUBJECT, " Meeting notes\n Q4 ") }),
        )
    }

    @Test
    fun `a blank subject is ignored`() {
        assertEquals(
            SharedContent("Buy milk", "2 liters", emptyList()),
            ShareIntentParser.parse(
                send("text/plain") {
                    putExtra(Intent.EXTRA_SUBJECT, "   ")
                    putExtra(Intent.EXTRA_TEXT, "Buy milk\n2 liters")
                },
            ),
        )
        assertNull(ShareIntentParser.parse(send("text/plain") { putExtra(Intent.EXTRA_SUBJECT, " ") }))
    }

    @Test
    fun `an image with a subject is titled with the subject`() {
        val content =
            ShareIntentParser.parse(
                send("image/png") {
                    putExtra(Intent.EXTRA_SUBJECT, "Whiteboard")
                    putExtra(Intent.EXTRA_STREAM, image(4))
                },
            )

        assertEquals(SharedContent("Whiteboard", null, listOf(image(4))), content)
    }

    @Test
    fun `a single image is attached`() {
        val content = ShareIntentParser.parse(send("image/jpeg") { putExtra(Intent.EXTRA_STREAM, image(1)) })

        assertEquals(SharedContent("", null, listOf(image(1))), content)
    }

    @Test
    fun `multiple images are all attached in order, with their caption`() {
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(image(1), image(2), image(3)))
                .putExtra(Intent.EXTRA_TEXT, "Receipts")

        assertEquals(SharedContent("Receipts", null, listOf(image(1), image(2), image(3))), ShareIntentParser.parse(intent))
    }

    @Test
    fun `multiple texts are joined`() {
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("text/plain")
                .putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf<CharSequence>("First", "", "Second"))

        assertEquals(SharedContent("First", "Second", emptyList()), ShareIntentParser.parse(intent))
    }

    @Test
    fun `uris only in the clip data are attached, without duplicates`() {
        val intent =
            send("*/*") {
                putExtra(Intent.EXTRA_STREAM, image(1))
                clipData = ClipData.newRawUri("", image(1)).apply { addItem(ClipData.Item(image(2))) }
            }

        assertEquals(listOf(image(1), image(2)), ShareIntentParser.parse(intent)?.uris)
    }

    @Test
    fun `file uris are ignored, so no app can make us read our own files`() {
        val own = Uri.fromFile(File("/data/data/ch.sonensei.cockpit/files/secret"))
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(own, image(2)))

        assertEquals(listOf(image(2)), ShareIntentParser.parse(intent)?.uris)
        assertNull(ShareIntentParser.parse(send("*/*") { putExtra(Intent.EXTRA_STREAM, own) }))
    }

    @Test
    fun `other actions and empty shares are no share`() {
        assertNull(ShareIntentParser.parse(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "x")))
        assertNull(ShareIntentParser.parse(send("text/plain") { putExtra(Intent.EXTRA_TEXT, "   ") }))
        assertNull(ShareIntentParser.parse(send("text/plain")))
    }
}
