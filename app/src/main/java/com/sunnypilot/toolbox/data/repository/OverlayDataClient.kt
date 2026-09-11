package com.sunnypilot.toolbox.data.repository

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 叠加数据（C3 → App，TCP :8084，JSON Lines）。
 *
 * C3 侧（`/data/local/tmp/streamfwd.py`）已经用**与 Qt UI 完全相同的矩阵链**把 modelV2 的
 * 三维点投影成了 2D 像素坐标，坐标系就是视频帧本身（1928×1208）。
 * 所以 App 侧**不需要做任何投影运算**，只要按控件尺寸等比缩放即可，画出来的东西
 * 与 C3 屏幕上看到的像素级重合。
 *
 * 坐标字段一律是 [[u, v], ...]（像素），v 向下为正。
 */

@Serializable
data class OverlayLead(
  val p: Int = 0,          // present
  val d: Float = 0f,       // 距离 m
  val rv: Float = 0f,      // 相对速度 km/h（C3 侧已换算）
  val a: Float = 0f,       // 前车加速度 m/s²
  /** 前车标记在视频帧上的位置（像素，1928×1208 坐标系） */
  val px: Float? = null,
  val py: Float? = null
)

@Serializable
data class OverlayCal(
  val st: Int = 0,                     // calStatus（1 = calibrated）
  val rpy: List<Float> = emptyList(),
  val we: List<Float> = emptyList()
)

@Serializable
data class OverlayMsg(
  val id: Int = 0,
  val w: Int = 1928,
  val h: Int = 1208,
  /** 车道线多边形：4 条，每条是闭合多边形点序列 */
  val lines: List<List<List<Float>>> = emptyList(),
  val probs: List<Float> = emptyList(),
  /** 车道线透明度（Qt: clamp(prob, 0, 0.7)） */
  val lAlpha: List<Float> = emptyList(),
  /** 道路边缘多边形 */
  val edges: List<List<List<Float>>> = emptyList(),
  val eAlpha: List<Float> = emptyList(),
  /** 行车轨迹多边形 */
  val track: List<List<Float>> = emptyList(),
  val lead: OverlayLead? = null,
  // ── 车辆状态 ──
  val v: Float? = null,        // 车速 m/s
  val a: Float? = null,        // 纵向加速度 m/s²
  val sa: Float? = null,       // 转向角 度
  val tq: Float? = null,       // 转向扭矩（EPS）
  val lat: Int? = null,        // 横向控制激活
  val lon: Int? = null,        // 纵向控制激活
  val en: Int? = null,         // 整体激活
  val cpu: Float? = null,      // C3 CPU 温度 °C
  val bat: Int? = null,        // 电量 %
  val mem: Int? = null,        // 内存占用 %
  val cal: OverlayCal? = null
)

class OverlayDataClient(
  private val host: String,
  private val port: Int = 8084,
  private val onFrame: (OverlayMsg) -> Unit,
  private val onStatus: (Boolean) -> Unit = {}
) {
  private val running = AtomicBoolean(false)
  private var worker: Thread? = null
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  fun start() {
    if (!running.compareAndSet(false, true)) return
    worker = Thread({ runLoop() }, "overlay-data").apply {
      isDaemon = true
      start()
    }
  }

  fun stop() {
    running.set(false)
    worker?.interrupt()
    worker = null
    onStatus(false)
  }

  private fun runLoop() {
    while (running.get()) {
      var sock: Socket? = null
      try {
        sock = Socket().apply {
          tcpNoDelay = true
          connect(InetSocketAddress(host, port), 3000)
        }
        Log.i(TAG, "connected $host:$port")
        onStatus(true)
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()), 64 * 1024)
        while (running.get()) {
          val line = reader.readLine() ?: break
          if (line.isBlank()) continue
          try {
            onFrame(json.decodeFromString(OverlayMsg.serializer(), line))
          } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${e.message}")
          }
        }
      } catch (e: Exception) {
        if (running.get()) Log.w(TAG, "connect/read failed: ${e.message}")
      } finally {
        onStatus(false)
        try {
          sock?.close()
        } catch (_: Exception) {
        }
      }
      if (running.get()) {
        try {
          Thread.sleep(1000)
        } catch (_: InterruptedException) {
          break
        }
      }
    }
  }

  companion object {
    private const val TAG = "OverlayDataClient"
  }
}
