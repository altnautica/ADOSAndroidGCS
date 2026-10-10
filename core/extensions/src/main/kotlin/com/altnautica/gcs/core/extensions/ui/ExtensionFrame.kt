package com.altnautica.gcs.core.extensions.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.theme.LocalAdosColors
import com.altnautica.gcs.core.extensions.R
import com.altnautica.gcs.core.extensions.host.ExtensionFrameSession
import com.altnautica.gcs.core.extensions.host.ExtensionFrameViewModel
import com.altnautica.gcs.core.extensions.host.FrameState
import com.altnautica.gcs.core.extensions.theme.ExtensionTheme
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Origin of the relay page; the only origin the host channel is injected into. */
internal const val RELAY_ORIGIN = "https://extension.invalid"

/**
 * One extension frame: a WebView running the relay page, which hosts the extension's
 * bundle in a sandboxed iframe and forwards bridge envelopes to the app.
 *
 * @param hostEvent latest one-way host event (`video.overlay.props` for overlays).
 * @param quiet render nothing instead of a status card while loading or failing
 * (overlays and widgets must never cover the video with chrome).
 */
@Composable
fun ExtensionFrame(
    node: DeviceId,
    pluginId: String,
    mountKey: String,
    modifier: Modifier = Modifier,
    hostEvent: Pair<String, JsonElement>? = null,
    quiet: Boolean = false,
) {
    val vm: ExtensionFrameViewModel = hiltViewModel(key = "extension-frame:${node.value}:$pluginId:$mountKey")
    LaunchedEffect(vm, node, pluginId) { vm.start(node, pluginId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val roles = LocalAdosColors.current
    val themeVars = remember(roles) { ExtensionTheme.cssVariables(roles) }
    LaunchedEffect(vm, themeVars) { vm.setTheme(themeVars) }
    LaunchedEffect(vm, hostEvent) { hostEvent?.let { vm.setHostEvent(it.first, it.second) } }

    when (val s = state) {
        FrameState.Loading -> if (!quiet) LoadingState(stringResource(R.string.extensions_loading), modifier)
        FrameState.InlineOnly -> if (!quiet) {
            EmptyState(
                icon = Icons.Outlined.DesktopWindows,
                title = stringResource(R.string.extensions_inline_title),
                message = stringResource(R.string.extensions_inline_message),
                modifier = modifier,
            )
        }
        FrameState.NoBundle -> if (!quiet) {
            EmptyState(
                icon = Icons.Outlined.Extension,
                title = stringResource(R.string.extensions_title),
                message = stringResource(R.string.extensions_no_bundle),
                modifier = modifier,
            )
        }
        is FrameState.Failed -> if (!quiet) {
            EmptyState(
                icon = Icons.Outlined.Extension,
                title = stringResource(R.string.extensions_frame_failed),
                message = failureText(s.failure),
                modifier = modifier,
            )
        }
        is FrameState.Ready -> {
            val session = vm.session ?: return
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                if (!quiet) {
                    EmptyState(
                        icon = Icons.Outlined.Extension,
                        title = stringResource(R.string.extensions_frame_failed),
                        message = stringResource(R.string.extensions_webview_unsupported),
                        modifier = modifier,
                    )
                }
                return
            }
            Box(modifier) {
                AndroidView(
                    factory = { ctx -> createRelayWebView(ctx, session, s.html) },
                    onRelease = { web ->
                        session.send = null
                        web.destroy()
                    },
                )
            }
        }
    }
}

private fun relayDocument(context: Context): String {
    val assets = context.assets
    val html = assets.open("extension-host/relay.html").bufferedReader().use { it.readText() }
    val js = assets.open("extension-host/relay.js").bufferedReader().use { it.readText() }
    return html.replace("/*RELAY_JS*/", js)
}

@SuppressLint("SetJavaScriptEnabled")
private fun createRelayWebView(context: Context, session: ExtensionFrameSession, frameHtml: String): WebView {
    val web = WebView(context)
    web.setBackgroundColor(Color.TRANSPARENT)
    web.settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
    }
    web.webViewClient = object : WebViewClient() {
        // Nothing in the relay or the extension frame may load from the network: the
        // bundle is inlined and every capability is a bridge method.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
    }
    WebViewCompat.addWebMessageListener(web, "adosHost", setOf(RELAY_ORIGIN)) { _, message, sourceOrigin, isMainFrame, reply ->
        if (!isMainFrame || sourceOrigin != Uri.parse(RELAY_ORIGIN)) return@addWebMessageListener
        val text = message.data ?: return@addWebMessageListener
        val kind = runCatching {
            (Json.parseToJsonElement(text) as? JsonObject)?.get("kind")?.let { (it as? JsonPrimitive)?.content }
        }.getOrNull()
        if (kind == "ready") {
            session.send = { reply.postMessage(it) }
            session.mount(frameHtml)
        } else {
            session.onRelayMessage(text)
        }
    }
    web.loadDataWithBaseURL("$RELAY_ORIGIN/", relayDocument(context), "text/html", "utf-8", null)
    return web
}
