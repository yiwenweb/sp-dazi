package com.sunnypilot.toolbox.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RecorderOverlay(
    val version: Int = 1,
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
    val controlsState: ControlsStateData?,
    val selfdriveState: SelfdriveStateData?,
    val longitudinalPlan: LongitudinalPlanData?,
    val liveCalibration: LiveCalibrationData?
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
    val vLead: Float
)

@Serializable
data class CarStateData(
    val vEgo: Float,
    val aEgo: Float,
    val steeringAngleDeg: Float,
    val cruiseState: CruiseStateData
)

@Serializable
data class CruiseStateData(
    val available: Boolean,
    val enabled: Boolean,
    val speed: Float
)

@Serializable
data class ControlsStateData(
    val longControlState: String
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
