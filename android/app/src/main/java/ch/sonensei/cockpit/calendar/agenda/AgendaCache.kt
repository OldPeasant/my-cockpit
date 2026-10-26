package ch.sonensei.cockpit.calendar.agenda

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import ch.sonensei.cockpit.auth.SessionCache
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The agenda as last loaded: the days [from, to), the calendars and occurrences of that load, and when it was loaded. */
data class CachedAgenda(
    val from: LocalDate,
    val to: LocalDate,
    val calendars: List<CalendarInfo>,
    val occurrences: List<Occurrence>,
    val loadedAt: Instant,
)

/**
 * The offline read cache of the agenda (SPEC.md §9.3): only a copy of server data, shown read-only while the server
 * cannot be reached. A write carries the [session] it was loaded in; after a [SessionCache] clear (a logout) the writes
 * of loads started before it are dropped, so they cannot bring the previous session's data back.
 */
interface AgendaCache {
    /** The current session, to be passed to the writes of a load that starts now. */
    suspend fun session(): Long

    /** Replaces the cache with the agenda [from, to) loaded at [loadedAt]. */
    suspend fun store(
        session: Long,
        agenda: CachedAgenda,
    )

    /** Adds the days [from, to) loaded on scrolling; ignored unless they continue the cached days. */
    suspend fun append(
        session: Long,
        from: LocalDate,
        to: LocalDate,
        occurrences: List<Occurrence>,
    )

    /** The cached agenda, or `null` when there is none (or it cannot be read). */
    suspend fun read(): CachedAgenda?

    /** No cache: nothing is stored, nothing is read. */
    object None : AgendaCache {
        override suspend fun session() = 0L

        override suspend fun store(
            session: Long,
            agenda: CachedAgenda,
        ) = Unit

        override suspend fun append(
            session: Long,
            from: LocalDate,
            to: LocalDate,
            occurrences: List<Occurrence>,
        ) = Unit

        override suspend fun read(): CachedAgenda? = null
    }
}

@Entity(tableName = "cached_calendar")
data class CachedCalendarEntity(
    @PrimaryKey val id: String,
    val ordinal: Int,
    val name: String,
    val color: Long?,
    val visible: Boolean,
    val readOnly: Boolean,
    val version: Long,
)

@Entity(tableName = "cached_occurrence")
data class CachedOccurrenceEntity(
    /** [Occurrence.key]: an occurrence in two loaded chunks is stored once. */
    @PrimaryKey val key: String,
    val ordinal: Int,
    val eventId: String,
    val calendarId: String,
    val title: String,
    val location: String?,
    val allDay: Boolean,
    /** ISO-8601 instants and dates. */
    val start: String?,
    val end: String?,
    val startDate: String?,
    val endDate: String?,
    val seriesId: String?,
    val occurrence: String?,
    val version: Long,
)

/** The one row saying which days are cached and when they were first loaded. */
@Entity(tableName = "agenda_cache_meta")
data class AgendaCacheMetaEntity(
    @PrimaryKey val id: Int = ONLY,
    val fromDate: String,
    val toDate: String,
    val loadedAtEpochMs: Long,
) {
    companion object {
        const val ONLY = 0
    }
}

/** The cached rows, read in one transaction so they all belong to the same write. */
data class AgendaCacheRows(
    val meta: AgendaCacheMetaEntity?,
    val calendars: List<CachedCalendarEntity>,
    val occurrences: List<CachedOccurrenceEntity>,
)

@Dao
abstract class AgendaCacheDao {
    @Transaction
    open suspend fun rows(): AgendaCacheRows = AgendaCacheRows(meta(), calendars(), occurrences())

    /** Replaces everything in one transaction, so a reader never sees half of a write. */
    @Transaction
    open suspend fun replace(
        meta: AgendaCacheMetaEntity,
        calendars: List<CachedCalendarEntity>,
        occurrences: List<CachedOccurrenceEntity>,
    ) {
        deleteCalendars()
        deleteOccurrences()
        insertCalendars(calendars)
        upsertOccurrences(occurrences)
        upsertMeta(meta)
    }

    /** Adds the days [from, to) when they continue the cached days; returns whether they did. */
    @Transaction
    open suspend fun append(
        from: String,
        to: String,
        occurrences: List<CachedOccurrenceEntity>,
    ): Boolean {
        val meta = meta() ?: return false
        if (meta.toDate != from) return false
        // Ordered after the cached ones; one already cached (it overlaps both chunks) keeps its place.
        val first = occurrenceCount()
        upsertOccurrences(
            occurrences
                .mapIndexed {
                    index,
                    occurrence,
                    ->
                    occurrence.copy(ordinal = first + index)
                }.filterNot { exists(it.key) },
        )
        upsertMeta(meta.copy(toDate = to))
        return true
    }

    @Query("SELECT * FROM agenda_cache_meta WHERE id = 0")
    protected abstract suspend fun meta(): AgendaCacheMetaEntity?

    @Query("SELECT * FROM cached_calendar ORDER BY ordinal")
    protected abstract suspend fun calendars(): List<CachedCalendarEntity>

