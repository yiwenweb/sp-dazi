package com.sunnypilot.toolbox.data.repository

import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * C3 MJPEG 拉流客户端（纯用户态，零系统改动）。
 *
 * 连接 `http://host:8081/stream`（`multipart/x-mixed-replace; boundary=c3frame`），
 * 逐帧切出 JPEG，并通过回调上报：
 *  - [onFrame]  每帧原始 JPEG 字节 + 该帧时间戳(微秒, System.nanoTime/1000)
 *  - [onFps]    每秒实际收到的帧数
 *  - [onSize]   首帧 / 分辨率变化时的图像宽高
 *  - [onStatus] 连接是否建立
 *  - [onError]  错误信息（内部会自动重连）
 *
 * 帧格式（见 C3 端 stream_server.py）：
 *   --c3frame\r\n
 *   Content-Type: image/jpeg\r\n
 *   Content-Length: NNN\r\n
 *   \r\n
 *   <NNN 字节 JPEG>\r\n
 */
class MjpegStreamClient(
  private val host: String,
  private val port: Int = 8081,
  private val onFrame: (jpeg: ByteArray, ptsUs: Long) -> Unit,
  private val onFps: (Int) -> Unit = {},
  private val onSize: (width: Int, height: Int) -> Unit = {},
  private val onStatus: (connected: Boolean) -> Unit = {},
  private val onError: (String) -> Unit = {}
) {
  private val running = AtomicBoolean(false)
  private var worker: Thread? = null

  fun start() {
    if (!running.compareAndSet(false, true)) return
    worker = thread(name = "mjpeg-stream", isDaemon = true) { runLoop() }
  }

  fun stop() {
    running.set(false)
    worker?.interrupt()
    worker = null
  }

  private fun runLoop() {
    var retry = 0
    while (running.get()) {
      var conn: HttpURLConnection? = null
      var input: InputStream? = null
      try {
        conn = (URL("http://$host:$port/stream").openConnection() as HttpURLConnection).apply {
          connectTimeout = 4000
          readTimeout = 0 // MJPEG 是持续流，不设读超时
          useCaches = false
          setRequestProperty("Cache-Control", "no-store")
        }
        conn.connect()
        val code = conn.responseCode
        if (code != 200) {
          onError("HTTP $code")
          sleep(1500)
          continue
        }
        input = BufferedInputStream(conn.inputStream, 64 * 1024)
        onStatus(true)
        retry = 0
        parseStream(input)
      } catch (e: Exception) {
        if (!running.get()) break
        Log.w(TAG, "stream error: ${e.message}")
        onStatus(false)
        onError(e.message ?: "stream error")
        retry++
        sleep(1500)
      } finally {
        try { input?.close() } catch (_: Exception) {}
        conn?.disconnect()
      }
    }
    onStatus(false)
  }

  /** 状态机解析 multipart 流。header 是 ASCII 按行读，帧体按 Content-Length 整块读。 */
  private fun parseStream(input: BufferedInputStream) {
    var frameCount = 0
    var fpsWindowNs = System.nanoTime()
    var reportedW = 0
    var reportedH = 0

    while (running.get()) {
      val line = readLine(input) ?: break
      val lineText = String(line, Charsets.US_ASCII).trim()

      // 新帧边界（结束边界 --c3frame-- 也以它开头，直接收尾）
      if (!lineText.startsWith(BOUNDARY_MARK)) continue
      if (lineText.startsWith("$BOUNDARY_MARK--")) break

      // 读 header，直到空行，解析 Content-Length
      var length = -1
      while (true) {
        val header = readLine(input) ?: break
        if (header.isEmpty()) break
        val hs = String(header, Charsets.US_ASCII).trim()
        if (hs.lowercase().startsWith("content-length:")) {
          length = hs.substringAfter(":").trim().toIntOrNull() ?: -1
        }
      }
      if (length <= 0 || length > MAX_FRAME) continue

      val jpeg = ByteArray(length)
      readFully(input, jpeg)

      val ptsUs = System.nanoTime() / 1000L

      // 首帧解析一次分辨率（inJustDecodeBounds 只读 JPEG 头，开销极小；分辨率固定不变）
      if (reportedW == 0) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
          reportedW = opts.outWidth
          reportedH = opts.outHeight
          onSize(reportedW, reportedH)
        }
      }

      onFrame(jpeg, ptsUs)

      frameCount++
      val now = System.nanoTime()
      if (now - fpsWindowNs >= 1_000_000_000L) {
        onFps(frameCount)
        frameCount = 0
        fpsWindowNs = now
      }
    }
  }

  /** 读到 \n，去掉结尾 \r\n；流结束返回 null（空行返回空数组）。 */
  private fun readLine(input: InputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    while (true) {
      val b = input.read()
      if (b == -1) return if (out.size() == 0) null else out.toByteArray()
      if (b == '\n'.code) break
      if (b != '\r'.code) out.write(b)
    }
    return out.toByteArray()
  }

  /** 反复读直到填满 out（InputStream 单次 read 不保证读满）。 */
  private fun readFully(input: InputStream, out: ByteArray) {
    var off = 0
    while (off < out.size) {
      val n = input.read(out, off, out.size - off)
      if (n < 0) throw java.io.EOFException("stream ended mid-frame at $off/${out.size}")
      off += n
    }
  }

  private fun sleep(ms: Long) {
    try { Thread.sleep(ms) } catch (_: InterruptedException) {}
  }

  companion object {
    private const val TAG = "MjpegStreamClient"
    private const val BOUNDARY_MARK = "--c3frame"
    private const val MAX_FRAME = 16 * 1024 * 1024
  }
}
