package com.example.templei.feature.export

/**
 * Endpoint contract for OBS Media Source SRT ingest.
 */
data class ObsEndpointSpec(
    val host: String,
    val port: Int,
    val latencyMs: Int = 120,
    val mode: String = "listener",
) {
    fun toSrtUrl(): String = "srt://$host:$port?mode=$mode"
}
