package com.zhousl.aether.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhousl.aether.data.pi.PiKernelBridge
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlpineRuntimeInstrumentedTest {
    @Test
    fun fileManagerListsFilesCreatedInGuestWorkspace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alpine = AlpineRuntime(context)
        val setup = alpine.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val fileName = "file-manager-${System.nanoTime()}.txt"
        val guestPath = "${alpine.workspaceRoot}/$fileName"
        val fixture = JSONObject(
            alpine.executeCommand(
                command = "printf 'visible from file manager' > '$guestPath'",
                workingDirectory = alpine.workspaceRoot,
                awaitTimeoutMillis = 30_000L,
            )
        )
        assertTrue(fixture.optString("errmsg"), fixture.optBoolean("ok"))

        try {
            val entries = AndroidAlpineFileManagerRuntime(alpine).listDirectory(alpine.workspaceRoot)
            assertTrue(entries.any { it.name == fileName && it.path == guestPath })
        } finally {
            alpine.executeCommand("rm -f '$guestPath'", alpine.workspaceRoot, 30_000L)
        }
    }

    @Test
    fun fileManagerExportsBinaryFilesAndSymbolicLinkTargets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alpine = AlpineRuntime(context)
        val setup = alpine.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val directory = "/tmp/aether-export-${System.nanoTime()}"
        val binaryPath = "$directory/payload.bin"
        val linkPath = "$directory/payload-link"
        val expected = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xfe.toByte(), 0xff.toByte())
        val fixture = JSONObject(
            alpine.executeCommand(
                command = "mkdir -p '$directory' && printf '\\000\\001\\177\\200\\376\\377' > '$binaryPath' && ln -s '$binaryPath' '$linkPath'",
                workingDirectory = alpine.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )
        assertTrue(fixture.optString("errmsg"), fixture.optBoolean("ok"))

        try {
            val runtime = AndroidAlpineFileManagerRuntime(alpine)
            listOf(binaryPath, linkPath).forEach { guestPath ->
                val output = ByteArrayOutputStream()
                runtime.exportFile(guestPath, output)
                assertArrayEquals(expected, output.toByteArray())
            }
        } finally {
            alpine.executeCommand("rm -rf '$directory'", alpine.homeDirectory, 30_000L)
        }
    }

    @Test
    fun alpineRuntimeStartsShellFromAppProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AlpineRuntime(context)

        val setup = runtime.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val result = JSONObject(
            runtime.executeCommand(
                command = "echo AETHER_ALPINE_APP_PROCESS_OK; cat /etc/alpine-release; uname -m; pwd",
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )

        assertTrue(result.optString("errmsg"), result.optBoolean("ok"))
        val stdout = result.optString("stdout")
        assertTrue(stdout, stdout.contains("AETHER_ALPINE_APP_PROCESS_OK"))
        assertTrue(stdout, stdout.contains("aarch64"))
        assertTrue(stdout, stdout.contains("/root"))
    }

    @Test
    fun pythonPackageProfileInstallsAndRuns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AlpineRuntime(context)

        val setup = runtime.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val profile = runtime.installPackageProfile("python")
        assertEquals(profile.detail, LocalRuntimeIssue.Ready, profile.issue)

        val result = JSONObject(
            runtime.executeCommand(
                command = "python3 --version && python3 - <<'PY'\nprint('AETHER_ALPINE_PYTHON_OK')\nPY",
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )

        assertTrue(result.optString("errmsg"), result.optBoolean("ok"))
        val stdout = result.optString("stdout")
        assertTrue(stdout, stdout.contains("Python"))
        assertTrue(stdout, stdout.contains("AETHER_ALPINE_PYTHON_OK"))
    }

    @Test
    fun piBridgeStartsWithSupportedNodeAndReportsPinnedVersions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = PiKernelBridge(AlpineRuntime(context))

        try {
            val ping = bridge.ping()
            assertEquals("2.0.0-alpha.0", ping.getString("bridge_version"))
            assertEquals("0.83.0", ping.getString("pi_ai_version"))
            assertEquals("0.83.0", ping.getString("pi_agent_core_version"))
            val nodeVersion = ping.getString("node_version").removePrefix("v")
            val major = nodeVersion.substringBefore('.').toInt()
            val minor = nodeVersion.substringAfter('.').substringBefore('.').toInt()
            assertTrue(nodeVersion, major > 22 || major == 22 && minor >= 19)
        } finally {
            bridge.stop()
        }
    }
}
