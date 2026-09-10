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

  /** Surface 就绪/变化时置位，通知解码线程重建解码器（见 waitForSurface）。 */
  @Volatile private var surfaceReady = false

  /** Surface 换代计数：TextureView 重建/旋转时 +1，解码器据此重建。 */
  @Volatile private var surfaceGeneration = 0

  /**
   * 兜底 Surface 提供者：Compose 有两条绑定路径可能都错过（时序竞态），
   * 设置后 worker 在每次解码器尝试配置时都会回调它拿 Surface。
   * 由 UI 层传入，直接返回当前 TextureView 的 Surface（可能为 null）。
   */
  @Volatile private var fallbackSurfaceProvider: (() -> Surface?)? = null

  fun setFallbackSurfaceProvider(p: () -> Surface?) {
    fallbackSurfaceProvider = p
  }

  /** 页面拿到 Surface 后调用；null 表示 Surface 销毁（解码器暂停重建）。 */
  fun setSurface(s: Surface?) {
    surface = s
    surfaceReady = s != null
    if (s != null) surfaceGeneration++
    Log.i(TAG, "setSurface ready=${s != null} gen=$surfaceGeneration")
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
          // 超时只用于让 read() 有机会检查 running，不代表流断了。
          // C3 在画面静止时会大幅跳帧，长静默是正常的：绝不能在超时时
          // 断开重连，否则解码器被反复重建，画面永远出不来（黑屏）。
          sock.soTimeout = 10000
          Log.d(TAG, "connected to $host:$port")
          onStatus(true)
          try {
            streamLoop(sock.getInputStream())
          } finally {
            onStatus(false)
          }
        }
      } catch (e: java.net.SocketTimeoutException) {
        // 静默期，非错误：继续同一连接读取（streamLoop 内部已处理）
        Log.d(TAG, "read timeout (idle), keeping connection")
      } catch (e: Exception) {
        if (running.get()) {
          Log.w(TAG, "stream error: ${e::class.java.simpleName}: ${e.message}")
          onError(e.message ?: "连接失败")
        }
      }
      if (running.get()) {
        try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
      }
    }
  }

  private fun streamLoop(input: InputStream) {
    val decoderController = DecoderController({ surface }, surfaceGeneration, onFps)
    try {
      val buf = ByteArray(256 * 1024)
      val carry = ByteArray(1024 * 1024)
      var carryLen = 0
      var totalBytes = 0L
      var reads = 0

      while (running.get()) {
        val n = try {
          input.read(buf)
        } catch (e: java.net.SocketTimeoutException) {
          // 静默期：解码器保持存活，继续读同一连接。C3 静止画面会跳帧，
          // 这不是流断裂 —— 直接退出 loop 会销毁解码器导致黑屏。
          continue
        }
        if (n < 0) throw IllegalStateException("stream closed")
        if (n == 0) continue
        totalBytes += n
        reads++

        if (carryLen + n > carry.size) {
          // 理论不会发生（一帧最多 ~100KB），防御性丢弃旧数据
          Log.w(TAG, "carry overflow (carryLen=$carryLen n=$n), dropping")
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
        if (reads % 50 == 0) {
          Log.d(TAG, "stream: $totalBytes bytes / $reads reads, carry=$carryLen")
        }
      }
    } finally {
      Log.i(TAG, "streamLoop exit")
      decoderController.release()
    }
  }

  // ---------------- NALU 切分 + 解码 ----------------

  private inner class DecoderController(
    private val surfaceProvider: () -> Surface?,
    private val surfaceGenAtCreate: Int,
    private val fpsReport: (Int) -> Unit
  ) {
    private var codec: MediaCodec? = null
    private var gotSps = false
    private var gotPps = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var boundGeneration = -1
    private var naluCount = 0
    private var lastLogNalu = 0

    private var frameCount = 0
    private var lastFpsTime = System.nanoTime()

    /** 输入缓冲里扫描 Annex-B：把完整 NALU 喂给解码器，返回消费字节数。 */
    fun feedNalus(data: ByteArray, len: Int): Int {
      // Surface 变化（重建/旋转）时丢弃旧解码器，用新 Surface 重建
      if (codec != null && boundGeneration != surfaceGeneration) {
        Log.i(TAG, "surface changed (gen $boundGeneration -> $surfaceGeneration), rebuilding codec")
        release()
      }
      if (codec == null && gotSps && gotPps) {
        tryConfigureDecoder()   // Surface 迟到时在此补建解码器
      }
      var lastStart = findStartCode(data, len, 0) ?: return 0
      var consumed = 0
      while (true) {
        val next = findStartCode(data, len, lastStart + 3)
        if (next == null) {
          // 尾部 NALU 尚无后继 start code，本轮的边界无法确定；
          // 返回 consumed 让尾巴留在 carry 里，等下一批数据补齐。
          // 注意：不能在此"丢弃"尾巴，否则该 NALU（常是 IDR）永不提交。
          return consumed
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
      naluCount++
      if (naluCount <= 8) {
        Log.d(TAG, "nalu#$naluCount type=$type len=${nalu.size} hdrOff=$hdrOff")
      }
      when (type) {
        7 -> { sps = nalu; gotSps = true; Log.i(TAG, "got SPS len=${nalu.size}") }
        8 -> { pps = nalu; gotPps = true; Log.i(TAG, "got PPS len=${nalu.size}") }
      }
      // 解码器未配置时每次收到 NALU 都重试，直到 Surface 就绪 ——
      // 否则首帧到达时 Surface 还没 attach，解码器永不建立（黑屏）。
      if (gotSps && gotPps && codec == null) {
        tryConfigureDecoder()
      }
      // SPS/PPS 已作为 CSD 交给解码器，不再重复喂入数据队列。
      if (type == 7 || type == 8) return
      codec?.let { feedAndDrain(nalu, type == 5) } ?: run {
        if (naluCount <= 8 || naluCount % 200 == 0) {
          Log.w(TAG, "nalu#$naluCount type=$type dropped: codec null")
        }
      }
    }

    private fun tryConfigureDecoder() {
      // 正常路径：UI 已通过 setSurface 送达。兜底路径：直接向 TextureView 取。
      var s = surfaceProvider()
      if (s == null) {
        s = fallbackSurfaceProvider?.invoke()
        if (s != null) {
          Log.i(TAG, "surface obtained via fallback provider (setSurface was never delivered)")
          setSurface(s)
        }
      }
      if (s == null) {
        // 每 ~200 个 NALU 提示一次，避免刷屏
        if (naluCount == 0 || naluCount - lastLogNalu > 200) {
          lastLogNalu = naluCount
          Log.w(TAG, "codec NOT configured: surface null (naluCount=$naluCount, sps=$gotSps pps=$gotPps)")
        }
        return
      }
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
        boundGeneration = surfaceGeneration
        Log.i(TAG, "decoder configured OK, gen=$surfaceGeneration, spsLen=${stripStartCode(sp).size}, ppsLen=${stripStartCode(pp).size}")
      } catch (e: Exception) {
        Log.e(TAG, "decoder configure FAILED: ${e::class.java.simpleName}: ${e.message}", e)
        onError("解码器配置失败: ${e.message}")
      }
    }

    private fun stripStartCode(nalu: ByteArray): ByteArray {
      val off = if (nalu.size > 2 && nalu[2].toInt() == 0x01) 3 else 4
      if (off >= nalu.size) return ByteArray(0)
      return nalu.copyOfRange(off, nalu.size)
    }

    private fun feedAndDrain(nalu: ByteArray, keyframe: Boolean) {
      val c = codec ?: return
      try {
        // 先 drain 再喂：解码器输出队列必须先排空，否则输入缓冲会被占满
        drainOutput(c)
        var inIdx = c.dequeueInputBuffer(10_000)
        if (inIdx < 0) {
          // 输入缓冲暂时耗尽（解码器落后），排空输出后重试一次，避免丢帧
          drainOutput(c)
          inIdx = c.dequeueInputBuffer(10_000)
        }
        if (inIdx >= 0) {
          val inBuf = c.getInputBuffer(inIdx) ?: return
          inBuf.clear()
          // 部分设备固件对"带 start code 的 Annex-B"处理不一致，统一去掉
          // start code 只喂裸 NALU（与 CSD 的 stripStartCode 保持一致）。
          val payload = stripStartCode(nalu)
          if (payload.isEmpty()) return
          inBuf.put(payload)
          val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
          c.queueInputBuffer(inIdx, 0, payload.size, System.nanoTime() / 1000, flags)
        }
        drainOutput(c)
      } catch (e: Exception) {
        Log.w(TAG, "decode error: ${e.message}")
      }
    }

    private fun drainOutput(c: MediaCodec) {
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
            Log.i(TAG, "output format changed: ${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}")
            onResolution(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT))
          }
          outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* legacy, ignore */ }
          outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
          else -> break
        }
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
