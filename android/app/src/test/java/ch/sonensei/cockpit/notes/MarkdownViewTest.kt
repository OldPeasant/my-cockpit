package ch.sonensei.cockpit.notes

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The rendered Markdown of a note: every block kind shows its text, images show as images. */
@RunWith(AndroidJUnit4::class)
class MarkdownViewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fileId = UUID.fromString("00000000-0000-7000-8000-000000000042")

    @Test
    fun `every block kind is rendered without its Markdown markers`() {
        val md =
            """
            ## Second level
            ### Third level
            #### Fourth level
            - [ ] passport
            - [x] **tickets**
            3. third step
            * loose item
            > quoted ~~line~~
            ---
            ```
            val code = 1
            ```
            """.trimIndent()
        composeRule.setContent { CockpitTheme { MarkdownView(md, imageLoader = null) } }

        listOf(
            "Second level",
            "Third level",
            "Fourth level",
            "passport",
            "tickets",
            "third step",
            "3.",
            "loose item",
            "•",
            "quoted line",
            "val code = 1",
        ).forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.onNodeWithText("##", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("[x]", substring = true).assertDoesNotExist()
    }

    @Test
    fun `images load through the notes image loader, described by their alt text`() {
        val loader = NoteImages(ApplicationProvider.getApplicationContext<Context>(), OkHttpClient()).loader
        composeRule.setContent {
            CockpitTheme {
                MarkdownView("![Receipt](cockpit-file:$fileId)\n\n![](https://example.org/a.png)", imageLoader = loader)
            }
        }

        assertEquals(2, composeRule.onAllNodesWithTag(MarkdownTags.IMAGE).fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Receipt").assertExists()
        // An image without alt text still has a description.
        composeRule.onNodeWithContentDescription("Image").assertExists()
    }

    @Test
    fun `a web image keeps its address, a drive image goes through the authenticated endpoint`() {
        assertEquals("https://example.org/a.png", ImageSource.Web("https://example.org/a.png").url())
        assertEquals("https://server.invalid/api/v1/files/$fileId/content", ImageSource.CockpitFile(fileId).url())
    }
}
