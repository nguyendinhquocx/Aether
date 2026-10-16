package com.zhousl.aether.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zhousl.aether.platform.LocalReduceMotion
import com.zhousl.aether.data.AgentComputerStore
import com.zhousl.aether.data.AgentExecution
import com.zhousl.aether.shared.resources.*
import com.zhousl.aether.ui.theme.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AgentComputerPanel(
    sessionId: String,
    workspaceDirectory: String,
    runtimeReady: Boolean,
    browserAvailable: Boolean,
    browserActivity: Long,
    browserTitle: String,
    onClose: () -> Unit,
    browser: @Composable () -> Unit,
) {
    val sessions by AgentComputerStore.sessions.collectAsState()
    val executions = sessions[sessionId].orEmpty()
    LaunchedEffect(sessionId) { AgentComputerStore.restore(sessionId) }
    var selectedBrowser by rememberSaveable(sessionId) { mutableStateOf(false) }
    var followAgent by rememberSaveable(sessionId) { mutableStateOf(true) }
    val latestCommand = executions.lastOrNull()?.startedAt ?: 0L
    LaunchedEffect(sessionId, latestCommand, browserActivity, browserAvailable, followAgent) {
        if (followAgent) selectedBrowser = browserAvailable && browserActivity > latestCommand
    }
    val reduceMotion = LocalReduceMotion.current
    Column(Modifier.fillMaxSize().background(AetherBackground).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.workspace_agent_computer), style = MaterialTheme.typography.titleSmall, color = AetherOnSurface)
                Text(stringResource(Res.string.workspace_read_only), style = MaterialTheme.typography.labelSmall, color = AetherOnSurfaceVariant)
            }
            val followLabel = stringResource(Res.string.workspace_auto_switch)
            IconToggleButton(followAgent, { followAgent = it }, Modifier.semantics { contentDescription = followLabel }) {
                Icon(Icons.Rounded.Sync, null, tint = if (followAgent) AetherPrimary else AetherOnSurfaceVariant)
            }
            IconButton(onClose) { Icon(Icons.Rounded.Close, stringResource(Res.string.workspace_hide_computer), tint = AetherOnSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton({ selectedBrowser = false; followAgent = false }, Modifier.weight(1f).semantics { selected = !selectedBrowser }) {
                Text(stringResource(Res.string.workspace_terminal), color = if (!selectedBrowser) AetherPrimary else AetherOnSurfaceVariant)
            }
            TextButton({ selectedBrowser = true; followAgent = false }, Modifier.weight(1f).semantics { selected = selectedBrowser }) {
                Text(browserTitle, color = if (selectedBrowser) AetherPrimary else AetherOnSurfaceVariant)
            }
        }
        HorizontalDivider(color = AetherOutlineSoft)
        AnimatedContent(
            targetState = selectedBrowser,
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            transitionSpec = {
                if (reduceMotion) fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                else {
                    val direction = if (targetState) 1 else -1
                    (slideInHorizontally(tween(240)) { it * direction } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(240)) { -it * direction } + fadeOut(tween(240)))
                }
            }, label = "agentComputer",
        ) { showBrowser ->
            if (showBrowser) {
                if (browserAvailable) browser() else Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.workspace_browser_waiting), color = AetherOnSurfaceVariant)
                }
            } else AgentTerminal(executions, workspaceDirectory, runtimeReady)
        }
    }
}

@Composable
private fun AgentTerminal(executions: List<AgentExecution>, directory: String, runtimeReady: Boolean) {
    val scroll = rememberLazyListState()
    var followOutput by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                val last = scroll.layoutInfo.visibleItemsInfo.lastOrNull()
                followOutput = last == null || last.index >= scroll.layoutInfo.totalItemsCount - 1
            }
        }
    }
        LaunchedEffect(executions, followOutput) {
        if (followOutput && executions.isNotEmpty()) scroll.scrollToItem(executions.size + 1, Int.MAX_VALUE)
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF111317))) {
        SelectionContainer {
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item(key = "ready") {
                    if (!runtimeReady) Text(stringResource(Res.string.workspace_runtime_not_ready), color = Color(0xFFB9B4AA), style = MaterialTheme.typography.bodySmall)
                }
                items(executions, key = { it.id }) { execution ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${execution.cwd} $ ${execution.command}", color = Color(0xFF9DD8BD), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        if (execution.trimmed) Text(stringResource(Res.string.workspace_scrollback_limit), color = Color(0xFFB9B4AA), style = MaterialTheme.typography.labelSmall)
                        if (execution.output.isNotEmpty()) Text(execution.output, color = Color(0xFFE5E7EB), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        if (execution.completedAt != null) Text(
                            if (execution.error.isNotEmpty() || execution.exitCode == null) stringResource(Res.string.workspace_execution_interrupted) else stringResource(Res.string.workspace_exit_code, execution.exitCode ?: 0),
                            color = if (execution.error.isNotEmpty() || execution.exitCode != 0) Color(0xFFFFADAD) else Color(0xFF9AA2AF),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                item(key = "prompt") { if (executions.lastOrNull()?.let { it.completedAt != null } != false) Text("$directory $ ▍", color = Color(0xFF9DD8BD), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (!followOutput) TextButton(
            onClick = { followOutput = true },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().background(Color(0xFF24282D)),
        ) { Text(stringResource(Res.string.workspace_latest_output), color = Color(0xFFD5C8FF)) }
    }
}
