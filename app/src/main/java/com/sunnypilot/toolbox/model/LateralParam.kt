package com.sunnypilot.toolbox.model

import kotlinx.serialization.Serializable

@Serializable
data class LateralParams(
    val ki: Float = 0.1f,
    val kp: Float = 1.0f,
    val friction: Float = 0.1f,
    val latAccelFactor: Float = 2.75f,
    val steeringAngleDeadzoneDeg: Float = 0.1f
) {
    companion object {
        /** sunnypilot BYD 唐 DM 当前基线值（门总 0.98 实测调参） */
        val DEFAULTS = LateralParams()
    }
}
