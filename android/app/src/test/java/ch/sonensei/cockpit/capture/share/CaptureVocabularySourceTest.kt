package ch.sonensei.cockpit.capture.share

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import ch.sonensei.cockpit.gtd.data.CachedContextEntity
import ch.sonensei.cockpit.gtd.data.CachedProjectEntity
import ch.sonensei.cockpit.gtd.data.GtdCacheDao
import ch.sonensei.cockpit.gtd.data.GtdCacheDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate

/** The preview's projects and contexts, read from the offline GTD cache in the server's order of preference. */
@RunWith(AndroidJUnit4::class)
class CaptureVocabularySourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: GtdCacheDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, GtdCacheDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun id(n: Int): String = "00000000-0000-7000-8000-%012d".format(n)

    private fun project(
        n: Int,
        title: String,
        status: String,
        ordinal: Int = n,
    ) = CachedProjectEntity(
        id = id(n),
        ordinal = ordinal,
        title = title,
        status = status,
        stalled = false,
        version = 1,
        areaId = null,
        nextTaskId = null,
        dueDate = null,
        reviewDate = null,
        descriptionMd = null,
    )

    private fun ctx(
        n: Int,
        name: String,
        position: Int,
        ordinal: Int = n,
    ) = CachedContextEntity(id = id(n), ordinal = ordinal, name = name, position = position, version = 1, color = null)

    @Test
    fun `projects are offered active first, then on hold, then someday, each in creation order`() {
        val projects =
            listOf(
                project(5, "Alpha someday", "SOMEDAY"),
                project(4, "Beta on hold", "ON_HOLD"),
                project(3, "Gamma active", "ACTIVE"),
                project(1, "Delta active", "ACTIVE"),
                project(2, "Epsilon on hold", "ON_HOLD"),
            )

        val vocabulary = CaptureVocabularySource.vocabulary(projects, emptyList())

        assertEquals(
            listOf("Delta active", "Gamma active", "Epsilon on hold", "Beta on hold", "Alpha someday"),
            vocabulary.projects.map { it.title },
        )
        assertEquals(listOf(id(1), id(3), id(2), id(4), id(5)), vocabulary.projects.map { it.id })
    }

    @Test
    fun `completed, dropped and unknown statuses are not offered`() {
        val projects =
            listOf(
                project(1, "Done", "COMPLETED"),
                project(2, "Gone", "DROPPED"),
                project(3, "Odd", "ARCHIVED"),
                project(4, "Garden", "ACTIVE"),
            )

        assertEquals(listOf(SyntaxProject(id(4), "Garden")), CaptureVocabularySource.vocabulary(projects, emptyList()).projects)
    }

    @Test
    fun `of two projects with the same name the active one wins in the preview`() {
        val vocabulary =
            CaptureVocabularySource.vocabulary(
                listOf(project(1, "Garden", "SOMEDAY"), project(2, "garden", "ACTIVE")),
                emptyList(),
            )

        assertEquals(id(2), CaptureSyntax.parse("Weeds #garden", LocalDate.of(2026, 3, 1), vocabulary).project?.id)
    }

    @Test
    fun `contexts are in their position order, ties by id`() {
        val contexts = listOf(ctx(3, "@phone", 2), ctx(2, "@home", 1), ctx(1, "@errands", 2), ctx(4, "@office", 0))

        val vocabulary = CaptureVocabularySource.vocabulary(emptyList(), contexts)

        assertEquals(listOf("@office", "@home", "@errands", "@phone"), vocabulary.contexts.map { it.name })
        assertEquals(SyntaxContext(id(4), "@office"), vocabulary.contexts.first())
    }

    @Test
    fun `an empty cache gives an empty vocabulary`() {
        assertEquals(CaptureVocabulary.EMPTY, CaptureVocabularySource.vocabulary(emptyList(), emptyList()))
    }

    @Test
    fun `observe follows the cache as the GTD screens refresh it`() =
        runBlocking {
            val dao = database.dao()
            val source = CaptureVocabularySource(dao)

            source.observe().test {
                assertEquals(CaptureVocabulary.EMPTY, awaitItem())

                dao.replaceProjects(listOf(project(1, "Garden", "ACTIVE"), project(2, "Old", "COMPLETED")), 1L)
                // Room may emit for the projects table before the contexts one changes: wait for the full answer.
                var vocabulary = awaitItem()
                while (vocabulary.projects.isEmpty()) vocabulary = awaitItem()
                assertEquals(listOf(SyntaxProject(id(1), "Garden")), vocabulary.projects)

                dao.replaceContexts(listOf(ctx(11, "@home", 0)), 2L)
                vocabulary = awaitItem()
                while (vocabulary.contexts.isEmpty()) vocabulary = awaitItem()
                assertEquals(listOf(SyntaxContext(id(11), "@home")), vocabulary.contexts)
                assertEquals(listOf(SyntaxProject(id(1), "Garden")), vocabulary.projects)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a broken cache gives an empty vocabulary instead of failing the sheet`() =
        runBlocking {
            val dao = mockk<GtdCacheDao>()
            every { dao.observeProjects() } returns flow { throw IOException("database file is corrupt") }
            every { dao.observeContexts() } returns flowOf(listOf(ctx(1, "@home", 0)))

            assertEquals(CaptureVocabulary.EMPTY, CaptureVocabularySource(dao).observe().first())
        }
}
