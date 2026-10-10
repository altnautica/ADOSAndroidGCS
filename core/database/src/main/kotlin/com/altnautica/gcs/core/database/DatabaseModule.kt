package com.altnautica.gcs.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AdosDatabase =
        Room.databaseBuilder(context, AdosDatabase::class.java, AdosDatabase.NAME)
            .addMigrations(*AdosDatabase.MIGRATIONS)
            .build()

    @Provides
    fun provideFlightSessionDao(database: AdosDatabase): FlightSessionDao =
        database.flightSessionDao()

    @Provides
    fun provideTlogFileDao(database: AdosDatabase): TlogFileDao = database.tlogFileDao()

    @Provides
    fun provideSavedPlanDao(database: AdosDatabase): SavedPlanDao = database.savedPlanDao()
}
