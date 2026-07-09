package com.sunnypilot.toolbox.data.sync

import android.content.Context
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.db.AppDatabase
import com.sunnypilot.toolbox.data.repository.DriveStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步状态分类
 */
enum class SyncStatus {
    IDLE,           // 未在同步
    CONNECTING,     // 正在连接 C3
    CHECKING,       // 正在检查数据
    COMPUTING,      // 正在远程计算
    PARSING,        // 正在解析
    SAVING,         // 正在保存
    DONE,           // 完成
    ERROR           // 出错
}

/**
 * 进程级同步状态持有者。
 *
 * 用独立的 [CoroutineScope]（非 Composable 生命周期）运行，
 * 用户离开页面后同步仍会继续。界面通过 collect StateFlow 观察进度。
 */
object SyncStateHolder {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _status = MutableStateFlow(SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _stageText = MutableStateFlow("")
    val stageText: StateFlow<String> = _stageText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _syncedCount = MutableStateFlow<Int?>(null)
    val syncedCount: StateFlow<Int?> = _syncedCount.asStateFlow()

    /** 最近一次同步开始的时间 */
    private val _lastSyncAt = MutableStateFlow<String?>(null)
    val lastSyncAt: StateFlow<String?> = _lastSyncAt.asStateFlow()

    val isRunning: Boolean get() = _status.value in setOf(
        SyncStatus.CONNECTING, SyncStatus.CHECKING,
        SyncStatus.COMPUTING, SyncStatus.PARSING, SyncStatus.SAVING
    )

    /** 获取本地数据库里最新的数据日期 */
    fun getLatestStoredDate(context: Context): String? {
        return try {
            val dao = AppDatabase.getDatabase(context).driveStatsDao()
            dao.getLatestDate()
        } catch (_: Exception) { null }
    }

    /** 获取本地数据库最早的数据日期 */
    fun getEarliestStoredDate(context: Context): String? {
        return try {
            val dao = AppDatabase.getDatabase(context).driveStatsDao()
            dao.getEarliestDate()
        } catch (_: Exception) { null }
    }

    /** 开始异步同步（不会因 UI 导航而中断） */
    fun start(context: Context, sshManager: SshManager) {
        if (isRunning) return

        scope.launch {
            _errorMessage.value = null
            _syncedCount.value = null
            _lastSyncAt.value = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val repo = DriveStatsRepository(context, sshManager)

            repo.syncFromDevice(onStage = { stage, syncStatus ->
                _stageText.value = stage
                _status.value = syncStatus
            }).fold(
                onSuccess = { count ->
                    _syncedCount.value = count
                    _status.value = SyncStatus.IDLE
                    _stageText.value = if (count > 0) "已同步 $count 条新记录" else "已同步，无新数据"
                },
                onFailure = { e ->
                    _errorMessage.value = e.message ?: "未知错误"
                    _status.value = SyncStatus.ERROR
                    _stageText.value = "同步失败"
                }
            )

            // 3 秒后自动回到 IDLE
            kotlinx.coroutines.delay(3000)
            _status.value = SyncStatus.IDLE
            _stageText.value = ""
            _syncedCount.value = null
        }
    }

    /** 手动清除错误状态 */
    fun dismissError() {
        _errorMessage.value = null
    }
}
