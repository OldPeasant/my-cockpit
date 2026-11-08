package ch.sonensei.cockpit.capture.share

import ch.sonensei.cockpit.gtd.data.CachedContextEntity
import ch.sonensei.cockpit.gtd.data.CachedProjectEntity
import ch.sonensei.cockpit.gtd.data.GtdCacheDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * The projects and contexts for the capture preview, read from the offline GTD cache only: the sheet never asks the
 * server (it must work offline and open instantly). The cache holds what the GTD screens loaded last, so a project created
 * since then on another device is not recognized by the preview until the next refresh, although the server will be.
 */
class CaptureVocabularySource
    @Inject
    constructor(
        private val dao: GtdCacheDao,
    ) {
        fun observe(): Flow<CaptureVocabulary> =
            combine(dao.observeProjects(), dao.observeContexts()) { projects, contexts -> vocabulary(projects, contexts) }
                // A broken cache only loses the preview, never the capture.
                .catch { emitAll(flowOf(CaptureVocabulary.EMPTY)) }

        companion object {
            /** The statuses `#` may name, in the server's order of preference (`CaptureService.CAPTURE_PROJECT_STATUSES`). */
            private val STATUSES = listOf("ACTIVE", "ON_HOLD", "SOMEDAY")

            internal fun vocabulary(
                projects: List<CachedProjectEntity>,
                contexts: List<CachedContextEntity>,
            ): CaptureVocabulary =
                CaptureVocabulary(
                    // The server orders by status, then by id (UUIDv7: creation order); the cache is ordered by title.
                    projects =
                        projects
                            .filter { it.status in STATUSES }
                            .sortedWith(compareBy({ STATUSES.indexOf(it.status) }, { it.id }))
                            .map { SyntaxProject(it.id, it.title) },
                    contexts =
                        contexts
                            .sortedWith(compareBy({ it.position }, { it.id }))
                            .map { SyntaxContext(it.id, it.name) },
                )
        }
    }
