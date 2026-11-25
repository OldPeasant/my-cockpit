package ch.sonensei.cockpit.mail.read

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** A drag over the mail body pans it sideways and scrolls the page in the same gesture (no axis lock). */
@RunWith(AndroidJUnit4::class)
class MailBodyPanTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Target(
        override var panX: Int = 0,
        override val maxPanX: Int = 500,
    ) : MailPanTarget {
        override fun panBy(dx: Int) {
            panX += dx
        }
    }

    @Test
    fun `a diagonal delta pans sideways against the finger and leaves the vertical part to the page`() {
        val split = HorizontalPan().split(Offset(-30f, -40f), Target(panX = 100))

        assertEquals(PanSplit(panX = 30, pageY = -40f), split)
    }

    @Test
    fun `a mostly vertical drag keeps its small horizontal part, also below a pixel per step`() {
        val pan = HorizontalPan()
        val target = Target(panX = 100)
        repeat(4) { target.panBy(pan.split(Offset(-0.5f, -20f), target).panX) }

        assertEquals(102, target.panX)
    }

    @Test
    fun `the pan is clamped to the content range`() {
        val pan = HorizontalPan()

        assertEquals(-10, pan.step(dx = 50f, x = 10, maxX = 500))
        assertEquals(20, pan.step(dx = -50f, x = 480, maxX = 500))
        assertEquals(0, pan.step(dx = -50f, x = 500, maxX = 500))
    }

    @Test
    fun `a mail no wider than the screen does not pan sideways`() {
        val split = HorizontalPan().split(Offset(-80f, -10f), Target(maxPanX = 0))

        assertEquals(PanSplit(panX = 0, pageY = -10f), split)
        assertEquals(0, HorizontalPan().step(dx = 80f, x = 0, maxX = -5))
    }

    @Test
    fun `without a WebView yet nothing pans`() {
        assertEquals(PanSplit(0, 5f), HorizontalPan().split(Offset(-9f, 5f), null))
    }

    @Test
    fun `the edges stop a fling`() {
        assertTrue(atEdge(5f, Target(panX = 0)))
        assertTrue(atEdge(-5f, Target(panX = 500)))
        assertFalse(atEdge(-5f, Target(panX = 200)))
        assertFalse(atEdge(5f, Target(panX = 200)))
    }

    /** A WebView whose content is [WIDE] pixels wider than the view and [TALL] pixels high, as a laid-out newsletter. */
    private class WideWebView(
        context: Context,
    ) : MailWebView(context) {
        override val maxPanX: Int get() = WIDE

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), TALL)
        }
    }

    private val message =
        MailMessage(
            id = UUID.fromString("00000000-0000-7000-8000-000000000001"),
            subject = "Newsletter",
            from = "Anna Example <anna@example.org>",
            to = listOf("me@example.org"),
            cc = emptyList(),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "<table width=\"800\"><tr><td>Wide</td></tr></table>",
            text = "Wide",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private var webView: WideWebView? = null

    private fun show() =
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = message),
                    MailReadCallbacks(),
                    zone = ZoneId.of("Europe/Zurich"),
                    body = { html, modifier ->
                        MailBodyView(html, modifier, message.id) { WideWebView(it).also { v -> webView = v } }
                    },
                )
            }
        }

    private fun pageScroll(): Float =
        composeRule
            .onNodeWithTag(MailReadTags.PAGE)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    @Test
    fun `a diagonal swipe on a wide body changes both the page scroll and the WebView's scrollX`() {
        show()
        composeRule.mainClock.autoAdvance = true

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.9f, 220f), Offset(width * 0.1f, 20f), durationMillis = 400)
        }
        composeRule.waitForIdle()

        val x = webView!!.scrollX
        assertTrue("scrollX $x", x >= 150)
        assertTrue("page ${pageScroll()}", pageScroll() >= 150f)
    }

    @Test
    fun `a fast diagonal fling keeps going in both axes and stops at the horizontal edge`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.9f, 220f), Offset(width * 0.1f, 20f), durationMillis = 50)
        }
        composeRule.waitForIdle()

        assertTrue("scrollX ${webView!!.scrollX}", webView!!.scrollX > 260)
        assertTrue("scrollX ${webView!!.scrollX}", webView!!.scrollX <= WIDE)
        assertTrue("page ${pageScroll()}", pageScroll() > 200f)
    }

    @Test
    fun `a plain vertical swipe on the body scrolls the page only`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(centerX, 220f), Offset(centerX, 20f), durationMillis = 400)
        }
        composeRule.waitForIdle()

        assertEquals(0, webView!!.scrollX)
        assertTrue("page ${pageScroll()}", pageScroll() >= 150f)
    }

    private companion object {
        const val WIDE = 1200
        const val TALL = 6000
    }
}
