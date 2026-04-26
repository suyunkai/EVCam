package com.kooo.evcam.v2.recording

data class RecordingMetrics(
    var requestedFrames: Long = 0,
    var renderedFrames: Long = 0,
    var encodedSamples: Long = 0,
    var droppedFrames: Long = 0,
    var segmentIndex: Int = 0,
    var segmentSwitchMs: Long = 0,
    var firstSampleLatencyMs: Long = -1,
    var lastError: String = "无"
)
