package ch.sonensei.cockpit.gtd.data

import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi.DueListTasks
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi.ListListTasks
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes [rows] of the cache as [Cached] values and, while observed, reloads the view with [refresh] whenever
 * [triggers] fire. The cache is the single source: a reload replaces it, and the screen sees the new rows from Room.
 */
private fun <E, T> cachedView(
    rows: Flow<List<E>>,
    meta: Flow<CacheMetaEntity?>,
    connectivity: GtdConnectivity,
    triggers: Flow<Unit>,
    refresh: suspend () -> RefreshResult,
    toDomain: (E) -> T,
): Flow<Cached<List<T>>> =
    channelFlow {
        launch { triggers.conflate().collect { refresh() } }
        combine(rows, meta, connectivity.offline) { entities, m, offline ->
            Cached(entities.map(toDomain), offline, m?.let { Instant.ofEpochMilli(it.refreshedAtEpochMs) })
        }.collect { send(it) }
    }

/** Tasks of the cached views (Inbox, Next, Today). */
@Singleton
class TaskRepository internal constructor(
    private val api: TasksApi,
    private val dao: GtdCacheDao,
    private val connectivity: GtdConnectivity,
    private val triggers: GtdRefreshTriggers,
    private val clock: Clock,
) {
    @Inject
    constructor(
        api: TasksApi,
        dao: GtdCacheDao,
        connectivity: GtdConnectivity,
        triggers: GtdRefreshTriggers,
    ) : this(api, dao, connectivity, triggers, Clock.systemUTC())

    /** The last loaded tasks of [view]; while collected, the view reloads on start and on task changes. */
    fun observe(view: TaskView): Flow<Cached<List<GtdTask>>> =
        cachedView(
            rows = dao.observeTasks(view.name),
            meta = dao.observeMeta(view.name),
            connectivity = connectivity,
            triggers = triggers.on(ChangeTypes.TASK),
            refresh = { refresh(view) },
            toDomain = CachedTaskEntity::toDomain,
        )

    /** Loads [view] from the server and, if that worked, replaces its cached copy. */
    suspend fun refresh(view: TaskView): RefreshResult =
        connectivity.refreshing {
            val tasks =
                when (view) {
                    TaskView.INBOX -> load(list = ListListTasks.INBOX)
                    TaskView.NEXT -> load(list = ListListTasks.NEXT)
                    TaskView.TODAY -> (load(due = DueListTasks.OVERDUE) + load(due = DueListTasks.TODAY)).distinctBy { it.id }
                }
            dao.replaceTasks(view.name, tasks.mapIndexed { i, task -> task.toEntity(view, i) }, clock.millis())
        }

    private suspend fun load(
        list: ListListTasks? = null,
        due: DueListTasks? = null,
    ): List<TaskResponse> =
        allPages(
            page = { cursor -> api.listTasks(list = list, due = due, cursor = cursor, limit = PAGE_SIZE) },
            items = { it.items },
            nextCursor = { it.nextCursor },
        )
}

/** All projects (every status, ordered by title), for the Projects screen and for showing a task's project. */
@Singleton
class ProjectRepository internal constructor(
    private val api: ProjectsApi,
    private val dao: GtdCacheDao,
    private val connectivity: GtdConnectivity,
    private val triggers: GtdRefreshTriggers,
    private val clock: Clock,
) {
    @Inject
    constructor(
        api: ProjectsApi,
        dao: GtdCacheDao,
        connectivity: GtdConnectivity,
        triggers: GtdRefreshTriggers,
    ) : this(api, dao, connectivity, triggers, Clock.systemUTC())

    /** The last loaded projects; while collected, they reload on start and on project or task changes (stalled). */
    fun observe(): Flow<Cached<List<GtdProject>>> =
        cachedView(
            rows = dao.observeProjects(),
            meta = dao.observeMeta(CacheMetaEntity.PROJECTS),
            connectivity = connectivity,
            triggers = triggers.on(ChangeTypes.PROJECT, ChangeTypes.TASK),
            refresh = ::refresh,
            toDomain = CachedProjectEntity::toDomain,
        )

    suspend fun refresh(): RefreshResult =
        connectivity.refreshing {
            val projects =
                allPages(
                    page = { cursor -> api.listProjects(cursor = cursor, limit = PAGE_SIZE) },
                    items = { it.items },
                    nextCursor = { it.nextCursor },
                )
            dao.replaceProjects(projects.mapIndexed { i, project -> project.toEntity(i) }, clock.millis())
        }
}

/** All contexts in their order. */
@Singleton
class ContextRepository internal constructor(
    private val api: ProjectsApi,
    private val dao: GtdCacheDao,
    private val connectivity: GtdConnectivity,
    private val triggers: GtdRefreshTriggers,
    private val clock: Clock,
) {
    @Inject
    constructor(
        api: ProjectsApi,
        dao: GtdCacheDao,
        connectivity: GtdConnectivity,
        triggers: GtdRefreshTriggers,
    ) : this(api, dao, connectivity, triggers, Clock.systemUTC())

    /** The last loaded contexts; while collected, they reload on start and on context changes. */
    fun observe(): Flow<Cached<List<GtdContext>>> =
        cachedView(
            rows = dao.observeContexts(),
            meta = dao.observeMeta(CacheMetaEntity.CONTEXTS),
            connectivity = connectivity,
            triggers = triggers.on(ChangeTypes.CONTEXT),
            refresh = ::refresh,
            toDomain = CachedContextEntity::toDomain,
        )

    suspend fun refresh(): RefreshResult =
        connectivity.refreshing {
            val contexts = body(api.listContexts()).items
            dao.replaceContexts(contexts.mapIndexed { i, context -> context.toEntity(i) }, clock.millis())
        }
}
