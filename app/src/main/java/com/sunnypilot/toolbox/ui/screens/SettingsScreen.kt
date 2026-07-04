package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.SettingsRepository
import com.sunnypilot.toolbox.model.C3SettingMeta
import com.sunnypilot.toolbox.model.valueAsBoolean
import com.sunnypilot.toolbox.model.valueAsChoiceIndex
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(sshManager) }

    var settings by remember { mutableStateOf<List<C3SettingMeta>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadSettings() {
        scope.launch {
            isLoading = true
            error = null
            repository.listSettings().fold(
                onSuccess = { settings = it },
                onFailure = { e ->
                    error = e.message
                    Toast.makeText(context, "读取设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadSettings()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "C3 设置中心",
                style = MaterialTheme.typography.headlineMedium,
                color = Slate900,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { loadSettings() }, enabled = !isLoading) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "刷新",
                    tint = Teal500
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "修改后的设置会立即同步到 C3 的 Params 参数系统。",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate600
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading && settings.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal500)
            }
        } else if (error != null && settings.isEmpty()) {
            ErrorSettingsState(error!!) { loadSettings() }
        } else {
            settings.forEach { setting ->
                SettingCard(
                    setting = setting,
                    onValueChanged = { newValue ->
                        scope.launch {
                            repository.setSetting(setting.key, newValue).fold(
                                onSuccess = { result ->
                                    if (result.error != null) {
                                        Toast.makeText(context, result.error, Toast.LENGTH_SHORT).show()
                                    } else {
                                        loadSettings()
                                    }
                                },
                                onFailure = { e ->
                                    Toast.makeText(context, "同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingCard(
    setting: C3SettingMeta,
    onValueChanged: (String) -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Slate50),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = setting.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Slate900
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = setting.desc,
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600
            )
            Spacer(modifier = Modifier.height(12.dp))
            when (setting.type) {
                "bool" -> BoolSettingControl(setting, onValueChanged)
                "int" -> ChoiceSettingControl(setting, onValueChanged)
                else -> Text(
                    text = "不支持的类型: ${setting.type}",
                    color = Slate500,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (setting.error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = setting.error,
                    color = Red500,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoolSettingControl(
    setting: C3SettingMeta,
    onValueChanged: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = if (setting.valueAsBoolean()) "已开启" else "已关闭",
            color = if (setting.valueAsBoolean()) Teal700 else Slate500,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = setting.valueAsBoolean(),
            onCheckedChange = { onValueChanged(if (it) "1" else "0") },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Teal500,
                checkedTrackColor = Teal100
            )
        )
    }
}

@Composable
private fun ChoiceSettingControl(
    setting: C3SettingMeta,
    onValueChanged: (String) -> Unit
) {
    val choices = setting.choices ?: return
    val selectedIndex = setting.valueAsChoiceIndex()

    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        choices.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onValueChanged(index.toString()) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                label = { Text(label, color = if (index == selectedIndex) Slate900 else Slate600) }
            )
        }
    }
}

@Composable
private fun ErrorSettingsState(error: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp)
    ) {
        Text("读取设置失败", color = Slate600, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(error, color = Red500, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}
