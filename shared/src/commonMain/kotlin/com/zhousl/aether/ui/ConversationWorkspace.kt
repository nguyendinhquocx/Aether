package com.zhousl.aether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zhousl.aether.shared.resources.*
import com.zhousl.aether.ui.theme.*
import org.jetbrains.compose.resources.stringResource

enum class WorkspaceAction { NewChat, ScheduledTasks, Skills, Files, Terminal, Settings }

@Composable
fun WorkspaceActionRail(onAction: (WorkspaceAction) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(52.dp).background(AetherSidebarBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            WorkspaceAction.entries.filter { it != WorkspaceAction.Settings }.forEach { action ->
                WorkspaceRailButton(action) { onAction(action) }
            }
        }
        WorkspaceRailButton(WorkspaceAction.Settings) { onAction(WorkspaceAction.Settings) }
    }
}

@Composable
private fun WorkspaceRailButton(action: WorkspaceAction, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp)) {
        Icon(workspaceActionIcon(action), workspaceActionLabel(action), Modifier.size(20.dp), tint = AetherOnSurface)
    }
}

@Composable
fun workspaceActionLabel(action: WorkspaceAction): String = stringResource(when (action) {
    WorkspaceAction.NewChat -> Res.string.common_new_chat
    WorkspaceAction.ScheduledTasks -> Res.string.workspace_scheduled_tasks
    WorkspaceAction.Skills -> Res.string.workspace_skills
    WorkspaceAction.Files -> Res.string.workspace_files
    WorkspaceAction.Terminal -> Res.string.workspace_terminal
    WorkspaceAction.Settings -> Res.string.settings_title
})

private fun workspaceActionIcon(action: WorkspaceAction): ImageVector = when (action) {
    WorkspaceAction.NewChat -> LucideIcons.SquarePen
    WorkspaceAction.ScheduledTasks -> LucideIcons.Clock8
    WorkspaceAction.Skills -> LucideIcons.Blocks
    WorkspaceAction.Files -> Icons.Rounded.FolderOpen
    WorkspaceAction.Terminal -> Icons.Rounded.Terminal
    WorkspaceAction.Settings -> LucideIcons.Settings
}

/** Duo chat navigation only; the native host owns the half-folded computer pane. */
@Composable
fun ConversationWorkspace(
    expanded: Boolean,
    drawerState: DrawerState,
    drawer: @Composable () -> Unit,
    rail: @Composable () -> Unit = {},
    railOnLeft: Boolean = false,
    navigationEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val direction by rememberUpdatedState(LocalLayoutDirection.current)
    val currentContent by rememberUpdatedState(content)
    val stableContent = remember { movableContentOf {
        CompositionLocalProvider(LocalLayoutDirection provides direction) { currentContent() }
    } }
    val currentDrawer by rememberUpdatedState(drawer)
    val stableDrawer = remember { movableContentOf {
        CompositionLocalProvider(LocalLayoutDirection provides direction) { currentDrawer() }
    } }
    val showList = expanded && navigationEnabled
    // A drawer with empty content has coincident open/closed anchors. Keeping it
    // mounted beside a permanent list can restore it as Open on the next fold.
    LaunchedEffect(showList) {
        drawerState.snapTo(DrawerValue.Closed)
    }
    val body: @Composable () -> Unit = {
        // The control edge follows window geometry, independently of the text direction.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxSize().background(AetherBackground)) {
                if (railOnLeft) rail()
                if (showList) { stableDrawer(); VerticalDivider(color = AetherOutlineSoft) }
                Box(Modifier.weight(1f).fillMaxHeight()) { stableContent() }
                if (!railOnLeft) rail()
            }
        }
    }
    if (showList) {
        body()
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = navigationEnabled,
            scrimColor = AetherScrim,
            drawerContent = { if (navigationEnabled) stableDrawer() },
            content = body,
        )
    }
}

@Composable
fun WorkspaceContextUsage(used: Long?, total: Int?, modifier: Modifier = Modifier) {
    val unknown = stringResource(Res.string.workspace_unknown)
    fun format(value: Long?): String = when {
        value == null -> unknown
        value >= 1000L -> "${value / 1000}k"
        else -> value.toString()
    }
    val label = "${format(used)} / ${format(total?.takeIf { it > 0 }?.toLong())}"
    val fraction = if (used != null && total != null && total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else null
    val description = stringResource(Res.string.workspace_context)
    Column(modifier.widthIn(max = 150.dp).semantics(mergeDescendants = true) {
        contentDescription = description
        stateDescription = label
        if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AetherOnSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction ?: 0f }, modifier = Modifier.fillMaxWidth().height(3.dp),
            color = AetherPrimary, trackColor = AetherOutlineSoft,
        )
    }
}
