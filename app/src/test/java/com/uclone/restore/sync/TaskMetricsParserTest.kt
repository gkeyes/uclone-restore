package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskMetricsParserTest {
    @Test
    fun compositeMetricLinesSumIoAndKeepPeakValues() {
        val metrics = TaskMetricsParser.parse(
            output = """
                UCLONE_METRIC:scanned_files=10 copied_files=8 copied_bytes=1024 peak_temporary_bytes=4096 target_downtime_ms=0
                UCLONE_METRIC:scanned_files=20 copied_files=15 copied_bytes=2048 peak_temporary_bytes=2048 target_downtime_ms=500
            """.trimIndent(),
            rootProcessStarts = 1,
            rootCommandCount = 1,
        )

        assertEquals(30, metrics.scannedFiles)
        assertEquals(23, metrics.copiedFiles)
        assertEquals(3072, metrics.copiedBytes)
        assertEquals(4096, metrics.peakTemporaryBytes)
        assertEquals(500, metrics.targetDowntimeMs)
    }
}
