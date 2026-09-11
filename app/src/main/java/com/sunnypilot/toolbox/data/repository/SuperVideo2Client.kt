package com.sunnypilot.toolbox.data.repository

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 超级视频2 客户端 —— 对应第三方 `openpilot_toolbox` 的 C3 投屏协议。
 *
 * **与 [SuperVideoClient] 完全独立**：后者是我们自己的 Plan 1（C3 `supervideo.cc`，
 * 视频 :8082 / 触控 HTTP :8081），本类走第三方的（视频 TCP :5000 / 触控 TCP :27184）。
 *
 * ## 视频链路（TCP :5000）
 *
 * C3 侧三级管道产出**裸 Annex-B H.264 流**：
 * ```
 * sde_rotator_stream | swscale_xrgb_to_nv12 | v4l_h264_encoder 1024 512 20 4500000 tcp
 * ```
 * 流特征：
 *  - 编码分辨率 **1024×512**（不是 1280×640，见 w5.java 的 C3 分支）
 *  - 帧率 20 fps，码率 4.5 Mbps
 *  - 无自定义包头，连上即收 Annex-B NALU（与 :8082 不同，后者先发 CODECCONFIG）
 *  - 进程自己给帧打时间戳（`unit_sequence`/`last_write_ns`），但我们**不依赖它** ——
 *    仍然按流内帧序号自造单调 pts（见 [feedAndDrain] 的踩坑记录）
 *
 * ## 触控链路（TCP :27184）
 *
 * 32 字节**大端**定长包头，格式 `>BBQIIHHHII`：
 * ```
 * 偏移  类型  字段        含义
 *  0    u8    msg_type    0x7F=心跳, 2=触摸
 *  1    u8    action      0=down, 1/3=up, 2=move
 *  2    u64   _           保留（App 侧写 -2L 作为序号占位）
 * 10    u32   x           触控区内的 x
 * 14    u32   y           触控区内的 y
 * 18    u16   width       触控区宽（App 侧填 view 宽）
 * 20    u16   height      触控区高
 * 22    u16   _           压力（down=65535, up=0）
 * 24    u32   _           保留 0
 * 28    u32   _           保留 0
 * ```
 * 心跳/时钟同步（**方向关键，勿搞反**）：
 * 连接建立后 **App 主动发一个 32 字节、首字节 `0x7F` 的包**，然后阻塞读 8 字节大端
 * 微秒时间戳，据此计算时钟偏移（`offset = tC3 - (t0 + t1)/2`，t0/t1 为发送前后本地时间）。
 * 这是**一次性的握手**，不是循环心跳。
 *
 * 来源（`cb/j0.java:59-66` 实测）：
 * ```java
 * byte[] bArr = new byte[32];
 * bArr[0] = Byte.MAX_VALUE;                  // 0x7F
 * long t0 = System.nanoTime() / 1000;
 * dataOutputStream.write(bArr); dataOutputStream.flush();
 * w5Var.f4375p = dataInputStream.readLong() - ((t0 + (System.nanoTime() / 1000)) / 2);
 * ```
 *
 * C3 侧坐标变换（`touch_proxy.py`）：
 * ```
 * raw_x = 1080 - scale(y, height - 1, 1080)
 * raw_y =        scale(x, width  - 1, 2160)
 * ```
 * 即 App 只需送**归一化到 0..width-1 / 0..height-1** 的坐标，映射交给 C3。
 *
 * ## 设计取舍
 *
 * 解码器的健壮性设计（per-connection 黑名单、延迟判死、Surface 双向收敛）
 * 直接沿用 [SuperVideoClient] 的成熟做法 —— 那套逻辑是踩了大量坑得到的，不重复踩。
 */
