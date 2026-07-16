package topview.fileloader.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ComposeMonitorScreen(
    state: AppUiState,
    onAction: (AppAction) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val colors = MaterialTheme.colorScheme
    val background = Brush.verticalGradient(
        listOf(colors.background, colors.surfaceVariant.copy(alpha = 0.28f), colors.background)
    )

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(padding)
        ) {
            if (state.isStarting) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator()
                    Text("正在确认登录状态…", color = colors.onSurfaceVariant)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    AppHeader(state, onAction)
                    DashboardContent(state, onAction, Modifier.weight(1f))
                }
            }

            if (state.isDragOver) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 48.dp, vertical = 34.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.CloudUpload, null, tint = colors.primary)
                            Text("松开即可添加", style = MaterialTheme.typography.titleLarge)
                            Text("支持文件夹和 ZIP", color = colors.onSurfaceVariant)
                        }
                    }
                }
            }

            if (state.showLogin) LoginDialog(state, onAction)
            if (state.showSettings) SettingsDialog(state, onAction)
            if (state.showSystemLog) SystemLogDialog(state, onAction)
            state.showStopConfirmationFor?.let { StopSourceDialog(it, onAction) }
            if (state.showDeleteTaskConfirmation) {
                DeleteTasksDialog(state.selectedTaskIds.size, onAction)
            }
        }
    }
}

@Composable
private fun AppHeader(state: AppUiState, onAction: (AppAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource("logo.png"),
                contentDescription = "广工校徽",
                modifier = Modifier.size(40.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("国基形式审查", style = MaterialTheme.typography.titleLarge)
                Text("文件上传助手", color = colors.onSurfaceVariant)
            }
            if (state.monitoredSources.isNotEmpty()) {
                Button(onClick = { onAction(AppAction.RequestAddSource) }, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("添加上传来源")
                }
            }
            Box {
                IconButton(onClick = { onAction(AppAction.ToggleAccountMenu) }) {
                    Icon(Icons.Default.AccountCircle, contentDescription = "账户菜单")
                }
                DropdownMenu(
                    expanded = state.showAccountMenu,
                    onDismissRequest = { onAction(AppAction.ToggleAccountMenu) }
                ) {
                    if (state.isLoggedIn) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(state.userName.ifBlank { "已登录用户" })
                                    if (state.userId.isNotBlank()) {
                                        Text(state.userId, color = colors.onSurfaceVariant)
                                    }
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.AccountCircle, null) },
                            onClick = { onAction(AppAction.ToggleAccountMenu) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("设置") },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        onClick = { onAction(AppAction.OpenSettings) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.isLoggedIn) "退出登录" else "登录") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                        onClick = { onAction(if (state.isLoggedIn) AppAction.Logout else AppAction.OpenLogin) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardContent(state: AppUiState, onAction: (AppAction) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < 860.dp
        if (compact) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { SourcesArea(state, onAction) }
                item { TaskArea(state, onAction, constrainHeight = false) }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(modifier = Modifier.width(330.dp).fillMaxHeight()) { SourcesArea(state, onAction) }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { TaskArea(state, onAction, constrainHeight = true) }
            }
        }
    }
}

@Composable
private fun SourcesArea(state: AppUiState, onAction: (AppAction) -> Unit) {
    if (state.monitoredSources.isEmpty()) {
        AddSourceCard(onAction, Modifier.fillMaxWidth())
    } else {
        SourceListCard(state.monitoredSources, onAction)
    }
}

@Composable
private fun AddSourceCard(onAction: (AppAction) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = colors.primary)
            Text("选择要上传的资料", style = MaterialTheme.typography.titleLarge)
            Text(
                "选择文件夹后会上传已有文件，并自动上传后来放入的新文件。也可以直接选择 ZIP。",
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = { onAction(AppAction.RequestAddSource) }, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加上传来源")
            }
            Text("也可以把文件夹或 ZIP 拖到窗口中", color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun TaskArea(
    state: AppUiState,
    onAction: (AppAction) -> Unit,
    constrainHeight: Boolean
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = if (constrainHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("上传任务", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.isTaskSelectionMode) {
                    Text("已选 ${state.selectedTaskIds.size} 项", color = colors.onSurfaceVariant)
                    TextButton(onClick = { onAction(AppAction.ToggleAllTaskSelection) }) {
                        Text(if (state.selectedTaskIds.size == state.tasks.size) "取消全选" else "全选")
                    }
                    if (state.selectedTaskIds.isNotEmpty()) {
                        Button(onClick = { onAction(AppAction.RequestDeleteSelectedTasks) }) {
                            Text("删除所选")
                        }
                    }
                    TextButton(onClick = { onAction(AppAction.ExitTaskSelectionMode) }) {
                        Text("退出多选")
                    }
                } else {
                    TextButton(onClick = { onAction(AppAction.RefreshTasks) }) {
                        Text("刷新状态")
                    }
                    TextButton(onClick = { onAction(AppAction.EnterTaskSelectionMode) }) {
                        Text("多选")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (state.tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("添加资料后，进度和结果会显示在这里", color = colors.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = if (constrainHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(520.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.sortedTasks, key = { it.batchId }) { task ->
                        UploadTaskCard(
                            task = task,
                            expanded = task.batchId in state.expandedTaskIds,
                            selectionMode = state.isTaskSelectionMode,
                            selected = task.batchId in state.selectedTaskIds,
                            onAction = onAction
                        )
                    }
                }
            }
        }
    }
}
