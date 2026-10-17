@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.zhousl.aether.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.zhousl.aether.data.pi.IosBrowserBackend
import com.zhousl.aether.data.pi.SharedChromeManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@Composable
internal fun PlatformBrowserView(manager: SharedChromeManager, modifier: Modifier) {
    if (!IosWorkspaceHost.isDuo.value) {
        val backend = manager.platformBackend as? IosBrowserBackend ?: return
        val activeTabId by backend.activeTabId.collectAsState()
        key(activeTabId) {
            UIKitView(
                modifier = modifier,
                factory = { backend.webView },
            )
        }
        return
    }
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView(platform.CoreGraphics.CGRectMake(0.0, 0.0, 1.0, 1.0), WKWebViewConfiguration()).apply {
                loadRequest(NSURLRequest(NSURL(string = manager.viewerUrl)))
            }
        },
        onRelease = { it.stopLoading(); it.loadHTMLString("", baseURL = null) },
    )
}
