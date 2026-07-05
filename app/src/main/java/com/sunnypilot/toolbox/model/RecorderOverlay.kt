package com.sunnypilot.toolbox.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RecorderOverlay(
    val version: Int = 2,
    val segmentId: String,
    val videoFile: String?,
    val videoPath: String?,
    val cameraConfig: CameraConfigData?,
    val frameCount: Int,
    val startMonoTime: Long,
    val endMonoTime: Long,
    val frames: List<RecorderFrame>
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): RecorderOverlay = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class RecorderFrame(
    val logMonoTime: Long,
    val modelV2: ModelV2Data?,
    val radarState: RadarStateData?,
    val carState: CarStateData?,
    val carControl: CarControlData?,
    val controlsState: ControlsStateData?,
    val selfdriveState: SelfdriveStateData?,
    val longitudinalPlan: LongitudinalPlanData?,
    val liveCalibration: LiveCalibrationData?,
    val deviceState: DeviceStateData?,
    val pandaState: PandaStateData?,
    val gpsLocation: GpsLocationData?,
    val managerState: ManagerStateData?
)

@Serializable
data class ModelV2Data(
    val frameId: Int,
    val timestampEof: Long,
    val position: XYZData,
    val laneLines: List<XYZData>,
    val laneLineProbs: List<Float>,
    val roadEdges: List<XYZData>,
    val roadEdgeStds: List<Float>,
    val leadsV3: List<LeadV3Data>,
    val acceleration: AccelerationData
)

@Serializable
data class XYZData(
    val x: List<Float>,
    val y: List<Float>,
    val z: List<Float>
)

@Serializable
data class LeadV3Data(
    val prob: Float,
    val x: List<Float>,
    val y: List<Float>,
    val xStd: List<Float>
)

@Serializable
data class AccelerationData(
    val x: List<Float>
)

@Serializable
data class RadarStateData(
    val leadOne: LeadData,
    val leadTwo: LeadData
)

@Serializable
data class LeadData(
    val status: Boolean,
    val dRel: Float,
    val yRel: Float,
    val vRel: Float,
    val vLead: Float,
    val aRel: Float = 0f,
    val dPath: Float = 0f
)

@Serializable
data class CarStateData(
    val vEgo: Float,
    val aEgo: Float,
    val vEgoCluster: Float = 0f,
    val vCruiseCluster: Float = 0f,
    val steeringAngleDeg: Float,
    val steeringRateDeg: Float = 0f,
    val steeringTorque: Float = 0f,
    val steeringPressed: Boolean = false,
    val gasPressed: Boolean = false,
    val brakePressed: Boolean = false,
    val leftBlinker: Boolean = false,
    val rightBlinker: Boolean = false,
    val standstill: Boolean = false,
    val gearShifter: String = "unknown",
    val doorOpen: Boolean = false,
    val seatbeltUnlatched: Boolean = false,
    val canValid: Boolean = true,
    val cruiseState: CruiseStateData
)

@Serializable
data class CruiseStateData(
    val available: Boolean,
    val enabled: Boolean,
    val speed: Float,
    val speedCluster: Float = 0f,
    val standstill: Boolean = false
)

@Serializable
data class CarControlData(
    val enabled: Boolean = false,
    val latActive: Boolean = false,
    val longActive: Boolean = false,
    val actuators: ActuatorsData? = null,
    val hudControl: HudControlData? = null,
    val currentCurvature: Float = 0f
)

@Serializable
data class ActuatorsData(
    val torque: Float = 0f,
    val steeringAngleDeg: Float = 0f,
    val curvature: Float = 0f,
    val accel: Float = 0f,
    val longControlState: String = "off",
    val gas: Float = 0f,
    val brake: Float = 0f,
    val speed: Float = 0f
)

@Serializable
data class HudControlData(
    val speedVisible: Boolean = false,
    val setSpeed: Float = 0f,
    val lanesVisible: Boolean = false,
    val leadVisible: Boolean = false,
    val rightLaneVisible: Boolean = false,
    val leftLaneVisible: Boolean = false,
    val rightLaneDepart: Boolean = false,
    val leftLaneDepart: Boolean = false,
    val leadDistanceBars: Int = 0
)

@Serializable
data class ControlsStateData(
    val longControlState: String,
    val curvature: Float = 0f,
    val desiredCurvature: Float = 0f,
    val lateralControlState: LateralControlStateData? = null
)

@Serializable
data class LateralControlStateData(
    val active: Boolean = false,
    val steeringAngleDeg: Float = 0f,
    val steeringAngleDesiredDeg: Float = 0f,
    val actualLateralAccel: Float = 0f,
    val desiredLateralAccel: Float = 0f,
    val error: Float = 0f,
    val output: Float = 0f
)

@Serializable
data class SelfdriveStateData(
    val enabled: Boolean,
    val active: Boolean,
    val state: String,
    val alertText1: String,
    val alertText2: String,
    val experimentalMode: Boolean
)

@Serializable
data class LongitudinalPlanData(
    val allowThrottle: Boolean,
    val accels: List<Float>
)

@Serializable
data class LiveCalibrationData(
    val rpyCalib: List<Float>,
    val height: List<Float>
)

@Serializable
data class DeviceStateData(
    val networkType: String = "none",
    val networkStrength: String = "unknown",
    val networkInfo: NetworkInfoData? = null,
    val started: Boolean = false,
    val freeSpacePercent: Float = 0f,
    val memoryUsagePercent: Int = 0,
    val cpuUsagePercent: List<Int> = emptyList(),
    val cpuTempC: List<Float> = emptyList(),
    val gpuTempC: List<Float> = emptyList(),
    val memoryTempC: Float = 0f,
    val maxTempC: Float = 0f,
    val thermalStatus: String = "green",
    val fanSpeedPercentDesired: Int = 0,
    val screenBrightnessPercent: Int = 0,
    val powerDrawW: Float = 0f,
    val somPowerDrawW: Float = 0f
)

@Serializable
data class NetworkInfoData(
    val technology: String = "",
    val operator: String = "",
    val band: String = "",
    val channel: Int = 0,
    val extra: String = "",
    val state: String = ""
)

@Serializable
data class PandaStateData(
    val ignitionLine: Boolean = false,
    val ignitionCan: Boolean = false,
    val controlsAllowed: Boolean = false,
    val harnessStatus: String = "notConnected",
    val pandaType: String = "unknown",
    val voltage: Int = 0,
    val current: Int = 0,
    val fanPower: Int = 0,
    val heartbeatLost: Boolean = false
)

@Serializable
data class GpsLocationData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val bearingDeg: Float = 0f,
    val horizontalAccuracy: Float = 0f,
    val verticalAccuracy: Float = 0f,
    val speedAccuracy: Float = 0f,
    val hasFix: Boolean = false,
    val satelliteCount: Int = 0,
    val source: String = "android"
)

@Serializable
data class ManagerStateData(
    val processes: List<ProcessStateData> = emptyList()
)

@Serializable
data class ProcessStateData(
    val name: String,
    val pid: Int,
    val running: Boolean = true
)

@Serializable
data class CameraConfigData(
    val fcam: CameraData,
    val ecam: CameraData
)

@Serializable
data class CameraData(
    val width: Int,
    val height: Int,
    val focalLength: Float
)
