package com.altnautica.gcs.data.di

import com.altnautica.gcs.data.groundstation.GroundStationApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideGroundStationApi(client: OkHttpClient): GroundStationApi =
        Retrofit.Builder()
            .baseUrl("http://192.168.4.1:8080/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroundStationApi::class.java)

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
