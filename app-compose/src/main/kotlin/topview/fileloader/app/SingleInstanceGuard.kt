package topview.fileloader.app

import topview.fileloader.config.AppPaths

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 单实例守卫：同一时间只允许运行一个 FileLoader 实例。
 *
 * 判定方式：
 * 1. 对 ~/.fileloader/instance.lock 加排他文件锁。锁被其他进程持有，说明已经有实例在运行。
 * 2. 主实例额外在 127.0.0.1 上监听一个随机端口，并把端口写入 ~/.fileloader/instance.port。
 *    第二个实例启动时会连接该端口发送激活请求，让已有窗口显示到最前面，然后自己退出。
 *
 * 进程退出（含崩溃）时操作系统会自动释放文件锁与端口，不会残留在“假死”状态。
 */
internal class SingleInstanceGuard private constructor(
    private val lockChannel: FileChannel?,
    private val fileLock: FileLock?,
    private val serverSocket: ServerSocket?,
    private val portFile: Path?
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var listener: ExecutorService? = null

    private val _activationRequests = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    /** 第二个实例请求“把已有窗口显示到最前面”时发出信号。 */
    val activationRequests: SharedFlow<Unit> = _activationRequests.asSharedFlow()

    /** 当前进程是否真的持有了单实例锁（false 表示锁不可用，处于无保护降级状态）。 */
    val isProtected: Boolean get() = fileLock != null

    private fun startListening(server: ServerSocket) {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "instance-activation-listener").apply { isDaemon = true }
        }
        listener = executor
        executor.submit {
            while (!closed.get()) {
                val connection = try {
                    server.accept()
                } catch (e: IOException) {
                    if (!closed.get()) logger.log(Level.FINE, "实例激活监听已停止", e)
                    return@submit
                }
                connection.use { socket ->
                    val request = try {
                        socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                    } catch (e: IOException) {
                        logger.log(Level.FINE, "读取实例激活请求失败", e)
                        null
                    }
                    if (request == ACTIVATE_REQUEST) {
                        logger.info("已有实例的窗口被请求激活")
                        _activationRequests.tryEmit(Unit)
                    }
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener?.shutdownNow()
        listener = null
        runCatching { serverSocket?.close() }
        portFile?.let { runCatching { Files.deleteIfExists(it) } }
        runCatching { fileLock?.release() }
        runCatching { lockChannel?.close() }
    }

    companion object {
        private val logger: Logger = Logger.getLogger(SingleInstanceGuard::class.java.name)
        private const val ACTIVATE_REQUEST = "FILELOADER_ACTIVATE"
        private const val LOCK_FILE_NAME = "instance.lock"
        private const val PORT_FILE_NAME = "instance.port"

        /**
         * 尝试取得单实例所有权。
         *
         * @return 非空表示当前进程可以继续启动；null 表示已经有实例在运行，调用方应提示并退出。
         */
        fun acquire(): SingleInstanceGuard? {
            val home = AppPaths.homeDirectory()
            val lockFile = home.resolve(LOCK_FILE_NAME)
            val portFile = home.resolve(PORT_FILE_NAME)

            val channel = try {
                Files.createDirectories(home)
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (e: IOException) {
                logger.log(Level.WARNING, "无法创建实例锁文件，本次启动不启用单实例保护", e)
                return SingleInstanceGuard(null, null, null, null)
            }

            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                closeQuietly(channel)
                requestExistingInstanceToActivate(portFile)
                return null
            } catch (e: IOException) {
                logger.log(Level.WARNING, "无法锁定实例锁文件，本次启动不启用单实例保护", e)
                closeQuietly(channel)
                return SingleInstanceGuard(null, null, null, null)
            }

            if (lock == null) {
                // 锁被其他进程持有：说明已经有实例在运行
                closeQuietly(channel)
                requestExistingInstanceToActivate(portFile)
                return null
            }

            val server = try {
                ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            } catch (e: IOException) {
                logger.log(Level.WARNING, "无法创建实例监听端口，第二个实例将无法自动前置窗口", e)
                null
            }
            val publishedPort = server?.let { socket ->
                try {
                    Files.writeString(
                        portFile,
                        socket.localPort.toString(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                    )
                    portFile
                } catch (e: IOException) {
                    logger.log(Level.WARNING, "无法写入实例端口文件", e)
                    null
                }
            }

            val guard = SingleInstanceGuard(channel, lock, server, publishedPort)
            if (server != null) guard.startListening(server)
            return guard
        }

        private fun requestExistingInstanceToActivate(portFile: Path) {
            val port = try {
                Files.readString(portFile, StandardCharsets.UTF_8).trim().toInt()
            } catch (e: IOException) {
                logger.log(Level.FINE, "没有找到已运行实例的端口信息", e)
                return
            } catch (e: NumberFormatException) {
                logger.log(Level.FINE, "已运行实例的端口信息无法解析", e)
                return
            }
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
                socket.getOutputStream().apply {
                    write("$ACTIVATE_REQUEST\n".toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
            } catch (e: IOException) {
                logger.log(Level.FINE, "无法通知已运行实例前置窗口", e)
            } finally {
                closeQuietly(socket)
            }
        }

        private fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (_: IOException) {
                // 关闭失败无需处理
            }
        }
    }
}
