package ch.sonensei.cockpit.push

import android.content.Context
import ch.sonensei.cockpit.auth.LogoutHook
import ch.sonensei.cockpit.auth.SessionCache
import ch.sonensei.cockpit.core.network.generated.apis.PushApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import retrofit2.Retrofit

/** Receives what the UnifiedPush distributor sends: endpoints and (already decrypted) push messages. */
class CockpitPushService : PushService() {
    private val graph by lazy { pushGraph(this) }

    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) = graph.pushRegistrar().onNewEndpoint(instance, endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth)

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        graph.pushMessageHandler().handle(message.content, message.decrypted, instance)
    }

    // No endpoint: the periodic syncs stay, and the next app start registers again.
    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) = Unit

    override fun onUnregistered(instance: String) = graph.pushRegistrar().onUnregistered(instance)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PushEntryPoint {
    fun pushRegistrar(): PushRegistrar

    fun pushMessageHandler(): PushMessageHandler
}

internal fun pushGraph(context: Context): PushEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, PushEntryPoint::class.java)

@Module
@InstallIn(SingletonComponent::class)
abstract class PushBindings {
    @Binds
    abstract fun connector(connector: LibraryUnifiedPushConnector): UnifiedPushConnector

    @Binds
    abstract fun instanceStore(store: PreferencesPushInstanceStore): PushInstanceStore

    @Binds
    abstract fun mailNotifier(notifier: SystemNewMailNotifier): NewMailNotifier

    @Binds
    abstract fun syncTrigger(trigger: WorkManagerPushSyncTrigger): PushSyncTrigger
}

@Module
@InstallIn(SingletonComponent::class)
object PushModule {
    @Provides
    fun pushApi(retrofit: Retrofit): PushApi = retrofit.create(PushApi::class.java)

    /** A user logout deletes the endpoint on the server first, while the request can still be authenticated. */
    @Provides
    @IntoSet
    fun pushLogoutHook(registrar: PushRegistrar): LogoutHook = LogoutHook { registrar.deleteOnServer() }

    /** Every logout unregisters at the distributor and drops the new-mail notification. */
    @Provides
    @IntoSet
    fun pushSessionCache(
        registrar: PushRegistrar,
        notifier: NewMailNotifier,
    ): SessionCache =
        SessionCache {
            // The library's database and broadcasts: off the main thread a user logout runs on.
            withContext(Dispatchers.IO) { registrar.clear() }
            notifier.dismiss()
        }
}
