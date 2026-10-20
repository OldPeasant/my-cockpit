package ch.sonensei.cockpit.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import ch.sonensei.cockpit.core.network.generated.apis.EventsApi
import ch.sonensei.cockpit.core.network.generated.apis.SettingsApi
import ch.sonensei.cockpit.core.network.generated.apis.SetupApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** The DataStore with the server connection settings. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ServerConfigStore

/** The client for the token refresh call itself: no access token, no [TokenAuthenticator]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshClient

/** Builds the HTTP stack shared by all features; tests build it with their own pieces. */
object CockpitHttp {
    fun okHttpClient(
        serverUrl: ServerUrlInterceptor,
        accessToken: AccessTokenInterceptor?,
        authenticator: TokenAuthenticator?,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(serverUrl)
            .apply {
                accessToken?.let { addInterceptor(it) }
                authenticator?.let { authenticator(it) }
            }.build()

    fun retrofit(
        client: OkHttpClient,
        json: Json = Serializer.kotlinxSerializationJson,
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}

/** The server-config DataStore; an unreadable file is replaced by empty settings instead of failing every read. */
internal fun serverConfigDataStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile: () -> File,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = produceFile,
    )

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindings {
    // TokenStore is bound by the auth module (Keystore-backed storage).

    @Binds
    abstract fun tokenRefresher(refresher: AuthApiTokenRefresher): TokenRefresher
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    @ServerConfigStore
    fun serverConfigStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = serverConfigDataStore { context.preferencesDataStoreFile("server_config") }

    @Provides
    @Singleton
    fun okHttpClient(
        serverUrl: ServerUrlInterceptor,
        accessToken: AccessTokenInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = CockpitHttp.okHttpClient(serverUrl, accessToken, authenticator)

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient): Retrofit = CockpitHttp.retrofit(client)

    @Provides
    @Singleton
    @RefreshClient
    fun refreshAuthApi(serverUrl: ServerUrlInterceptor): AuthApi =
        CockpitHttp.retrofit(CockpitHttp.okHttpClient(serverUrl, null, null)).create(AuthApi::class.java)

    @Provides
    fun authApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    fun eventsApi(retrofit: Retrofit): EventsApi = retrofit.create(EventsApi::class.java)

    @Provides
    fun settingsApi(retrofit: Retrofit): SettingsApi = retrofit.create(SettingsApi::class.java)

    @Provides
    fun setupApi(retrofit: Retrofit): SetupApi = retrofit.create(SetupApi::class.java)
}
