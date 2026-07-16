package topview.fileloader.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import topview.fileloader.service.BatchIdService
import topview.fileloader.service.BatchStatusService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BatchTrackerTest {
    @Test
    fun `automatic polling interval is three seconds`() {
        assertEquals(3L, BatchTracker.POLL_INTERVAL_SECONDS)
    }

    @Test
    fun `manual refresh queries the requested batch immediately`() {
        val queried = CountDownLatch(1)
        val statusDelivered = CountDownLatch(1)
        val gateway = object : BatchGateway {
            override fun createBatch() = BatchIdService.BatchIdResult(200, "ok", "batch")
            override fun status(batchId: String): BatchStatusService.BatchStatusResult {
                if (batchId == "batch-manual") queried.countDown()
                return BatchStatusService.BatchStatusResult(200, "处理中", false, -1, -1, -1, 1, 0, 1)
            }
            override fun downloadResult(batchId: String, directory: File) = ""
            override fun downloadWrongFiles(batchId: String, directory: File) = ""
        }
        val tracker = BatchTracker(gateway, { _, _ -> statusDelivered.countDown() }, {})
        try {
            tracker.setAuthenticated(true)
            tracker.refreshNow(listOf("batch-manual"))

            assertTrue(queried.await(1, TimeUnit.SECONDS))
            assertTrue(statusDelivered.await(1, TimeUnit.SECONDS))
        } finally {
            tracker.close()
        }
    }
}
