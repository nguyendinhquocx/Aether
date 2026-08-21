package com.zhousl.aether.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.statusBarsPadding
import com.zhousl.aether.runtime.MultiplatformLocalRuntime
import com.zhousl.aether.runtime.RuntimeProcessSpec
import com.zhousl.aether.shared.resources.Res
import com.zhousl.aether.shared.resources.file_manager_create_file
import com.zhousl.aether.shared.resources.file_manager_create_folder
import com.zhousl.aether.shared.resources.file_manager_delete
import com.zhousl.aether.shared.resources.file_manager_empty
import com.zhousl.aether.shared.resources.file_manager_new_name
import com.zhousl.aether.shared.resources.file_manager_open
import com.zhousl.aether.shared.resources.file_manager_rename
import com.zhousl.aether.shared.resources.file_manager_title
import com.zhousl.aether.shared.resources.file_manager_view_grid
import com.zhousl.aether.shared.resources.file_manager_view_list
import com.zhousl.aether.shared.resources.common_cancel
import com.zhousl.aether.shared.resources.common_confirm
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherSettingsBackground
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private data class FileManagerEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

private enum class FileManagerDialog { None, NewFolder, NewFile, Rename, Delete }

class SharedFileManagerState internal constructor(
    private val root: String = "/",
) {
    var path by mutableStateOf(root)
        private set

    fun navigateTo(next: String) {
        path = normalizeFileManagerPath(next)
    }

    fun navigateBack(): Boolean {
        if (path == root) return false
        path = path.substringBeforeLast('/', "").ifBlank { root }
        return true
    }
}

@Composable
fun rememberSharedFileManagerState(): SharedFileManagerState = remember { SharedFileManagerState() }

