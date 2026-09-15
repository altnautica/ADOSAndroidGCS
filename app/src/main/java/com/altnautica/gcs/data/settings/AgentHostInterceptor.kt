package com.altnautica.gcs.data.settings

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retargets every agent request at the base URL the operator has configured
 * *now*, rather than the one that happened to be stored when the Retrofit
 * instance was built.
 *
 * Retrofit resolves its base URL once, at construction. Without this the
 * address field in Settings moved the MAVLink socket but left every REST call
 * pointed at the previous host until the process restarted — telemetry from one
 * machine, status and configuration from another.
 *
 * Only scheme, host and port are replaced; the path Retrofit built from the
 * `@GET`/`@POST` annotation and its query are left untouched.
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
            // drop the request: let it go to the placeholder host and fail with
            // a transport error the repository already surfaces.
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
