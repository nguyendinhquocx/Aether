package com.zhousl.aether.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zhousl.aether.data.pi.SharedChromeManager

@Composable
internal expect fun PlatformBrowserView(
    manager: SharedChromeManager,
    modifier: Modifier = Modifier,
)
