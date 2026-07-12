package com.sunnypilot.toolbox.model

import kotlinx.serialization.Serializable

/**
 * DEBUG 看门狗状态 (读 /data/byd_debug/status.json)
 */
@Serializable
data class DebugStatus(
    val running: Boolean = false,   // 守护进程是否在跑
    val active: Boolean = false,    // 是否已开启监测(enabled 文件存在)
    val triggers: Int = 0,          // 已捕获事件数
    val buffer: Int = 0,            // 当前缓冲记录条数
    val eps: String = "?",          // EPS TorqueFailed 状态: OK/LOCKED/?
    val dumping: Boolean = false,   // 是否正在录制触发尾巴
    val note: String = "",          // 状态描述
    val time: String = ""           // 心跳时间
)

/**
 * 单个故障事件 (读事件目录下 event.json)
 */
@Serializable
data class DebugEvent(
    val fault: String = "",          // LAT1/LAT2/LON1/LON2/SYS1
    val faultName: String = "",      // 中文名
    val triggerTime: Double = 0.0,
    val triggerTimeStr: String = "",
    val clue: String = "",           // 一级线索
    val records: Int = 0,
    val spanSec: Double = 0.0,
    val bufferSec: Int = 0,
    val postSec: Double = 0.0,
    // 运行时附加(非json字段)
    val dirName: String = "",        // 事件目录名(用于下载/删除)
    val hasDump: Boolean = false     // dump.jsonl 是否存在
)
