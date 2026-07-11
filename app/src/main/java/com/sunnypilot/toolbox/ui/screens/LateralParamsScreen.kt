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
 * 从 C3 读取 interface.py / override.toml 的当前值，支持手动修改、恢复门总实测默认、
 * 保存后验证是否写入成功。所有默认值 = 门总(00000006)原始 rlog 实测基线。
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
    var editKp by remember { mutableStateOf("") }
    var editKf by remember { mutableStateOf("") }
    var editFriction by remember { mutableStateOf("") }
    var editLatAccel by remember { mutableStateOf("") }
    var editDeadzone by remember { mutableStateOf("") }
    var editSteerRatio by remember { mutableStateOf("") }
    var editSteerDelay by remember { mutableStateOf("") }
    var editSteerLimit by remember { mutableStateOf("") }

    // 验证状态: key -> true(成功) / false(失败) / null(未验证)
    var verifyResult by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }

    fun loadParams() {
        scope.launch {
            isLoading = true
            repository.readParams().fold(
                onSuccess = { p ->
                    params = p
                    editKi = p.ki.toString()
                    editKp = p.kp.toString()
                    editKf = p.kf.toString()
                    editFriction = p.friction.toString()
                    editLatAccel = p.latAccelFactor.toString()
                    editDeadzone = p.steeringAngleDeadzoneDeg.toString()
                    editSteerRatio = p.steerRatio.toString()
                    editSteerDelay = p.steerActuatorDelay.toString()
                    editSteerLimit = p.steerLimitTimer.toString()
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
                    repository.verify(mapOf(key to value)).fold(
                        onSuccess = { results ->
                            verifyResult = verifyResult + results
                            val ok = results[key] == true
                            Toast.makeText(
                                context,
                                if (ok) "$key 已保存并验证通过 ✅" else "$key 写入失败，请重试 ❌",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (ok) loadParams()
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

    fun setEdit(key: String, v: Float) {
        val s = v.toString()
        when (key) {
            "ki" -> editKi = s
            "kp" -> editKp = s
            "kf" -> editKf = s
            "friction" -> editFriction = s
            "latAccelFactor" -> editLatAccel = s
            "steeringAngleDeadzoneDeg" -> editDeadzone = s
            "steerRatio" -> editSteerRatio = s
            "steerActuatorDelay" -> editSteerDelay = s
            "steerLimitTimer" -> editSteerLimit = s
        }
    }

    LaunchedEffect(Unit) {
        try {
            if (sshManager.isConnected()) loadParams()
        } catch (e: Exception) {
            Toast.makeText(context, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
                Text("横向调参", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Slate900)
                Text(
                    "以门总实测为默认基线，逐个对齐/调整（interface.py / override.toml）",
                    fontSize = 13.sp,
                    color = Slate500
                )
            }
            OutlinedButton(onClick = { loadParams() }, enabled = !isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isLoading) "读取中..." else "刷新读取")
            }
        }

        if (!sshManager.isConnected()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Amber50)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Amber500)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("请先连接 C3 设备", color = Slate700, fontSize = 14.sp)
                }
            }
        } else if (params == null) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                if (isLoading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Teal500)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("正在读取 C3 参数...", fontSize = 14.sp, color = Slate500)
                    }
                } else {
                    OutlinedButton(onClick = { loadParams() }) { Text("点击读取参数") }
                }
            }
        } else {
            val p = params!!

            // ══════ 第一组：几何/时序（最可能影响偏右、转弯不居中）══════
            SectionHeader("几何 / 时序参数", "最可能影响「转弯不居中、往外切、偏右」")

            ParamCard(
                icon = Icons.Default.Straighten,
                key = "steerRatio",
                name = "steerRatio — 转向传动比",
                description = "方向盘转角 → 车轮转角 的比值。偏大 → OP 以为小角度就够 → 转向不足、过弯往外切。\n" +
                        "门总在线学习稳定锁定 19.0（17984 样本），原值 20.1478 偏大 6%。\n" +
                        "建议范围 18.5~20.5，先用门总 19.0。",
                defaultValue = LateralParams.DEFAULTS.steerRatio,
                currentValue = p.steerRatio,
                editValue = editSteerRatio,
                onEditChange = { editSteerRatio = it },
                saving = savingKey == "steerRatio",
                verified = verifyResult["steerRatio"],
                onSave = { v -> saveAndVerify("steerRatio", v) },
                onRestore = { setEdit("steerRatio", LateralParams.DEFAULTS.steerRatio) }
            )

            ParamCard(
                icon = Icons.Default.Timer,
                key = "steerActuatorDelay",
                name = "steerActuatorDelay — 转向执行延迟(s)",
                description = "OP 预估的转向响应滞后。偏小 → 无超前量 → 入弯迟钝；偏大 → 提前打方向可能过冲。\n" +
                        "门总实测 0.30（BYD EPS 约 0.3s 滞后）。建议范围 0.20~0.40。",
                defaultValue = LateralParams.DEFAULTS.steerActuatorDelay,
                currentValue = p.steerActuatorDelay,
                editValue = editSteerDelay,
                onEditChange = { editSteerDelay = it },
                saving = savingKey == "steerActuatorDelay",
                verified = verifyResult["steerActuatorDelay"],
                onSave = { v -> saveAndVerify("steerActuatorDelay", v) },
                onRestore = { setEdit("steerActuatorDelay", LateralParams.DEFAULTS.steerActuatorDelay) }
            )

            ParamCard(
                icon = Icons.Default.HourglassEmpty,
                key = "steerLimitTimer",
                name = "steerLimitTimer — 扭矩保持限时(s)",
                description = "达到最大扭矩后允许维持的时间。门总实测 0.5（原 0.4）。\n" +
                        "偏小转向力容易被提前收回，急弯可能撑不住。建议范围 0.4~0.8。",
                defaultValue = LateralParams.DEFAULTS.steerLimitTimer,
                currentValue = p.steerLimitTimer,
                editValue = editSteerLimit,
                onEditChange = { editSteerLimit = it },
                saving = savingKey == "steerLimitTimer",
                verified = verifyResult["steerLimitTimer"],
                onSave = { v -> saveAndVerify("steerLimitTimer", v) },
                onRestore = { setEdit("steerLimitTimer", LateralParams.DEFAULTS.steerLimitTimer) }
            )

            // ══════ 第二组：Torque 增益（纠偏力度/快慢）══════
            SectionHeader("Torque 增益", "控制纠偏的力度与快慢")

            ParamCard(
                icon = Icons.Default.TrendingUp,
                key = "ki",
                name = "ki — 积分增益",
                description = "消除稳态偏差的关键。偏差持续时每帧叠加修正，值越大纠偏越快。\n" +
                        "调大：压线改善，过大高速振荡；调小：偏差消除慢。\n" +
                        "门总实测 0.10，建议范围 0.10~0.20。",
                defaultValue = LateralParams.DEFAULTS.ki,
                currentValue = p.ki,
                editValue = editKi,
                onEditChange = { editKi = it },
                saving = savingKey == "ki",
                verified = verifyResult["ki"],
                onSave = { v -> saveAndVerify("ki", v) },
                onRestore = { setEdit("ki", LateralParams.DEFAULTS.ki) }
            )

            ParamCard(
                icon = Icons.Default.ShowChart,
                key = "kp",
                name = "kp — 比例增益",
                description = "对当前误差的即时响应强度。调大响应更硬更快，过大易左右摆动/抖动。\n" +
                        "门总实测 1.0（configure_torque_tune 默认，未覆写）。\n" +
                        "保存后会在 interface.py 追加显式覆写行。建议范围 0.7~1.5。",
                defaultValue = LateralParams.DEFAULTS.kp,
                currentValue = p.kp,
                editValue = editKp,
                onEditChange = { editKp = it },
                saving = savingKey == "kp",
                verified = verifyResult["kp"],
                onSave = { v -> saveAndVerify("kp", v) },
                onRestore = { setEdit("kp", LateralParams.DEFAULTS.kp) }
            )

            ParamCard(
                icon = Icons.Default.Functions,
                key = "kf",
                name = "kf — 前馈增益",
                description = "根据模型期望曲率直接前馈出力（不等误差产生）。调大入弯更跟手。\n" +
                        "门总实测 1.0。保存后会在 interface.py 追加显式覆写行。建议范围 0.7~1.3。",
                defaultValue = LateralParams.DEFAULTS.kf,
                currentValue = p.kf,
                editValue = editKf,
                onEditChange = { editKf = it },
                saving = savingKey == "kf",
                verified = verifyResult["kf"],
                onSave = { v -> saveAndVerify("kf", v) },
                onRestore = { setEdit("kf", LateralParams.DEFAULTS.kf) }
            )

            ParamCard(
                icon = Icons.Default.Build,
                key = "friction",
                name = "friction — 静摩擦补偿",
                description = "叠加固定方向的力突破方向盘静摩擦。\n" +
                        "调大：小偏差也能推动、居中更积极；调小：小偏差被吃掉「差一点不转」。\n" +
                        "门总实测 0.10（门总未开 torque 在线学习，此静态值即生效值）。",
                defaultValue = LateralParams.DEFAULTS.friction,
                currentValue = p.friction,
                editValue = editFriction,
                onEditChange = { editFriction = it },
                saving = savingKey == "friction",
                verified = verifyResult["friction"],
                onSave = { v -> saveAndVerify("friction", v) },
                onRestore = { setEdit("friction", LateralParams.DEFAULTS.friction) }
            )

            ParamCard(
                icon = Icons.Default.Speed,
                key = "latAccelFactor",
                name = "latAccelFactor — 扭矩缩放因子",
                description = "横向加速需求(m/s²) → 电机扭矩 换算系数。\n" +
                        "调大：输出扭矩变小、转向整体变弱；调小：转向变强（高速可能过猛）。\n" +
                        "门总实测 2.75，建议范围 2.2~2.8。",
                defaultValue = LateralParams.DEFAULTS.latAccelFactor,
                currentValue = p.latAccelFactor,
                editValue = editLatAccel,
                onEditChange = { editLatAccel = it },
                saving = savingKey == "latAccelFactor",
                verified = verifyResult["latAccelFactor"],
                onSave = { v -> saveAndVerify("latAccelFactor", v) },
                onRestore = { setEdit("latAccelFactor", LateralParams.DEFAULTS.latAccelFactor) }
            )

            ParamCard(
                icon = Icons.Default.MyLocation,
                key = "steeringAngleDeadzoneDeg",
                name = "deadzone — 角度死区(°)",
                description = "低于此角度的偏差不处理，防止中心附近抖动。\n" +
                        "门总实测 0.0（一直修正，原写 0.1 有误）。增大可抑制低速小幅摆动但居中变钝。",
                defaultValue = LateralParams.DEFAULTS.steeringAngleDeadzoneDeg,
                currentValue = p.steeringAngleDeadzoneDeg,
                editValue = editDeadzone,
                onEditChange = { editDeadzone = it },
                saving = savingKey == "steeringAngleDeadzoneDeg",
                verified = verifyResult["steeringAngleDeadzoneDeg"],
                onSave = { v -> saveAndVerify("steeringAngleDeadzoneDeg", v) },
                onRestore = { setEdit("steeringAngleDeadzoneDeg", LateralParams.DEFAULTS.steeringAngleDeadzoneDeg) }
            )

            // ── 全部恢复门总默认 ──
            OutlinedButton(
                onClick = {
                    val d = LateralParams.DEFAULTS
                    editKi = d.ki.toString(); editKp = d.kp.toString(); editKf = d.kf.toString()
                    editFriction = d.friction.toString(); editLatAccel = d.latAccelFactor.toString()
                    editDeadzone = d.steeringAngleDeadzoneDeg.toString()
                    editSteerRatio = d.steerRatio.toString(); editSteerDelay = d.steerActuatorDelay.toString()
                    editSteerLimit = d.steerLimitTimer.toString()
                    verifyResult = emptyMap()
                    Toast.makeText(context, "已填入门总默认值（需逐个保存到 C3）", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate600)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("全部填入门总默认值（需逐个保存）")
            }

            // ── 注意事项 ──
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate50)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("调参建议", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Slate700)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "1. 每次只改一个参数，路测确认效果后再改下一个\n" +
                                "2. 建议顺序：steerRatio → steerActuatorDelay → ki → friction → latAccelFactor\n" +
                                "3. 改后需重启 openpilot / C3 才生效（.py/.toml 无需编译）\n" +
                                "4. 门总未开 torque 在线学习，friction/latAccelFactor 静态值即生效值，不会被覆盖\n" +
                                "5. 「偏右基线」属模型/摄像头层，这些增益调不动，需 path offset（另行处理）",
                        fontSize = 12.sp,
                        color = Slate500,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate900)
        Text(subtitle, fontSize = 12.sp, color = Slate500)
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
        kotlin.math.abs(editValue.toFloat() - currentValue) > 0.0001f
    } catch (_: NumberFormatException) {
        false
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Teal500, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Slate900, modifier = Modifier.weight(1f))
                when (verified) {
                    true -> Icon(Icons.Default.CheckCircle, contentDescription = "已验证", tint = Green500, modifier = Modifier.size(20.dp))
                    false -> Icon(Icons.Default.Error, contentDescription = "验证失败", tint = Red500, modifier = Modifier.size(20.dp))
                    null -> {}
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("C3 当前值: ", fontSize = 13.sp, color = Slate500)
                Text(currentValue.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Teal500)
                Spacer(modifier = Modifier.width(12.dp))
                Text("门总默认: ", fontSize = 12.sp, color = Slate400)
                Text(defaultValue.toString(), fontSize = 13.sp, color = Slate400)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(shape = RoundedCornerShape(8.dp), color = Slate50, modifier = Modifier.fillMaxWidth()) {
                Text(description, fontSize = 12.sp, color = Slate600, lineHeight = 17.sp, modifier = Modifier.padding(12.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                OutlinedButton(onClick = onRestore, enabled = !saving, modifier = Modifier.height(56.dp)) {
                    Text("默认", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        try { onSave(editValue.toFloat()) } catch (_: NumberFormatException) {}
                    },
                    enabled = !saving && changed,
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (changed) Teal500 else Slate300)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
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