@Composable
fun SharedFileManagerScreen(
    runtime: MultiplatformLocalRuntime,
    onBack: () -> Unit,
    state: SharedFileManagerState = rememberSharedFileManagerState(),
) {
    val scope = rememberCoroutineScope()
    val fileManagerRoot = "/"
    val path = state.path
    var entries by remember { mutableStateOf<List<FileManagerEntry>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FileManagerEntry?>(null) }
    var dialog by remember { mutableStateOf(FileManagerDialog.None) }
    var draftName by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var imagePreview by remember { mutableStateOf<ByteArray?>(null) }
    var previewPath by remember { mutableStateOf<String?>(null) }

    fun refresh(targetPath: String = path) {
        busy = true
        if (targetPath == path) entries = emptyList()
        scope.launch {
            runCatching { listFileManagerDirectory(runtime, targetPath) }
                .onSuccess {
                    if (path == targetPath) entries = it
                    error = ""
                }
                .onFailure { error = it.message.orEmpty() }
            if (path == targetPath) busy = false
        }
    }

    fun goTo(next: String) {
        state.navigateTo(next)
        selected = null
        filter = ""
    }

    LaunchedEffect(path) { refresh(path) }

    val visibleEntries = entries.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
    val parent = path.substringBeforeLast('/', "").ifBlank { fileManagerRoot }

    Box(Modifier.fillMaxSize().background(AetherSettingsBackground)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            FileManagerTopBar(
                path = path,
                showSearch = showSearch,
                filter = filter,
                grid = grid,
                onBack = onBack,
                onUp = { if (path != fileManagerRoot) goTo(parent) },
                isRoot = path == fileManagerRoot,
                onRefresh = ::refresh,
                onSearchToggle = { showSearch = !showSearch; if (!showSearch) filter = "" },
                onFilterChanged = { filter = it },
                onGridChanged = { grid = it },
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = AetherPrimary)
            } else if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            } else if (visibleEntries.isEmpty()) {
                Text(stringResource(Res.string.file_manager_empty), color = AetherOnSurfaceVariant, modifier = Modifier.padding(32.dp))
            } else AnimatedContent(
                targetState = path,
                transitionSpec = {
                    val forward = targetState.trim('/').split('/').count { it.isNotBlank() } >
                        initialState.trim('/').split('/').count { it.isNotBlank() }
                    slideInHorizontally { if (forward) it else -it } togetherWith
                        slideOutHorizontally { if (forward) -it else it }
                },
                label = "file_directory_transition",
            ) {
            if (grid) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleEntries, key = { it.path }) { entry ->
                        FileManagerGridEntry(entry, selected == entry, onClick = {
                            if (entry.isDirectory) goTo(entry.path) else selected = entry
                        })
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visibleEntries, key = { it.path }) { entry ->
                        FileManagerListEntry(entry, selected == entry, onClick = {
                            if (entry.isDirectory) goTo(entry.path) else selected = entry
                        })
                    }
                }
            }
            }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = { draftName = ""; dialog = FileManagerDialog.NewFile },
                modifier = Modifier.size(52.dp).clip(CircleShape).background(AetherSurfaceHigh),
            ) { Icon(Icons.Rounded.Description, stringResource(Res.string.file_manager_create_file), tint = AetherOnSurface) }
            IconButton(
                onClick = { draftName = ""; dialog = FileManagerDialog.NewFolder },
                modifier = Modifier.size(58.dp).clip(CircleShape).background(AetherPrimary),
            ) { Icon(Icons.Rounded.CreateNewFolder, stringResource(Res.string.file_manager_create_folder), tint = Color.White) }
        }
    }

    if (selected != null) {
        FileManagerEntryMenu(
            entry = selected!!,
            onDismiss = { selected = null },
            onOpen = {
                val entry = selected ?: return@FileManagerEntryMenu
                selected = null
                if (entry.isDirectory) goTo(entry.path) else scope.launch {
                    previewPath = entry.path
                    val bytes = runCatching { runtime.fileSystem.read(entry.path, 8 * 1024 * 1024) }.getOrElse { error = it.message.orEmpty(); return@launch }
                    if (entry.name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")) imagePreview = bytes
                    else preview = runCatching { bytes.decodeToString() }.getOrElse { it.message.orEmpty() }
                }
            },
            onRename = { draftName = selected!!.name; dialog = FileManagerDialog.Rename },
            onDelete = { dialog = FileManagerDialog.Delete },
        )
    }

    if (dialog != FileManagerDialog.None) {
        val title = when (dialog) {
            FileManagerDialog.NewFolder -> stringResource(Res.string.file_manager_create_folder)
            FileManagerDialog.NewFile -> stringResource(Res.string.file_manager_create_file)
            FileManagerDialog.Rename -> stringResource(Res.string.file_manager_rename)
            FileManagerDialog.Delete, FileManagerDialog.None -> stringResource(Res.string.file_manager_delete)
        }
        AlertDialog(
            onDismissRequest = { dialog = FileManagerDialog.None },
            title = { Text(title) },
            text = {
                if (dialog == FileManagerDialog.Delete) Text("Delete ${selected?.name.orEmpty()}?")
                else OutlinedTextField(value = draftName, onValueChange = { draftName = it },
                    label = { Text(stringResource(Res.string.file_manager_new_name)) }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    val currentDialog = dialog
                    val name = draftName.trim()
                    val current = selected
                    dialog = FileManagerDialog.None
                    scope.launch {
                        runCatching {
                            when (currentDialog) {
                                FileManagerDialog.NewFolder -> runtime.fileSystem.createDirectories(joinFileManagerPath(path, name))
                                FileManagerDialog.NewFile -> runtime.fileSystem.write(joinFileManagerPath(path, name), ByteArray(0))
                                FileManagerDialog.Rename -> {
                                    require(current != null && name.isNotBlank())
                                    runFileManagerCommand(runtime, "mv -- ${shellQuote(current.path)} ${shellQuote(joinFileManagerPath(path, name))}")
                                }
                                FileManagerDialog.Delete -> require(current != null).also {
                                    runtime.fileSystem.remove(current!!.path, recursive = current.isDirectory)
                                }
                                FileManagerDialog.None -> Unit
                            }
                        }.onFailure { error = it.message.orEmpty() }
                        selected = null
                        refresh()
                    }
                }, enabled = dialog == FileManagerDialog.Delete || draftName.trim().isNotBlank()) { Text(stringResource(Res.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { dialog = FileManagerDialog.None }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
    preview?.let { text ->
        var edited by remember(text) { mutableStateOf(text) }
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Preview") },
            text = { OutlinedTextField(value = edited, onValueChange = { edited = it }, minLines = 8, maxLines = 20) },
            confirmButton = { Row {
                TextButton(onClick = { scope.launch { previewPath?.let { runtime.fileSystem.write(it, edited.encodeToByteArray()) }; preview = null } }) { Text("Save") }
                TextButton(onClick = { preview = null }) { Text(stringResource(Res.string.common_cancel)) }
            } })
    }
    imagePreview?.let { bytes ->
        Dialog(onDismissRequest = { imagePreview = null }) {
            Surface(shape = RoundedCornerShape(8.dp), color = AetherSurface) {
                Image(
                    bitmap = remember(bytes) { bytes.decodeToImageBitmap() },
                    contentDescription = previewPath,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun FileManagerTopBar(
    path: String, showSearch: Boolean, filter: String, grid: Boolean,
    isRoot: Boolean, onBack: () -> Unit, onUp: () -> Unit, onRefresh: () -> Unit,
    onSearchToggle: () -> Unit, onFilterChanged: (String) -> Unit, onGridChanged: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(AetherSurface).statusBarsPadding().padding(top = 8.dp, start = 12.dp, end = 12.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.file_manager_title), style = MaterialTheme.typography.titleMedium, color = AetherOnSurface)
                Text(path, style = MaterialTheme.typography.labelSmall, color = AetherOnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onUp, enabled = !isRoot) { Icon(Icons.Rounded.ArrowUpward, "Up") }
            IconButton(onClick = onSearchToggle) { Icon(Icons.Rounded.Search, "Search") }
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh") }
            IconButton(onClick = { onGridChanged(!grid) }) { Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, if (grid) stringResource(Res.string.file_manager_view_list) else stringResource(Res.string.file_manager_view_grid)) }
        }
        if (showSearch) OutlinedTextField(value = filter, onValueChange = onFilterChanged, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search") })
    }
}

@Composable
private fun FileManagerListEntry(entry: FileManagerEntry, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (selected) AetherSurfaceHigh else Color.Transparent).padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = if (entry.isDirectory) AetherPrimary else AetherOnSurfaceVariant, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = AetherOnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (entry.isDirectory) "Folder" else formatFileManagerSize(entry.size), color = AetherOnSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Icon(Icons.Rounded.MoreVert, "More", tint = AetherOnSurfaceVariant)
    }
}

@Composable
private fun FileManagerGridEntry(entry: FileManagerEntry, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).background(if (selected) AetherSurfaceHigh else AetherSurface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = if (entry.isDirectory) AetherPrimary else AetherOnSurfaceVariant, modifier = Modifier.size(30.dp))
        Spacer(Modifier.size(12.dp))
        Text(entry.name, color = AetherOnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FileManagerEntryMenu(entry: FileManagerEntry, onDismiss: () -> Unit, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = AetherSurface) {
        Column {
            DropdownMenuItem(text = { Text(stringResource(Res.string.file_manager_open)) }, onClick = onOpen, leadingIcon = { Icon(if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description, null) })
            DropdownMenuItem(text = { Text(stringResource(Res.string.file_manager_rename)) }, onClick = onRename, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem(text = { Text(stringResource(Res.string.file_manager_delete)) }, onClick = onDelete, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
        }
        }
    }
}

private suspend fun listFileManagerDirectory(runtime: MultiplatformLocalRuntime, path: String): List<FileManagerEntry> = coroutineScope {
    val command = "for p in ${shellQuote(path)}/* ${shellQuote(path)}/.[!.]* ${shellQuote(path)}/..?*; do [ -e \"\$p\" ] || continue; if [ -d \"\$p\" ]; then k=d; s=0; else k=f; s=\$(wc -c < \"\$p\" 2>/dev/null || echo 0); fi; printf '%s\\t%s\\t%s\\n' \"\$k\" \"\$s\" \"\$(basename \"\$p\")\"; done"
    val process = runtime.startProcess(RuntimeProcessSpec("/bin/sh", listOf("-lc", command), mapOf("HOME" to runtime.homeDirectory), runtime.homeDirectory, redirectErrorStream = true))
    val output = async { process.stdout.toList().joinToString("") { it.decodeToString() } }
    val exit = process.awaitExit()
    check(exit.exitCode == 0) { output.await().trim().ifBlank { "Unable to list $path." } }
    output.await().lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        val name = parts[2]
        FileManagerEntry(name, joinFileManagerPath(path, name), parts[0] == "d", parts[1].toLongOrNull() ?: 0L)
    }.sortedWith(compareByDescending<FileManagerEntry> { it.isDirectory }.thenBy { it.name.lowercase() }).toList()
}

private suspend fun runFileManagerCommand(runtime: MultiplatformLocalRuntime, command: String) {
    val process = runtime.startProcess(RuntimeProcessSpec("/bin/sh", listOf("-lc", command), mapOf("HOME" to runtime.homeDirectory), runtime.homeDirectory, redirectErrorStream = true))
    val output = process.stdout.toList().joinToString("") { it.decodeToString() }
    val exit = process.awaitExit()
    check(exit.exitCode == 0) { output.trim().ifBlank { "File operation failed." } }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun normalizeFileManagerPath(value: String): String {
    val parts = value.split('/').filter { it.isNotBlank() && it != "." }
    val result = ArrayDeque<String>()
    parts.forEach { if (it == "..") result.removeLastOrNull() else result.addLast(it) }
    return "/" + result.joinToString("/")
}

private fun joinFileManagerPath(parent: String, name: String): String = normalizeFileManagerPath("$parent/$name")

private fun formatFileManagerSize(size: Long): String = when {
    size >= 1_048_576 -> "${size / 1_048_576} MB"
    size >= 1_024 -> "${size / 1_024} KB"
    else -> "$size B"
}
