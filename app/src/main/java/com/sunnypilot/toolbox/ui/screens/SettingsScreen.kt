package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.SettingsRepository
import com.sunnypilot.toolbox.model.C3SettingMeta
import com.sunnypilot.toolbox.model.valueAsBoolean
import com.sunnypilot.toolbox.model.valueAsChoiceIndex
import com.sunnypilot.toolbox.model.valueAsFloat
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

    LaunchedEffect(Unit) { loadSettings() }

    // 按分类分组
    val grouped = remember(settings) {
        settings.groupBy { it.category ?: "其他" }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
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
                Icon(Icons.Default.Settings, contentDescription = "刷新", tint = Teal500)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "修改后立即同步到 C3，按车机 UI 分类排列。",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate600
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (isLoading && settings.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal500)
            }
        } else if (error != null && settings.isEmpty()) {
            ErrorSettingsState(error!!) { loadSettings() }
        } else {
            grouped.forEach { (category, items) ->
                // 分类标题
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Teal50,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = category,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Teal700,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                items.forEach { setting ->
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
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
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
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = setting.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Slate900
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = setting.desc,
                style = MaterialTheme.typography.bodySmall,
                color = Slate600,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            when (setting.type) {
                "bool" -> BoolSettingControl(setting, onValueChanged)
                "int" -> ChoiceSettingControl(setting, onValueChanged)
                "float" -> FloatSettingControl(setting, onValueChanged)
                else -> Text("不支持: ${setting.type}", color = Slate500, fontSize = 12.sp)
            }
            if (setting.error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(setting.error, color = Red500, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BoolSettingControl(setting: C3SettingMeta, onValueChanged: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = if (setting.valueAsBoolean()) "已开启" else "已关闭",
            color = if (setting.valueAsBoolean()) Teal700 else Slate500,
            style = MaterialTheme.typography.bodySmall
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSettingControl(setting: C3SettingMeta, onValueChanged: (String) -> Unit) {
    val choices = setting.choices ?: return
    val selectedIndex = setting.valueAsChoiceIndex()

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onValueChanged(index.toString()) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                label = {
                    Text(
                        label,
                        color = if (index == selectedIndex) Slate900 else Slate600,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                },
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Teal100
                )
            )
        }
    }
}

@Composable
private fun FloatSettingControl(setting: C3SettingMeta, onValueChanged: (String) -> Unit) {
    val currentValue = remember(setting.value) { setting.valueAsFloat() }
    var text by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("当前: $currentValue") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(12.dp))
        Button(
            onClick = { onValueChanged(text) },
            colors = ButtonDefaults.buttonColors(containerColor = Teal500),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("应用", color = Slate50)
        }
    }
}

@Composable
private fun ErrorSettingsState(error: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp)
    ) {
        Text("读取设置失败", color = Slate600)
        Spacer(modifier = Modifier.height(8.dp))
        Text(error, color = Red500, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}
