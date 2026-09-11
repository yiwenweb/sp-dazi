package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 C3 送来的 H.264 **Annex-B 裸码流直接封装成 MP4**（不做二次编码）。
 *
 * 与 [MjpegRecorder] 的区别：
 *  - MjpegRecorder：Bitmap → MediaCodec 硬编 → MP4（适合 MJPEG 拉流，CPU 有开销）
 *  - 本类：收到的 NALU 原样写入 MediaMuxer（零编码开销、零画质损失、零额外耗电），
 *    C3 端已经用 msm_vidc 硬编过一遍，没必要再编一次。
 *
 * 封装格式要求（MP4/AVC）：
 *  - `csd-0` / `csd-1` 必须是**去掉 start code** 的 SPS / PPS；
 *  - 每个 sample 必须是 **4 字节大端长度前缀 + NALU**（AVCC），不能用 Annex-B；
 *  - 第一个写入的 sample 必须是关键帧（IDR），否则部分播放器起播黑屏 / 无法 seek。
 *    因此 start() 之后进入「等 IDR」状态，收到首个 IDR 才真正建 muxer 并开写。
 *
 * 参数集（SPS/PPS）由 [setCsd] 提供：本 App 里由 SuperVideoClient 从它已缓存的
 * 参数集灌入 —— 这样即使录制是在流中途开始的（此时 C3 早已不再重发参数集），
 * muxer 依然能正确初始化。
 *
 * 输出到 App 专属影片目录 Movies/SunnyPilot（无需存储权限）。
 * 所有公开方法 @Synchronized：喂帧在拉流线程，开始/停止在 UI 线程。
 */
class H264StreamRecorder(private val context: Context) {

  private val recordingFlag = AtomicBoolean(false)
  val isRecording: Boolean get() = recordingFlag.get()

  private var muxer: MediaMuxer? = null
  private var trackIndex = -1
  private var muxerStarted = false
  private var outputPath: String? = null

  /** 已去 start code 的参数集，用于建 muxer 的 csd-0/csd-1。 */
  private var csdSps: ByteArray? = null
  private var csdPps: ByteArray? = null

  private var startPtsUs = 0L
  private var lastPtsUs = -1L

  /** 已写入的样本数（用于判断「等不到 IDR 就停止」的半成品）。 */
  private var sampleCount = 0

  /** 实际流的宽高，用于 muxer 的 track format。 */
  private var width = 0
  private var height = 0

  /**
   * 开始录制（准备阶段，不立即产生文件内容）。
   * 真正的 muxer 建立推迟到第一个 IDR 到达。返回输出路径。
   */
  @Synchronized
  fun start(width: Int, height: Int): String {
    if (recordingFlag.get()) throw IllegalStateException("already recording")
    require(width > 0 && height > 0) { "invalid size ${width}x$height" }
    this.width = width
    this.height = height

    val path = createOutputFile()
    outputPath = path
    muxer = null
    muxerStarted = false
    trackIndex = -1
    sampleCount = 0
    lastPtsUs = -1L
    recordingFlag.set(true)
    Log.i(TAG, "armed -> $path (${width}x$height), waiting for IDR")
    return path
  }

  /**
   * 更新参数集（Annex-B，含 start code 也可，内部会剥离）。
   * 录制中 SPS/PPS 变化（分辨率切换）时按新值生效；已建立的 muxer 不会重建。
   */
  @Synchronized
  fun setCsd(sps: ByteArray?, pps: ByteArray?) {
    sps?.let { csdSps = stripStartCode(it) }
    pps?.let { csdPps = stripStartCode(it) }
  }

  /**
   * 输入一个完整 NALU（Annex-B，含 start code）。
   *
   * 只处理 type 1（非 IDR 片）与 type 5（IDR 片）；SPS/PPS/SEI 等不进 MP4 样本
   * （参数集已由 csd-0/csd-1 承载，重复写入会被部分播放器判为非法）。
   *
   * @param tsUs 该 NALU 的到达时刻（微秒，调用方给，如 System.nanoTime()/1000）。
   */
  @Synchronized
  fun onNalu(nalu: ByteArray, type: Int, tsUs: Long) {
    if (!recordingFlag.get()) return
    if (type != 1 && type != 5) return

    val sp = csdSps ?: return
    val pp = csdPps ?: return

    try {
      if (!muxerStarted) {
        // 必须从关键帧开始，否则首个 sample 无法独立解码
        if (type != 5) return
        val format = MediaFormat.createVideoFormat(
          MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
          setByteBuffer("csd-0", ByteBuffer.wrap(sp))
          setByteBuffer("csd-1", ByteBuffer.wrap(pp))
        }
        val m = MediaMuxer(outputPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = m.addTrack(format)
        m.start()
        muxer = m
        muxerStarted = true
        // 以首个关键帧的时刻为 0 点，避免 MP4 起始 PTS 是个巨大的绝对值
        startPtsUs = tsUs
        lastPtsUs = -1L
        Log.i(TAG, "muxer started on IDR (${width}x$height)")
      }

      val payload = stripStartCode(nalu)
      if (payload.isEmpty()) return
      val buf = ByteBuffer.allocate(4 + payload.size)
      buf.putInt(payload.size)
      buf.put(payload)
      buf.rewind()

      var pts = tsUs - startPtsUs
      if (pts < 0) pts = 0
      if (pts <= lastPtsUs) pts = lastPtsUs + 1   // 保证严格递增
      lastPtsUs = pts

      val info = MediaCodec.BufferInfo().apply {
        set(0, buf.limit(), pts, if (type == 5) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
      }
      muxer?.writeSampleData(trackIndex, buf, info)
      sampleCount++
    } catch (e: Exception) {
      Log.w(TAG, "write sample failed: ${e.message}")
    }
  }

  /** 当前已写入的样本数（UI 可用来显示录制进度 / 判断是否已真正开写）。 */
  @Synchronized
  fun recordedFrames(): Int = sampleCount

  /** 停止并收尾，返回 MP4 路径；没写进任何帧则删除半成品并返回 null。 */
  @Synchronized
  fun stop(): String? {
    if (!recordingFlag.get()) return null
    recordingFlag.set(false)
    val path = outputPath
    val hadFrames = sampleCount
    try {
      if (muxerStarted) {
        muxer?.stop()
      }
      muxer?.release()
      muxer = null
      muxerStarted = false
      if (hadFrames == 0) {
        // 全程没等到 IDR：文件是空的，删掉避免留下 0 字节垃圾
        if (path != null) File(path).delete()
        Log.w(TAG, "stopped with 0 samples -> discarded $path")
        return null
      }
      Log.i(TAG, "saved $hadFrames samples -> $path")
      return path
    } catch (e: Exception) {
      Log.e(TAG, "stop failed: ${e.message}", e)
      if (path != null) runCatching { File(path).delete() }
      return null
    } finally {
      runCatching { muxer?.release() }
      muxer = null
      muxerStarted = false
      outputPath = null
    }
  }

  private fun stripStartCode(nalu: ByteArray): ByteArray {
    val off = if (nalu.size > 2 && nalu[2].toInt() == 0x01) 3 else 4
    if (off >= nalu.size) return ByteArray(0)
    return nalu.copyOfRange(off, nalu.size)
  }

  private fun createOutputFile(): String {
    val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    val dir = File(movies, "SunnyPilot").apply { mkdirs() }
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return File(dir, "c3_h264_$ts.mp4").absolutePath
  }

  companion object {
    private const val TAG = "H264StreamRecorder"
  }
}
