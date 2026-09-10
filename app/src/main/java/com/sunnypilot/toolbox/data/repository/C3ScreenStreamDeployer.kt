package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import java.security.MessageDigest

/**
 * C3 超级视频2 —— payload 部署器。
 *
 * ## 背景
 *
 * 本类实现的是**第三方 openpilot_toolbox 那套「零 openpilot 依赖」的投屏方案**，
 * 与我们自己的 Plan 1（`selfdrive/ui/sunnypilot/qt/stream/supervideo.cc`，走 :8082）
 * 完全独立、可以并存对比。
 *
 * 逆向来源：`openpilot_toolbox_v1.8.0.apk` → `assets/c3-screen-v2.tar`
 * （详见 `apk_reverse/逆向报告_openpilot_toolbox_v1.8.0_超级视频.md` 与
 * `apk_reverse/重写规格书_c3-screen-stream-v2.md`）。
 *
 * ## payload 内容（7 个业务文件，共约 122 KB）
 *
 * ```
 * install.sh                  1,394 B   明文 shell
 * start-native.sh               340 B   明文 shell
 * touch_proxy.py              4,285 B   明文 Python（触控代理，监听 27184）
 * bin/sde_rotator_stream     18,816 B   aarch64 ELF：DRM scanout → SDE rotator → XRGB
 * bin/swscale_xrgb_to_nv12   14,528 B   aarch64 ELF：libswscale XRGB→NV12（1920x1080→1024x512）
 * bin/v4l_h264_encoder       24,256 B   aarch64 ELF：V4L2 msm_vidc 硬编 → TCP :5000
 * bin/weston_tiny_guard      20,240 B   aarch64 ELF：1×1 假 Wayland 客户端，防 Weston 拆 DRM 输出
 * ```
 *
 * ## 部署流程
 *
 * 1. 从 APK assets 读出 `c3screen/c3-screen-v2.tar`（离线自足，不依赖任何外部服务器）
 * 2. 校验 md5（防资产损坏 / 传输截断）
 * 3. SFTP 上传到 `/data/local/tmp/c3-screen-v2.tar`
 * 4. 远端解包到 `/tmp/c3-pkg`（必须解到 /tmp，因为 install.sh 用 `$0` 定位同级 `bin/` 目录）
 * 5. 执行 `install.sh`（原子替换 7 个文件 + 拉起 weston_tiny_guard + touch_proxy + 推流）
 * 6. 校验 5000 / 27184 两个端口在监听
 *
 * ## 为什么必须走 install.sh 而不是手工 cp
 *
 * install.sh 里做了三件不可省的事：
 *   - `chmod 755` 四个二进制（ELF 必须有可执行位）
 *   - `sudo -n pkill` 清理旧进程（否则端口占用导致新进程起不来）
 *   - `nohup ... &` 脱离 SSH 会话（SSH 断开后进程不能死）
 *
 * ## 权限前提
 *
 * `sde_rotator_stream` 与 `v4l_h264_encoder` 都以 `sudo -n` 启动
 * （需要访问 /dev/dri/card0 与 /dev/video*）。C3 上 comma 用户默认免密 sudo，
 * 若 `sudo -n true` 失败，本类会在前置检查阶段提前报错，不进入部署。
 */
