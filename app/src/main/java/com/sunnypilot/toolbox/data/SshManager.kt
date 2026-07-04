package com.sunnypilot.toolbox.data

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.sunnypilot.toolbox.model.ConnectionStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
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

    private val _connectionStage = MutableStateFlow(ConnectionStage.IDLE)
    val connectionStage: StateFlow<ConnectionStage> = _connectionStage.asStateFlow()

    companion object {
        const val DEFAULT_PORT = 22
        const val DEFAULT_USER = "comma"
    }

    fun resetStage() {
        _connectionStage.value = ConnectionStage.IDLE
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
            session = JSch().getSession(username, host, port).apply {
                setPassword(password)
                val config = Properties().apply {
                    setProperty("StrictHostKeyChecking", "no")
                }
                setConfig(config)
                _connectionStage.value = ConnectionStage.AUTHENTICATING
                connect(15000)
            }
            _connectionStage.value = ConnectionStage.CONNECTED
            Result.success(true)
        } catch (e: Exception) {
            Log.e("SshManager", "connectWithPassword failed", e)
            _connectionStage.value = ConnectionStage.FAILED
            Result.failure(e)
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

            val normalizedKey = PpkToPemConverter.convertIfNeeded(privateKeyContent)
            val jsch = JSch()
            jsch.addIdentity("default-key", normalizedKey.toByteArray(), null, null)
            session = jsch.getSession(username, host, port).apply {
                val config = Properties().apply {
                    setProperty("StrictHostKeyChecking", "no")
                }
                setConfig(config)
                _connectionStage.value = ConnectionStage.AUTHENTICATING
                connect(15000)
            }
            _connectionStage.value = ConnectionStage.CONNECTED
            Result.success(true)
        } catch (e: Exception) {
            Log.e("SshManager", "connectWithPrivateKey failed", e)
            _connectionStage.value = ConnectionStage.FAILED
            Result.failure(e)
        }
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
            "ps -A | grep -E 'manager|openpilot' | grep -v grep | wc -l"
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

    fun openShell(): Result<SshShell> {
        val sess = session ?: return Result.failure(IllegalStateException("未连接"))
        return try {
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
    }
}
