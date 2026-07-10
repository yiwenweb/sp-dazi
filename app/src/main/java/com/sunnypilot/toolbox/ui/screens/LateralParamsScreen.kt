package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.LateralParamsRepository
import com.sunnypilot.toolbox.model.LateralParams
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 横向控制参数快捷修改页面
 *
 * 从 C3 读取 interface.py / override.toml 的当前值，
 * 支持手动修改、恢复默认、保存后验证是否写入成功。
 */
@Composable
fun LateralParamsScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { LateralParamsRepository(sshManager) }

    var params by remember { mutableStateOf<LateralParams?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var savingKey by remember { mutableStateOf<String?>(null) }

    // 每个参数的当前编辑值
    var editKi by remember { mutableStateOf("") }
    var editFriction by remember { mutableStateOf("") }
    var editLatAccel by remember { mutableStateOf("") }
    var editDeadzone by remember { mutableStateOf("") }

    // 验证状态: key -> true(成功) / false(失败) / null(未验证)
    var verifyResult by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }

    fun loadParams() {
        scope.launch {
            isLoading = true
            repository.readParams().fold(
                onSuccess = { p ->
                    params = p
                    editKi = p.ki.toString()
                    editFriction = p.friction.toString()
                    editLatAccel = p.latAccelFactor.toString()
                    editDeadzone = p.steeringAngleDeadzoneDeg.toString()
                    verifyResult = emptyMap()
                },
                onFailure = { e ->
                    Toast.makeText(context, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
            isLoading = false
        }
    }

    fun saveAndVerify(key: String, value: Float) {
        scope.launch {
            savingKey = key
            repository.writeParam(key, value).fold(
                onSuccess = {
                    // 写入后立即验证
                    repository.verify(mapOf(key to value)).fold(
                        onSuccess = { results ->
                            verifyResult = verifyResult + results
                            val ok = results[key] == true
                            Toast.makeText(
                                context,
                                if (ok) "$key 已保存并验证通过 ✅" else "$key 写入失败，请重试 ❌",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (ok) loadParams() // 刷新显示
                        },
                        onFailure = { e ->
                            verifyResult = verifyResult + (key to false)
                            Toast.makeText(context, "验证失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                onFailure = { e ->
                    verifyResult = verifyResult + (key to false)
                    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
            savingKey = null
        }
    }

    fun restoreDefault(key: String, default: Float) {
        when (key) {
            "ki" -> editKi = default.toString()
            "friction" -> editFriction = default.toString()
            "latAccelFactor" -> editLatAccel = default.toString()
            "steeringAngleDeadzoneDeg" -> editDeadzone = default.toString()
        }
    }

    // 首次加载
    LaunchedEffect(Unit) {
        if (sshManager.isConnected()) loadParams()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── 标题栏 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    "横向调参",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                Text(
                    "快捷修改 C3 上的横向控制参数（interface.py / override.toml）",
                    fontSize = 13.sp,
                    color = Slate500
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { loadParams() }, enabled = !isLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isLoading) "读取中..." else "刷新读取")
                }
            }
        }

        // 未连接提示
        if (!sshManager.isConnected()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Amber50)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Amber500)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("请先连接 C3 设备", color = Slate700, fontSize = 14.sp)
                }
            }
            return@Column
        }

        if (params == null && !isLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal500)
            }
            return@Column
        }

        val p = params ?: return@Column

        // ── 参数卡片 ──

        ParamCard(
            icon = Icons.Default.TrendingUp,
            key = "ki",
            name = "ki — 积分增益",
            description = "消除稳态偏差的关键参数。偏差持续时每秒叠加扭矩修正，值越大纠偏越快。\n" +
                    "调大：压线问题改善，高速过大可能振荡\n" +
                    "调小：偏差消除慢，容易压线\n" +
                    "当前基线 0.10（门总 0.98 实测），建议范围 0.10~0.20",
            defaultValue = LateralParams.DEFAULTS.ki,
            currentValue = p.ki,
            editValue = editKi,
            onEditChange = { editKi = it },
            saving = savingKey == "ki",
            verified = verifyResult["ki"],
            onSave = { v -> saveAndVerify("ki", v) },
            onRestore = { restoreDefault("ki", LateralParams.DEFAULTS.ki) }
        )

        ParamCard(
            icon = Icons.Default.SettingsBackupRestore,
            key = "friction",
            name = "friction — 静摩擦补偿",
            description = "额外叠加一个固定方向的力来突破方向盘静摩擦。\n" +
                    "调大：小偏差也能推动方向盘，居中更积极\n" +
                    "调小：小偏差被静摩擦吃掉 — \"明明再转一点就够但它不转\"\n" +
                    "同类车型参考：HONDA 0.15~0.23, CHEVROLET 0.175",
            defaultValue = LateralParams.DEFAULTS.friction,
            currentValue = p.friction,
            editValue = editFriction,
            onEditChange = { editFriction = it },
            saving = savingKey == "friction",
            verified = verifyResult["friction"],
            onSave = { v -> saveAndVerify("friction", v) },
            onRestore = { restoreDefault("friction", LateralParams.DEFAULTS.friction) }
        )

        ParamCard(
            icon = Icons.Default.Speed,
            key = "latAccelFactor",
            name = "latAccelFactor — 扭矩缩放因子",
            description = "横加需求(m/s²) → 电机扭矩 的换算系数。\n" +
                    "调大：分母变大，输出扭矩变小，转向整体变弱\n" +
                    "调小：输出扭矩变大，转向整体变强（高速可能过猛）\n" +
                    "门总 0.98 确认值 2.75，建议范围 2.2~2.8",
            defaultValue = LateralParams.DEFAULTS.latAccelFactor,
            currentValue = p.latAccelFactor,
            editValue = editLatAccel,
            onEditChange = { editLatAccel = it },
            saving = savingKey == "latAccelFactor",
            verified = verifyResult["latAccelFactor"],
            onSave = { v -> saveAndVerify("latAccelFactor", v) },
            onRestore = { restoreDefault("latAccelFactor", LateralParams.DEFAULTS.latAccelFactor) }
        )

        ParamCard(
            icon = Icons.Default.GpsFixed,
            key = "steeringAngleDeadzoneDeg",
            name = "deadzone — 角度死区",
            description = "低于此角度（度）的偏差不处理，防止中心附近抖动。\n" +
                    "门总当前值 0.1°，增大可抑制低速小幅度摆动。",
            defaultValue = LateralParams.DEFAULTS.steeringAngleDeadzoneDeg,
            currentValue = p.steeringAngleDeadzoneDeg,
            editValue = editDeadzone,
            onEditChange = { editDeadzone = it },
            saving = savingKey == "steeringAngleDeadzoneDeg",
            verified = verifyResult["steeringAngleDeadzoneDeg"],
            onSave = { v -> saveAndVerify("steeringAngleDeadzoneDeg", v) },
            onRestore = { restoreDefault("steeringAngleDeadzoneDeg", LateralParams.DEFAULTS.steeringAngleDeadzoneDeg) }
        )

        // ── 恢复默认按钮 ──
        OutlinedButton(
            onClick = {
                editKi = LateralParams.DEFAULTS.ki.toString()
                editFriction = LateralParams.DEFAULTS.friction.toString()
                editLatAccel = LateralParams.DEFAULTS.latAccelFactor.toString()
                editDeadzone = LateralParams.DEFAULTS.steeringAngleDeadzoneDeg.toString()
                verifyResult = emptyMap()
                Toast.makeText(context, "已恢复为默认值（未保存到 C3）", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate600)
        ) {
            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("全部恢复默认值（需逐个保存）")
        }

        // ── 注意事项 ──
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate50)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⚠️ 调参建议", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Slate700)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "1. 每次只改一个参数，路测 3-5 天确认效果后再改下一个\n" +
                            "2. 建议优先级：ki → friction → latAccelFactor\n" +
                            "3. 重启 C3 后参数生效\n" +
                            "4. 修改前可用「刷新读取」确认当前实际值",
                    fontSize = 12.sp,
                    color = Slate500,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** 单个参数编辑卡片 */
