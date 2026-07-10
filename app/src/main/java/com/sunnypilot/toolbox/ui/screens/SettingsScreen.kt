package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.SettingsRepository
import com.sunnypilot.toolbox.model.C3SettingMeta
import com.sunnypilot.toolbox.model.valueAsBoolean
import com.sunnypilot.toolbox.model.valueAsChoiceIndex
import com.sunnypilot.toolbox.model.valueAsFloat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// C3 风格配色
private val C3Bg = Color(0xFF1A1A2E)
private val C3Card = Color(0xFF232340)
private val C3CardAlt = Color(0xFF2A2A4A)
private val C3Accent = Color(0xFF0D9488)
private val C3Text = Color(0xFFE2E8F0)
private val C3SubText = Color(0xFF94A3B8)
private val C3Divider = Color(0xFF334155)
private val C3Section = Color(0xFF1E293B)
private val C3Green = Color(0xFF34D399)
private val C3Red = Color(0xFFEF4444)
private val C3Yellow = Color(0xFFFBBF24)

// 分类图标映射 (与 C3 侧边栏一致)
private val categoryIcons = mapOf(
    "驾驶开关" to Icons.Outlined.PowerSettingsNew,
    "驾驶风格" to Icons.Outlined.Rocket,
    "转向设置" to Icons.Outlined.SwapHoriz,
    "巡航设置" to Icons.Outlined.TrendingUp,
    "视觉设置" to Icons.Outlined.Visibility,
    "SP 功能" to Icons.Outlined.Star,
    "其他" to Icons.Outlined.Settings
)

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
    var pendingKey by remember { mutableStateOf<String?>(null) }

    fun loadSettings() {
        scope.launch {
            isLoading = true
            error = null
            repository.listSettings().fold(
                onSuccess = { settings = it },
                onFailure = { e -> error = e.message }
            )
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadSettings() }

    val grouped = remember(settings) {
        settings.groupBy { it.category ?: "其他" }
    }

    // 可折叠分类
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    Box(modifier = modifier.fillMaxSize().background(C3Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // 标题栏
            Surface(color = C3Section) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("C3 设置中心", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C3Text)
                        Spacer(Modifier.weight(1f))
                        if (isLoading) CircularProgressIndicator(color = C3Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else IconButton(onClick = { loadSettings() }) {
                            Icon(Icons.Outlined.Refresh, "刷新", tint = C3Accent)
                        }
                    }
                    Text("修改后立即同步到 C3，与车机 UI 分类排列一致", fontSize = 12.sp, color = C3SubText)
                }
            }

            if (error != null && settings.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("读取设置失败", color = C3Red, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = C3SubText, fontSize = 11.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { loadSettings() }) { Text("重试", color = C3Accent) }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // 分类分组 - 按 C3 侧边栏顺序
            grouped.forEach { (category, items) ->
                val isExpanded = expandedCategories.getOrPut(category) { true }
                val icon = categoryIcons[category] ?: Icons.Outlined.Settings

                // 分类标题
                Surface(
                    color = if (isExpanded) C3CardAlt else C3Section,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedCategories[category] = !isExpanded }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = C3Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(category, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C3Text, modifier = Modifier.weight(1f))
                        Text("${items.size}", fontSize = 11.sp, color = C3SubText)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null, tint = C3SubText, modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 分类内容
                AnimatedVisibility(visible = isExpanded) {
                    Column {
                        items.forEachIndexed { index, setting ->
                            C3SettingItem(
                                setting = setting,
                                isPending = pendingKey == setting.key,
                                onValueChanged = { newValue ->
                                    pendingKey = setting.key
                                    scope.launch {
                                        repository.setSetting(setting.key, newValue).fold(
                                            onSuccess = { result ->
                                                pendingKey = null
                                                if (result.error != null) {
                                                    Toast.makeText(context, "${setting.title}: ${result.error}", Toast.LENGTH_LONG).show()
                                                    delay(300)
                                                    loadSettings()
                                                } else {
                                                    // 短暂显示成功
                                                    delay(200)
                                                    loadSettings()
                                                }
                                            },
                                            onFailure = { e ->
                                                pendingKey = null
                                                Toast.makeText(context, "${setting.title}: 同步失败 - ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        )
                                    }
                                }
                            )
                            // 分割线
                            if (index < items.size - 1) {
                                Divider(color = C3Divider, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun C3SettingItem(
    setting: C3SettingMeta,
    isPending: Boolean,
    onValueChanged: (String) -> Unit
) {

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isPending) C3Accent.copy(alpha = 0.08f) else C3Bg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    setting.title,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C3Text,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // 成功/失败指示
                AnimatedVisibility(visible = isPending) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp, modifier = Modifier.size(14.dp).padding(start = 6.dp),
                        color = C3Accent
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                setting.desc,
                fontSize = 10.sp, color = C3SubText,
                maxLines = 3, lineHeight = 14.sp
            )
            // 当前值
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (setting.type) {
                    "bool" -> if (setting.valueAsBoolean()) "● 已开启" else "○ 已关闭"
                    "int" -> {
                        val idx = setting.valueAsChoiceIndex()
                        val label = setting.choices?.getOrNull(idx) ?: "$idx"
                        "当前: $label"
                    }
                    "float" -> "当前: ${setting.valueAsFloat()}"
                    else -> ""
                },
                fontSize = 10.sp, color = if (setting.type == "bool" && setting.valueAsBoolean()) C3Green else C3SubText
            )
        }

        Spacer(Modifier.width(12.dp))

        // 控件在右侧 (模拟 C3 布局)
        when (setting.type) {
            "bool" -> {
                Switch(
                    checked = setting.valueAsBoolean(),
                    onCheckedChange = { onValueChanged(if (it) "1" else "0") },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = C3Accent,
                        checkedTrackColor = C3Accent.copy(alpha = 0.3f),
                        uncheckedThumbColor = C3SubText,
                        uncheckedTrackColor = C3Divider
                    ),
                    enabled = !isPending
                )
            }
            "int" -> {
                C3ChoiceControl(setting, onValueChanged, isPending)
            }
            "float" -> {
                C3FloatControl(setting, onValueChanged, isPending)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun C3ChoiceControl(
    setting: C3SettingMeta,
    onValueChanged: (String) -> Unit,
    disabled: Boolean
) {
    val choices = setting.choices ?: return
    val selectedIndex = setting.valueAsChoiceIndex()

    SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = 300.dp)) {
        choices.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onValueChanged(index.toString()) },
                enabled = !disabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = C3Accent.copy(alpha = 0.25f),
                    activeContentColor = C3Text,
                    inactiveContainerColor = C3Card,
                    inactiveContentColor = C3SubText
                ),
                label = { Text(label, fontSize = 10.sp, maxLines = 1) }
            )
        }
    }
}

@Composable
private fun C3FloatControl(
    setting: C3SettingMeta,
    onValueChanged: (String) -> Unit,
    disabled: Boolean
) {
    val currentValue = remember(setting.value) { setting.valueAsFloat() }
    var text by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.width(80.dp),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 11.sp, color = C3Text, textAlign = TextAlign.Center
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C3Accent,
                unfocusedBorderColor = C3Divider,
                cursorColor = C3Accent
            ),
            enabled = !disabled
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = { onValueChanged(text) },
            enabled = !disabled,
            colors = ButtonDefaults.textButtonColors(contentColor = C3Accent),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("应用", fontSize = 11.sp)
        }
    }
}
