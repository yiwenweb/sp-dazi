package com.sunnypilot.toolbox.data.repository

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.File
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
  private val onDiag: (List<String>) -> Unit = { _ -> }
) {
  private val running = AtomicBoolean(false)
  private var worker: Thread? = null

  /** 诊断快照：把关键链路状态暴露到 UI，免 adb 也能看出黑屏卡在哪一步。 */
  private val diagLines = java.util.concurrent.ConcurrentLinkedQueue<String>()
  private val counters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

  private fun bump(k: String) {
    counters.computeIfAbsent(k) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
  }

  private fun diag(msg: String) {
    Log.i(TAG, "DIAG $msg")
    diagLines.add(msg)
    while (diagLines.size > 40) diagLines.poll()
    publishDiag()
  }

  private val diagThrottle = java.util.concurrent.atomic.AtomicLong(0)

  /** 把计数器 + 最近事件拼成多行文本推给 UI（限频，避免刷爆主线程）。 */
  private fun publishDiag(force: Boolean = false) {
    val now = System.currentTimeMillis()
    val last = diagThrottle.get()
    if (!force && now - last < 500) return
    if (!diagThrottle.compareAndSet(last, now)) return
    val c = counters
    val codecLine = "codec: ${activeCodecName ?: "-"} (启${c["cfg_ok"] ?: 0} 败${c["cfg_fail"] ?: 0} 死${c["codec_dead"] ?: 0}) " +
      "入${c["queued"] ?: 0} 出${c["frames"] ?: 0}"
    val head = listOf(
      "sock: ${c["connect"] ?: 0} 连上 / ${c["disconnect"] ?: 0} 断开",
      "recv: ${c["bytes"] ?: 0} B, ${c["reads"] ?: 0} 次读",
      "nalu: ${c["nalu"] ?: 0} 个 (SPS ${c["sps"] ?: 0} PPS ${c["pps"] ?: 0} IDR ${c["idr"] ?: 0} P ${c["slice"] ?: 0})",
      codecLine,
      "surface: 送达${c["surf_set"] ?: 0}次, 兜底取${c["surf_fb"] ?: 0}次, 空${c["surf_null"] ?: 0}次",
      "drop: codec空${c["drop_nocodec"] ?: 0} / 无输入buf${c["drop_nobuf"] ?: 0} / 空payload${c["drop_empty"] ?: 0}",
      "模拟器: ${if (isEmulator()) "是" else "否"}"
    )
    onDiag(head + diagLines.toList().takeLast(10))
  }

  /** UI 上显示当前正在使用的解码器名（由 worker 线程写入）。 */
  @Volatile var activeCodecName: String? = null
    private set

  /**
   * 判断是否运行在模拟器上（LDPlayer / AVD / Genymotion 等）。
   *
   * 放在**外层类**而不是 DecoderController：publishDiag() 要用它（诊断面板显示），
   * 而 publishDiag() 在外层；内层类可以访问外层成员，反之不行。
   * 解码器候选排序（buildCodecCandidates）也复用这一份实现。
   */
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

  /** 所有候选解码器均被判定为无输出时抛出，用于打断当前连接并触发重连。 */
  class DecoderDeadException : RuntimeException("all codec candidates dead")

  /**
   * "假解码器"黑名单 —— 整个 client 生命周期内持久存在，**不随重连清空**。
   *
   * 理由：LDDec 这类 shim 是设备固件层面的问题，重连一万次它还是解不出帧。
   * 若每次重连都重新尝试它，就会白白吃掉 1.5MB 输入、浪费十几秒，
   * 在只有两个候选解码器的模拟器上更是会来回打转。
   * 因此一旦某个候选被证实"喂了 1.5MB 都不出帧"，就永久排除。
   */
  private val codecBlacklist = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
          bump("connect")
          diag("已连接 $host:$port")
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
      } catch (e: DecoderDeadException) {
        // 解码器全军覆没：重连以重新协商（新连接会拿到 SPS/PPS + 关键帧）。
        // 黑名单刻意保留 —— 已证实解不出帧的候选不会因为重连就变好。
        Log.w(TAG, "all decoders dead, reconnecting to renegotiate")
        bump("disconnect")
        publishDiag(true)
      } catch (e: Exception) {
        if (running.get()) {
          Log.w(TAG, "stream error: ${e::class.java.simpleName}: ${e.message}")
          diag("流错误 ${e::class.java.simpleName}: ${e.message}")
          bump("disconnect")
          publishDiag(true)
        }
      }
      if (running.get()) {
        try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
      }
    }
  }

  private fun streamLoop(input: InputStream) {
    val decoderController = DecoderController({ surface }, surfaceGeneration, onFps)
    // 诊断：把收到的字节原样落盘（与 PC 抓包逐字节比对）
    val dumpOut = if (rawDumpEnabled) {
      runCatching { java.io.FileOutputStream(rawDumpPath!!, false) }.getOrNull().also {
        if (it != null) Log.i(TAG, "raw dump -> $rawDumpPath")
      }
    } else null
    var dumpBytes = 0L
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
          bump("idle_timeout")
          publishDiag()
          continue
        }
        if (n < 0) throw IllegalStateException("stream closed")
        if (n == 0) continue
        totalBytes += n
        reads++
        counters.computeIfAbsent("bytes") { java.util.concurrent.atomic.AtomicLong() }.addAndGet(n.toLong())
        counters.computeIfAbsent("reads") { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        if (reads == 1) diag("首次收到数据 n=$n")
        // 原样落盘：与 PC 侧抓包做逐字节比对，判断「App 收到的流」是否被破坏
        if (dumpOut != null && dumpBytes < 8L * 1024 * 1024) {
          runCatching { dumpOut.write(buf, 0, n); dumpBytes += n }
        }

        if (carryLen + n > carry.size) {
          // 理论不会发生（一帧最多 ~100KB），防御性丢弃旧数据
          Log.w(TAG, "carry overflow (carryLen=$carryLen n=$n), dropping")
          carryLen = 0
        }
        System.arraycopy(buf, 0, carry, carryLen, n)
        carryLen += n

        // 按 start code 切 NALU，返回本次可消费的长度
        val consumed = try {
          decoderController.feedNalus(carry, carryLen)
        } catch (e: DecoderDeadException) {
          // 所有候选解码器都无输出：断开当前连接重连，
          // 依靠 C3 端 onNewConnection 的 SPS/PPS + request_keyframe 重新起流。
          diag("解码链全灭，重连以获取新关键帧")
          throw e
        }
        if (consumed > 0) {
          System.arraycopy(carry, consumed, carry, 0, carryLen - consumed)
          carryLen -= consumed
        }
        if (reads % 50 == 0) {
          Log.d(TAG, "stream: $totalBytes bytes / $reads reads, carry=$carryLen")
          publishDiag()
        }
      }
    } finally {
      Log.i(TAG, "streamLoop exit")
      runCatching { dumpOut?.flush() }
      runCatching { dumpOut?.close() }
      if (dumpOut != null) Log.i(TAG, "raw dump closed, wrote $dumpBytes bytes")
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
    private var codecName: String = "?"
    private var gotSps = false
    private var gotPps = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var boundGeneration = -1
    private var naluCount = 0
    private var lastLogNalu = 0
    /**
     * 喂给 MediaCodec 的 presentationTimeUs，从 0 起按 1/30 秒递增。
     * 绝不使用 System.nanoTime()/1000（详见 feedAndDrain 内注释）。
     */
    private var feedPtsUs = 0L

    private var frameCount = 0
    private var lastFpsTime = System.nanoTime()

    // ---- 解码器健康监测 ----
    // 有些"假解码器"（典型：雷电模拟器的 LDDec shim）configure/start 全成功、
    // 不抛任何异常，但永远不出帧。只靠 try/catch 判断不了，必须做存活探测：
    // 若已喂入大量输入却长时间 0 输出，则判定该解码器不可用，换下一个候选。
    private var totalQueued = 0L
    private var totalOutputs = 0L
    /** 已被证伪的候选解码器（client 级共享，见 codecBlacklist 注释）。 */
    private val triedCodecNames: MutableSet<String> get() = codecBlacklist
    /**
     * 已喂入多少字节仍无任何输出，就判定该解码器是死的。
     * 取 1.5MB：C3 一个 IDR 约 80–100KB，1.5MB ≈ 15 帧，
     * 足以排除"只是还没攒够"的正常延迟，又不会让用户等太久。
     */
    private val noOutputByteLimit = 1536L * 1024

    /**
     * 上一次枚举到的**具名**候选解码器（不含末尾的 null 兜底）。
     * 用于判断"是否所有可试的解码器都已试过"。
     */
    private var lastNamedCandidates: List<String> = emptyList()

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
      bump("nalu")
      when (type) {
        7 -> bump("sps")
        8 -> bump("pps")
        5 -> bump("idr")
        1 -> bump("slice")
      }
      if (naluCount <= 8) {
        Log.d(TAG, "nalu#$naluCount type=$type len=${nalu.size} hdrOff=$hdrOff")
      }
      when (type) {
        7 -> { sps = nalu; gotSps = true; Log.i(TAG, "got SPS len=${nalu.size}"); diag("收到 SPS len=${nalu.size}") }
        8 -> { pps = nalu; gotPps = true; Log.i(TAG, "got PPS len=${nalu.size}"); diag("收到 PPS len=${nalu.size}") }
      }
      // 诊断：把前 60 个 NALU 的 (序号/类型/长度/剥离后长度) 打成一行紧凑序列，
      // 可直接与 PC 侧抓包解析结果逐项比对，判断切分是否一致。
      if (naluCount <= 60) {
        val strippedLen = stripStartCode(nalu).size
        Log.i(TAG, "SEQ nalu#$naluCount t=$type raw=${nalu.size} strip=$strippedLen")
      }
      // 解码器未配置时每次收到 NALU 都重试，直到 Surface 就绪 ——
      // 否则首帧到达时 Surface 还没 attach，解码器永不建立（黑屏）。
      if (gotSps && gotPps && codec == null) {
        tryConfigureDecoder()
      }
      // SPS/PPS 既要作为 CSD 提交（configure 时已做），也要按原样喂进数据队列。
      //
      // 踩坑记录：早先这里直接 `if (type == 7 || type == 8) return`，即 SPS/PPS 只
      // 通过 CSD 出现一次、数据流里再无它们。PC 端对照实验（见 feedAndDrain 注释的
      // D1/D2/D3 组）显示：只有「CSD 有 SPS/PPS」+「数据流里也有 SPS/PPS」的组合能出帧。
      // 原因是 C3 编码器每个 GOP 都重发 IDR，而带内 SPS/PPS 是解码器重新同步参数集的
      // 唯一依据；只给 CSD 的话，解码器在收到新 IDR 时无参数集可用 → 静默丢弃。
      codec?.let { feedAndDrain(nalu, type == 5) } ?: run {
        bump("drop_nocodec")
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
          bump("surf_fb")
          diag("Surface 由兜底路径取得")
          setSurface(s)
        }
      }
      if (s == null) {
        bump("surf_null")
        // 每 ~200 个 NALU 提示一次，避免刷屏
        if (naluCount == 0 || naluCount - lastLogNalu > 200) {
          lastLogNalu = naluCount
          diag("解码器未配置：Surface 为空 (nalu=$naluCount sps=$gotSps pps=$gotPps)")
          Log.w(TAG, "codec NOT configured: surface null (naluCount=$naluCount, sps=$gotSps pps=$gotPps)")
        }
        return
      }
      val sp = sps ?: return
      val pp = pps ?: return

      // 逐个候选解码器尝试配置，直到某个能 start 成功。
      //
      // 为什么需要多个候选：createDecoderByType("video/avc") 返回的是**列表第一个**，
      // 而在雷电模拟器上第一个恰好是 LDPlayer 的软件 shim
      // （OMX.qcom.video.decoder.avc → LDDec），它 configure/start 都不报错，
      // 但内部 init 分辨率为 0x0 且输出 ANWBuffer 失败（err -1010），
      // 结果是：连接成功、无任何异常、但永远不出帧 → 黑屏。
      // 真机（如华为 NOH-AN00）第一个是真正的硬解器，不应盲目跳过，
      // 因此策略是「按顺序试，能 start 且后续真出帧才算数」。
      val candidates = buildCodecCandidates()
      lastNamedCandidates = candidates.filterNotNull()
      for (name in candidates) {
        // 跳过已被判定为"假解码器"的候选，避免每轮都重试到死循环
        if (name != null && name in triedCodecNames) continue
        try {
          val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 640)
          format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
          // 去掉 start code，MediaCodec CSD 需要不带 00 00 00 01 的纯 NALU
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
          totalQueued = 0
          totalOutputs = 0
          feedPtsUs = 0L
          bump("cfg_ok")
          if (surfaceProvider() == null) bump("surf_fb") else bump("surf_direct")
          diag("解码器已启动: $codecName（待验证出帧）")
          Log.i(TAG, "decoder started: name=$codecName gen=$surfaceGeneration " +
                  "spsLen=${stripStartCode(sp).size} ppsLen=${stripStartCode(pp).size}")
          return
        } catch (e: Exception) {
          bump("cfg_fail")
          diag("解码器 ${name ?: "default"} 启动失败: ${e::class.java.simpleName}")
          Log.w(TAG, "codec ${name ?: "default"} configure/start FAILED: ${e::class.java.simpleName}: ${e.message}")
          // 继续尝试下一个候选
        }
      }
      diag("所有候选解码器均失败，无可用 video/avc 解码器")
      Log.e(TAG, "ALL codec candidates failed")
    }

    /**
     * 构造解码器候选顺序。
     *
     * 关键：把 Google 软解（OMX.google.h264.decoder / c2.android.avc.decoder）排在
     * 模拟器 shim 前面。这类 shim 的特征名字是 OMX.qcom.*（厂商伪装），
     * 在模拟器里实际由 LDDec 实现，只做直通不做解码，必然黑屏。
     *
     * 真机上 OMX.qcom.* 通常是**真·硬解器**，性能远好于软解，
     * 所以只在「模拟器」下才把它降级排到最后。
     */
    private fun buildCodecCandidates(): List<String?> {
      val all = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
          .codecInfos
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

      val onEmulator = isEmulator()
      val ordered = if (onEmulator) {
        // 模拟器：软解优先，绕开 LDDec 之类的直通 shim
        software + others
      } else {
        // 真机：硬解优先（省电、低延迟），软解兜底
        others + software
      }
      // 末尾补一个"交给系统默认"的兜底
      return ordered + listOf(null)
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
          // 必须保留 Annex-B start code！
          //
          // 踩坑记录（2026-09-10，PC 端逐字节复现）：
          // 早先这里调用了 stripStartCode(nalu)，把 00 00 00 01 剥掉后喂裸 NALU。
          // 结果：真机 4 款解码器（OMX.hisi.video.decoder.avc / c2.android.avc.decoder /
          // OMX.google.h264.decoder / default）全部「入 2436 帧、出 0 帧」，且不报任何错。
          // 原因是裸 NALU 之间没有分隔符，解码器把 IDR(105KB) 与紧随其后的 P 帧(14KB)
          // 误判为同一个访问单元，语法层直接解析失败 → 静默丢弃。
          //
          // PC 端用同一份 C3 抓包做对照实验：
          //   A) 保留 start code 的完整 Annex-B 连续流 → 125 帧 ✅
          //   B) 剥离 start code、每 NALU 独立喂 → 0 帧 ❌（与 App 症状完全一致）
          //   C) extradata=SPS/PPS + 数据带 start code → 0 帧 ❌
          // 结论：数据层必须原样保留 start code。
          val payload = nalu
          if (payload.size <= 4) { bump("drop_empty"); return }
          inBuf.put(payload)
          val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
          // pts 必须从 0 开始单调递增，不能用 System.nanoTime()/1000。
          //
          // 踩坑记录：System.nanoTime()/1000 等于「设备开机至今的微秒数」，在开机
          // 数天后会达到 1e11 量级，远超 32 位有符号上限（约 2.1e9）。部分厂商 OMX
          // 实现内部以 int32 承载 presentationTimeUs，溢出后变成随机负数/乱值，
          // 导致解码器把所有帧当成"时间戳异常"而静默丢弃 → 同样是「入 N 帧、出 0 帧」。
          // 这里改为按流内帧序号推算时间轴，从 0 起步、步长 1/30 秒。
          c.queueInputBuffer(inIdx, 0, payload.size, feedPtsUs, flags)
          feedPtsUs += 33_333L
          bump("queued")
          totalQueued += payload.size
        } else {
          bump("drop_nobuf")
        }
        drainOutput(c)
        if (!checkCodecAlive()) {
          // 所有候选解码器都无输出 —— 让上层重建连接
          throw DecoderDeadException()
        }
      } catch (e: DecoderDeadException) {
        throw e
      } catch (e: Exception) {
        bump("drop_err")
        Log.w(TAG, "decode error: ${e.message}")
      }
    }

    /**
     * 解码器存活探测 —— 这是识别"假解码器"的唯一可靠手段。
     *
     * LDDec 这类 shim 的特征：queueInputBuffer 全部接受、不报错、不返回错误码，
     * 但内部根本不解码，dequeueOutputBuffer 永远只有 TRY_AGAIN_LATER。
     * 因此判据是「已喂 N 字节仍 0 输出」。命中后标记该 codec 名称不可用并重建。
     */
    private fun checkCodecAlive(): Boolean {
      val c = codec ?: return true
      if (totalOutputs > 0) return true                    // 已出过帧，健康
      if (totalQueued < noOutputByteLimit) return true     // 样本还不够
      // 判定为死解码器
      triedCodecNames.add(codecName)
      bump("codec_dead")
      diag("解码器 $codecName 无输出（喂入 ${totalQueued / 1024}KB / 0 帧），换下一个")
      Log.w(TAG, "codec $codecName is DEAD: queued=${totalQueued}B/0 frames, dropping")
      runCatching { c.stop() }
      runCatching { c.release() }
      codec = null
      totalQueued = 0
      totalOutputs = 0
      feedPtsUs = 0L

      // 如果所有具名候选都被判死了，说明问题不在"选哪个解码器"上：
      // 重建整个连接，让上层重连重新走一遍协商（拿新的 SPS/PPS + 关键帧）。
      val allNamedDead = lastNamedCandidates.isNotEmpty() &&
        lastNamedCandidates.all { it in triedCodecNames }
      if (allNamedDead) {
        diag("全部候选解码器(${lastNamedCandidates.size}个)均无输出，重建连接")
        bump("reconnect_all_dead")
        return false   // 通知调用方结束当前 streamLoop
      }
      return true
    }

    private fun drainOutput(c: MediaCodec) {
      val info = MediaCodec.BufferInfo()
      while (true) {
        val outIdx = c.dequeueOutputBuffer(info, 0)
        when {
          outIdx >= 0 -> {
            c.releaseOutputBuffer(outIdx, true)   // 渲染到 Surface
            countFrame()
            bump("frames")
            totalOutputs++
            if (totalOutputs == 1L) {
              diag("首帧已解码并渲染（$codecName）")
              publishDiag(true)
            }
          }
          outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val f = c.outputFormat
            val fw = f.getInteger(MediaFormat.KEY_WIDTH)
            val fh = f.getInteger(MediaFormat.KEY_HEIGHT)
            Log.i(TAG, "output format changed: ${fw}x${fh}")
            diag("输出格式 ${fw}x${fh}（解码器已出帧）")
            publishDiag(true)
            onResolution(fw, fh)
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

    /**
     * 诊断开关：把收到的字节原样落到 App 私有目录，用于与 PC 侧抓包做逐字节比对。
     *
     * 背景：C3 推的流在 PC 上能被 PyAV 完整解码（126 帧），但真机 / 模拟器上
     * 四类解码器（hisi/c2/google/default）累计喂入 58MB / 2436 个 NALU 仍 0 输出。
     * 打开本开关后跑一次，把 filesDir 下的 recv_dump.h264 拉回来用 PyAV 解即可判定
     * 「App 收到的字节」是否与「PC 抓到的字节」一致。
     */
    @Volatile var rawDumpEnabled = false
    private var rawDumpPath: String? = null

    /**
     * 打开原始字节落盘。优先写外部公共目录 /sdcard/Download/，这样**未签名的
     * release 包**也能用 `adb pull` 直接取回（release 包无法 `run-as` 读 filesDir）。
     * 外部目录不可用时回退到传入的私有目录。
     */
    @JvmStatic
    fun setRawDump(dir: java.io.File?) {
      val ext = java.io.File("/sdcard/Download")
      val target = if (ext.isDirectory && ext.canWrite()) {
        java.io.File(ext, "recv_dump.h264")
      } else {
        dir?.let { java.io.File(it, "recv_dump.h264") }
      }
      rawDumpPath = target?.absolutePath
      rawDumpEnabled = rawDumpPath != null
    }
  }
}
