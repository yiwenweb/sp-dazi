package com.sunnypilot.toolbox.data

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.sunnypilot.toolbox.model.ConnectionStage
import com.sunnypilot.toolbox.network.AutoDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties

class SshShell(
    val inputStream: InputStream,
    val outputStream: OutputStream,
    private val channel: com.jcraft.jsch.ChannelShell
) {
    fun isConnected(): Boolean = channel.isConnected && !channel.isClosed
    fun disconnect() {
        try {
            outputStream.close()
            inputStream.close()
            channel.disconnect()
        } catch (_: Exception) {}
    }
}

class SshManager {
    private var session: Session? = null
    var connectedHost: String? = null
        private set

    private val _connectionStage = MutableStateFlow(ConnectionStage.IDLE)
    val connectionStage: StateFlow<ConnectionStage> = _connectionStage.asStateFlow()

    companion object {
        const val DEFAULT_PORT = 22
        const val DEFAULT_USER = "comma"
    }

    fun resetStage() {
        _connectionStage.value = ConnectionStage.IDLE
    }

    /**
     * 动态检测当前设备是否支持 EC 算法。
     * 车机/定制 Android 可能精简了 BouncyCastle，导致 EC 不可用。
     */
    private fun isEcAvailable(): Boolean {
        return try {
            java.security.AlgorithmParameters.getInstance("EC")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 构建 SSH 配置：
     * - EC 可用时：使用完整算法集（含 ECDH/ECDSA），安全性更高
     * - EC 不可用时：回退到 RSA + DH，保证在车机等精简系统上也能连接
     */
    private fun buildSshConfig(): Properties {
        val config = Properties()
        config.setProperty("StrictHostKeyChecking", "no")

        if (isEcAvailable()) {
            // 完整算法集
            config.setProperty(
                "kex",
                "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1"
            )
            config.setProperty(
                "server_host_key",
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa"
            )
        } else {
            // RSA 回退：不引用任何 EC 算法，避免 JSch 加载 EC 类时崩溃
            config.setProperty(
                "kex",
                "diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1"
            )
            config.setProperty(
                "server_host_key",
                "rsa-sha2-512,rsa-sha2-256,ssh-rsa"
            )
        }

        return config
    }

    suspend fun connectWithPassword(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String = DEFAULT_USER,
        password: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            resetStage()
            _connectionStage.value = ConnectionStage.RESOLVING
            _connectionStage.value = ConnectionStage.CONNECTING
            disconnect()
            connectedHost = host
            session = JSch().getSession(username, host, port).apply {
                setPassword(password)
                setConfig(buildSshConfig())
                _connectionStage.value = ConnectionStage.AUTHENTICATING
                connect(15000)
            }
            _connectionStage.value = ConnectionStage.CONNECTED
            Result.success(true)
        } catch (e: Exception) {
            Log.e("SshManager", "connectWithPassword failed", e)
            _connectionStage.value = ConnectionStage.FAILED
            Result.failure(mapConnectionError(e))
        }
    }

    suspend fun connectWithPrivateKey(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String = DEFAULT_USER,
        privateKeyContent: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            resetStage()
            _connectionStage.value = ConnectionStage.RESOLVING
            _connectionStage.value = ConnectionStage.CONNECTING
            disconnect()

            connectedHost = host
            val normalizedKey = PpkToPemConverter.convertIfNeeded(privateKeyContent)
            val jsch = JSch()
            jsch.addIdentity("default-key", normalizedKey.toByteArray(), null, null)
            session = jsch.getSession(username, host, port).apply {
                setConfig(buildSshConfig())
                _connectionStage.value = ConnectionStage.AUTHENTICATING
                connect(15000)
            }
            _connectionStage.value = ConnectionStage.CONNECTED
            Result.success(true)
        } catch (e: Exception) {
            Log.e("SshManager", "connectWithPrivateKey failed", e)
            _connectionStage.value = ConnectionStage.FAILED
            Result.failure(mapConnectionError(e))
        }
    }

    private fun mapConnectionError(e: Exception): Exception {
        return when (e) {
            is NoRouteToHostException -> Exception(
                "无法到达目标主机。请检查：1) 手机与 C3 是否连接同一 WiFi/热点；2) IP 和端口是否填写正确；3) 是否开启 VPN、私人 DNS 或移动数据导致路由失败。",
                e
            )
            is UnknownHostException -> Exception(
                "无法解析主机地址，请检查 IP 或 DNS 设置。",
                e
            )
            is SocketTimeoutException -> Exception(
                "连接超时，请确认 C3 已开机且 SSH 服务已开启。",
                e
            )
            else -> e
        }
    }

    suspend fun tryConnect(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String = DEFAULT_USER,
        privateKeyContent: String? = null,
        password: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        when {
            !privateKeyContent.isNullOrBlank() -> connectWithPrivateKey(
                host = host,
                port = port,
                username = username,
                privateKeyContent = privateKeyContent
            )
            !password.isNullOrBlank() -> connectWithPassword(
                host = host,
                port = port,
                username = username,
                password = password
            )
            else -> Result.failure(IllegalArgumentException("未提供认证凭证"))
        }
    }

