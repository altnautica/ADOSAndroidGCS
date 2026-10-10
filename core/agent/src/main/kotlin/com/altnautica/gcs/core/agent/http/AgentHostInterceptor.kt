package com.altnautica.gcs.core.agent.http

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retargets every agent request at the base URL the operator has configured
 * *now*, so a request built against an earlier address (or by a caller that
 * holds only a path) still reaches the agent the Settings field points at.
 *
 * Without this, editing the address could move the MAVLink socket while REST
 * calls built earlier kept going to the previous host — telemetry from one
 * machine, status and configuration from another.
 *
 * Only scheme, host and port are replaced; the request's path and query are
 * left untouched.
 */
@Singleton
class AgentHostInterceptor @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
) : Interceptor {

    companion object {
        private const val TAG = "AgentHostInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = baseUrlProvider.currentBaseUrl().toHttpUrlOrNull()
        if (target == null) {
            // An unparseable stored value is a settings bug, not a reason to
            // drop the request: let it go to its original host and fail with
            // a transport error the caller already surfaces.
            Log.w(TAG, "stored base URL is not a valid HTTP URL; leaving request host as-is")
            return chain.proceed(request)
        }
        val retargeted = request.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        return chain.proceed(request.newBuilder().url(retargeted).build())
    }
}
