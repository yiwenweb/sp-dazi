package com.sunnypilot.toolbox.data.repository

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 超级视频 H.264 客户端（方案1：C3 msm_vidc 硬编码 → TCP :8082 裸 Annex-B 流）。
 *
 * 协议（见 C3 端 selfdrive/ui/sunnypilot/qt/stream/supervideo.cc）：
 *   TCP 连上后先收 CODECCONFIG（SPS/PPS，Annex-B start code），
 *   之后为实时 Annex-B NALU 连续流。
 *
 * 本类职责：
 *   - Socket 拉流 + 按 start code 切 NALU
 *   - 收到 SPS+PPS 后配置 MediaCodec(video/avc) 硬解，渲染到给定 Surface
 *   - 自动重连；每秒上报渲染帧率
 *
 * 反向触摸控制不走本类（由 VideoPreviewScreen 的 sendTouch → C3 :8081/input）。
 */
class SuperVideoClient(
  private val host: String,
  private val port: Int = 8082,
  private val onFps: (Int) -> Unit = { _ -> },
  private val onStatus: (Boolean) -> Unit = { _ -> },
  private val onResolution: (Int, Int) -> Unit = { _, _ -> },
  private val onError: (String) -> Unit = { _ -> }
) {
  private val running = AtomicBoolean(false)
  private var worker: Thread? = null

  @Volatile private var surface: Surface? = null

  /** 页面拿到 Surface 后调用；null 表示 Surface 销毁（解码器暂停重建）。 */
  fun setSurface(s: Surface?) {
    surface = s
  }

  fun start() {
    if (!running.compareAndSet(false, true)) return
    worker = Thread({ runLoop() }, "supervideo-h264").apply {
      isDaemon = true
      start()
    }
  }

  fun stop() {
    running.set(false)
    worker?.interrupt()
    worker = null
  }

  // ---------------- 网络循环 ----------------

  private fun runLoop() {
    while (running.get()) {
      try {
        Socket().use { sock ->
          sock.connect(InetSocketAddress(host, port), 3000)
          sock.tcpNoDelay = true
          sock.soTimeout = 5000
          Log.d(TAG, "connected to $host:$port")
          onStatus(true)
          try {
            streamLoop(sock.getInputStream())
          } finally {
            onStatus(false)
          }
        }
      } catch (e: Exception) {
        if (running.get()) {
          Log.w(TAG, "stream error: ${e.message}")
          onError(e.message ?: "连接失败")
        }
      }
      if (running.get()) {
        try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
      }
    }
  }

  private fun streamLoop(input: InputStream) {
    val decoderController = DecoderController({ surface }, onFps)
    try {
      val buf = ByteArray(256 * 1024)
      val carry = ByteArray(1024 * 1024)
      var carryLen = 0

      while (running.get()) {
        val n = input.read(buf)
        if (n < 0) throw IllegalStateException("stream closed")
        if (n == 0) continue

        if (carryLen + n > carry.size) {
          // 理论不会发生（一帧最多 ~100KB），防御性丢弃旧数据
          carryLen = 0
        }
        System.arraycopy(buf, 0, carry, carryLen, n)
        carryLen += n

        // 按 start code 切 NALU，返回本次可消费的长度
        val consumed = decoderController.feedNalus(carry, carryLen)
        if (consumed > 0) {
          System.arraycopy(carry, consumed, carry, 0, carryLen - consumed)
          carryLen -= consumed
        }
      }
    } finally {
      decoderController.release()
    }
  }

  // ---------------- NALU 切分 + 解码 ----------------

  private inner class DecoderController(
    private val surfaceProvider: () -> Surface?,
    private val fpsReport: (Int) -> Unit
  ) {
    private var codec: MediaCodec? = null
    private var gotSps = false
    private var gotPps = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private var frameCount = 0
    private var lastFpsTime = System.nanoTime()

    /** 输入缓冲里扫描 Annex-B：把完整 NALU 喂给解码器，返回消费字节数。 */
    fun feedNalus(data: ByteArray, len: Int): Int {
      var lastStart = findStartCode(data, len, 0) ?: return 0
      var consumed = 0
      while (true) {
        val next = findStartCode(data, len, lastStart + 3)
        if (next == null) {
          // 剩余部分可能是不完整 NALU，不消费
          if (consumed > 0) return consumed
          // 流里一直只有一个 NALU 且等不到下一个 —— 保守等更多数据
          return 0
        }
        val nalu = data.copyOfRange(lastStart, next)
        handleNalu(nalu)
        consumed = next
        lastStart = next
        if (consumed >= len) return consumed
      }
      @Suppress("UNREACHABLE_CODE")
      return consumed
    }

    private fun handleNalu(nalu: ByteArray) {
      if (nalu.size < 5) return
      // Annex-B start code 可能是 3 或 4 字节，取 NALU header 字节
      val hdrOff = if (nalu[2].toInt() == 0x01) 3 else 4
      if (hdrOff >= nalu.size) return
      val type = (nalu[hdrOff].toInt() and 0x1F)
      when (type) {
        7 -> { sps = nalu; gotSps = true }
        8 -> { pps = nalu; gotPps = true }
      }
      if (gotSps && gotPps && codec == null) {
        tryConfigureDecoder()
      }
      codec?.let { feedAndDrain(nalu, type == 5) }
    }

    private fun tryConfigureDecoder() {
      val s = surfaceProvider() ?: return   // Surface 未就绪，等下一帧再试
      val sp = sps ?: return
      val pp = pps ?: return
      try {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 640)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
        // 去掉 start code，MediaCodec CSD 需要不带 00 00 00 01 的纯 NALU
        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(stripStartCode(sp)))
        format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(stripStartCode(pp)))
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, s, null, 0)
        c.start()
        codec = c
        Log.d(TAG, "decoder configured, surface=${s != null}")
      } catch (e: Exception) {
        Log.e(TAG, "decoder configure failed: ${e.message}")
        onError("解码器配置失败: ${e.message}")
      }
    }

    private fun stripStartCode(nalu: ByteArray): ByteArray {
      val off = if (nalu[2].toInt() == 0x01) 3 else 4
      return nalu.copyOfRange(off, nalu.size)
    }

    private fun feedAndDrain(nalu: ByteArray, keyframe: Boolean) {
      val c = codec ?: return
      try {
        val inIdx = c.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
          val inBuf = c.getInputBuffer(inIdx) ?: return
          inBuf.clear()
          // MediaCodec 接受带 start code 的 Annex-B
          inBuf.put(nalu)
          val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
          c.queueInputBuffer(inIdx, 0, nalu.size, System.nanoTime() / 1000, flags)
        }
        // 每帧都 drain 输出
        val info = MediaCodec.BufferInfo()
        while (true) {
          val outIdx = c.dequeueOutputBuffer(info, 0)
          when {
            outIdx >= 0 -> {
              c.releaseOutputBuffer(outIdx, true)   // 渲染到 Surface
              countFrame()
            }
            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              val f = c.outputFormat
              onResolution(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT))
            }
            else -> break
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "decode error: ${e.message}")
      }
    }

    private fun countFrame() {
      frameCount++
      val now = System.nanoTime()
      if (now - lastFpsTime >= 1_000_000_000L) {
        fpsReport(frameCount)
        frameCount = 0
        lastFpsTime = now
      }
    }

    fun release() {
      codec?.let {
        try {
          it.stop()
          it.release()
        } catch (_: Exception) {}
      }
      codec = null
    }
  }

  private fun findStartCode(data: ByteArray, len: Int, from: Int): Int? {
    var i = from
    while (i + 3 < len) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
      i++
    }
    return null
  }

  companion object {
    private const val TAG = "SuperVideoH264"
  }
}
