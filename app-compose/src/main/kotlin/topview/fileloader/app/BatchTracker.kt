package topview.fileloader.app

import topview.fileloader.service.BatchStatusService

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class BatchTracker(
    private val gateway: BatchGateway,
    private val onStatus: (String, BatchStatusService.BatchStatusResult) -> Unit,
    private val onAuthExpired: (String) -> Unit
) : AutoCloseable {
    companion object {
        internal const val POLL_INTERVAL_SECONDS = 3L
    }

    private val tracked = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "batch-status-tracker").apply { isDaemon = true }
    }

    init {
        scheduler.scheduleWithFixedDelay(
            ::refreshTracked,
            POLL_INTERVAL_SECONDS,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    fun track(batchId: String, refreshNow: Boolean = true) {
        if (batchId.isBlank() || closed.get()) return
        tracked.add(batchId)
        if (refreshNow && authenticated.get()) scheduler.submit { refresh(batchId) }
    }

    fun setAuthenticated(value: Boolean) {
        authenticated.set(value)
        if (value) scheduler.submit(::refreshTracked)
    }

    fun refreshNow(batchIds: Collection<String>) {
        if (closed.get() || !authenticated.get()) return
        val ids = batchIds.filter { it.isNotBlank() }.distinct()
        tracked.addAll(ids)
        scheduler.submit { ids.forEach(::refresh) }
    }

    fun stopTracking(batchId: String) {
        tracked.remove(batchId)
    }

    fun refreshTracked() {
        if (closed.get() || !authenticated.get()) return
        tracked.toList().forEach(::refresh)
    }

    private fun refresh(batchId: String) {
        if (closed.get() || !authenticated.get()) return
        val result = gateway.status(batchId)
        if (result.code == 401) {
            // 停止轮询并只通知一次，避免每轮都重复弹出登录框
            if (authenticated.compareAndSet(true, false)) onAuthExpired("登录已过期，请重新登录")
            return
        }
        onStatus(batchId, result)
        val isTerminal = result.isDownloadable || result.code == 404 || result.code == 410
                || result.msg.contains("不存在") || result.msg.contains("已失效") || result.msg.contains("已过期")
        if (isTerminal) tracked.remove(batchId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scheduler.shutdownNow()
    }
}
