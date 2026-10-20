package ch.sonensei.cockpit.capture.queue

import android.content.Context
import androidx.room.Room
import ch.sonensei.cockpit.core.network.generated.apis.CaptureApi
import ch.sonensei.cockpit.core.network.generated.apis.FilesApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureQueueBindings {
    @Binds
    abstract fun syncScheduler(scheduler: WorkManagerCaptureSyncScheduler): CaptureSyncScheduler
}

@Module
@InstallIn(SingletonComponent::class)
object CaptureQueueModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): CaptureQueueDatabase = Room.databaseBuilder(context, CaptureQueueDatabase::class.java, CaptureQueueDatabase.NAME).build()

    @Provides
    fun dao(database: CaptureQueueDatabase): CaptureQueueDao = database.dao()

    @Provides
    fun captureApi(retrofit: Retrofit): CaptureApi = retrofit.create(CaptureApi::class.java)

    @Provides
    fun filesApi(retrofit: Retrofit): FilesApi = retrofit.create(FilesApi::class.java)
}
