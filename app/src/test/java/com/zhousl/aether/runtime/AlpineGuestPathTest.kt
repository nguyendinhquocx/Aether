package com.zhousl.aether.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class AlpineGuestPathTest {
    @Test
    fun workspaceGuestPathsResolveToBoundHostWorkspace() {
        val runtimeRoot = Files.createTempDirectory("aether-alpine-path-test").toFile()
        try {
            val rootfs = runtimeRoot.resolve("rootfs").apply { mkdirs() }
            val workspace = runtimeRoot.resolve("workspace").apply { mkdirs() }

            assertEquals(
                workspace.canonicalFile,
                resolveAlpineManagedHostFile(rootfs, workspace, "/workspace", "/workspace"),
            )
            assertEquals(
                workspace.resolve("notes/today.md").canonicalFile,
                resolveAlpineManagedHostFile(rootfs, workspace, "/workspace", "/workspace/notes/today.md"),
            )
            assertEquals(
                rootfs.resolve("root/.profile").canonicalFile,
                resolveAlpineManagedHostFile(rootfs, workspace, "/workspace", "/root/.profile"),
            )
        } finally {
            runtimeRoot.deleteRecursively()
        }
    }
}
