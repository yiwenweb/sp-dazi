package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 MJPEG 的 JPEG 帧实时编码为 H.264 并封装成 MP4。
 *
 * 输入帧来自 [MjpegStreamClient.onFrame]；使用 MediaCodec 硬编码 + MediaMuxer，
 * 颜色格式 COLOR_FormatYUV420Flexible（按各 plane 的 rowStride/pixelStride 通用填充，
 * 兼容 planar(I420) 与 semi-planar(NV12/NV21)）。
 *
 * 输出到 App 专属影片目录 Movies/SunnyPilot（无需存储权限，minSdk24 通用）。
 * 所有公开方法加 @Synchronized，可在拉流线程喂帧、主线程点停止。
 */
class MjpegRecorder(private val context: Context) {

  private val recordingFlag = AtomicBoolean(false)
  val isRecording: Boolean get() = recordingFlag.get()

  private var codec: MediaCodec? = null
  private var muxer: MediaMuxer? = null
  private var trackIndex = -1
  private var muxerStarted = false

  private var width = 0
  private var height = 0
  private var startPtsUs = 0L
  private var lastPtsUs = -1L
  private var outputPath: String? = null

  /** 开始录制，返回输出文件路径。 */
  @Synchronized
  fun start(width: Int, height: Int): String {
    if (recordingFlag.get()) throw IllegalStateException("already recording")
    require(width > 0 && height > 0) { "invalid size ${width}x$height" }
    this.width = width
    this.height = height
    val path = createOutputFile()
    outputPath = path

    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
      setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
      )
      setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    }
    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    encoder.start()
    codec = encoder

    muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    trackIndex = -1
    muxerStarted = false

    startPtsUs = System.nanoTime() / 1000L
    lastPtsUs = -1L
    recordingFlag.set(true)
    Log.i(TAG, "recording started -> $path (${width}x$height)")
    return path
  }

  /** 喂一帧（Bitmap 由显示侧解码一次后复用；ptsUs 为拉流时间戳）。 */
  @Synchronized
  fun encodeBitmap(bitmap: Bitmap, ptsUs: Long) {
    if (!recordingFlag.get()) return
    val encoder = codec ?: return
    var frame = bitmap
    var ownScaled = false
    try {
      if (frame.width != width || frame.height != height) {
        frame = Bitmap.createScaledBitmap(frame, width, height, true)
        ownScaled = true
      }
      val inIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
      if (inIndex < 0) return // 编码器忙，丢这一帧
      val image = encoder.getInputImage(inIndex) ?: return
      fillYuv420(image, frame)
      val inputSize = encoder.getInputBuffer(inIndex)?.capacity() ?: (width * height * 3 / 2)

      var pts = ptsUs - startPtsUs
      if (pts <= lastPtsUs) pts = lastPtsUs + 1 // 保证时间戳严格递增
      lastPtsUs = pts

      encoder.queueInputBuffer(inIndex, 0, inputSize, pts, 0)
      drainEncoder(false)
    } catch (e: Exception) {
      Log.w(TAG, "frame encode failed: ${e.message}")
    } finally {
      if (ownScaled) frame.recycle()
    }
  }

  /** 停止并收尾，返回 MP4 路径；失败返回 null 并清理半成品。 */
  @Synchronized
  fun stop(): String? {
    if (!recordingFlag.get()) return null
    recordingFlag.set(false)
    val path = outputPath
    var ok = false
    try {
      val encoder = codec ?: return null
      val eosIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
      if (eosIndex >= 0) {
        encoder.queueInputBuffer(
          eosIndex, 0, 0, (lastPtsUs + 1).coerceAtLeast(0),
          MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
      }
      drainEncoder(true)
      encoder.stop()
      encoder.release()
      codec = null
      if (muxerStarted) {
        muxer?.stop()
        muxer?.release()
      }
      muxer = null
      ok = true
      Log.i(TAG, "recording saved -> $path")
      return path
    } catch (e: Exception) {
      Log.e(TAG, "stop failed: ${e.message}", e)
      return null
    } finally {
      try { codec?.release() } catch (_: Exception) {}
      codec = null
      try { muxer?.release() } catch (_: Exception) {}
      muxer = null
      if (!ok && path != null) File(path).delete()
    }
  }

  private fun drainEncoder(endOfStream: Boolean) {
    val encoder = codec ?: return
    val info = MediaCodec.BufferInfo()
    var loops = 0
    while (true) {
      val outIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
      when {
        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (!endOfStream) return
          if (++loops > 200) return
        }
        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          if (muxerStarted) throw IllegalStateException("format changed twice")
          trackIndex = muxer!!.addTrack(encoder.outputFormat)
          muxer!!.start()
          muxerStarted = true
        }
        outIndex >= 0 -> {
          val out = encoder.getOutputBuffer(outIndex)
          if (out != null && info.size > 0 && muxerStarted) {
            out.position(info.offset)
            out.limit(info.offset + info.size)
            muxer!!.writeSampleData(trackIndex, out, info)
          }
          encoder.releaseOutputBuffer(outIndex, false)
          if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
      }
    }
  }

  /** ARGB Bitmap → YUV420 Image，按 plane 的 rowStride/pixelStride 通用写入。 */
  private fun fillYuv420(image: android.media.Image, bitmap: Bitmap) {
    val w = width
    val h = height
    val argb = IntArray(w * h)
    bitmap.getPixels(argb, 0, w, 0, 0, w, h)

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val yRowStride = yPlane.rowStride
    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride
    val chromaH = (h + 1) / 2
    val chromaW = (w + 1) / 2

    // Y
    var pixel = 0
    for (row in 0 until h) {
      var off = row * yRowStride
      for (col in 0 until w) {
        val c = argb[pixel++]
        yBuffer.put(off++, rgbToY(c).toByte())
      }
    }

    // U / V（色度子采样，取偶数位置像素；各自使用本 plane 的 stride/pixelStride，
    // 兼容 planar(I420) 与 semi-planar(NV12/NV21，U/V 共享交错 buffer)）
    for (row in 0 until chromaH) {
      val srcRow = row * 2
      for (col in 0 until chromaW) {
        val c = argb[srcRow * w + col * 2]
        uBuffer.put(row * uRowStride + col * uPixelStride, rgbToU(c).toByte())
        vBuffer.put(row * vRowStride + col * vPixelStride, rgbToV(c).toByte())
      }
    }
  }

  private fun createOutputFile(): String {
    val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    val dir = File(movies, "SunnyPilot").apply { mkdirs() }
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return File(dir, "c3_$ts.mp4").absolutePath
  }

  private fun rgbToY(c: Int): Int {
    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
    return ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
  }

  private fun rgbToU(c: Int): Int {
    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
    return ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
  }

  private fun rgbToV(c: Int): Int {
    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
    return ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
  }

  companion object {
    private const val TAG = "MjpegRecorder"
    private const val TARGET_FPS = 15
    private const val INPUT_TIMEOUT_US = 10_000L
  }
}
