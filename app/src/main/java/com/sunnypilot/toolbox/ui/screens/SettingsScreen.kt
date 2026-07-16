package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

// 现代化配色 - 统一 App 风格
private val Slate50 = Color(0xFFF8FAFC)
private val Slate100 = Color(0xFFF1F5F9)
private val Slate200 = Color(0xFFE2E8F0)
private val Slate400 = Color(0xFF94A3B8)
private val Slate500 = Color(0xFF64748B)
private val Slate700 = Color(0xFF334155)
private val Slate900 = Color(0xFF0F172A)
private val Teal500 = Color(0xFF14B8A6)
private val Teal50 = Color(0xFFF0FDFA)
private val Green500 = Color(0xFF22C55E)
private val Red500 = Color(0xFFEF4444)
private val Amber500 = Color(0xFFF59E0B)
private val Blue500 = Color(0xFF3B82F6)

// 分类图标映射 - 更现代化的图标
private val categoryIcons = mapOf(
    "驾驶开关" to Icons.Filled.ToggleOn,
    "驾驶风格" to Icons.Filled.DriveEta,
    "转向设置" to Icons.Filled.AutoMode,
    "巡航设置" to Icons.Filled.Speed,
    "视觉设置" to Icons.Filled.Visibility,
    "SP 功能" to Icons.Filled.Stars,
    "其他" to Icons.Filled.Settings
)

// 分类颜色映射
private val categoryColors = mapOf(
    "驾驶开关" to Blue500,
    "驾驶风格" to Teal500,
    "转向设置" to Amber500,
    "巡航设置" to Green500,
    "视觉设置" to Color(0xFF8B5CF6),
    "SP 功能" to Color(0xFFEC4899),
    "其他" to Slate500
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
    var pendingKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

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

    // 分类展开状态
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    Box(modifier = modifier.fillMaxSize().background(Slate50)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 现代化标题栏
            Surface(
                color = Color.White,
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = null,
                            tint = Teal500,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "驾驶设置",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900
                            )
                            Text(
                                "修改后立即同步到 C3",
                                fontSize = 11.sp,
                                color = Slate500
                            )
                        }
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Teal500,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            IconButton(onClick = { loadSettings() }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    "刷新",
                                    tint = Teal500
                                )
                            }
                        }
                    }
                }
            }

            if (error != null && settings.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = Red500,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("读取设置失败", color = Slate900, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = Slate500, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { loadSettings() },
                            colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                        ) {
                            Text("重试")
                        }
                    }
                }
            } else {
                // 可滚动内容区
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    // 分类分组
                    grouped.forEach { (category, items) ->
                        val isExpanded = expandedCategories.getOrPut(category) { true }
                        val icon = categoryIcons[category] ?: Icons.Filled.Settings
                        val color = categoryColors[category] ?: Slate500

                        // 分类标题卡片
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isExpanded) color.copy(alpha = 0.1f) else Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clickable { expandedCategories[category] = !isExpanded }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = color,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    category,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Slate900,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = color.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        "${items.size}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = color,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = Slate400,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 分类内容 - 卡片网格布局
                        AnimatedVisibility(visible = isExpanded) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                                // 只有 bool 类型采用网格布局
                                val boolSettings = items.filter { it.type == "bool" }
                                val otherSettings = items.filter { it.type != "bool" }

                                // Bool 开关 - 2列网格
                                if (boolSettings.isNotEmpty()) {
                                    boolSettings.chunked(2).forEach { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowItems.forEach { setting ->
                                                BoolSettingCard(
                                                    setting = setting,
                                                    isPending = pendingKeys.contains(setting.key),
                                                    modifier = Modifier.weight(1f),
                                                    onValueChanged = { newValue ->
                                                        pendingKeys = pendingKeys + setting.key
                                                        scope.launch {
                                                            repository.setSetting(setting.key, newValue).fold(
                                                                onSuccess = { result ->
                                                                    pendingKeys = pendingKeys - setting.key
                                                                    if (result.error != null) {
                                                                        Toast.makeText(
                                                                            context,
                                                                            "${setting.title}: ${result.error}",
                                                                            Toast.LENGTH_SHORT
                                                                        ).show()
                                                                    }
                                                                    delay(200)
                                                                    loadSettings()
                                                                },
                                                                onFailure = { e ->
                                                                    pendingKeys = pendingKeys - setting.key
                                                                    Toast.makeText(
                                                                        context,
                                                                        "${setting.title}: ${e.message}",
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                }
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                            // 填充空白
                                            if (rowItems.size == 1) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }

                                // 其他类型 - 全宽卡片
                                otherSettings.forEach { setting ->
                                    ComplexSettingCard(
                                        setting = setting,
                                        isPending = pendingKeys.contains(setting.key),
                                        onValueChanged = { newValue ->
                                            pendingKeys = pendingKeys + setting.key
                                            scope.launch {
                                                repository.setSetting(setting.key, newValue).fold(
                                                    onSuccess = { result ->
                                                        pendingKeys = pendingKeys - setting.key
                                                        if (result.error != null) {
                                                            Toast.makeText(
                                                                context,
                                                                "${setting.title}: ${result.error}",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        delay(200)
                                                        loadSettings()
                                                    },
                                                    onFailure = { e ->
                                                        pendingKeys = pendingKeys - setting.key
                                                        Toast.makeText(
                                                            context,
                                                            "${setting.title}: ${e.message}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                )
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// Bool 开关卡片 - 紧凑型
@Composable
private fun BoolSettingCard(
    setting: C3SettingMeta,
    isPending: Boolean,
    modifier: Modifier = Modifier,
    onValueChanged: (String) -> Unit
) {
    val isEnabled = setting.valueAsBoolean()
    
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) Teal50 else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isPending) {
                    onValueChanged(if (isEnabled) "0" else "1")
                }
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 状态指示器
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isEnabled) Teal500 else Slate200,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isPending) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                                color = if (isEnabled) Color.White else Slate500
                            )
                        } else {
                            Icon(
                                if (isEnabled) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isEnabled) Color.White else Slate400,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                
                Spacer(Modifier.width(10.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = setting.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isEnabled) Teal500 else Slate900,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }
            }
            
            Spacer(Modifier.height(6.dp))
            
            Text(
                text = setting.desc,
                fontSize = 10.sp,
                color = Slate500,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp
            )
        }
    }
}

// 复杂设置卡片 (int/float) - 全宽
@Composable
private fun ComplexSettingCard(
    setting: C3SettingMeta,
    isPending: Boolean,
    onValueChanged: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = setting.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = setting.desc,
                        fontSize = 11.sp,
                        color = Slate500,
                        lineHeight = 15.sp
                    )
                </Column>
                
                if (isPending) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                        color = Teal500
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // 控件区域
            when (setting.type) {
                "int" -> {
                    val choices = setting.choices ?: return@Column
                    val selectedIndex = setting.valueAsChoiceIndex()
                    
                    // 分段按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        choices.forEachIndexed { index, label ->
                            val isSelected = index == selectedIndex
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Teal500 else Slate100,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isPending) {
                                        onValueChanged(index.toString())
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else Slate700,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                "float" -> {
                    val currentValue = remember(setting.value) { setting.valueAsFloat() }
                    var text by remember(currentValue) { mutableStateOf(currentValue.toString()) }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 14.sp,
                                color = Slate900
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Teal500,
                                unfocusedBorderColor = Slate200,
                                cursorColor = Teal500
                            ),
                            enabled = !isPending,
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onValueChanged(text) },
                            enabled = !isPending,
                            colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text("应用", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
