package ch.sonensei.cockpit.reminders

import ch.sonensei.cockpit.auth.SessionCache
import ch.sonensei.cockpit.core.network.generated.apis.RemindersApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class RemindersBindings {
    @Binds
    abstract fun alarms(alarms: AlarmManagerReminderAlarms): ReminderAlarms

    @Binds
    abstract fun syncScheduler(scheduler: WorkManagerReminderSyncScheduler): ReminderSyncScheduler
}

@Module
@InstallIn(SingletonComponent::class)
object RemindersModule {
    @Provides
    fun remindersApi(retrofit: Retrofit): RemindersApi = retrofit.create(RemindersApi::class.java)

    /** The reminders belong to the user: a logout stops the sync and removes every alarm and notification. */
    @Provides
    @IntoSet
    fun reminders(
        syncScheduler: ReminderSyncScheduler,
        scheduler: ReminderScheduler,
    ): SessionCache =
        SessionCache {
            syncScheduler.cancel()
            scheduler.clear()
        }
}