    suspend fun connectWithAutoDiscovery(
        port: Int = DEFAULT_PORT,
        username: String = DEFAULT_USER,
        privateKeyContent: String,
        timeoutMs: Int = 400,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<String> = withContext(Dispatchers.IO) {
        val hosts = AutoDiscovery.findSshHosts(port, timeoutMs, onProgress)
        hosts.forEach { discovered ->
            val result = tryConnect(
                host = discovered.host,
                port = discovered.port,
                username = username,
                privateKeyContent = privateKeyContent
            )
            if (result.isSuccess) {
                return@withContext Result.success(discovered.host)
            }
        }
        Result.failure(Exception("未找到可用设备，请确认 C3 已联网且 SSH 已开启"))
    }


    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        val sess = session ?: return@withContext Result.failure(IllegalStateException("未连接"))
        try {
            val channel = sess.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()
            channel.outputStream = output
            channel.setErrStream(error)
            channel.connect(10000)

            while (!channel.isClosed) {
                Thread.sleep(100)
            }

            val result = output.toString("UTF-8") + error.toString("UTF-8")
            channel.disconnect()
            Result.success(result)
        } catch (e: Exception) {
            Log.e("SshManager", "executeCommand failed", e)
            Result.failure(e)
        }
    }

    suspend fun readFile(remotePath: String): Result<String> = withContext(Dispatchers.IO) {
        val sess = session ?: return@withContext Result.failure(IllegalStateException("未连接"))
        try {
            val channel = sess.openChannel("sftp") as ChannelSftp
            channel.connect(10000)
            val stream = channel.get(remotePath)
            val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            channel.disconnect()
            Result.success(content)
        } catch (e: Exception) {
            Log.e("SshManager", "readFile failed", e)
            Result.failure(e)
        }
    }

    suspend fun downloadFile(remotePath: String, localPath: java.io.File): Result<Unit> = withContext(Dispatchers.IO) {
        val sess = session ?: return@withContext Result.failure(IllegalStateException("未连接"))
        try {
            localPath.parentFile?.mkdirs()
            val channel = sess.openChannel("sftp") as ChannelSftp
            channel.connect(10000)
            channel.get(remotePath, localPath.absolutePath)
            channel.disconnect()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("SshManager", "downloadFile failed", e)
            Result.failure(e)
        }
    }

    suspend fun getDeviceStatus(): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        val commands = listOf(
            "cat /proc/cpuinfo | grep 'Hardware' | head -1",
            "cat /sys/class/thermal/thermal_zone0/temp",
            "cat /sys/class/thermal/thermal_zone1/temp",
            "cat /sys/class/power_supply/bms/temp 2>/dev/null || echo 0",
            "cat /proc/loadavg | awk '{print $1}'",
            "free -m | grep Mem | awk '{print $3\" \"$2}'",
            "df -h /data | tail -1 | awk '{print $4}'",
            "cat /proc/sys/kernel/hostname",
            "cat /data/params/d/HardwareSerial 2>/dev/null || echo unknown",
            "cat /data/params/d/DongleId 2>/dev/null || echo unknown",
            "ps -A | grep '/data/openpilot/system/manager' | grep -v grep | wc -l"
        )
        val script = commands.joinToString("; echo '---'; ")
        executeCommand(script).map { output ->
            val parts = output.split("---").map { it.trim() }
            mapOf(
                "hardware" to (parts.getOrNull(0)?.substringAfter("Hardware\t: ")?.trim() ?: "comma three"),
                "cpuTemp" to (parts.getOrNull(1) ?: "0"),
                "deviceTemp" to (parts.getOrNull(2) ?: "0"),
                "bmsTemp" to (parts.getOrNull(3) ?: "0"),
                "cpuLoad" to (parts.getOrNull(4) ?: "0"),
                "memory" to (parts.getOrNull(5) ?: "0 1"),
                "storageFree" to (parts.getOrNull(6) ?: "--"),
                "hostname" to (parts.getOrNull(7) ?: ""),
                "serial" to (parts.getOrNull(8) ?: "unknown"),
                "dongleId" to (parts.getOrNull(9) ?: "unknown"),
                "openpilotProcesses" to (parts.getOrNull(10) ?: "0")
            )
        }
    }

    suspend fun openShell(): Result<SshShell> = withContext(Dispatchers.IO) {
        val sess = session ?: return@withContext Result.failure(IllegalStateException("未连接"))
        try {
            val channel = sess.openChannel("shell") as com.jcraft.jsch.ChannelShell
            channel.setPty(true)
            channel.setPtyType("xterm-256color")
            channel.setPtySize(120, 40, 0, 0)
            channel.connect(10000)
            Result.success(SshShell(channel.inputStream, channel.outputStream, channel))
        } catch (e: Exception) {
            Log.e("SshManager", "openShell failed", e)
            Result.failure(e)
        }
    }

    fun isConnected(): Boolean {
        return session?.isConnected == true
    }

    fun disconnect() {
        try {
            session?.disconnect()
        } catch (_: Exception) {}
        session = null
        connectedHost = null
    }
}