class C3ScreenStreamDeployer(
  private val ssh: SshManager,
  private val context: Context
) {

  companion object {
    private const val TAG = "SV2-Deploy"

    /** payload 在 APK assets 中的路径。 */
    private const val ASSET_PATH = "c3screen/c3-screen-v2.tar"

    /** 上传到 C3 的临时 tar 路径。 */
    private const val REMOTE_TAR = "/data/local/tmp/c3-screen-v2.tar"

    /** 远端解包目录。install.sh 靠 `$0` 的同级目录找 bin/，所以必须整包解开。 */
    private const val REMOTE_PKG_DIR = "/tmp/c3-pkg"

    /** 安装后的最终位置（与第三方一致）。 */
    const val BASE_DIR = "/data/local/tmp/c3-screen-stream-v2"

    /** 视频流端口（H.264 Annex-B）。 */
    const val VIDEO_PORT = 5000

    /** 触控端口（32 字节大端二进制协议）。 */
    const val TOUCH_PORT = 27184

    /** 与 assets 中 payload 逐字节一致的 md5，用作完整性校验。 */
    private const val EXPECTED_MD5 = "bf0b471d9661a77725936479b934f1ce"

    /** payload 字节数（122,880 B）。 */
    private const val EXPECTED_SIZE = 122_880L
  }

  /** 部署阶段回调：用于 UI 实时显示进度。 */
  enum class Stage(val label: String) {
    PREFLIGHT("设备前置检查"),
    READ_ASSET("读取内置 payload"),
    UPLOAD("上传 payload"),
    EXTRACT("解包"),
    INSTALL("安装与启动"),
    VERIFY("端口校验"),
    DONE("完成")
  }

  /** 部署结果。 */
  sealed class Result2 {
    data class Ok(val log: List<String>) : Result2()
    data class Fail(val stage: Stage, val message: String, val log: List<String>) : Result2()
  }

  /**
   * 执行完整部署。这是一个阻塞式的 suspend 函数，调用方应在 IO 上下文里跑，
   * 并通过 [onStage] 把进度打到 UI。
   *
   * @param onStage 每个阶段开始时回调（stage, 人类可读描述）
   * @param onLog   产生一条日志时回调
   */
  suspend fun deploy(
    onStage: (Stage, String) -> Unit = { _, _ -> },
    onLog: (String) -> Unit = {}
  ): Result2 {
    val log = mutableListOf<String>()
    fun emit(line: String) {
      Log.i(TAG, line)
      log.add(line)
      onLog(line)
    }

    try {
      // ───────────────────────── ① 前置检查 ─────────────────────────
      onStage(Stage.PREFLIGHT, "检查 sudo 免密与 payload 完整性")

      val assetBytes = try {
        context.assets.open(ASSET_PATH).use { it.readBytes() }
      } catch (e: Exception) {
        return Result2.Fail(Stage.READ_ASSET, "无法读取内置 payload：${e.message}", log)
      }
      if (assetBytes.size.toLong() != EXPECTED_SIZE) {
        return Result2.Fail(
          Stage.READ_ASSET,
          "payload 大小异常：${assetBytes.size} B（期望 $EXPECTED_SIZE B）",
          log
        )
      }
      val md5 = md5Hex(assetBytes)
      if (md5 != EXPECTED_MD5) {
        return Result2.Fail(
          Stage.READ_ASSET,
          "payload 校验失败：md5=$md5（期望 $EXPECTED_MD5）",
          log
        )
      }
      emit("✓ 内置 payload 校验通过：${assetBytes.size} B / md5=$md5")

      // C3 上 comma 用户默认免密 sudo；若失败后面所有启动都会静默失败，必须提前拦住。
      val sudoCheck = ssh.executeCommand("sudo -n true 2>&1 && echo SUDO_OK || echo SUDO_FAIL")
      val sudoOut = sudoCheck.getOrElse { "" }.trim()
      if (!sudoOut.contains("SUDO_OK")) {
        emit("✗ sudo -n 不可用（输出：$sudoOut）")
        return Result2.Fail(
          Stage.PREFLIGHT,
          "C3 上 sudo 免密不可用，无法启动抓屏与编码进程。请先在 C3 上确认 comma 用户可免密 sudo。",
          log
        )
      }
      emit("✓ sudo -n 免密可用")

      // 关键设备节点存在性（缺失会导致 sde_rotator_stream / encoder 无法打开）
      val devCheck = ssh.executeCommand(
        "for f in /dev/dri/card0 /dev/video2 /dev/ion /dev/uinput /dev/input/event2; do " +
          "[ -e \$f ] && echo \"OK \$f\" || echo \"MISS \$f\"; done"
      ).getOrElse { "" }
      devCheck.lines().filter { it.isNotBlank() }.forEach { emit("  $it") }
      val missing = devCheck.lines().filter { it.startsWith("MISS") }
      if (missing.isNotEmpty()) {
        // 不直接失败：/dev/uinput 与 event2 是触控用的，缺了画面仍可看；
        // 但 /dev/dri/card0 与 /dev/video2 缺失则必然失败。
        val fatal = missing.filter {
          it.contains("/dev/dri/card0") || it.contains("/dev/video2")
        }
        if (fatal.isNotEmpty()) {
          return Result2.Fail(
            Stage.PREFLIGHT,
            "缺少关键设备节点：${fatal.joinToString("，") { it.removePrefix("MISS ").trim() }}",
            log
          )
        }
        emit("⚠ 部分触控设备缺失（画面可看，触摸可能无效）")
      }

      // ───────────────────────── ② 上传 ─────────────────────────
      onStage(Stage.UPLOAD, "上传 payload 到 $REMOTE_TAR（${assetBytes.size / 1024} KB）")
      ssh.writeFile(REMOTE_TAR, assetBytes)
        .onFailure { return Result2.Fail(Stage.UPLOAD, "上传失败：${it.message}", log) }
      emit("✓ 已上传 $REMOTE_TAR")

      // 远端再校验一次（防传输截断 —— 与本地 md5 比对）
      val remoteMd5 = ssh.executeCommand("md5sum $REMOTE_TAR 2>/dev/null | awk '{print \$1}'")
        .getOrElse { "" }.trim()
      if (remoteMd5.isNotEmpty() && remoteMd5 != md5) {
        return Result2.Fail(
          Stage.UPLOAD,
          "远端文件校验失败：$remoteMd5 ≠ $md5（传输可能被截断）",
          log
        )
      }
      if (remoteMd5 == md5) emit("✓ 远端 md5 一致")

      // ───────────────────────── ③ 解包 ─────────────────────────
      onStage(Stage.EXTRACT, "解包到 $REMOTE_PKG_DIR")
      val extract = ssh.executeCommand(
        "rm -rf $REMOTE_PKG_DIR && mkdir -p $REMOTE_PKG_DIR && " +
          "tar -xf $REMOTE_TAR -C $REMOTE_PKG_DIR 2>&1 && " +
          "chmod 755 $REMOTE_PKG_DIR/install.sh $REMOTE_PKG_DIR/start-native.sh " +
          "$REMOTE_PKG_DIR/touch_proxy.py $REMOTE_PKG_DIR/bin/[a-z]* && " +
          "ls -l $REMOTE_PKG_DIR $REMOTE_PKG_DIR/bin | head -20"
      )
      extract.onFailure { return Result2.Fail(Stage.EXTRACT, "解包失败：${it.message}", log) }
      emit("✓ 解包完成")

      // ───────────────────────── ④ 安装与启动 ─────────────────────────
      onStage(Stage.INSTALL, "执行 install.sh（清理旧进程 → 原子替换 → 拉起三个进程）")
      // install.sh 内部会 nohup 拉起后台进程，因此命令本身应立即返回。
      // 但解包目录必须保留到脚本执行完 —— 脚本用 $0 定位同级 bin/。
      val install = ssh.executeCommand(
        "cd $REMOTE_PKG_DIR && sh ./install.sh 2>&1; echo \"INSTALL_EXIT=\$?\""
      )
      val installOut = install.getOrElse { e ->
        return Result2.Fail(Stage.INSTALL, "install.sh 执行失败：${e.message}", log)
      }
      installOut.lines().filter { it.isNotBlank() }.forEach { emit("  $it") }
      if (!installOut.contains("INSTALL_EXIT=0")) {
        // install.sh 用了 set -eu，任何一步失败都会非 0 退出
        val code = Regex("INSTALL_EXIT=(\\d+)").find(installOut)?.groupValues?.get(1) ?: "?"
        return Result2.Fail(Stage.INSTALL, "install.sh 退出码 $code", log)
      }
      emit("✓ install.sh 执行成功")

      // install.sh 末尾只 sleep 1；给三个进程留出监听端口的余量
      kotlinx.coroutines.delay(2500)

      // ───────────────────────── ⑤ 端口校验 ─────────────────────────
      onStage(Stage.VERIFY, "校验 $VIDEO_PORT（视频）/ $TOUCH_PORT（触控）端口")
      // C3 上 netstat 可能不可用，ss 兜底；再兜底用 pgrep 看进程
      val portOut = ssh.executeCommand(
        "(" +
          "netstat -tulpn 2>/dev/null || ss -tulpn 2>/dev/null || true" +
          ") | grep -E '$VIDEO_PORT|$TOUCH_PORT' || true; " +
          "echo '---procs---'; " +
          "pgrep -af 'v4l_h264_encoder|sde_rotator_stream|swscale_xrgb_to_nv12|touch_proxy|weston_tiny_guard' " +
          "|| echo '(无相关进程)'"
      ).getOrElse { "" }
      portOut.lines().filter { it.isNotBlank() }.forEach { emit("  $it") }

      // 先尝试做一次延迟复检：c3 上遇到的实际情况是
      // sde_rotator_stream 能成功启动、拿到 scanout framebuffer，
      // 但在第一帧就被标记 V4L2_BUF_FLAG_ERROR(0x4040) 并 exit(17)。
      // 因此"刚装完进程在跑"不等于"真的能出货"，必须等一下再看。
      var portOutNow = portOut
      if (!portOutNow.contains("sde_rotator_stream") || !portOutNow.contains("$VIDEO_PORT")) {
        emit("… 等待 8 秒后复检（抓屏进程常在首帧失败后退出）")
        kotlinx.coroutines.delay(8_000)
        portOutNow = ssh.executeCommand(
          "(" +
            "netstat -tulpn 2>/dev/null || ss -tulpn 2>/dev/null || true" +
            ") | grep -E '$VIDEO_PORT|$TOUCH_PORT' || true; " +
            "echo '---procs---'; " +
            "pgrep -af 'v4l_h264_encoder|sde_rotator_stream|swscale_xrgb_to_nv12|touch_proxy|weston_tiny_guard' " +
            "|| echo '(无相关进程)'"
        ).getOrElse { "" }
        portOutNow.lines().filter { it.isNotBlank() }.forEach { emit("  复检| $it") }
      }

      val videoListening = portOutNow.contains("$VIDEO_PORT")
      val touchListening = portOutNow.contains("$TOUCH_PORT")
      val hasRotator = portOutNow.contains("sde_rotator_stream")

      // 抓屏与转换日志末尾：无论成败都带上，这是最能说明问题的一段
      val captureTail = ssh.executeCommand(
        "echo '== capture.log =='; tail -8 $BASE_DIR/capture.log 2>/dev/null || true; " +
          "echo '== convert.log =='; tail -5 $BASE_DIR/convert.log 2>/dev/null || true"
      ).getOrElse { "" }

      if (!hasRotator) {
        captureTail.lines().filter { it.isNotBlank() }.forEach { emit("  cap| $it") }
        // 真实原因很可能是首帧 buffer 导入失败（rotator frame error flags=0x4040），
        // 而不是"拿不到 framebuffer"——后者只会在完全没有 1080x2160 扫描输出时出现。
        val frameErr = captureTail.contains("rotator frame error")
        return Result2.Fail(
          Stage.VERIFY,
          if (frameErr) {
            "抓屏进程在首帧失败退出（rotator frame error）。" +
              "这是 sde_rotator 的 buffer 导入路径问题，不是硬件不可用。"
          } else {
            "抓屏进程未起来，详见上方 capture.log。"
          },
          log
        )
      }
      if (!videoListening) {
        // 进程在但端口没监听 → 检查 encoder 日志
        val encLog = ssh.executeCommand("tail -20 $BASE_DIR/encoder.log 2>/dev/null || echo '(无日志)'")
          .getOrElse { "" }
        encLog.lines().takeLast(10).filter { it.isNotBlank() }.forEach { emit("  enc| $it") }
        return Result2.Fail(Stage.VERIFY, "编码进程未监听 $VIDEO_PORT（详见 encoder.log）", log)
      }
      emit("✓ 视频端口 $VIDEO_PORT 已监听")
      if (touchListening) emit("✓ 触控端口 $TOUCH_PORT 已监听")
      else emit("⚠ 触控端口 $TOUCH_PORT 未监听（画面可看，触摸无效）")

      // ───────────────────────── ⑥ 完成 ─────────────────────────
      onStage(Stage.DONE, "部署完成")
      emit("✓ 部署完成，可以开始拉流")

      captureTail.lines().filter { it.isNotBlank() }.forEach { emit("  cap| $it") }

      return Result2.Ok(log)
    } catch (e: Exception) {
      Log.e(TAG, "deploy failed", e)
      return Result2.Fail(Stage.INSTALL, "部署异常：${e::class.java.simpleName}: ${e.message}", log)
    }
  }

  /**
   * 停止三个推流进程（保留 payload 文件）。
   *
   * 用户下次进来只需重新 [deploy]（install.sh 本身幂等，会先 pkill 再起）。
   */
  suspend fun stopStream(): Result<String> = ssh.executeCommand(
    "sudo -n pkill -9 -f v4l_h264_encoder 2>/dev/null || true; " +
      "sudo -n pkill -9 -f sde_rotator_stream 2>/dev/null || true; " +
      "pkill -9 -f swscale_xrgb_to_nv12 2>/dev/null || true; " +
      "pkill -9 -f touch_proxy 2>/dev/null || true; " +
      "pkill -9 -f weston_tiny_guard 2>/dev/null || true; " +
      "echo STOPPED"
  )

  /** 查询当前 payload 版本号（install.sh 写入的 VERSION 文件）。 */
  suspend fun remoteVersion(): String = ssh.executeCommand(
    "cat $BASE_DIR/VERSION 2>/dev/null || echo '(未安装)'"
  ).getOrElse { "(查询失败)" }.trim()

  private fun md5Hex(data: ByteArray): String {
    val d = MessageDigest.getInstance("MD5").digest(data)
    return d.joinToString("") { "%02x".format(it) }
  }
}
