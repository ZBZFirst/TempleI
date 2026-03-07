package com.example.templei.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPipelineMetricsTest {
    @Test
    fun snapshotTracksCountersLatenciesAndQueues() {
        StreamPipelineMetrics.reset()

        StreamPipelineMetrics.recordCameraArrival(nowNs = 1_000_000)
        StreamPipelineMetrics.recordEncoderQueueIn(nowNs = 1_500_000)
        StreamPipelineMetrics.recordEncoderOutput(nowNs = 2_000_000)
        StreamPipelineMetrics.recordMuxVideoIngest()
        StreamPipelineMetrics.recordMuxAudioIngest()
        StreamPipelineMetrics.recordMuxPacketDrain(nowNs = 3_000_000)
        StreamPipelineMetrics.recordSrtSendAttempt(success = true, nowNs = 3_500_000)
        StreamPipelineMetrics.recordSrtSendAttempt(success = false, nowNs = 4_000_000)
        StreamPipelineMetrics.updateQueueDepths(cameraToEncoder = 2, encoderToMux = 3, muxToSrt = 4)
        StreamPipelineMetrics.recordQueueDrop(cameraToEncoder = 1, encoderToMux = 2, muxToSrt = 3)

        val snapshot = StreamPipelineMetrics.snapshot()

        assertEquals(1, snapshot.cameraArrivalCount)
        assertEquals(1, snapshot.encoderQueueInCount)
        assertEquals(1, snapshot.encoderOutputCount)
        assertEquals(1, snapshot.muxVideoIngestCount)
        assertEquals(1, snapshot.muxAudioIngestCount)
        assertEquals(1, snapshot.muxDrainPacketCount)
        assertEquals(2, snapshot.srtSendAttemptCount)
        assertEquals(1, snapshot.srtSendSuccessCount)
        assertEquals(1, snapshot.srtSendFailureCount)

        assertEquals(500, snapshot.cameraToEncoderLastLatencyUs)
        assertEquals(500, snapshot.encoderToMuxLastLatencyUs)
        assertEquals(1000, snapshot.muxToSrtLastLatencyUs)

        assertEquals(2, snapshot.cameraToEncoderQueueDepth)
        assertEquals(3, snapshot.encoderToMuxQueueDepth)
        assertEquals(4, snapshot.muxToSrtQueueDepth)

        assertEquals(1, snapshot.cameraToEncoderDropCount)
        assertEquals(2, snapshot.encoderToMuxDropCount)
        assertEquals(3, snapshot.muxToSrtDropCount)
    }

    @Test
    fun diagnosticSnapshotReportsFirstBackpressureOrigin() {
        StreamPipelineMetrics.reset()

        StreamPipelineMetrics.recordCameraArrival(nowNs = 1_000_000)
        StreamPipelineMetrics.recordEncoderQueueIn(nowNs = 1_050_000)
        StreamPipelineMetrics.recordEncoderOutput(nowNs = 1_100_000)
        StreamPipelineMetrics.recordMuxPacketDrain(nowNs = 1_150_000)
        StreamPipelineMetrics.recordSrtSendAttempt(success = true, nowNs = 1_200_000)
        StreamPipelineMetrics.recordQueueDrop(encoderToMux = 1)

        val diagnostic = StreamPipelineMetrics.captureDiagnosticSnapshot(frameBudgetUs = 33_333, nowMs = 10_000)

        assertEquals(StreamPipelineMetrics.BackpressureOrigin.EncoderToMux, diagnostic.origin)
        assertEquals("encoder queue drops", diagnostic.reason)
        assertTrue(diagnostic.compactSummary().contains("origin=EncoderToMux"))
    }

    @Test
    fun diagnosticSnapshotReportsLatencyOriginWhenNoDrops() {
        StreamPipelineMetrics.reset()

        StreamPipelineMetrics.recordCameraArrival(nowNs = 1_000_000)
        StreamPipelineMetrics.recordEncoderQueueIn(nowNs = 1_100_000)
        StreamPipelineMetrics.recordEncoderOutput(nowNs = 1_300_000)
        StreamPipelineMetrics.recordMuxPacketDrain(nowNs = 1_350_000)
        StreamPipelineMetrics.recordSrtSendAttempt(success = true, nowNs = 1_400_000)

        val diagnostic = StreamPipelineMetrics.captureDiagnosticSnapshot(frameBudgetUs = 150, nowMs = 20_000)

        assertEquals(StreamPipelineMetrics.BackpressureOrigin.EncoderToMux, diagnostic.origin)
        assertEquals("encoder->mux latency over budget", diagnostic.reason)
    }

    @Test
    fun diagnosticSnapshotReportsWithinBudgetWhenHealthy() {
        StreamPipelineMetrics.reset()

        StreamPipelineMetrics.recordCameraArrival(nowNs = 1_000_000)
        StreamPipelineMetrics.recordEncoderQueueIn(nowNs = 1_010_000)
        StreamPipelineMetrics.recordEncoderOutput(nowNs = 1_020_000)
        StreamPipelineMetrics.recordMuxPacketDrain(nowNs = 1_030_000)
        StreamPipelineMetrics.recordSrtSendAttempt(success = true, nowNs = 1_040_000)

        val diagnostic = StreamPipelineMetrics.captureDiagnosticSnapshot(frameBudgetUs = 1_000, nowMs = 30_000)

        assertEquals(StreamPipelineMetrics.BackpressureOrigin.None, diagnostic.origin)
        assertEquals("within budget", diagnostic.reason)
    }

}
