package com.vmodal.smartglass

data class StreamingMetrics(
    val acceptedSamples: Long = 0,
    val droppedSamples: Long = 0,
    val keyframeRequests: Long = 0,
    val sentBytes: Long = 0,
    val reconnects: Long = 0,
    val rttMs: Long = 0,
    val retransmittedPackets: Long = 0,
    val queueBytes: Long = 0,
    val queueDepth: Long = 0,
    val uptimeMs: Long = 0,
    val rawFramesDropped: Long = 0,
)
