package com.zhousl.aether.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zhousl.aether.data.pi.SharedChromeManager

@Composable
internal actual fun PlatformBrowserView(
    manager: SharedChromeManager,
    modifier: Modifier,
) {
    PlatformWebView(url = manager.viewerUrl, modifier = modifier)
}
