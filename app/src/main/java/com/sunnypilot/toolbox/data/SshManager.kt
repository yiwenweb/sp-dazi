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
import java.util.Properties

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
                "memory" to (parts.getOrNull(3) ?: "0 1"),
                "storageFree" to (parts.getOrNull(4) ?: "--"),
                "hostname" to (parts.getOrNull(5) ?: ""),
                "serial" to (parts.getOrNull(6) ?: "unknown"),
                "dongleId" to (parts.getOrNull(7) ?: "unknown"),
                "openpilotProcesses" to (parts.getOrNull(8) ?: "0")
            )
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
