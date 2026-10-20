package ch.sonensei.cockpit.gtd.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A task as last loaded for one [view]; a task due today in the Inbox has a row for INBOX and one for TODAY. */
@Entity(tableName = "cached_task", primaryKeys = ["view", "id"])
data class CachedTaskEntity(
    val view: String,
    val id: String,
    /** Order within the view as the server returned it. */
    val ordinal: Int,
    val title: String,
    val list: String,
    val position: Int,
    val version: Long,
    val projectId: String?,
    /** Comma-separated context ids. */
    val contextIds: String,
    val dueDate: String?,
    val dueTime: String?,
    val scheduledDate: String?,
    val energy: String?,
    val creativity: String?,
    val timeEstimateMin: Int?,
    val waitingForText: String?,
    val waitingSince: String?,
    val notesMd: String?,
)

@Entity(tableName = "cached_project")
data class CachedProjectEntity(
    @PrimaryKey val id: String,
    val ordinal: Int,
    val title: String,
    val status: String,
    val stalled: Boolean,
    val version: Long,
    val areaId: String?,
    val nextTaskId: String?,
    val dueDate: String?,
    val reviewDate: String?,
    val descriptionMd: String?,
)

@Entity(tableName = "cached_context")
data class CachedContextEntity(
    @PrimaryKey val id: String,
    val ordinal: Int,
    val name: String,
    val position: Int,
    val version: Long,
    val color: String?,
)

/** When a cached view ([key]: a [TaskView] name, [PROJECTS] or [CONTEXTS]) was last replaced by a server answer. */
@Entity(tableName = "cache_meta")
data class CacheMetaEntity(
    @PrimaryKey val key: String,
    val refreshedAtEpochMs: Long,
) {
    companion object {
        const val PROJECTS = "PROJECTS"
        const val CONTEXTS = "CONTEXTS"
    }
}

/** The rows of a cached view together with its refresh time, read in one transaction so both belong to the same replace. */
data class CachedRows<E>(
    val rows: List<E>,
    val meta: CacheMetaEntity?,
)

@Dao
abstract class GtdCacheDao {
    @Query("SELECT * FROM cached_task WHERE view = :view ORDER BY ordinal")
    abstract fun observeTasks(view: String): Flow<List<CachedTaskEntity>>

    @Query("SELECT * FROM cached_project ORDER BY ordinal")
    abstract fun observeProjects(): Flow<List<CachedProjectEntity>>

    @Query("SELECT * FROM cached_context ORDER BY ordinal")
    abstract fun observeContexts(): Flow<List<CachedContextEntity>>

    @Query("SELECT * FROM cache_meta WHERE `key` = :key")
    abstract fun observeMeta(key: String): Flow<CacheMetaEntity?>

    @Transaction
    open suspend fun taskRows(view: String): CachedRows<CachedTaskEntity> = CachedRows(tasks(view), meta(view))

    @Transaction
    open suspend fun projectRows(): CachedRows<CachedProjectEntity> = CachedRows(projects(), meta(CacheMetaEntity.PROJECTS))

    @Transaction
    open suspend fun contextRows(): CachedRows<CachedContextEntity> = CachedRows(contexts(), meta(CacheMetaEntity.CONTEXTS))

    @Query("SELECT * FROM cached_task WHERE view = :view ORDER BY ordinal")
    protected abstract suspend fun tasks(view: String): List<CachedTaskEntity>

    @Query("SELECT * FROM cached_project ORDER BY ordinal")
    protected abstract suspend fun projects(): List<CachedProjectEntity>

    @Query("SELECT * FROM cached_context ORDER BY ordinal")
    protected abstract suspend fun contexts(): List<CachedContextEntity>

    @Query("SELECT * FROM cache_meta WHERE `key` = :key")
    protected abstract suspend fun meta(key: String): CacheMetaEntity?

    /** Replaces everything cached for [view] in one transaction, so readers never see a half-written view. */
    @Transaction
    open suspend fun replaceTasks(
        view: String,
        tasks: List<CachedTaskEntity>,
        refreshedAtEpochMs: Long,
    ) {
        deleteTasks(view)
        insertTasks(tasks)
        upsertMeta(CacheMetaEntity(view, refreshedAtEpochMs))
    }

    @Transaction
    open suspend fun replaceProjects(
        projects: List<CachedProjectEntity>,
        refreshedAtEpochMs: Long,
    ) {
        deleteProjects()
        insertProjects(projects)
        upsertMeta(CacheMetaEntity(CacheMetaEntity.PROJECTS, refreshedAtEpochMs))
    }

    @Transaction
    open suspend fun replaceContexts(
        contexts: List<CachedContextEntity>,
        refreshedAtEpochMs: Long,
    ) {
        deleteContexts()
        insertContexts(contexts)
        upsertMeta(CacheMetaEntity(CacheMetaEntity.CONTEXTS, refreshedAtEpochMs))
    }

    @Query("DELETE FROM cached_task WHERE view = :view")
    protected abstract suspend fun deleteTasks(view: String)

    @Insert
    protected abstract suspend fun insertTasks(tasks: List<CachedTaskEntity>)

    @Query("DELETE FROM cached_project")
    protected abstract suspend fun deleteProjects()

    @Insert
    protected abstract suspend fun insertProjects(projects: List<CachedProjectEntity>)

    @Query("DELETE FROM cached_context")
    protected abstract suspend fun deleteContexts()

    @Insert
    protected abstract suspend fun insertContexts(contexts: List<CachedContextEntity>)

    @Upsert
    protected abstract suspend fun upsertMeta(meta: CacheMetaEntity)
}

/** The offline read cache of the GTD screens (SPEC.md §9.3). It only holds copies of server data, so it may be dropped. */
@Database(
    entities = [CachedTaskEntity::class, CachedProjectEntity::class, CachedContextEntity::class, CacheMetaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GtdCacheDatabase : RoomDatabase() {
    abstract fun dao(): GtdCacheDao

    companion object {
        const val NAME = "gtd_cache.db"
    }
}
