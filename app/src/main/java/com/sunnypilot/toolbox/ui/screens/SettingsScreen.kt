package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
private val Slate600 = Color(0xFF475569)
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
    var selectedCategory by remember { mutableStateOf<String?>("全部") }

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
    
    // 所有分类（包括"全部"和"模型"）
    val allCategories = remember(grouped) {
        listOf("全部") + grouped.keys.sorted() + "模型"
    }

    Box(modifier = modifier.fillMaxSize().background(Slate50)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 现代风格标签栏
            Surface(color = Color.White, shadowElevation = 1.dp) {
                CategoryTabsModern(
                    categories = allCategories,
                    selectedCategory = selectedCategory ?: "全部",
                    onCategoryClick = { selectedCategory = it },
                    isLoading = isLoading,
                    onRefresh = { loadSettings() }
                )
            }

            // 内容区域
            when (selectedCategory) {
                "全部" -> {
                    AllSettingsView(
                        grouped = grouped,
                        error = error,
                        repository = repository,
                        pendingKeys = pendingKeys,
                        onPendingChange = { key, isPending ->
                            pendingKeys = if (isPending) pendingKeys + key else pendingKeys - key
                        },
                        onSettingChanged = { loadSettings() },
                        onRefresh = { loadSettings() }
                    )
                }
                "模型" -> {
                    ModelSelectionView(sshManager = sshManager)
                }
                else -> {
                    CategoryDetailScreen(
                        category = selectedCategory!!,
                        settings = grouped[selectedCategory] ?: emptyList(),
                        repository = repository,
                        pendingKeys = pendingKeys,
                        onBack = { selectedCategory = "全部" },
                        onPendingChange = { key, isPending ->
                            pendingKeys = if (isPending) pendingKeys + key else pendingKeys - key
                        },
                        onSettingChanged = { loadSettings() }
                    )
                }
            }
        }
    }
}

// 现代风格标签栏
@Composable
private fun CategoryTabsModern(
    categories: List<String>,
    selectedCategory: String,
    onCategoryClick: (String) -> Unit,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标签滚动区域
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            categories.forEach { category ->
                val isSelected = category == selectedCategory
                val icon = when (category) {
                    "全部" -> Icons.Filled.Apps
                    "模型" -> Icons.Filled.Psychology
                    else -> categoryIcons[category] ?: Icons.Filled.Settings
                }
                val color = when (category) {
                    "全部" -> Teal500
                    "模型" -> Color(0xFF9333EA) // Purple600
                    else -> categoryColors[category] ?: Slate500
                }
                
                // 现代扁平风格标签
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) color else Slate100)
                        .clickable { onCategoryClick(category) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (isSelected) Color.White else Slate600,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            category,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) Color.White else Slate700
                        )
                    }
                }
            }
        }
        
        // 刷新按钮
        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
            if (!isLoading) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = Slate600,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                        color = Teal500
                    )
                }
            }
        }
    }
}

// 全部设置视图（显示所有分类的设置）
@Composable
private fun AllSettingsView(
    grouped: Map<String, List<C3SettingMeta>>,
    error: String?,
    repository: SettingsRepository,
    pendingKeys: Set<String>,
    onPendingChange: (String, Boolean) -> Unit,
    onSettingChanged: () -> Unit,
    onRefresh: () -> Unit
) {
    if (error != null && grouped.isEmpty()) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            grouped.forEach { (category, items) ->
                CategorySection(
                    category = category,
                    settings = items,
                    repository = repository,
                    pendingKeys = pendingKeys,
                    onPendingChange = onPendingChange,
                    onSettingChanged = onSettingChanged
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// 分类区块（用于"全部"视图）
@Composable
private fun CategorySection(
    category: String,
    settings: List<C3SettingMeta>,
    repository: SettingsRepository,
    pendingKeys: Set<String>,
    onPendingChange: (String, Boolean) -> Unit,
    onSettingChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val icon = categoryIcons[category] ?: Icons.Filled.Settings
    val color = categoryColors[category] ?: Slate500
    
    Column {
        // 分类标题
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                category,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Slate900
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${settings.size}项",
                fontSize = 12.sp,
                color = Slate500
            )
        }
        
        // 设置列表
        val boolSettings = settings.filter { it.type == "bool" }
        val otherSettings = settings.filter { it.type != "bool" }
        
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
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        
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


// 模型选择视图
@Composable
private fun ModelSelectionView(sshManager: SshManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var currentModel by remember { mutableStateOf<String?>(null) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isSwitching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    
    fun loadCurrentModel() {
        scope.launch {
            isLoading = true
            error = null
            // 使用 Params 类读取，如果不存在则使用默认值
            sshManager.executeCommand("cd /data/openpilot && python3 -c \"from common.params import Params; print(Params().get('Model', encoding='utf-8') or 'supercombo.onnx')\"").fold(
                onSuccess = { output -> 
                    currentModel = output.trim()
                },
                onFailure = { e -> 
                    error = "读取失败: ${e.message}"
                    currentModel = null
                }
            )
            isLoading = false
        }
    }
    
    fun loadAvailableModels() {
        scope.launch {
            sshManager.executeCommand("ls /data/models 2>/dev/null || echo 'supercombo.onnx'").fold(
                onSuccess = { output ->
                    availableModels = output.trim().split("\n")
                        .filter { it.isNotBlank() && (it.endsWith(".onnx") || it.endsWith(".dlc")) }
                        .sorted()
                },
                onFailure = { e ->
                    availableModels = listOf("supercombo.onnx")
                }
            )
        }
    }
    
    fun switchModel(model: String) {
        scope.launch {
            isSwitching = true
            sshManager.executeCommand("cd /data/openpilot && python3 -c \"from common.params import Params; Params().put('Model', '$model')\"").fold(
                onSuccess = {
                    currentModel = model
                    Toast.makeText(context, "模型已切换: $model\n重启 openpilot 后生效", Toast.LENGTH_LONG).show()
                },
                onFailure = { e ->
                    Toast.makeText(context, "切换失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
            isSwitching = false
        }
    }
    
    LaunchedEffect(Unit) {
        loadCurrentModel()
        loadAvailableModels()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 当前模型卡片
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)), // Blue50
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF0284C7), // Sky600
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "当前模型",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Spacer(Modifier.weight(1f))
                    if (isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFF0284C7)
                        )
                    } else {
                        IconButton(onClick = { loadCurrentModel() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Refresh, "刷新", tint = Color(0xFF0284C7))
                        }
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                if (currentModel != null) {
                    Text(
                        currentModel!!,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0284C7)
                    )
                } else {
                    Text(
                        error ?: "未知",
                        fontSize = 14.sp,
                        color = Slate500
                    )
                }
            }
        }
        
        // 可用模型列表
        Text(
            "可用模型",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Slate900
        )
        
        if (availableModels.isEmpty()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Slate50)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = Slate400,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "未找到可用模型",
                            fontSize = 14.sp,
                            color = Slate500
                        )
                    }
                }
            }
        } else {
            availableModels.forEach { model ->
                val isCurrent = model == currentModel
                val isPending = isSwitching && model != currentModel
                
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) Color(0xFFF0FDF4) else Color.White // Green50
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrent) 2.dp else 1.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCurrent && !isSwitching) {
                            switchModel(model)
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            if (isCurrent) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isCurrent) Green500 else Slate400,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            model,
                            fontSize = 15.sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) Green500 else Slate900,
                            modifier = Modifier.weight(1f)
                        )
                        if (isPending) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                                color = Teal500
                            )
                        } else if (isCurrent) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Green500.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    "使用中",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Green500,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
    }
}
