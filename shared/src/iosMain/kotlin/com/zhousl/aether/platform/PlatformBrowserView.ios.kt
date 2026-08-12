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

@Composable
internal actual fun PlatformBrowserView(
    manager: SharedChromeManager,
    modifier: Modifier,
) {
    val backend = manager.platformBackend as? IosBrowserBackend ?: return
    val activeTabId by backend.activeTabId.collectAsState()
    key(activeTabId) {
        UIKitView(
            modifier = modifier,
            factory = { backend.webView },
        )
    }
}
