package topview.fileloader.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SourceListCard(sources: List<MonitoredSource>, onAction: (AppAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(
                "自动上传文件夹",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "新增文件会自动上传",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = colors.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height((sources.size.coerceAtMost(6) * 78 + 16).dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)
            ) {
                items(sources, key = { it.path }) { source -> SourceRow(source, onAction) }
            }
        }
    }
}

@Composable
private fun SourceRow(source: MonitoredSource, onAction: (AppAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.secondaryContainer.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = colors.secondary)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("自动上传中", color = colors.secondary, style = MaterialTheme.typography.labelLarge)
                Text(source.path, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "文件夹操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("打开文件夹") },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                        onClick = {
                            menuOpen = false
                            onAction(AppAction.OpenSource(source.path))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("停止自动上传") },
                        leadingIcon = { Icon(Icons.Default.StopCircle, null) },
                        onClick = {
                            menuOpen = false
                            onAction(AppAction.RequestStopSource(source))
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun UploadTaskCard(
    task: UploadTask,
    expanded: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onAction: (AppAction) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val accent = when (task.stage) {
        TaskStage.READY -> colors.secondary
        TaskStage.NEEDS_ATTENTION -> colors.error
        TaskStage.PREPARING, TaskStage.UPLOADING, TaskStage.PROCESSING -> colors.primary
    }
    val container = when (task.stage) {
        TaskStage.NEEDS_ATTENTION -> colors.errorContainer.copy(alpha = 0.28f)
        TaskStage.READY -> colors.secondaryContainer.copy(alpha = 0.3f)
        else -> colors.surface
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            onAction(
                if (selectionMode) AppAction.ToggleTaskSelection(task.batchId)
                else AppAction.ToggleTaskDetails(task.batchId)
            )
        },
        shape = RoundedCornerShape(16.dp),
        color = if (selectionMode && selected) colors.primaryContainer.copy(alpha = 0.55f) else container,
        border = BorderStroke(
            if (selectionMode && selected) 2.dp else 1.dp,
            if (selectionMode && selected) colors.primary else accent.copy(alpha = 0.25f)
        ),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onAction(AppAction.ToggleTaskSelection(task.batchId)) }
                    )
                }
                Icon(stageIcon(task.stage), contentDescription = null, tint = accent)
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.sourceLabel.ifBlank { "上传任务" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stageText(task), color = accent, style = MaterialTheme.typography.labelLarge)
                }
                if (!selectionMode) {
                    when (task.stage) {
                        TaskStage.NEEDS_ATTENTION -> Button(
                            onClick = { onAction(AppAction.RetryTask(task.batchId)) },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("重试失败文件") }
                        TaskStage.READY -> Button(
                            onClick = { onAction(AppAction.DownloadResult(task.batchId)) },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("下载结果") }
                        else -> Unit
                    }
                    if (task.stage == TaskStage.READY) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多任务操作")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("下载异常文件") },
                                    onClick = {
                                        menuOpen = false
                                        onAction(AppAction.DownloadWrongFiles(task.batchId))
                                    }
                                )
                            }
                        }
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起详情" else "展开详情",
                        tint = colors.onSurfaceVariant
                    )
                }
            }

            if (task.stage == TaskStage.PREPARING || task.stage == TaskStage.UPLOADING || task.stage == TaskStage.PROCESSING) {
                Spacer(Modifier.height(10.dp))
                val progress = task.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.createdAt, color = colors.onSurfaceVariant)
                when (task.stage) {
                    TaskStage.PROCESSING -> if (task.serverTotalFiles > 0 && task.processedFiles >= 0) {
                        Text("${task.processedFiles}/${task.serverTotalFiles} 已处理", color = colors.onSurfaceVariant)
                    } else {
                        Text("等待服务器返回处理进度", color = colors.onSurfaceVariant)
                    }
                    TaskStage.PREPARING, TaskStage.UPLOADING, TaskStage.NEEDS_ATTENTION -> {
                        if (task.totalFiles > 0) {
                            Text("${task.completedFiles}/${task.totalFiles} 已上传", color = colors.onSurfaceVariant)
                        }
                    }
                    TaskStage.READY -> if (task.totalFiles > 0) {
                        Text("${task.totalFiles} 个文件处理完成", color = colors.onSurfaceVariant)
                    }
                }
                if (task.failedFiles > 0) Text("${task.failedFiles} 失败", color = colors.error)
            }

            if (expanded && !selectionMode) {
                Spacer(Modifier.height(10.dp))
                Text("技术详情", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Text("任务编号：${task.batchId}", color = colors.onSurfaceVariant)
                if (task.sourcePath.isNotBlank()) {
                    Text("来源：${task.sourcePath}", maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                }
                if (task.message.isNotBlank()) Text(task.message, color = colors.onSurfaceVariant)
                task.files.filter { it.stage == FileStage.FAILED }.forEach { file ->
                    Text("${file.name}：${file.message}", color = colors.error)
                }
            }
        }
    }
}

private fun stageIcon(stage: TaskStage) = when (stage) {
    TaskStage.READY -> Icons.Default.CheckCircle
    TaskStage.NEEDS_ATTENTION -> Icons.Default.Error
    TaskStage.PROCESSING -> Icons.Default.Sync
    TaskStage.UPLOADING -> Icons.Default.CloudUpload
    TaskStage.PREPARING -> Icons.Default.Schedule
}

private fun stageText(task: UploadTask): String = when (task.stage) {
    TaskStage.PREPARING -> "正在准备"
    TaskStage.UPLOADING -> task.progress?.let { "正在上传 · ${(it * 100).toInt()}%" } ?: "正在上传"
    TaskStage.PROCESSING -> if (task.serverTotalFiles > 0 && task.processedFiles >= 0) {
        "服务器处理中 · ${task.processedFiles}/${task.serverTotalFiles}"
    } else "服务器处理中"
    TaskStage.NEEDS_ATTENTION -> "需要处理"
    TaskStage.READY -> "可以下载"
}