class SuperVideo2Client(
  private val host: String,
  private val videoPort: Int = 5000,
  private val touchPort: Int = 27184,
  private val onFps: (Int) -> Unit = { _ -> },
  private val onStatus: (Boolean) -> Unit = { _ -> },
  private val onResolution: (Int, Int) -> Unit = { _, _ -> },
  private val onDiag: (List<String>) -> Unit = { _ -> },
  /**
   * 连接失败时的远端诊断钩子（由 UI 层注入，内部走 SSH 拉 C3 日志）。
   * 返回若干行文本，会追加进诊断面板。
   *
   * 存在意义：C3 上"端口连不上"最常见的原因是上游抓屏进程
   * 首帧就失败退出（capture.log 里会出现 `rotator frame error`），
   * 而在车机屏幕上原本只能看到一个 ECONNREFUSED，必须 SSH 才知道真相。
   */
  private val onRemoteLog: suspend (String) -> List<String> = { _ -> emptyList() }
) {

  // ═══════════════════════════ 公共接口 ═══════════════════════════

  private val running = AtomicBoolean(false)
  private var videoWorker: Thread? = null
  private var touchWorker: Thread? = null

  @Volatile private var surface: Surface? = null
  @Volatile private var surfaceReady = false
  @Volatile private var surfaceGeneration = 0
  @Volatile private var fallbackSurfaceProvider: (() -> Surface?)? = null

  @Volatile var activeCodecName: String? = null
    private set

  /** 最近一次触摸发送是否成功（UI 可据此提示"触摸未连通"）。 */
  @Volatile var touchConnected: Boolean = false

  /** 远端日志只拉一次，避免重连风暴里反复 SSH。 */
  private val remoteLogFetched = java.util.concurrent.atomic.AtomicBoolean(false)

  /** 与 C3 的时钟偏移（微秒），由握手阶段算出。仅作诊断展示。 */
  @Volatile var clockOffsetUs: Long = 0L
    private set

  fun setFallbackSurfaceProvider(p: () -> Surface?) {
    fallbackSurfaceProvider = p
  }

  fun setSurface(s: Surface?) {
    surface = s
    surfaceReady = s != null
    if (s != null) {
      surfaceGeneration++
      bump("surf_set")
      diag("Surface 送达 gen=$surfaceGeneration")
    } else {
      diag("Surface 置空（TextureView 销毁）")
    }
    Log.i(TAG, "setSurface ready=${s != null} gen=$surfaceGeneration")
  }

  fun start() {
    if (!running.compareAndSet(false, true)) return
    videoWorker = Thread({ runVideoLoop() }, "sv2-h264").apply {
      isDaemon = true
      start()
    }
    touchWorker = Thread({ runTouchLoop() }, "sv2-touch").apply {
      isDaemon = true
      start()
    }
  }

  fun stop() {
    running.set(false)
    videoWorker?.interrupt()
    touchWorker?.interrupt()
    videoWorker = null
    touchWorker = null
    touchQueue.clear()
  }

  // ═══════════════════════════ 诊断 ═══════════════════════════

  private val diagLines = java.util.concurrent.ConcurrentLinkedQueue<String>()
  private val counters = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
  private val diagThrottle = AtomicLong(0)

  private fun bump(k: String) {
    counters.computeIfAbsent(k) { AtomicLong() }.incrementAndGet()
  }

  private fun diag(msg: String) {
    Log.i(TAG, "DIAG $msg")
    diagLines.add(msg)
    while (diagLines.size > 40) diagLines.poll()
    publishDiag()
  }

  private fun publishDiag(force: Boolean = false) {
    val now = System.currentTimeMillis()
    val last = diagThrottle.get()
    if (!force && now - last < 500) return
    if (!diagThrottle.compareAndSet(last, now)) return
    val c = counters
    val head = listOf(
      "sock: ${c["connect"] ?: 0} 连上 / ${c["disconnect"] ?: 0} 断开",
      "recv: ${c["bytes"] ?: 0} B, ${c["reads"] ?: 0} 次读",
      "nalu: ${c["nalu"] ?: 0} 个 (SPS ${c["sps"] ?: 0} PPS ${c["pps"] ?: 0} IDR ${c["idr"] ?: 0} P ${c["slice"] ?: 0})",
      "codec: ${activeCodecName ?: "-"} (启${c["cfg_ok"] ?: 0} 败${c["cfg_fail"] ?: 0} 死${c["codec_dead"] ?: 0}) " +
        "入${c["queued"] ?: 0} 出${c["frames"] ?: 0}",
      "surface: 送达${c["surf_set"] ?: 0} 兜底${c["surf_fb"] ?: 0} 空${c["surf_null"] ?: 0}",
      "touch: ${if (touchConnected) "已连通" else "未连通"} 发${c["touch_tx"] ?: 0} 心跳${c["touch_hb"] ?: 0}"
    )
    onDiag(head + diagLines.toList().takeLast(10))
  }

  // ═══════════════════════════ 视频：网络循环 ═══════════════════════════

  class DecoderDeadException : RuntimeException("all codec candidates dead")

  /** 假解码器黑名单 —— **按连接会话作用域**（详见 SuperVideoClient 的同名字段注释）。 */
  private val codecBlacklist = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val codecPreferred = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  @Volatile private var sessionEpoch = 0

  private fun runVideoLoop() {
    while (running.get()) {
      try {
        Socket().use { sock ->
          sock.connect(InetSocketAddress(host, videoPort), 3000)
          sock.tcpNoDelay = true
          // 超时只用于让 read() 有机会检查 running，不代表流断了。
          // C3 静止画面会跳帧，长静默正常 —— 绝不能超时重连，否则解码器反复重建 → 黑屏。
          sock.soTimeout = 10000
          Log.d(TAG, "video connected $host:$videoPort")
          bump("connect")
          diag("视频已连接 $host:$videoPort")
          onStatus(true)
          try {
            streamLoop(sock.getInputStream())
          } finally {
            onStatus(false)
          }
        }
      } catch (e: java.net.SocketTimeoutException) {
        Log.d(TAG, "video read timeout (idle), keep connection")
      } catch (e: DecoderDeadException) {
        Log.w(TAG, "all decoders dead, reconnecting")
        bump("disconnect")
        publishDiag(true)
      } catch (e: Exception) {
        if (running.get()) {
          Log.w(TAG, "video error: ${e::class.java.simpleName}: ${e.message}")
          val msg = e.message.orEmpty()
          val refused = msg.contains("ECONNREFUSED") ||
            msg.contains("Connection refused") ||
            msg.contains("ConnectException")
          diag(
            if (refused) {
              // 连不上端口 = 编码器没起来 = 上游 rotator/swscale 已退出
              "上游未就绪：端口 $videoPort 拒绝连接（编码器未运行）"
            } else {
              "视频错误 ${e::class.java.simpleName}: $msg"
            }
          )
          // 首次拒绝连接时，拉一次远端日志，把真正的原因暴露到面板上
          if (refused && remoteLogFetched.compareAndSet(false, true)) {
            val lines = try {
              kotlinx.coroutines.runBlocking { onRemoteLog("refused") }
            } catch (te: Throwable) {
              Log.w(TAG, "remote log fetch failed: ${te.message}")
              emptyList()
            }
            lines.forEach { diag("c3| $it") }
          }
          bump("disconnect")
          publishDiag(true)
        }
      }
      if (running.get()) {
        try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
      }
    }
  }

  private fun streamLoop(input: java.io.InputStream) {
    if (codecBlacklist.isNotEmpty()) {
      Log.i(TAG, "new session: clearing codec blacklist $codecBlacklist")
    }
    codecBlacklist.clear()
    sessionEpoch++
    val decoder = DecoderController({ surface }, onFps)

    try {
      val buf = ByteArray(256 * 1024)
      val carry = ByteArray(1024 * 1024)
      var carryLen = 0
      var reads = 0

      while (running.get()) {
        val n = try {
          input.read(buf)
        } catch (e: java.net.SocketTimeoutException) {
          bump("idle_timeout")
          publishDiag()
          continue
        }
        if (n < 0) throw IllegalStateException("stream closed")
        if (n == 0) continue
        reads++
        counters.computeIfAbsent("bytes") { AtomicLong() }.addAndGet(n.toLong())
        counters.computeIfAbsent("reads") { AtomicLong() }.incrementAndGet()
        if (reads == 1) diag("首次收到数据 n=$n")

        if (carryLen + n > carry.size) {
          Log.w(TAG, "carry overflow (carryLen=$carryLen n=$n), dropping")
          carryLen = 0
        }
        System.arraycopy(buf, 0, carry, carryLen, n)
        carryLen += n

        val consumed = try {
          decoder.feedNalus(carry, carryLen)
        } catch (e: DecoderDeadException) {
          diag("解码链全灭，重连以获取新关键帧")
          throw e
        }
        if (consumed > 0) {
          System.arraycopy(carry, consumed, carry, 0, carryLen - consumed)
          carryLen -= consumed
        }
        if (reads % 50 == 0) {
          Log.d(TAG, "stream: ${counters["bytes"]} bytes / $reads reads, carry=$carryLen")
          publishDiag()
        }
      }
    } finally {
      Log.i(TAG, "streamLoop exit")
      decoder.release()
    }
  }

  // ═══════════════════════════ 视频：NALU → MediaCodec ═══════════════════════════

  private inner class DecoderController(
    private val surfaceProvider: () -> Surface?,
    private val fpsReport: (Int) -> Unit
  ) {
    private var codec: MediaCodec? = null
    private var codecName: String = "?"
    private var gotSps = false
    private var gotPps = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var boundGeneration = -1
    private var naluCount = 0
    private var lastLogNalu = 0
    private var feedPtsUs = 0L
    private var frameCount = 0
    private var lastFpsTime = System.nanoTime()

    private var totalQueued = 0L
    private var totalOutputs = 0L
    private val triedCodecNames: MutableSet<String> get() = codecBlacklist
    private val noOutputByteLimit = 4L * 1024 * 1024
    private val noOutputMinDurationMs = 4000L
    private var codecCreatedAt = 0L
    private var queuedCount = 0
    private var sawInputStarved = 0
    private var sawTryAgain = 0
    private var sawOutputBuffersChanged = 0
    private var lastNamedCandidates: List<String> = emptyList()

    fun feedNalus(data: ByteArray, len: Int): Int {
      if (codec != null && boundGeneration != surfaceGeneration) {
        Log.i(TAG, "surface changed ($boundGeneration -> $surfaceGeneration), rebuilding codec")
        release()
      }
      if (codec == null && gotSps && gotPps) tryConfigureDecoder()

      var lastStart = findStartCode(data, len, 0) ?: return 0
      var consumed = 0
      while (true) {
        val next = findStartCode(data, len, lastStart + 3) ?: return consumed
        handleNalu(data.copyOfRange(lastStart, next))
        consumed = next
        lastStart = next
        if (consumed >= len) return consumed
      }
      @Suppress("UNREACHABLE_CODE")
      return consumed
    }

    private fun handleNalu(nalu: ByteArray) {
      if (nalu.size < 5) return
      val hdrOff = if (nalu[2].toInt() == 0x01) 3 else 4
      if (hdrOff >= nalu.size) return
      val type = nalu[hdrOff].toInt() and 0x1F
      naluCount++
      bump("nalu")
      when (type) {
        7 -> bump("sps"); 8 -> bump("pps"); 5 -> bump("idr"); 1 -> bump("slice")
      }
      when (type) {
        7 -> { sps = nalu; gotSps = true; diag("收到 SPS len=${nalu.size}") }
        8 -> { pps = nalu; gotPps = true; diag("收到 PPS len=${nalu.size}") }
      }
      if (gotSps && gotPps && codec == null) tryConfigureDecoder()
      // ★ 与 SuperVideoClient 同一处根因修复：IDR 前重发带内 SPS/PPS。
      // 只给 csd-0/csd-1 时，硬解器会在新 GOP 上找不到参数集而静默丢帧
      // （现象：输入全吃下、输出恒 0、无任何报错）。详见 SuperVideoClient.handleNalu。
      if (type == 5 && codec != null) {
        sps?.let { feedAndDrain(it, keyframe = false) }
        pps?.let { feedAndDrain(it, keyframe = false) }
      }
      codec?.let { feedAndDrain(nalu, type == 5) } ?: run {
        bump("drop_nocodec")
      }
    }

    private fun tryConfigureDecoder() {
      var s = surfaceProvider()
      if (s == null) {
        s = fallbackSurfaceProvider?.invoke()
        if (s != null) {
          bump("surf_fb")
          diag("Surface 由兜底路径取得")
          setSurface(s)
        }
      }
      if (s == null) {
        bump("surf_null")
        if (naluCount == 0 || naluCount - lastLogNalu > 200) {
          lastLogNalu = naluCount
          diag("解码器未配置：Surface 为空 (nalu=$naluCount sps=$gotSps pps=$gotPps)")
        }
        return
      }
      val sp = sps ?: return
      val pp = pps ?: return

      val candidates = buildCodecCandidates()
      lastNamedCandidates = candidates.filterNotNull()
      for (name in candidates) {
        if (name != null && name in triedCodecNames) continue
        try {
          // 用流真实分辨率（第三方 C3 编码输出 1024×512，见 w5.java）。
          // 若用错尺寸，部分厂商解码器会按 CSD 里的 SPS 纠偏，但有些会直接丢弃。
          val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, STREAM_WIDTH, STREAM_HEIGHT
          )
          format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
          format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(stripStartCode(sp)))
          format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(stripStartCode(pp)))

          val c = if (name != null) MediaCodec.createByCodecName(name)
                  else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
          c.configure(format, s, null, 0)
          c.start()

          codec = c
          codecName = name ?: "default"
          activeCodecName = codecName
          boundGeneration = surfaceGeneration
          totalQueued = 0; totalOutputs = 0; feedPtsUs = 0L
          queuedCount = 0; sawTryAgain = 0; sawInputStarved = 0; sawOutputBuffersChanged = 0
          codecCreatedAt = System.currentTimeMillis()
          bump("cfg_ok")
          if (surfaceProvider() == null) bump("surf_fb") else bump("surf_direct")
          diag("解码器已启动: $codecName")
          Log.i(TAG, "decoder started: name=$codecName gen=$surfaceGeneration")
          return
        } catch (e: Exception) {
          bump("cfg_fail")
          diag("解码器 ${name ?: "default"} 启动失败: ${e::class.java.simpleName}")
          Log.w(TAG, "codec ${name ?: "default"} FAILED: ${e.message}")
        }
      }
      diag("所有候选解码器均失败")
      Log.e(TAG, "ALL codec candidates failed")
    }

    private fun buildCodecCandidates(): List<String?> {
      val all = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
          .filter { !it.isEncoder }
          .filter { info -> info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
          .map { it.name }
      } catch (e: Exception) {
        Log.w(TAG, "MediaCodecList enumerate failed: ${e.message}")
        emptyList()
      }
      Log.i(TAG, "available avc decoders: $all")
      val software = all.filter { it.startsWith("OMX.google.") || it.startsWith("c2.android.") }
      val others = all.filter { it !in software }
      val ordered = if (isEmulator()) software + others else others + software
      val (preferred, rest) = ordered.partition { it in codecPreferred }
      return preferred + rest + listOf(null)
    }

    private fun stripStartCode(nalu: ByteArray): ByteArray {
      val off = if (nalu.size > 2 && nalu[2].toInt() == 0x01) 3 else 4
      if (off >= nalu.size) return ByteArray(0)
      return nalu.copyOfRange(off, nalu.size)
    }

    private fun feedAndDrain(nalu: ByteArray, keyframe: Boolean) {
      val c = codec ?: return
      try {
        drainOutput(c)
        var inIdx = c.dequeueInputBuffer(10_000)
        if (inIdx < 0) {
          sawInputStarved++
          drainOutput(c)
          inIdx = c.dequeueInputBuffer(10_000)
        }
        if (inIdx >= 0) {
          val inBuf = c.getInputBuffer(inIdx) ?: return
          inBuf.clear()
          // 必须保留 Annex-B start code（踩坑记录见 SuperVideoClient.feedAndDrain：
          // 剥掉 start code 会导致裸 NALU 无分隔符 → 解码器把 IDR+P 误判为同一访问单元 → 0 帧）
          if (nalu.size <= 4) { bump("drop_empty"); return }
          inBuf.put(nalu)
          val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
          // pts 从 0 起单调递增，绝不用 System.nanoTime()/1000（会溢出 int32 → 全部丢弃）
          c.queueInputBuffer(inIdx, 0, nalu.size, feedPtsUs, flags)
          feedPtsUs += 50_000L   // 第三方 C3 是 20fps → 50ms
          bump("queued")
          queuedCount++
          totalQueued += nalu.size
        } else {
          bump("drop_nobuf")
        }
        drainOutput(c)
        if (!checkCodecAlive()) throw DecoderDeadException()
      } catch (e: DecoderDeadException) {
        throw e
      } catch (e: Exception) {
        bump("drop_err")
        Log.w(TAG, "decode error: ${e.message}")
      }
    }

    private fun checkCodecAlive(): Boolean {
      val c = codec ?: return true
      if (totalOutputs > 0) return true
      if (totalQueued < noOutputByteLimit) return true
      if (System.currentTimeMillis() - codecCreatedAt < noOutputMinDurationMs) return true
      if (codecName != "default") {
        triedCodecNames.add(codecName)
        diag("解码器 $codecName 无输出（喂入 ${totalQueued / 1024}KB / 0 帧），换下一个")
      } else {
        diag("兜底解码器 default 无输出（喂入 ${totalQueued / 1024}KB / 0 帧）")
      }
      bump("codec_dead")
      Log.w(TAG, "DEAD-DIAG name=$codecName queued=${totalQueued}B queuedCount=$queuedCount " +
        "tryAgain=$sawTryAgain starved=$sawInputStarved outChanged=$sawOutputBuffersChanged " +
        "outputs=$totalOutputs elapsedMs=${System.currentTimeMillis() - codecCreatedAt}")
      runCatching { c.stop() }
      runCatching { c.release() }
      codec = null
      totalQueued = 0; totalOutputs = 0; feedPtsUs = 0L

      val allNamedDead = lastNamedCandidates.isNotEmpty() &&
        lastNamedCandidates.all { it in triedCodecNames }
      if (allNamedDead) {
        diag("全部候选解码器(${lastNamedCandidates.size}个)均无输出，重建连接")
        bump("reconnect_all_dead")
        return false
      }
      return true
    }

    private fun drainOutput(c: MediaCodec) {
      val info = MediaCodec.BufferInfo()
      while (true) {
        val outIdx = c.dequeueOutputBuffer(info, 0)
        when {
          outIdx >= 0 -> {
            c.releaseOutputBuffer(outIdx, true)
            countFrame()
            bump("frames")
            totalOutputs++
            if (totalOutputs == 1L) {
              diag("首帧已解码并渲染（$codecName）")
              if (codecName != "default") codecPreferred.add(codecName)
              Log.i(TAG, "FIRST FRAME ok name=$codecName after=${totalQueued}B")
              publishDiag(true)
            }
          }
          outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val f = c.outputFormat
            val fw = f.getInteger(MediaFormat.KEY_WIDTH)
            val fh = f.getInteger(MediaFormat.KEY_HEIGHT)
            diag("输出格式 ${fw}x${fh}")
            publishDiag(true)
            onResolution(fw, fh)
          }
          outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { sawOutputBuffersChanged++ }
          outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { sawTryAgain++; break }
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
        try { it.stop(); it.release() } catch (_: Exception) {}
      }
      codec = null
    }
  }

  private fun findStartCode(data: ByteArray, len: Int, from: Int): Int? {
    var i = from
    while (i + 3 < len) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
        data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
      i++
    }
    return null
  }

  // ═══════════════════════════ 触控：TCP :27184 ═══════════════════════════

  /**
   * 待发送的触摸事件：(action, x, y, w, h)。
   * 触摸是高频事件，用队列解耦 UI 线程与网络线程，避免 UI 卡顿。
   */
  private data class TouchEvent(val action: Int, val x: Int, val y: Int, val w: Int, val h: Int)

  private val touchQueue = java.util.concurrent.LinkedBlockingQueue<TouchEvent>()

  /** 由 UI 调用：入队一个触摸事件。action: 0=down, 1/3=up, 2=move。 */
  fun sendTouch(action: Int, x: Int, y: Int, w: Int, h: Int) {
    if (touchQueue.size > 64) touchQueue.clear()   // 背压保护：丢弃积压
    touchQueue.offer(TouchEvent(action, x, y, w, h))
  }

  private fun runTouchLoop() {
    while (running.get()) {
      var sock: Socket? = null
      try {
        sock = Socket().apply {
          connect(InetSocketAddress(host, touchPort), 3000)
          tcpNoDelay = true
          soTimeout = 5000
        }
        val out = DataOutputStream(sock.getOutputStream())
        val inp = java.io.DataInputStream(sock.getInputStream())
        touchConnected = true
        diag("触控已连接 $host:$touchPort")
        publishDiag(true)
        Log.i(TAG, "touch connected $host:$touchPort")

        // ── 握手：App 发 32 字节首字节 0x7F，C3 回 8 字节微秒时间戳 ──
        // 与第三方 cb/j0.java:59-66 行为一致。这一步不能省：
        // touch_proxy.py 靠它确认客户端在线，且会用返回的偏移做后续时间基准。
        runCatching {
          val hello = ByteArray(32)
          hello[0] = TOUCH_MSG_HEARTBEAT.toByte()   // 0x7F
          val t0 = System.nanoTime() / 1000
          synchronized(out) {
            out.write(hello)
            out.flush()
          }
          val reply = inp.readLong()                 // 8 字节大端
          val t1 = System.nanoTime() / 1000
          clockOffsetUs = reply - (t0 + t1) / 2
          bump("touch_hb")
          Log.i(TAG, "touch handshake ok, clockOffsetUs=$clockOffsetUs")
          diag("触控握手成功 offset=${clockOffsetUs}us")
        }.onFailure {
          // 握手失败不致命（部分 touch_proxy 版本不回包），继续走触摸循环
          Log.w(TAG, "touch handshake no reply: ${it.message}")
          diag("触控握手无应答（继续尝试发送触摸）")
        }

        while (running.get()) {
          val ev = try {
            touchQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
          } catch (_: InterruptedException) { break }
          if (ev == null) {
            if (sock.isClosed) break
            continue
          }
          // 32 字节大端，字段顺序与第三方逐字段对齐（cb/j0.java:80-90）：
          //   writeByte(2) / writeByte(action) / writeLong(-2L)
          //   writeInt(x) / writeInt(y) / writeShort(w) / writeShort(h)
          //   writeShort(action == 1 ? 0 : 65535) / writeInt(0) / writeInt(0)
          synchronized(out) {
            out.writeByte(2)
            out.writeByte(ev.action)
            out.writeLong(-2L)
            out.writeInt(ev.x)
            out.writeInt(ev.y)
            out.writeShort(ev.w)
            out.writeShort(ev.h)
            out.writeShort(if (ev.action == ACTION_UP) 0 else 65535)
            out.writeInt(0)
            out.writeInt(0)
            out.flush()
          }
          bump("touch_tx")
        }
      } catch (e: Exception) {
        if (running.get()) {
          Log.w(TAG, "touch error: ${e::class.java.simpleName}: ${e.message}")
          diag("触控错误 ${e::class.java.simpleName}: ${e.message}")
        }
      } finally {
        touchConnected = false
        try { sock?.close() } catch (_: Exception) {}
      }
      if (running.get()) {
        try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
      }
    }
  }

  private fun isEmulator(): Boolean {
    val fp = android.os.Build.FINGERPRINT ?: ""
    val model = android.os.Build.MODEL ?: ""
    val brand = android.os.Build.BRAND ?: ""
    val product = android.os.Build.PRODUCT ?: ""
    return fp.startsWith("generic") ||
      fp.contains("vbox") || fp.contains("test-keys") ||
      model.contains("Emulator") || model.contains("Android SDK built for") ||
      model.contains("sdk_gphone") || model.contains("25098PN5AC") ||
      brand.startsWith("generic") || brand.contains("genymotion") ||
      product.contains("sdk") || product.contains("vbox") ||
      product.contains("ldplayer") || product.contains("nox") ||
      File("/dev/socket/qemud").exists() || File("/dev/qemu_pipe").exists()
  }

  companion object {
    private const val TAG = "SuperVideo2"

    /** 第三方 C3 编码输出的真实分辨率（见 w5.java：C3 分支 1024×512）。 */
    const val STREAM_WIDTH = 1024
    const val STREAM_HEIGHT = 512

    /** 心跳消息类型（>BBQIIHHHII 的 msg_type 字段）。App **主动发送**它做握手。 */
    const val TOUCH_MSG_HEARTBEAT = 0x7F

    /** 触摸 action 常量（与第三方 cb/j0.java / touch_proxy.py 一致）。 */
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
  }
}
