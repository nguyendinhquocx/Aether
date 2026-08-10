package com.zhousl.aether.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.zhousl.aether.runtime.IosAlpineRuntime
import com.zhousl.aether.runtime.MultiplatformLocalRuntime
import com.zhousl.aether.runtime.NativeTerminalViewListener
import com.zhousl.aether.runtime.RuntimeProcess
import com.zhousl.aether.runtime.RuntimeProcessSignal
import com.zhousl.aether.runtime.RuntimeProcessSpec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import platform.UIKit.UIView

actual val platformNativeTerminalAvailable: Boolean = true

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun PlatformTerminalSurface(
    runtime: MultiplatformLocalRuntime,
    interruptSignal: Int,
    inputEvent: PlatformTerminalInputEvent?,
    darkTheme: Boolean,
    onTitleChanged: (String) -> Unit,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val iosRuntime = runtime as IosAlpineRuntime
    val bridge = remember(iosRuntime) { IosNativeTerminalBridge(iosRuntime, onTitleChanged) }

    LaunchedEffect(iosRuntime, bridge) {
        try {
            iosRuntime.initialize()
            val process = iosRuntime.startProcess(
                RuntimeProcessSpec(
                    executable = "/bin/sh",
                    arguments = listOf("-l"),
                    environment = mapOf(
                        "HOME" to iosRuntime.homeDirectory,
                        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                        "LANG" to "C.UTF-8",
                        "CHARSET" to "UTF-8",
                        "AETHER_RUNTIME" to "alpine",
                        "AETHER_HOST_WORKSPACE" to iosRuntime.workspaceRoot,
                        "PS1" to "aether-alpine:\\w# ",
                        "TERM" to "xterm-256color",
                        "COLORTERM" to "truecolor",
                    ),
                    workingDirectory = iosRuntime.homeDirectory,
                    interactiveTerminal = true,
                )
            )
            bridge.attach(process)
            onReady()
            coroutineScope {
                launch { process.stdout.collect(bridge::write) }
                launch { process.stderr.collect(bridge::write) }
                process.awaitExit()
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            onError(failure.message ?: "Unable to start Alpine terminal.")
        }
    }
    DisposableEffect(bridge) {
        onDispose { bridge.close() }
    }
    LaunchedEffect(interruptSignal) {
        if (interruptSignal > 0) bridge.interrupt()
    }
    LaunchedEffect(inputEvent?.sequence) {
        inputEvent?.let { event ->
            if (event.text.isNotEmpty()) bridge.send(event.text)
            event.key?.let { key -> bridge.sendKey(key, event.controlDown, event.altDown) }
            if (event.requestFocus) bridge.focus()
        }
    }
    UIKitView(
        modifier = modifier,
        factory = { bridge.view },
        update = {
            bridge.setDarkTheme(darkTheme)
            bridge.focus()
        },
    )
}

private class IosNativeTerminalBridge(
    private val runtime: IosAlpineRuntime,
    onTitleChanged: (String) -> Unit,
) {
    private val scope = MainScope()
    private var process: RuntimeProcess? = null
    private val pending = mutableListOf<ByteArray>()
    private val listener = object : NativeTerminalViewListener {
        override fun onInput(bytes: ByteArray) {
            val target = process ?: return
            scope.launch { target.writeStdin(bytes) }
        }

        override fun onResize(columns: Int, rows: Int) {
            val target = process ?: return
            scope.launch { target.resize(columns, rows) }
        }

        override fun onTitleChanged(title: String) = onTitleChanged(title)
    }
    private val nativeView = runtime.createTerminalView(listener)
    val view: UIView = nativeView as UIView

    fun attach(process: RuntimeProcess) {
        this.process = process
        pending.toList().also { pending.clear() }.forEach(::write)
        focus()
    }

    fun write(bytes: ByteArray) {
        if (process == null) {
            pending += bytes.copyOf()
        } else {
            runtime.updateTerminalView(nativeView, bytes)
        }
    }

    fun focus() = runtime.focusTerminalView(nativeView)

    fun setDarkTheme(darkTheme: Boolean) =
        runtime.setTerminalDarkTheme(nativeView, darkTheme)

    suspend fun interrupt() {
        process?.signal(RuntimeProcessSignal.Interrupt)
    }

    suspend fun send(text: String) {
        process?.writeStdin(text.encodeToByteArray())
    }

    fun sendKey(key: PlatformTerminalKey, controlDown: Boolean, altDown: Boolean) {
        runtime.sendTerminalKey(nativeView, key.name, controlDown, altDown)
    }

    fun close() {
        runtime.destroyTerminalView(nativeView)
        val running = process
        process = null
        if (running == null) {
            scope.cancel()
        } else {
            scope.launch {
                try {
                    running.signal(RuntimeProcessSignal.Terminate)
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
