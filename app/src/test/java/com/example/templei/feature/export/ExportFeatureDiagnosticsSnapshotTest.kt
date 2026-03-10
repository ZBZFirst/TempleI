package com.example.templei.feature.export

import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFeatureDiagnosticsSnapshotTest {
    @Test
    fun `diagnostics snapshot includes run id and adb capture metadata`() {
        val config = ExportFeature.ObsStreamConfig(host = "192.168.1.50", port = 9000)
        val snapshot = ExportFeature.createDiagnosticsSnapshot(config = config, nowMs = 1700000000000)

        assertTrue(snapshot.runId.startsWith("run-"))
        assertTrue(snapshot.content.contains("runId=${snapshot.runId}"))
        assertTrue(snapshot.content.contains("captureWindowSeconds=30"))
        assertTrue(snapshot.content.contains("adbFilter="))
        assertTrue(snapshot.content.contains("adbCaptureCommand="))
        assertTrue(snapshot.content.contains("startup-${snapshot.runId}.log"))
        assertTrue(snapshot.content.contains("obsInputUrl=srt://192.168.1.50:9000?mode=listener"))
        assertTrue(snapshot.content.contains("transportCallerUrl=srt://192.168.1.50:9000?mode=caller"))
        assertTrue(snapshot.content.contains("muxPacketsProduced="))
        assertTrue(snapshot.content.contains("writePacketsFailed="))
        assertTrue(snapshot.content.contains("consecutiveWriteFailures="))
        assertTrue(snapshot.content.contains("outputOpened="))
        assertTrue(snapshot.content.contains("headerWritten="))
    }
}
