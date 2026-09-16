package topview.fileloader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsDialog(state: AppUiState, onAction: (AppAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    AnimatedDialog(onDismissRequest = { onAction(AppAction.CloseSettings) }) {
        Card(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { onAction(AppAction.CloseSettings) }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅上传 PDF", style = MaterialTheme.typography.titleMedium)
                        Text("关闭后会上传文件夹中的所有可读文件", color = colors.onSurfaceVariant)
                    }
                    Switch(
                        checked = state.settings.onlyPdf,
                        onCheckedChange = { onAction(AppAction.OnlyPdfChanged(it)) }
                    )
                }

                HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("连接与环境", style = MaterialTheme.typography.titleMedium)
                    Text("遇到登录或上传问题时，可运行一次安全检查。不会上传测试文件。", color = colors.onSurfaceVariant)
                    OutlinedButton(
                        onClick = { onAction(AppAction.RunSelfCheck) },
                        enabled = !state.isChecking,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.isChecking) {
                            CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.isChecking) "正在检查…" else "检查连接")
                    }
                    state.selfCheck?.let { result ->
                        SelfCheckResult(result)
                        TextButton(onClick = { onAction(AppAction.CopySelfCheck) }) { Text("复制检查结果") }
                    }
                }

                HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("本地历史数据", style = MaterialTheme.typography.titleMedium)
                        Text("清空本地数据库中的所有批次记录（不影响服务端数据）", color = colors.onSurfaceVariant)
                    }
                    OutlinedButton(
                        onClick = { onAction(AppAction.ClearAllHistory) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("清空记录")
                    }
                }

                HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                TextButton(onClick = { onAction(AppAction.ToggleAdvancedSettings) }) {
                    Text("高级设置")
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (state.settings.advancedVisible) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
                if (state.settings.advancedVisible) {
                    Text(
                        "这些设置通常由管理员维护。修改错误会导致无法登录或上传。",
                        color = colors.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = state.settings.serverUrl,
                        onValueChange = { onAction(AppAction.ServerUrlChanged(it)) },
                        label = { Text("服务器地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.settings.connectTimeout,
                            onValueChange = { onAction(AppAction.ConnectTimeoutChanged(it)) },
                            label = { Text("连接等待（秒）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.settings.readTimeout,
                            onValueChange = { onAction(AppAction.ReadTimeoutChanged(it)) },
                            label = { Text("响应等待（秒）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TextButton(onClick = { onAction(AppAction.OpenSystemLog) }) { Text("系统日志") }
                }

                if (state.settings.error.isNotBlank()) Text(state.settings.error, color = colors.error)
                Button(
                    onClick = { onAction(AppAction.SaveSettings) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("保存设置") }
            }
        }
    }
}

@Composable
private fun SelfCheckResult(result: SelfCheckResultUi) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(result.server, result.login, result.storage).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = when (item.status) {
                        CheckStatus.OK -> Icons.Default.CheckCircle
                        CheckStatus.WARNING -> Icons.Default.Info
                        CheckStatus.ERROR -> Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = when (item.status) {
                        CheckStatus.OK -> MaterialTheme.colorScheme.secondary
                        CheckStatus.WARNING -> Color(0xFF9A6700)
                        CheckStatus.ERROR -> MaterialTheme.colorScheme.error
                    }
                )
                Column {
                    Text(item.title, style = MaterialTheme.typography.labelLarge)
                    Text(item.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun SystemLogDialog(state: AppUiState, onAction: (AppAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    AnimatedDialog(onDismissRequest = { onAction(AppAction.CloseSystemLog) }) {
        Card(
            modifier = Modifier.width(640.dp),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("系统日志", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { onAction(AppAction.CloseSystemLog) }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                Text("这是供管理员排查问题的运行日志，普通使用无需关注。", color = colors.onSurfaceVariant)
                Box(
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                        .background(colors.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                ) {
                    if (state.systemLogLoading && state.systemLogContent.isBlank()) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = state.systemLogContent.ifBlank { "暂无日志" },
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = { onAction(AppAction.RefreshSystemLog) }) { Text("刷新") }
                    TextButton(onClick = { onAction(AppAction.CopySystemLog) }) { Text("复制") }
                    TextButton(onClick = { onAction(AppAction.OpenLogFolder) }) { Text("打开文件夹") }
                    TextButton(onClick = { onAction(AppAction.ClearSystemLog) }) { Text("清空") }
                    Button(
                        onClick = { onAction(AppAction.CloseSystemLog) },
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("关闭") }
                }
            }
        }
    }
}