@Composable
private fun ParamCard(
    icon: ImageVector,
    key: String,
    name: String,
    description: String,
    defaultValue: Float,
    currentValue: Float,
    editValue: String,
    onEditChange: (String) -> Unit,
    saving: Boolean,
    verified: Boolean?,
    onSave: (Float) -> Unit,
    onRestore: () -> Unit
) {
    val changed = try {
        kotlin.math.abs(editValue.toFloat() - currentValue) > 0.001f
    } catch (_: NumberFormatException) {
        false
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Teal500, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Slate900, modifier = Modifier.weight(1f))

                // 验证状态图标
                when (verified) {
                    true -> Icon(Icons.Default.CheckCircle, contentDescription = "已验证", tint = Green500, modifier = Modifier.size(20.dp))
                    false -> Icon(Icons.Default.Error, contentDescription = "验证失败", tint = Red500, modifier = Modifier.size(20.dp))
                    null -> {}
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 当前值
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("C3 当前值: ", fontSize = 13.sp, color = Slate500)
                Text(
                    currentValue.toString(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Teal500
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("默认值: ", fontSize = 12.sp, color = Slate400)
                Text(defaultValue.toString(), fontSize = 13.sp, color = Slate400)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 描述
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Slate50,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    description,
                    fontSize = 12.sp,
                    color = Slate600,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 编辑行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { onEditChange(it.filter { c -> c.isDigit() || c == '.' }) },
                    label = { Text("新值") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Teal500,
                        cursorColor = Teal500
                    )
                )

                // 默认按钮
                OutlinedButton(
                    onClick = onRestore,
                    enabled = !saving,
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("默认", fontSize = 12.sp)
                }

                // 保存按钮
                Button(
                    onClick = {
                        try {
                            onSave(editValue.toFloat())
                        } catch (_: NumberFormatException) {
                            // ignore
                        }
                    },
                    enabled = !saving && changed,
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (changed) Teal500 else Slate300
                    )
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("保存", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
