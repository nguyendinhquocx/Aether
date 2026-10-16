@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.zhousl.aether.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.zhousl.aether.data.pi.AgentBrowserObserverUrl
import com.zhousl.aether.data.pi.SharedChromeManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@Composable
internal fun PlatformBrowserView(manager: SharedChromeManager, modifier: Modifier, readOnly: Boolean = false) {
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView(platform.CoreGraphics.CGRectMake(0.0, 0.0, 1.0, 1.0), WKWebViewConfiguration()).apply {
                userInteractionEnabled = !readOnly
                loadRequest(NSURLRequest(NSURL(string = if (readOnly) AgentBrowserObserverUrl else manager.viewerUrl)))
            }
        },
        onRelease = { it.stopLoading(); it.loadHTMLString("", baseURL = null) },
    )
}
