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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes the cached view as [Cached] values and, while observed, reloads it with [refresh] whenever [triggers] fire.
 * The cache is the single source: a reload replaces it, and the screen sees the new rows from Room. Every replace and
 * the logout clear write the view's [meta] row, so on each [meta] change the rows and their refresh time are [read]
 * again together.
 */
private fun <E, T> cachedView(
    meta: Flow<CacheMetaEntity?>,
    read: suspend () -> CachedRows<E>,
    connectivity: GtdConnectivity,
    triggers: Flow<Unit>,
    refresh: suspend () -> RefreshResult,
    toDomain: (E) -> T,
): Flow<Cached<List<T>>> =
    channelFlow {
        launch { triggers.conflate().collect { refresh() } }
        combine(meta.map { read() }, connectivity.offline) { cached, offline ->
            Cached(cached.rows.map(toDomain), offline, cached.meta?.let { Instant.ofEpochMilli(it.refreshedAtEpochMs) })
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
            meta = dao.observeMeta(view.name),
            read = { dao.taskRows(view.name) },
            connectivity = connectivity,
            triggers = triggers.on(ChangeTypes.TASK),
            refresh = { refresh(view) },
            toDomain = CachedTaskEntity::toDomain,
        )

    /** Loads [view] from the server and, if that worked, replaces its cached copy. */
    suspend fun refresh(view: TaskView): RefreshResult =
        connectivity.refreshing(
            view.name,
            load = {
                when (view) {
                    TaskView.INBOX -> load(list = ListListTasks.INBOX)
                    TaskView.NEXT -> load(list = ListListTasks.NEXT)
                    TaskView.TODAY -> (load(due = DueListTasks.OVERDUE) + load(due = DueListTasks.TODAY)).distinctBy { it.id }
                }
            },
            store = { tasks -> dao.replaceTasks(view.name, tasks.mapIndexed { i, task -> task.toEntity(view, i) }, clock.millis()) },
        )

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
            meta = dao.observeMeta(CacheMetaEntity.PROJECTS),
            read = dao::projectRows,
            connectivity = connectivity,
            triggers = triggers.on(ChangeTypes.PROJECT, ChangeTypes.TASK),
            refresh = ::refresh,
            toDomain = CachedProjectEntity::toDomain,
        )

    suspend fun refresh(): RefreshResult =
        connectivity.refreshing(
            CacheMetaEntity.PROJECTS,
            load = {
                allPages(
                    page = { cursor -> api.listProjects(cursor = cursor, limit = PAGE_SIZE) },
                    items = { it.items },
                    nextCursor = { it.nextCursor },
                )
            },
            store = { projects -> dao.replaceProjects(projects.mapIndexed { i, project -> project.toEntity(i) }, clock.millis()) },
        )
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
            meta = dao.observeMeta(CacheMetaEntity.CONTEXTS),
            read = dao::contextRows,
            connectivity = connectivity,
            triggers = triggers.on(ChangeTypes.CONTEXT),
            refresh = ::refresh,
            toDomain = CachedContextEntity::toDomain,
        )

    suspend fun refresh(): RefreshResult =
        connectivity.refreshing(
            CacheMetaEntity.CONTEXTS,
            load = { body(api.listContexts()).items },
            store = { contexts -> dao.replaceContexts(contexts.mapIndexed { i, context -> context.toEntity(i) }, clock.millis()) },
        )
}
