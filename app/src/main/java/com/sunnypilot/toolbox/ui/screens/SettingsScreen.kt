package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
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
    var selectedCategory by remember { mutableStateOf<String?>(null) }

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

    Box(modifier = modifier.fillMaxSize().background(Slate50)) {
        if (selectedCategory == null) {
            // 主页：显示所有分类
            CategoryListScreen(
                grouped = grouped,
                isLoading = isLoading,
                error = error,
                onCategoryClick = { category -> selectedCategory = category },
                onRefresh = { loadSettings() }
            )
        } else {
            // 分类详情页
            CategoryDetailScreen(
                category = selectedCategory!!,
                settings = grouped[selectedCategory] ?: emptyList(),
                repository = repository,
                pendingKeys = pendingKeys,
                onBack = { selectedCategory = null },
                onPendingChange = { key, isPending ->
                    pendingKeys = if (isPending) pendingKeys + key else pendingKeys - key
                },
                onSettingChanged = { loadSettings() }
            )
        }
    }
}

// 分类列表主页
@Composable
private fun CategoryListScreen(
    grouped: Map<String, List<C3SettingMeta>>,
    isLoading: Boolean,
    error: String?,
    onCategoryClick: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 标题栏
        Surface(color = Color.White, shadowElevation = 2.dp) {
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
                            "点击分类查看详细设置",
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
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, "刷新", tint = Teal500)
                        }
                    }
                }
            }
        }

        if (error != null && grouped.isEmpty()) {
            // 错误状态
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
                    Text(error, color = Slate500, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                    ) {
                        Text("重试")
                    }
                }
            }
        } else {
            // 分类网格
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                grouped.forEach { (category, items) ->
                    val icon = categoryIcons[category] ?: Icons.Filled.Settings
                    val color = categoryColors[category] ?: Slate500
                    
                    CategoryCard(
                        category = category,
                        itemCount = items.size,
                        icon = icon,
                        color = color,
                        onClick = { onCategoryClick(category) }
                    )
                }
                
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// 分类卡片
@Composable
private fun CategoryCard(
    category: String,
    itemCount: Int,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(20.dp)
        ) {
            // 图标
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.15f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    category,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$itemCount 项设置",
                    fontSize = 13.sp,
                    color = Slate500
                )
            }
            
            // 箭头
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Slate400,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// 分类详情页
@Composable
private fun CategoryDetailScreen(
    category: String,
    settings: List<C3SettingMeta>,
    repository: SettingsRepository,
    pendingKeys: Set<String>,
    onBack: () -> Unit,
    onPendingChange: (String, Boolean) -> Unit,
    onSettingChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val icon = categoryIcons[category] ?: Icons.Filled.Settings
    val color = categoryColors[category] ?: Slate500

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题栏
        Surface(color = Color.White, shadowElevation = 2.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, "返回", tint = Slate700)
                }
                
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        category,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        "${settings.size} 项设置 · 修改后立即同步",
                        fontSize = 11.sp,
                        color = Slate500
                    )
                }
            }
        }

        // 设置列表
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            val boolSettings = settings.filter { it.type == "bool" }
            val otherSettings = settings.filter { it.type != "bool" }

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
                                    onPendingChange(setting.key, true)
                                    scope.launch {
                                        repository.setSetting(setting.key, newValue).fold(
                                            onSuccess = { result ->
                                                onPendingChange(setting.key, false)
                                                if (result.error != null) {
                                                    Toast.makeText(
                                                        context,
                                                        "${setting.title}: ${result.error}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                delay(200)
                                                onSettingChanged()
                                            },
                                            onFailure = { e ->
                                                onPendingChange(setting.key, false)
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
                        onPendingChange(setting.key, true)
                        scope.launch {
                            repository.setSetting(setting.key, newValue).fold(
                                onSuccess = { result ->
                                    onPendingChange(setting.key, false)
                                    if (result.error != null) {
                                        Toast.makeText(
                                            context,
                                            "${setting.title}: ${result.error}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    delay(200)
                                    onSettingChanged()
                                },
                                onFailure = { e ->
                                    onPendingChange(setting.key, false)
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
            
            Spacer(Modifier.height(16.dp))
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
                }
                
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
