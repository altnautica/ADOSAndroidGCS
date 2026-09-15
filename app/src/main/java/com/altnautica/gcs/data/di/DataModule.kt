package com.altnautica.gcs.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.altnautica.gcs.BuildConfig
import com.altnautica.gcs.data.flightlog.FlightDatabase
import com.altnautica.gcs.data.flightlog.FlightSessionDao
import com.altnautica.gcs.data.groundstation.GroundStationApi
import com.altnautica.gcs.data.pairing.AgentAuthInterceptor
import com.altnautica.gcs.data.pairing.PairingApi
import com.altnautica.gcs.data.settings.AgentHostInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * The host Retrofit is built against. Every request is retargeted at the
 * operator's configured agent by [AgentHostInterceptor], so this value is only
 * a syntactically valid placeholder that Retrofit's builder insists on — baking
 * the real host in here is what froze the address at first injection.
 */
private const val RETROFIT_PLACEHOLDER_BASE_URL = "http://localhost/"

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    @Provides
    @Singleton
    fun provideOkHttpClient(
        hostInterceptor: AgentHostInterceptor,
        authInterceptor: AgentAuthInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(hostInterceptor)
        .addInterceptor(authInterceptor)
        // Bodies carry radio configuration, AP passphrases and recording paths,
        // and the request headers carry the agent's full-authority pairing key.
        // Release builds log nothing; debug logs the request line only, with the
        // key redacted so it never reaches logcat even on a bench device.
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
                redactHeader(AgentAuthInterceptor.KEY_HEADER)
            },
        )
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(RETROFIT_PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideGroundStationApi(retrofit: Retrofit): GroundStationApi =
        retrofit.create(GroundStationApi::class.java)

    @Provides
    @Singleton
    fun providePairingApi(retrofit: Retrofit): PairingApi =
        retrofit.create(PairingApi::class.java)

    @Provides
    @Singleton
    fun provideFlightDatabase(@ApplicationContext context: Context): FlightDatabase =
        Room.databaseBuilder(context, FlightDatabase::class.java, "ados_flights.db").build()

    @Provides
    fun provideFlightSessionDao(db: FlightDatabase): FlightSessionDao = db.flightSessionDao()

    @Provides
    @Singleton
    fun provideKtorHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 15_000
        }
        engine {
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(0, TimeUnit.SECONDS) // No read timeout for WebSocket
            }
        }
    }
}
