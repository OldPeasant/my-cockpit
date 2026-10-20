package ch.sonensei.cockpit.auth

import android.content.Context
import ch.sonensei.cockpit.core.network.TokenStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthBindings {
    @Binds
    abstract fun tokenStore(store: PersistentTokenStore): TokenStore

    @Binds
    abstract fun tokenCipher(cipher: KeystoreTokenCipher): TokenCipher

    @Multibinds
    abstract fun sessionCaches(): Set<SessionCache>
}

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    /** Files the app cached for the user (downloads, thumbnails) go with the logout. */
    @Provides
    @IntoSet
    fun cacheDirectory(
        @ApplicationContext context: Context,
    ): SessionCache =
        SessionCache {
            withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        }
}
