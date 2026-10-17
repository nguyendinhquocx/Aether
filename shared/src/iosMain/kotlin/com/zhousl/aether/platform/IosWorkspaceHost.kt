package com.zhousl.aether.platform

import com.zhousl.aether.data.AgentExecution
import com.zhousl.aether.data.pi.SharedBrowserDisplayState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class IosWorkspaceFold(val active: Boolean = false, val x: Float = 0f, val width: Float = 0f)
data class IosScheduledPrompt(val taskId: String, val title: String, val prompt: String)

interface IosWorkspaceListener {
    fun onChatVisibilityChanged(visible: Boolean)
    fun onDuoCapabilityChanged(enabled: Boolean)
    fun onComputerSnapshotChanged(snapshotJson: String)
}

/** Main-thread bridge for scene traits and native navigation, never owns an execution. */
object IosWorkspaceHost {
    private val duo = MutableStateFlow(false)
    val isDuo = duo.asStateFlow()
    val duoEnabled: Boolean get() = duo.value
    /** Only native iOS 27.1 reserved-region capability detection may enable this. */
    fun confirmDuoCapability() {
        if (duo.value) return
        com.zhousl.aether.data.AgentComputerStore.configure(platform.Foundation.NSHomeDirectory() + "/Library/Application Support/agent-computer")
        duo.value = true
        listener?.onDuoCapabilityChanged(true)
    }
    private var listener: IosWorkspaceListener? = null
    private var computerSnapshot = "{}"
    private var chatVisible = false
    private val width = MutableStateFlow(false)
    val regularWidth = width.asStateFlow()
    private val actionChannel = Channel<String>(Channel.BUFFERED)
    val actions = actionChannel.receiveAsFlow()
    private val foldState = MutableStateFlow(IosWorkspaceFold())
    val fold = foldState.asStateFlow()
    fun updateFold(active: Boolean, x: Float, width: Float) { foldState.value = IosWorkspaceFold(active, x, width) }
    private val promptChannel = Channel<IosScheduledPrompt>(Channel.BUFFERED)
    val scheduledPrompts = promptChannel.receiveAsFlow()
    fun setListener(listener: IosWorkspaceListener?) { this.listener = listener; listener?.onDuoCapabilityChanged(duo.value); listener?.onChatVisibilityChanged(chatVisible); listener?.onComputerSnapshotChanged(computerSnapshot) }
    fun showChat(visible: Boolean) {
        if (!duo.value || chatVisible == visible) return
        chatVisible = visible
        listener?.onChatVisibilityChanged(visible)
    }
    fun publishComputerSnapshot(snapshotJson: String) {
        if (!duo.value || computerSnapshot == snapshotJson) return
        computerSnapshot = snapshotJson
        listener?.onComputerSnapshotChanged(snapshotJson)
    }
    fun updateScene(regularWidth: Boolean) { width.value = regularWidth }
    fun perform(action: String) { if (duo.value) actionChannel.trySend(action) }
    fun runScheduledPrompt(taskId: String, title: String, prompt: String): Boolean = duo.value && promptChannel.trySend(IosScheduledPrompt(taskId, title, prompt)).isSuccess
}

/** Transport only: the Duo observer's presentation and fold eligibility belong to SwiftUI/UIKit. */
internal fun iosComputerSnapshot(
    sessionId: String,
    directory: String,
    runtimeReady: Boolean,
    executions: List<AgentExecution>,
    browser: SharedBrowserDisplayState,
): String = buildJsonObject {
    put("sessionId", sessionId)
    put("directory", directory)
    put("runtimeReady", runtimeReady)
    put("executions", Json.parseToJsonElement(Json.encodeToString(executions)))
    put("browserSessionId", browser.sessionId)
    put("browserActive", browser.isActive && browser.sessionId == sessionId)
    put("browserReady", browser.screenshotBase64.isNotBlank() && browser.sessionId == sessionId)
    put("browserUpdatedAt", browser.lastUpdatedMillis)
    put("browserURL", browser.url.takeIf { browser.sessionId == sessionId }.orEmpty())
}.toString()
