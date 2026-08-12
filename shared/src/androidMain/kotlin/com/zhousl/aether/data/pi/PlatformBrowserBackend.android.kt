package com.zhousl.aether.data.pi

import com.zhousl.aether.runtime.MultiplatformLocalRuntime

internal actual fun createPlatformBrowserBackend(
    runtime: MultiplatformLocalRuntime,
): SharedBrowserBackend? = null
