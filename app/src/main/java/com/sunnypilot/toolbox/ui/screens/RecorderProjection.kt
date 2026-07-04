package com.sunnypilot.toolbox.ui.screens

import com.sunnypilot.toolbox.model.CameraConfigData
import com.sunnypilot.toolbox.model.CameraData
import kotlin.math.cos
import kotlin.math.sin

/**
 * 把 C3 车坐标系下的 3D 点投影到视频画面上。
 * 参考 openpilot/selfdrive/ui/onroad/augmented_road_view.py 的 _calc_frame_matrix。
 */
class RecorderProjection(
    cameraConfig: CameraConfigData,
    rpyCalib: List<Float>,
    private val contentWidth: Float,
    private val contentHeight: Float,
    private val contentX: Float = 0f,
    private val contentY: Float = 0f,
    private val zoom: Float = 1.1f
) {
    private val fcam: CameraData = cameraConfig.fcam
    private val cx: Float = fcam.width / 2f
    private val cy: Float = fcam.height / 2f

    // device_frame_from_view_frame 的转置
    private val viewFromDevice: Array<FloatArray> = arrayOf(
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(1f, 0f, 0f)
    )

    private val intrinsic: Array<FloatArray> = arrayOf(
        floatArrayOf(fcam.focalLength, 0f, cx),
        floatArrayOf(0f, fcam.focalLength, cy),
        floatArrayOf(0f, 0f, 1f)
    )

    private val viewFromCalib: Array<FloatArray>
    private val carSpaceTransform: Array<FloatArray>

    init {
        val deviceFromCalib = rotFromEuler(rpyCalib[0], rpyCalib[1], rpyCalib[2])
        viewFromCalib = multiply3x3(viewFromDevice, deviceFromCalib)
        carSpaceTransform = computeTransform()
    }

    /**
     * 投影单个 3D 点到屏幕坐标；返回 null 表示在画面外或无法投影。
     */
    fun project(x: Float, y: Float, z: Float): Pair<Float, Float>? {
        val t = carSpaceTransform
        val px = t[0][0] * x + t[0][1] * y + t[0][2] * z
        val py = t[1][0] * x + t[1][1] * y + t[1][2] * z
        val pz = t[2][0] * x + t[2][1] * y + t[2][2] * z
        if (kotlin.math.abs(pz) < 1e-6f) return null
        val sx = px / pz
        val sy = py / pz
        if (sx < contentX || sx > contentX + contentWidth ||
            sy < contentY || sy > contentY + contentHeight
        ) {
            return null
        }
        return sx to sy
    }

    private fun computeTransform(): Array<FloatArray> {
        val calibTransform = multiply3x3(intrinsic, viewFromCalib)

        val infPoint = floatArrayOf(1000f, 0f, 0f)
        val kep = multiply3x3ByVector(calibTransform, infPoint)

        val margin = 5f
        val maxXOffset = cx * zoom - contentWidth / 2f - margin
        val maxYOffset = cy * zoom - contentHeight / 2f - margin

        val xOffset = if (kotlin.math.abs(kep[2]) > 1e-6f) {
            val raw = (kep[0] / kep[2] - cx) * zoom
            raw.coerceIn(-maxXOffset, maxXOffset)
        } else 0f

        val yOffset = if (kotlin.math.abs(kep[2]) > 1e-6f) {
            val raw = (kep[1] / kep[2] - cy) * zoom
            raw.coerceIn(-maxYOffset, maxYOffset)
        } else 0f

        val videoTransform = arrayOf(
            floatArrayOf(zoom, 0f, (contentWidth / 2f + contentX - xOffset) - (cx * zoom)),
            floatArrayOf(0f, zoom, (contentHeight / 2f + contentY - yOffset) - (cy * zoom)),
            floatArrayOf(0f, 0f, 1f)
        )

        return multiply3x3(videoTransform, calibTransform)
    }

    companion object {
        fun rotFromEuler(roll: Float, pitch: Float, yaw: Float): Array<FloatArray> {
            val cr = cos(roll)
            val sr = sin(roll)
            val cp = cos(pitch)
            val sp = sin(pitch)
            val cy = cos(yaw)
            val sy = sin(yaw)

            return arrayOf(
                floatArrayOf(cp * cy, sr * sp * cy - cr * sy, cr * sp * cy + sr * sy),
                floatArrayOf(cp * sy, sr * sp * sy + cr * cy, cr * sp * sy - sr * cy),
                floatArrayOf(-sp, sr * cp, cr * cp)
            )
        }

        fun multiply3x3(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
            val out = Array(3) { FloatArray(3) }
            for (i in 0..2) {
                for (j in 0..2) {
                    var sum = 0f
                    for (k in 0..2) {
                        sum += a[i][k] * b[k][j]
                    }
                    out[i][j] = sum
                }
            }
            return out
        }

        fun multiply3x3ByVector(m: Array<FloatArray>, v: FloatArray): FloatArray {
            return floatArrayOf(
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
            )
        }
    }
}
