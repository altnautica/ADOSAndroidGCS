package com.altnautica.gcs.feature.configure.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Emits immediately and then once per second, so time-based state (link freshness) re-evaluates. */
fun secondTicks(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(1_000)
    }
}