    @Query("SELECT * FROM cached_occurrence ORDER BY ordinal")
    protected abstract suspend fun occurrences(): List<CachedOccurrenceEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM cached_occurrence WHERE `key` = :key)")
    protected abstract suspend fun exists(key: String): Boolean

    @Query("SELECT COUNT(*) FROM cached_occurrence")
    protected abstract suspend fun occurrenceCount(): Int

    @Query("DELETE FROM cached_calendar")
    protected abstract suspend fun deleteCalendars()

    @Query("DELETE FROM cached_occurrence")
    protected abstract suspend fun deleteOccurrences()

    @Insert
    protected abstract suspend fun insertCalendars(calendars: List<CachedCalendarEntity>)

    @Upsert
    protected abstract suspend fun upsertOccurrences(occurrences: List<CachedOccurrenceEntity>)

    @Upsert
    protected abstract suspend fun upsertMeta(meta: AgendaCacheMetaEntity)
}

@Database(
    entities = [CachedCalendarEntity::class, CachedOccurrenceEntity::class, AgendaCacheMetaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AgendaCacheDatabase : RoomDatabase() {
    abstract fun dao(): AgendaCacheDao

    companion object {
        const val NAME = "agenda_cache.db"
    }
}

/** [AgendaCache] in Room. A cache that cannot be written or read is skipped: it only holds copies. */
@Singleton
class RoomAgendaCache
    @Inject
    constructor(
        private val database: AgendaCacheDatabase,
    ) : AgendaCache {
        private val dao = database.dao()

        /** Writes and [clear] exclude each other; [sessions] counts the clears (guarded by it). */
        private val lock = Mutex()
        private var sessions = 0L

        override suspend fun session(): Long = lock.withLock { sessions }

        override suspend fun store(
            session: Long,
            agenda: CachedAgenda,
        ) = writing(session) {
            dao.replace(
                AgendaCacheMetaEntity(
                    fromDate = agenda.from.toString(),
                    toDate = agenda.to.toString(),
                    loadedAtEpochMs = agenda.loadedAt.toEpochMilli(),
                ),
                agenda.calendars.mapIndexed { index, calendar -> calendar.toEntity(index) },
                agenda.occurrences.mapIndexed { index, occurrence -> occurrence.toEntity(index) },
            )
        }

        override suspend fun append(
            session: Long,
            from: LocalDate,
            to: LocalDate,
            occurrences: List<Occurrence>,
        ) = writing(session) {
            dao.append(from.toString(), to.toString(), occurrences.map { it.toEntity(0) })
        }

        override suspend fun read(): CachedAgenda? =
            try {
                val rows = dao.rows()
                val meta = rows.meta ?: return null
                CachedAgenda(
                    from = LocalDate.parse(meta.fromDate),
                    to = LocalDate.parse(meta.toDate),
                    calendars = rows.calendars.map { it.toCalendar() },
                    occurrences = rows.occurrences.map { it.toOccurrence() },
                    loadedAt = Instant.ofEpochMilli(meta.loadedAtEpochMs),
                )
            } catch (_: SQLiteException) {
                null
            } catch (_: DateTimeException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        /** Drops everything cached and the writes of loads started before. */
        suspend fun clear() {
            lock.withLock {
                sessions++
                withContext(Dispatchers.IO) { database.clearAllTables() }
            }
        }

        private suspend fun writing(
            session: Long,
            write: suspend () -> Unit,
        ) {
            lock.withLock {
                if (session != sessions) return
                try {
                    write()
                } catch (_: SQLiteException) {
                    // Not written (e.g. the device is full); the transaction left the cache as it was.
                }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AgendaCacheBindings {
    @Binds
    abstract fun agendaCache(cache: RoomAgendaCache): AgendaCache
}

@Module
@InstallIn(SingletonComponent::class)
object AgendaCacheModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): AgendaCacheDatabase =
        Room
            .databaseBuilder(context, AgendaCacheDatabase::class.java, AgendaCacheDatabase.NAME)
            // Only a copy of server data: on a schema change it is dropped and reloaded rather than migrated.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    /** The cached agenda belongs to the user: a logout drops it. */
    @Provides
    @IntoSet
    fun agendaSessionCache(cache: RoomAgendaCache): SessionCache = SessionCache { cache.clear() }
}

private fun CalendarInfo.toEntity(ordinal: Int) = CachedCalendarEntity(id.toString(), ordinal, name, color, visible, readOnly, version)

private fun CachedCalendarEntity.toCalendar() = CalendarInfo(UUID.fromString(id), name, color, visible, readOnly, version)

private fun Occurrence.toEntity(ordinal: Int) =
    CachedOccurrenceEntity(
        key = key,
        ordinal = ordinal,
        eventId = eventId.toString(),
        calendarId = calendarId.toString(),
        title = title,
        location = location,
        allDay = allDay,
        start = start?.toString(),
        end = end?.toString(),
        startDate = startDate?.toString(),
        endDate = endDate?.toString(),
        seriesId = seriesId?.toString(),
        occurrence = occurrence,
        version = version,
    )

private fun CachedOccurrenceEntity.toOccurrence() =
    Occurrence(
        eventId = UUID.fromString(eventId),
        calendarId = UUID.fromString(calendarId),
        title = title,
        location = location,
        allDay = allDay,
        start = start?.let(Instant::parse),
        end = end?.let(Instant::parse),
        startDate = startDate?.let(LocalDate::parse),
        endDate = endDate?.let(LocalDate::parse),
        seriesId = seriesId?.let(UUID::fromString),
        occurrence = occurrence,
        version = version,
    )
