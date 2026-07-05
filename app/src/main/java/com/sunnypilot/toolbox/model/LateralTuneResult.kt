package com.sunnypilot.toolbox.model

import kotlinx.serialization.Serializable

@Serializable
data class LateralTuneResult(
    val success: Boolean,
    val path: String,
    val segments_analyzed: Int,
    val report: String
)
