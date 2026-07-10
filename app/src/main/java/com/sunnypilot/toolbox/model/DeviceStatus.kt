package com.sunnypilot.toolbox.model

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
    val serviceDetails: List<ServiceStatus> = emptyList()
) {
    /** 获取应该运行但未运行的服务列表 */
    val abnormalServices: List<ServiceStatus>
        get() = serviceDetails.filter { it.isAbnormal }

    /** 获取所有异常服务的显示名称，用顿号连接 */
    val abnormalServiceNames: String
        get() = abnormalServices.joinToString("、") { it.displayName }
}
