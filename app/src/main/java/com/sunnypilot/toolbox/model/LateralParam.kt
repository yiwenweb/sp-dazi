package com.sunnypilot.toolbox.model

import kotlinx.serialization.Serializable

/**
 * BYD 唐 DM 横向控制参数
 *
 * 默认值 = 门总(00000006 版本)原始 rlog 逐帧实测值:
 *   steerRatio 在线学习稳定锁定 19.0 (17984 样本)
 *   steerActuatorDelay/steerLimitTimer/kp/ki/kf/deadzone 来自 carParams 静态标定
 *   latAccelFactor/friction 来自 override.toml (门总未开 torque 在线学习, 静态值即生效值)
 */
@Serializable
data class LateralParams(
    val ki: Float = 0.1f,
    val kp: Float = 1.0f,
    val kf: Float = 1.0f,
    val friction: Float = 0.1f,
    val latAccelFactor: Float = 2.75f,
    val steeringAngleDeadzoneDeg: Float = 0.0f,
    val steerRatio: Float = 19.0f,
    val steerActuatorDelay: Float = 0.3f,
    val steerLimitTimer: Float = 0.5f
) {
    companion object {
        /** 门总实测基线值 (作为默认/恢复目标) */
        val DEFAULTS = LateralParams()
    }
}
