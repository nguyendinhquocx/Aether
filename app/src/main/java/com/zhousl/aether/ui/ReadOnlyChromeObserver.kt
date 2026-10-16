package com.zhousl.aether.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** A second RFB viewer observes the existing display; it cannot control or resize it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReadOnlyChromeObserver() {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isFocusable = false
                isFocusableInTouchMode = false
                loadUrl(com.zhousl.aether.data.pi.AgentBrowserObserverUrl)
            }
        },
        onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() },
    )
}
