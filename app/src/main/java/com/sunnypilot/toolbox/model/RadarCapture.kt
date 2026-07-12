package com.sunnypilot.toolbox.model

import kotlinx.serialization.Serializable

/**
 * 雷达距离标定 - 采集状态 (读 /data/byd_radar/status.json)
 */
@Serializable
data class RadarCaptureStatus(
    val running: Boolean = false,      // 守护脚本是否在跑
    val active: Boolean = false,       // 是否正在采集(enabled存在)
    val marks: Int = 0,                // 已打点数
    val recentFrames: Int = 0,         // 最近1秒雷达帧数
    val targetFrames: Int = 0,         // 最近1秒"有目标"帧数(后6字节非FF)
    val targetAddrs: List<String> = emptyList(),  // 当前有目标的雷达地址
    val note: String = "",
    val time: String = ""
)

/**
 * 一个已完成的标定点 (marks 目录下的文件)
 */
@Serializable
data class RadarMark(
    val index: Int = 0,        // 打点序号
    val distanceM: Float = 0f, // 标定的真实距离(米)
    val fileName: String = "", // 文件名
    val frames: Int = 0        // 抓取的雷达帧数
)
