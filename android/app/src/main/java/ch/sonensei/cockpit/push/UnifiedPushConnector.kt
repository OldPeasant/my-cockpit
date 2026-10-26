package ch.sonensei.cockpit.push

import android.content.Context
import androidx.core.content.edit
import ch.sonensei.cockpit.R
import dagger.hilt.android.qualifiers.ApplicationContext
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The UnifiedPush library, behind an interface so tests need no distributor app. */
interface UnifiedPushConnector {
    /**
     * Picks the distributor (e.g. ntfy) to use: the one saved before, else the system default or the only one installed.
     * Returns false when no distributor is installed.
     */
    fun selectDistributor(): Boolean

    /** Asks the distributor for an endpoint for [instance]; it answers through [CockpitPushService.onNewEndpoint]. */
    fun register(instance: String)

    /** Drops the registration of [instance] at the distributor and forgets its keys. */
    fun unregister(instance: String)
}

class LibraryUnifiedPushConnector
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : UnifiedPushConnector {
        override fun selectDistributor(): Boolean {
            if (UnifiedPush.getSavedDistributor(context) != null) return true
            val distributor =
                when (val resolved = UnifiedPush.resolveDefaultDistributor(context)) {
                    is ResolvedDistributor.Found -> resolved.packageName
                    // Several distributors and no default (or ones without a link activity): the app has no picker,
                    // so it takes the first installed one.
                    ResolvedDistributor.ToSelect, ResolvedDistributor.NoneAvailable ->
                        UnifiedPush.getDistributors(context).firstOrNull()
                } ?: return false
            UnifiedPush.saveDistributor(context, distributor)
            return true
        }

        override fun register(instance: String) =
            UnifiedPush.register(context, instance, messageForDistributor = context.getString(R.string.app_name))

        override fun unregister(instance: String) = UnifiedPush.unregister(context, instance)
    }

/**
 * The UnifiedPush instance of this login: a random id that names the registration at the distributor and on the
 * server (`PUT /api/v1/push/unifiedpush/{instanceId}`). A logout forgets it, so the next login gets a new one.
 */
interface PushInstanceStore {
    fun current(): String?

    fun getOrCreate(): String

    fun clear()
}

@Singleton
class PreferencesPushInstanceStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : PushInstanceStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun current(): String? = prefs.getString(KEY_INSTANCE, null)

        @Synchronized
        override fun getOrCreate(): String =
            current() ?: UUID.randomUUID().toString().also { id -> prefs.edit(commit = true) { putString(KEY_INSTANCE, id) } }

        @Synchronized
        override fun clear() = prefs.edit(commit = true) { remove(KEY_INSTANCE) }

        private companion object {
            const val PREFS_NAME = "push"
            const val KEY_INSTANCE = "unifiedpush_instance"
        }
    }
