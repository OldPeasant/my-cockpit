package ch.sonensei.cockpit.push

import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.generated.apis.PushApi
import ch.sonensei.cockpit.core.network.generated.models.UnifiedPushRegistrationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps this device registered for UnifiedPush while the user is logged in: registers with the distributor at app
 * start and after each login, sends the endpoint it gets to the server, and on logout deletes it there and unregisters.
 * Without a distributor nothing happens: the periodic WorkManager syncs alone keep the app up to date.
 *
 * Every step is best effort: a failure leaves the periodic syncs, and the next app start registers again (the
 * distributor then sends the endpoint again, and the server's PUT is idempotent).
 */
@Singleton
class PushRegistrar internal constructor(
    private val session: AuthSession,
    private val api: PushApi,
    private val connector: UnifiedPushConnector,
    private val store: PushInstanceStore,
    private val loggedIn: Flow<Boolean>,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        session: AuthSession,
        api: PushApi,
        connector: UnifiedPushConnector,
        store: PushInstanceStore,
        tokenStore: PersistentTokenStore,
    ) : this(
        session,
        api,
        connector,
        store,
        tokenStore.session.map { it != null },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val started = AtomicBoolean(false)

    /** Orders the endpoint PUT and the logout's DELETE, so the server never sees a PUT after the DELETE. */
    private val requests = Mutex()

    /** The instance whose endpoint the logout deleted on the server: a later endpoint of it is not sent. */
    @Volatile
    private var deleted: String? = null

    /** Registers now if logged in, and again after every later login. Called once, at app start. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { loggedIn.distinctUntilChanged().filter { it }.collect { register() } }
    }

    /** Asks the distributor for an endpoint; no distributor installed means periodic sync only. */
    internal fun register() {
        try {
            if (!session.isLoggedIn || !connector.selectDistributor()) return
            connector.register(store.getOrCreate())
        } catch (_: Exception) {
            // The library failed (e.g. its database or the keystore): the periodic syncs stay, the next app start
            // tries again.
        }
    }

    /** The distributor gave [instance] an endpoint (new or changed): the server pushes to it from now on. */
    fun onNewEndpoint(
        instance: String,
        endpoint: String,
        p256dh: String?,
        auth: String?,
    ) {
        scope.launch {
            requests.withLock {
                // An endpoint of an older login (or one that arrives during or after the logout) is not sent.
                if (!session.isLoggedIn || instance != store.current() || instance == deleted) return@launch
                val keys = p256dh != null && auth != null
                bestEffort {
                    api.registerUnifiedPush(
                        instance,
                        UnifiedPushRegistrationRequest(endpoint, auth.takeIf { keys }, p256dh.takeIf { keys }),
                    )
                }
            }
        }
    }

    /** The distributor dropped [instance] (e.g. it was uninstalled): the server stops pushing to it. */
    fun onUnregistered(instance: String) {
        scope.launch {
            if (session.isLoggedIn && instance == store.current()) bestEffort { api.unregisterUnifiedPush(instance) }
        }
    }

    /** At the start of a logout, while still logged in: the server forgets the endpoint of this login. */
    suspend fun deleteOnServer() {
        val instance = store.current() ?: return
        // Set before waiting for the lock: the PUTs still waiting and later ones are dropped, even if the logout hook's
        // timeout cancels this wait.
        deleted = instance
        requests.withLock {
            // Waits for a PUT already being sent.
            bestEffort { api.unregisterUnifiedPush(instance) }
        }
    }

    /** After a logout (of any kind): unregisters at the distributor and forgets the instance. */
    fun clear() {
        val instance = store.current() ?: return
        try {
            connector.unregister(instance)
        } catch (_: Exception) {
            // The distributor keeps a dead registration; the server has none, so nothing is pushed to it.
        }
        store.clear()
    }

    private suspend fun bestEffort(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or an error answer. Not logged: the exception may carry the endpoint, which grants pushing.
        }
    }
}
