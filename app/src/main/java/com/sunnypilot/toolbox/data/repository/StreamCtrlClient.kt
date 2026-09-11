package com.sunnypilot.toolbox.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * C3 侧 streamfwd 的控制通道（:8085）客户端 —— 用于切换摄像头。
 *
 * 协议：短连接，一行一条命令，服务端回一行 JSON。
 * ```
 *   wide     切到广角摄像头
 *   narrow   切到普通/长焦摄像头
 *   status   查询当前摄像头
 * ```
 *
 * 为什么是短连接：每次点击 connect → send → 读一行 → close，服务端不需要维护连接，
 * 因此不会出现「客户端进程异常退出、服务端还挂着一条只连不读的连接」那种僵尸连接
 * —— supervideo 时代正是被这种连接把 C3 的 UI 写缓冲堵死并最终崩掉的。
 *
 * ⚠️ 不要在 `Socket().apply { }` 里构造 InetSocketAddress：`apply` 的接收者是 Socket，
 * 而 Socket 自带 `port` 属性（未连接时为 0），会把 `port` 静默解析成 0，
 * 导致连到 0 号端口报 ECONNREFUSED（这个坑在 OverlayDataClient 上真实踩过）。
 */
object StreamCtrlClient {
  const val PORT = 8085

  suspend fun send(host: String, cmd: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val addr = InetSocketAddress(host, PORT)
      Socket().use { s ->
        s.tcpNoDelay = true
        s.connect(addr, 3000)
        s.soTimeout = 3000
        val out = s.getOutputStream()
        out.write((cmd + "\n").toByteArray())
        out.flush()
        val buf = ByteArray(256)
        val n = s.getInputStream().read(buf)
        if (n > 0) String(buf, 0, n).trim() else ""
      }
    }
  }
}
