package com.sunnypilot.toolbox.model

data class DeviceStatus(
    val name: String = "Comma C3",
    val software: String = "SunnyPilot 2025 (SP2025)",
    val hardware: String = "comma three",
    val cpuTemp: Float = 0f,
    val deviceTemp: Float = 0f,
    val bmsTemp: Float = 0f,
    val memoryUsage: Int = 0,
    val storageFree: String = "--",
    val ipAddress: String = "",
    val serial: String = "",
    val stableId: String = "",
    val isConnected: Boolean = false,
    val openpilotService: Boolean = false,
    val pandaComm: Boolean = false
)
