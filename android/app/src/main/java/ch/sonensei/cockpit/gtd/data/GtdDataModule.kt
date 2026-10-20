package ch.sonensei.cockpit.gtd.data

import android.content.Context
import androidx.room.Room
import ch.sonensei.cockpit.auth.SessionCache
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GtdDataBindings {
    @Binds
    abstract fun syncScheduler(scheduler: WorkManagerGtdSyncScheduler): GtdSyncScheduler
}

@Module
@InstallIn(SingletonComponent::class)
object GtdDataModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): GtdCacheDatabase =
        Room
            .databaseBuilder(context, GtdCacheDatabase::class.java, GtdCacheDatabase.NAME)
            // Only a copy of server data: on a schema change it is dropped and reloaded rather than migrated.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun dao(database: GtdCacheDatabase): GtdCacheDao = database.dao()

    @Provides
    fun tasksApi(retrofit: Retrofit): TasksApi = retrofit.create(TasksApi::class.java)

    @Provides
    fun projectsApi(retrofit: Retrofit): ProjectsApi = retrofit.create(ProjectsApi::class.java)

    /** The cached lists belong to the user: a logout drops them and stops the periodic sync. */
    @Provides
    @IntoSet
    fun gtdCache(
        database: GtdCacheDatabase,
        scheduler: GtdSyncScheduler,
        connectivity: GtdConnectivity,
    ): SessionCache =
        SessionCache {
            scheduler.cancel()
            // Excludes cache writes and drops the answers of loads still running, so they cannot refill the cache.
            connectivity.clearing { withContext(Dispatchers.IO) { database.clearAllTables() } }
        }
}
