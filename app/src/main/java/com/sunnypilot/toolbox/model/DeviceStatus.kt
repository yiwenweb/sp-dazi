package com.sunnypilot.toolbox.model

import java.util.Locale

/**
 * 单个 CPU 核心的运行状态
 */
data class CpuCoreStatus(
    val index: Int = 0,             // 核心编号 (cpu0..cpuN)
    val online: Boolean = true,     // 是否在线
    val currentFreqKHz: Long = 0,   // 当前频率 (kHz)
    val minFreqKHz: Long = 0,       // 最低频率 (kHz)
    val maxFreqKHz: Long = 0,       // 最高频率 (kHz)
    val governor: String = "unknown", // 调频策略 (schedutil/performance/...)
    val loadPercent: Float = 0f     // 实时负载 (0~100)
) {
    /** 当前频率相对最高频率的比例 (0..1)，用于频率条显示 */
    val freqRatio: Float
        get() = if (maxFreqKHz > 0) (currentFreqKHz.toFloat() / maxFreqKHz).coerceIn(0f, 1f) else 0f

    /** 频率显示文本，如 "1.77 GHz / 2.80 GHz" 或 "1400 MHz" */
    val freqText: String
        get() {
            val cur = if (currentFreqKHz > 0) formatFreq(currentFreqKHz) else "--"
            val max = if (maxFreqKHz > 0) formatFreq(maxFreqKHz) else ""
            return if (max.isNotEmpty()) "$cur / $max" else cur
        }
}

private fun formatFreq(kHz: Long): String {
    val mhz = kHz / 1000f
    return if (mhz >= 1000f) {
        String.format(Locale.US, "%.2f GHz", mhz / 1000f)
    } else {
        "${mhz.toInt()} MHz"
    }
}

/**
 * 单个 openpilot 服务的运行状态
 */
data class ServiceStatus(
    val name: String,           // 进程名
    val displayName: String,    // 显示名称
    val running: Boolean,       // 是否正在运行
    val shouldBeRunning: Boolean = true, // 是否应该运行
    val category: String = ""   // 分类：核心服务/传感器/定位/硬件/日志通信/UI
) {
    val isAbnormal: Boolean get() = shouldBeRunning && !running
}

data class DeviceStatus(
    val name: String = "Comma C3",
    val software: String = "SunnyPilot 2025 (SP2025)",
    val hardware: String = "comma three",
    val cpuTemp: Float = 0f,
    val deviceTemp: Float = 0f,
    val bmsTemp: Float = 0f,
    val cpuLoad: Float = 0f,
    val memoryUsage: Int = 0,
    val storageFree: String = "--",
    val storageFreeSsd: String = "--",
    val ipAddress: String = "",
    val serial: String = "",
    val stableId: String = "",
    val isConnected: Boolean = false,
    val openpilotService: Boolean = false,
    val pandaComm: Boolean = false,
    /** 各关键服务的详细运行状态 */
    val serviceDetails: List<ServiceStatus> = emptyList(),
    /** 每个 CPU 核心的运行状态与负载 */
    val cpuCores: List<CpuCoreStatus> = emptyList()
) {
    /** 获取应该运行但未运行的服务列表 */
    val abnormalServices: List<ServiceStatus>
        get() = serviceDetails.filter { it.isAbnormal }

    /** 获取所有异常服务的显示名称，用顿号连接 */
    val abnormalServiceNames: String
        get() = abnormalServices.joinToString("、") { it.displayName }
}
