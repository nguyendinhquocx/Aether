package com.zhousl.aether.platform

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

interface IosWorkspaceListener { fun onChatVisibilityChanged(visible: Boolean) }

/** Main-thread bridge for scene traits and native navigation, never owns an execution. */
object IosWorkspaceHost {
    private var listener: IosWorkspaceListener? = null
    private var chatVisible = false
    private val width = MutableStateFlow(false)
    val regularWidth = width.asStateFlow()
    private val actionChannel = Channel<String>(Channel.BUFFERED)
    val actions = actionChannel.receiveAsFlow()
    private val promptChannel = Channel<String>(Channel.BUFFERED)
    val scheduledPrompts = promptChannel.receiveAsFlow()
    fun setListener(listener: IosWorkspaceListener?) { this.listener = listener; listener?.onChatVisibilityChanged(chatVisible) }
    fun showChat(visible: Boolean) {
        if (chatVisible == visible) return
        chatVisible = visible
        listener?.onChatVisibilityChanged(visible)
    }
    fun updateScene(regularWidth: Boolean) { width.value = regularWidth }
    fun perform(action: String) { actionChannel.trySend(action) }
    fun runScheduledPrompt(prompt: String): Boolean = promptChannel.trySend(prompt).isSuccess
}
