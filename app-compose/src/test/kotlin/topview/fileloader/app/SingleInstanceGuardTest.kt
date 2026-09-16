package topview.fileloader.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import topview.fileloader.config.AppPaths

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceGuardTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun clearHomeOverride() {
        System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    }

    @Test
    fun `first instance owns the lock and the second one is rejected`() = runBlocking {
        System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())

        val primary = SingleInstanceGuard.acquire()
        assertNotNull(primary, "第一个实例应取得单实例锁")
        try {
            val activated = CountDownLatch(1)
            val collector = launch(Dispatchers.IO) {
                primary!!.activationRequests.collect { activated.countDown() }
            }
            try {
                assertNull(SingleInstanceGuard.acquire(), "已有实例运行时不应再启动第二个实例")
                assertTrue(activated.await(5, TimeUnit.SECONDS), "已运行实例应收到窗口激活请求")
            } finally {
                collector.cancel()
            }
        } finally {
            primary!!.close()
        }
    }

    @Test
    fun `lock can be reacquired after the previous instance exits`() {
        System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())

        val first = SingleInstanceGuard.acquire()
        assertNotNull(first, "第一个实例应取得单实例锁")
        first!!.close()

        val second = SingleInstanceGuard.acquire()
        assertNotNull(second, "上一个实例退出后应能重新取得单实例锁")
        second!!.close()
    }
}
