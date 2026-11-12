package com.altnautica.gcs.core.flightlog

import androidx.room.withTransaction
import com.altnautica.gcs.core.database.AdosDatabase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Runs a group of flight-index writes so they all land or none do. */
interface IndexTransaction {
    suspend fun run(block: suspend () -> Unit)
}

/** One database transaction. */
class RoomIndexTransaction @Inject constructor(private val database: AdosDatabase) : IndexTransaction {
    override suspend fun run(block: suspend () -> Unit) = database.withTransaction { block() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class IndexTransactionModule {
    @Binds
    abstract fun bindIndexTransaction(impl: RoomIndexTransaction): IndexTransaction
}
