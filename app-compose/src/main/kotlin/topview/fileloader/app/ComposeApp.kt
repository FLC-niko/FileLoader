package topview.fileloader.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp

import topview.fileloader.config.LoggingConfig

import java.awt.Desktop
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.UIManager
import javax.swing.filechooser.FileFilter
import kotlin.system.exitProcess

fun startComposeApp() {
    LoggingConfig.init()
    val instanceGuard = SingleInstanceGuard.acquire()
    if (instanceGuard == null) {
        notifyAlreadyRunning()
        exitProcess(0)
    }
    try {
        application {
            val viewModel = remember { AppViewModel() }

            Window(
                title = "国基形式审查 · 文件上传助手",
                state = WindowState(width = 1120.dp, height = 760.dp),
                onCloseRequest = {
                    viewModel.close()
                    exitApplication()
                }
            ) {
                LaunchedEffect(instanceGuard) {
                    instanceGuard.activationRequests.collect { bringWindowToFront(window) }
                }

                DisposableEffect(Unit) {
                    DropTarget(window, object : DropTargetAdapter() {
                        override fun dragEnter(event: java.awt.dnd.DropTargetDragEvent?) {
                            event?.acceptDrag(DnDConstants.ACTION_COPY)
                            viewModel.dispatch(AppAction.DragChanged(true))
                        }

                        override fun dragExit(event: java.awt.dnd.DropTargetEvent?) {
                            viewModel.dispatch(AppAction.DragChanged(false))
                        }

                        override fun drop(event: DropTargetDropEvent?) {
                            viewModel.dispatch(AppAction.DragChanged(false))
                            event ?: return
                            event.acceptDrop(DnDConstants.ACTION_COPY)
                            try {
                                val transferable = event.transferable
                                if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                                    viewModel.dispatch(AppAction.SourcesSelected(files.orEmpty().filterIsInstance<File>()))
                                }
                                event.dropComplete(true)
                            } catch (_: Exception) {
                                event.dropComplete(false)
                            }
                        }
                    })
                    onDispose { window.dropTarget = null }
                }

                DisposableEffect(Unit) {
                    onDispose { viewModel.close() }
                }

                FileLoaderRoot(viewModel)
            }
        }
    } finally {
        instanceGuard.close()
    }
}

/** 把已有窗口从最小化/后台恢复并置于最前，供第二个实例请求激活时使用。 */
private fun bringWindowToFront(window: java.awt.Window) {
    runCatching {
        if (window is Frame) {
            val state = window.extendedState
            if (state and Frame.ICONIFIED != 0) {
                window.extendedState = state and Frame.ICONIFIED.inv()
            }
        }
        window.isVisible = true
        window.toFront()
        window.requestFocus()
    }
}

/** 已有实例在运行时，提示用户并结束本次启动。 */
private fun notifyAlreadyRunning() {
    println("[FileLoader] 文件上传助手已经在运行，本次启动已取消。")
    val message = "文件上传助手已经在运行了，请不要重复打开。\n已为你切到正在运行的窗口，如果没有看到请到后台窗口里查找。"
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    runCatching {
        JOptionPane.showMessageDialog(null, message, "程序已在运行", JOptionPane.INFORMATION_MESSAGE)
    }.onFailure {
        // 无图形环境时只保留控制台提示
    }
}

@androidx.compose.runtime.Composable
private fun FileLoaderRoot(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.dispatch(AppAction.Started)
        viewModel.effects.collect { effect ->
            when (effect) {
                UiEffect.ChooseSource -> chooseSources()?.let { viewModel.dispatch(AppAction.SourcesSelected(it)) }
                is UiEffect.ChooseDownloadDirectory -> chooseDirectory("选择结果保存位置")?.let {
                    viewModel.dispatch(
                        AppAction.DownloadDirectorySelected(effect.batchId, effect.wrongFiles, it)
                    )
                }
                is UiEffect.OpenFolder -> runCatching { Desktop.getDesktop().open(File(effect.path)) }
                    .onFailure { snackbarHostState.showSnackbar("无法打开此文件夹") }
                is UiEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                is UiEffect.CopyText -> {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(effect.text), null)
                    snackbarHostState.showSnackbar(effect.toast)
                }
            }
        }
    }

    FileLoaderTheme {
        ComposeMonitorScreen(state, viewModel::dispatch, snackbarHostState)
    }
}

private fun chooseSources(): List<File>? {
    val chooser = JFileChooser().apply {
        dialogTitle = "选择文件夹或 ZIP"
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        isMultiSelectionEnabled = true
        isAcceptAllFileFilterUsed = false
        fileFilter = object : FileFilter() {
            override fun accept(file: File): Boolean = file.isDirectory || file.extension.equals("zip", true)
            override fun getDescription(): String = "文件夹或 ZIP 压缩包"
        }
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFiles?.takeIf { it.isNotEmpty() }?.toList()
    return selected ?: listOfNotNull(chooser.selectedFile)
}

private fun chooseDirectory(title: String): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

fun main() = startComposeApp()
