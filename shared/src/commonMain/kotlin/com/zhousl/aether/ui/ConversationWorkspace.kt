package com.zhousl.aether.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhousl.aether.data.AgentComputerStore
import com.zhousl.aether.platform.LocalReduceMotion
import com.zhousl.aether.shared.resources.Res
import com.zhousl.aether.shared.resources.common_close
import com.zhousl.aether.shared.resources.common_new_chat
import com.zhousl.aether.shared.resources.settings_title
import com.zhousl.aether.shared.resources.workspace_agent_computer
import com.zhousl.aether.shared.resources.workspace_auto_switch
import com.zhousl.aether.shared.resources.workspace_context
import com.zhousl.aether.shared.resources.workspace_execution_interrupted
import com.zhousl.aether.shared.resources.workspace_exit_code
import com.zhousl.aether.shared.resources.workspace_files
import com.zhousl.aether.shared.resources.workspace_latest_output
import com.zhousl.aether.shared.resources.workspace_read_only
import com.zhousl.aether.shared.resources.workspace_runtime_not_ready
import com.zhousl.aether.shared.resources.workspace_scheduled_tasks
import com.zhousl.aether.shared.resources.workspace_scrollback_limit
import com.zhousl.aether.shared.resources.workspace_skills
import com.zhousl.aether.shared.resources.workspace_terminal
import com.zhousl.aether.shared.resources.workspace_unknown
import com.zhousl.aether.ui.theme.AetherBackground
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherOutlineSoft
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherScrim
import com.zhousl.aether.ui.theme.AetherSidebarBackground
import com.zhousl.aether.ui.theme.AetherSurface
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.safeDrawing

enum class WorkspaceAction {
    NewChat, ScheduledTasks, Skills, Files, Terminal, Settings
}

@Composable
fun WorkspaceActionRail(
    onAction: (WorkspaceAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(52.dp)
            .background(AetherSidebarBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WorkspaceAction.entries.filter { it != WorkspaceAction.Settings }.forEach { action ->
            IconButton(
                onClick = { onAction(action) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = workspaceActionIcon(action),
                    contentDescription = workspaceActionLabel(action),
                    modifier = Modifier.size(20.dp),
                    tint = AetherOnSurface,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { onAction(WorkspaceAction.Settings) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = LucideIcons.Settings,
                contentDescription = workspaceActionLabel(WorkspaceAction.Settings),
                modifier = Modifier.size(20.dp),
                tint = AetherOnSurface,
            )
        }
    }
}

@Composable
fun workspaceActionLabel(action: WorkspaceAction): String = stringResource(
    when (action) {
        WorkspaceAction.NewChat -> Res.string.common_new_chat
        WorkspaceAction.ScheduledTasks -> Res.string.workspace_scheduled_tasks
        WorkspaceAction.Skills -> Res.string.workspace_skills
        WorkspaceAction.Files -> Res.string.workspace_files
        WorkspaceAction.Terminal -> Res.string.workspace_terminal
        WorkspaceAction.Settings -> Res.string.settings_title
    }
)

private fun workspaceActionIcon(action: WorkspaceAction): ImageVector = when (action) {
    WorkspaceAction.NewChat -> LucideIcons.SquarePen
    WorkspaceAction.ScheduledTasks -> LucideIcons.Clock8
    WorkspaceAction.Skills -> LucideIcons.Blocks
    WorkspaceAction.Files -> Icons.Rounded.FolderOpen
    WorkspaceAction.Terminal -> Icons.Rounded.Terminal
    WorkspaceAction.Settings -> LucideIcons.Settings
}

@Composable
fun ConversationWorkspace(
    expanded: Boolean,
    computerVisible: Boolean,
    drawerState: DrawerState,
    drawer: @Composable () -> Unit,
    computer: @Composable () -> Unit,
    rail: @Composable () -> Unit = {},
    hingeWidthDp: Float = 0f,
    railOnLeft: Boolean = false,
    navigationEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    val stableContent = remember { movableContentOf { currentContent() } }
    val currentDrawer by rememberUpdatedState(drawer)
    val stableDrawer = remember { movableContentOf { currentDrawer() } }
    val showList = expanded && !computerVisible && navigationEnabled

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = navigationEnabled && !showList,
        scrimColor = AetherScrim,
        drawerContent = {
            if (navigationEnabled && !showList) {
                stableDrawer()
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().background(AetherBackground),
            verticalAlignment = Alignment.Top,
        ) {
            if (railOnLeft) rail()
            if (showList) {
                stableDrawer()
                VerticalDivider(color = AetherOutlineSoft)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                stableContent()
            }
            if (computerVisible) {
                Spacer(
                    Modifier
                        .width(maxOf(1f, hingeWidthDp).dp)
                        .fillMaxHeight()
                        .background(AetherOutlineSoft)
                )
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    computer()
                }
            }
            if (!railOnLeft) rail()
        }
    }
}

@Composable
fun WorkspaceContextUsage(
    used: Long?,
    total: Int?,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(Res.string.workspace_unknown)
    fun format(value: Long?): String = when {
        value == null -> unknown
        value >= 1000L -> "${value / 1000}k"
        else -> value.toString()
    }
    val label = "${format(used)} / ${format(total?.toLong())}"
    val fraction = if (used != null && total != null && total > 0) {
        (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else null
    val description = stringResource(Res.string.workspace_context)

    Column(
        modifier = modifier
            .widthIn(max = 150.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = label
                if (fraction != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                }
            },
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction ?: 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = AetherPrimary,
            trackColor = AetherOutlineSoft,
        )
    }
}

