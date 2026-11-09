package ch.sonensei.cockpit.files

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.links.ItemRef
import ch.sonensei.cockpit.core.links.LinkedItem
import ch.sonensei.cockpit.core.links.LinkedItemsTags
import ch.sonensei.cockpit.core.links.LinksRepository
import ch.sonensei.cockpit.core.links.LinksResult
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.notes.Note
import ch.sonensei.cockpit.notes.NotesRepository
import ch.sonensei.cockpit.notes.NotesResult
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * The file preview's linked-items panel (SPEC.md §4.3) in the Hilt activity, with fake files, links and notes
 * repositories: the toggle shows the previewed file's real links, a linked note opens in place of the files, and Back
 * returns to the preview with its panel still open.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class FilePreviewLinksRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: FilesRepository = mockk()

    @BindValue
    @JvmField
    val links: LinksRepository = mockk()

    @BindValue
    @JvmField
    val notes: NotesRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val shopping = DriveEntry.File(id(1), "Einkauf.txt", "text/plain", 20, Instant.parse("2026-09-01T09:00:00Z"))
    private val recipe =
        Note(id(2), null, "Recipe ideas", "Recipe ideas\n\nA fictitious soup", false, emptyList(), Instant.EPOCH, 1)
    private val toRecipe = LinkedItem(id(10), "NOTE", recipe.id, "Recipe ideas", null, "/notes/${id(2)}", true, manual = true)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { repo.list(null) } returns FilesResult.Ok(listOf(shopping))
        coEvery { repo.previewText(shopping.id) } returns FilesResult.Ok(TextPreview("Milk, bread", truncated = false))
        coEvery { links.list(any()) } returns LinksResult.Ok(emptyList())
        coEvery { links.list(ItemRef("FILE", shopping.id)) } returns LinksResult.Ok(listOf(toRecipe))
        coEvery { notes.tags() } returns NotesResult.Ok(emptyList())
        coEvery { notes.list(any(), any()) } returns NotesResult.Ok(listOf(recipe))
        coEvery { notes.get(recipe.id) } returns NotesResult.Ok(recipe)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun back() = scenario!!.onActivity { (it as ComponentActivity).onBackPressedDispatcher.onBackPressed() }

    /** Opens Files, previews the shopping list and shows its linked items. */
    private fun previewWithLinks() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Files"))
        composeRule.onNodeWithText("Files").performClick()
        waitForTag(FilesTags.entry(shopping.id))
        composeRule.onNodeWithTag(FilesTags.entry(shopping.id)).performClick()
        waitForTag(PreviewTags.TEXT)
        composeRule.onNodeWithTag(PreviewTags.LINKS_TOGGLE).performClick()
        waitForTag(LinkedItemsTags.link(toRecipe))
    }

    @Test
    fun `the preview's links toggle shows the real panel with the previewed file's linked items`() {
        previewWithLinks()

        composeRule.onNodeWithTag(PreviewTags.LINKS).assertExists()
        composeRule.onNodeWithTag(LinkedItemsTags.PANEL).assertExists()
        composeRule.onNodeWithTag(PreviewTags.NAME).assertTextEquals("Einkauf.txt")
        coVerify(exactly = 1) { links.list(ItemRef("FILE", shopping.id)) }
    }

    @Test
    fun `a linked note opens in place of the files, and Back returns to the preview with its links still shown`() {
        previewWithLinks()

        composeRule.onNodeWithTag(LinkedItemsTags.link(toRecipe)).performScrollTo().performClick()
        waitForText("fictitious soup")
        composeRule.onNodeWithTag(PreviewTags.PREVIEW).assertDoesNotExist()
        coVerify(exactly = 1) { notes.get(recipe.id) }

        back()
        waitForTag(PreviewTags.PREVIEW)
        composeRule.onNodeWithText("fictitious soup", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(PreviewTags.NAME).assertTextEquals("Einkauf.txt")
        waitForTag(LinkedItemsTags.link(toRecipe))
        composeRule.onNodeWithTag(PreviewTags.LINKS).assertExists()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
