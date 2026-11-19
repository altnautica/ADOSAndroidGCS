package com.altnautica.gcs.core.link.fleet

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class FleetModule {
    /** Bound by the phone radio, which carries the aux uplink's Request lane. */
    @BindsOptionalOf
    abstract fun requestLane(): FleetRequestLane
}
