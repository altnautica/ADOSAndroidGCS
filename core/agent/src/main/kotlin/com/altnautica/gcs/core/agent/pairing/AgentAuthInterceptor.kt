package com.altnautica.gcs.core.agent.pairing

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the stored pairing key to every agent request as `X-ADOS-Key`.
 *
 * The agent's LAN edge reads exactly `X-ADOS-Key` and answers a paired node's
 * data routes with 401 without it. The only routes that work unauthenticated
 * are the agent's public set (`/api/pairing/{info,code,claim}`, `/api/ping`,
 * `/api/version`, `/healthz`), and sending the header there is harmless — the
 * agent does not consult it on a public path.
 *
 * While this device is unpaired there is no key and no header; the resulting
 * 401 is raised as [NotPairedError] so the UI can say what is actually wrong.
 */
@Singleton
class AgentAuthInterceptor @Inject constructor(
    private val credentials: AgentCredentialStore,
) : Interceptor {

    companion object {
        /** The header the agent's control front reads the pairing key from. */
        const val KEY_HEADER = "X-ADOS-Key"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val key = credentials.currentApiKey()
            ?: return chain.proceed(chain.request())
        val authed = chain.request().newBuilder()
            .header(KEY_HEADER, key)
            .build()
        return chain.proceed(authed)
    }
}
