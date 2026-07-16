package topview.fileloader.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AnimatedDialog(
    visible: Boolean = true,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.46f)).clickable { onDismissRequest() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(visible = visible, enter = scaleIn(), exit = scaleOut()) {
                Box(modifier = Modifier.clickable { }) { content() }
            }
        }
    }
}

@Composable
internal fun LoginDialog(state: AppUiState, onAction: (AppAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    AnimatedDialog(onDismissRequest = { if (!state.isAuthenticating) onAction(AppAction.CloseLogin) }) {
        Card(
            modifier = Modifier
                .width(400.dp)
                .onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (enter && event.type == KeyEventType.KeyDown && !state.isAuthenticating) {
                        onAction(AppAction.Login)
                        true
                    } else false
                },
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("登录", style = MaterialTheme.typography.headlineSmall)
                        Text("登录后即可自动上传资料", color = colors.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = { onAction(AppAction.CloseLogin) },
                        enabled = !state.isAuthenticating
                    ) { Icon(Icons.Default.Close, contentDescription = "关闭") }
                }
                OutlinedTextField(
                    value = state.loginUserId,
                    onValueChange = { onAction(AppAction.LoginUserChanged(it)) },
                    label = { Text("账号") },
                    singleLine = true,
                    enabled = !state.isAuthenticating,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.loginPassword,
                    onValueChange = { onAction(AppAction.LoginPasswordChanged(it)) },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !state.isAuthenticating,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(AppAction.Login) }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.loginError.isNotBlank()) {
                    Text(state.loginError, color = colors.error)
                    if (state.loginError.contains("连接") || state.loginError.contains("检查")) {
                        TextButton(onClick = { onAction(AppAction.RunSelfCheck) }) { Text("检查连接") }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = { onAction(AppAction.Login) },
                    enabled = !state.isAuthenticating,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (state.isAuthenticating) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isAuthenticating) "正在登录…" else "登录并开始使用")
                }
            }
        }
    }
}

@Composable
internal fun StopSourceDialog(source: MonitoredSource, onAction: (AppAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CancelStopSource) },
        title = { Text("停止自动上传？") },
        text = {
            Text("停止后，${source.name} 中新增的文件将不再自动上传；已经进入队列的文件会继续完成。")
        },
        dismissButton = {
            TextButton(onClick = { onAction(AppAction.CancelStopSource) }) { Text("继续监控") }
        },
        confirmButton = {
            Button(onClick = { onAction(AppAction.ConfirmStopSource) }) { Text("停止自动上传") }
        }
    )
}

@Composable
internal fun DeleteTasksDialog(selectedCount: Int, onAction: (AppAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CancelDeleteSelectedTasks) },
        title = { Text("删除所选任务？") },
        text = {
            Text("将从本机删除 $selectedCount 条任务记录，不会删除服务器上的结果。正在上传的任务会自动保留。")
        },
        dismissButton = {
            TextButton(onClick = { onAction(AppAction.CancelDeleteSelectedTasks) }) { Text("取消") }
        },
        confirmButton = {
            Button(onClick = { onAction(AppAction.ConfirmDeleteSelectedTasks) }) { Text("删除") }
        }
    )
}
