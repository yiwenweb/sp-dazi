package com.sunnypilot.toolbox.data.repository

import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.LateralParams

/**
 * 通过 SSH 在 C3 上读写横向控制参数
 *
 * 涉及 C3 上的两个文件:
 *   - opendbc_repo/opendbc/car/byd/interface.py
 *       → ki, deadzone, steerRatio, steerActuatorDelay, steerLimitTimer
 *       → kp/kf 由 configure_torque_tune 提供, interface.py 未覆写 (门总实测 kp=1.0/kf=1.0)
 *   - opendbc_repo/opendbc/car/torque_data/override.toml
 *       → latAccelFactor, friction
 *
 * 说明: kp/kf 门总实测均为 1.0 且未在 interface.py 显式覆写。
 *       若用户要调 kp/kf, 由本仓库在 configure_torque_tune 调用后追加覆写行。
 */
class LateralParamsRepository(
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "LateralParamsRepo"
        private const val BASE = "cd /data/openpilot &&"
        private const val IFACE = "opendbc_repo/opendbc/car/byd/interface.py"
        private const val OVERRIDE = "opendbc_repo/opendbc/car/torque_data/override.toml"
    }

    /** 读取 C3 上当前所有的横向参数 */
    suspend fun readParams(): Result<LateralParams> {
        val script = """
            $BASE python3 -c "
import re, json

with open('$IFACE') as f:
    iface = f.read()

def find(pat, default, txt=iface):
    m = re.search(pat, txt)
    return float(m.group(1)) if m else default

# interface.py 中显式覆写的值
ki = find(r'torque\.ki\s*=\s*([\d.]+)', 0.1)
kp = find(r'torque\.kp\s*=\s*([\d.]+)', 1.0)
kf = find(r'torque\.kf\s*=\s*([\d.]+)', 1.0)
deadzone = find(r'steeringAngleDeadzoneDeg\s*=\s*([\d.]+)', 0.0)
steer_ratio = find(r'ret\.steerRatio\s*=\s*([\d.]+)', 19.0)
steer_delay = find(r'ret\.steerActuatorDelay\s*=\s*([\d.]+)', 0.3)
steer_limit = find(r'ret\.steerLimitTimer\s*=\s*([\d.]+)', 0.5)

# override.toml 中的 LAT_ACCEL_FACTOR / FRICTION
lat_accel = 2.75
friction = 0.1
try:
    with open('$OVERRIDE') as f:
        toml = f.read()
    m = re.search(r'\"BYD_TANG_DM\"\s*=\s*\[([^\]]+)\]', toml)
    if m:
        vals = [float(x.strip()) for x in m.group(1).split(',')]
        lat_accel = vals[0]
        friction = vals[2] if len(vals) > 2 else 0.1
except:
    pass

print(json.dumps({'ki': ki, 'kp': kp, 'kf': kf, 'friction': friction,
                  'latAccelFactor': lat_accel, 'steeringAngleDeadzoneDeg': deadzone,
                  'steerRatio': steer_ratio, 'steerActuatorDelay': steer_delay,
                  'steerLimitTimer': steer_limit}))
"
        """.trimIndent()

        return sshManager.executeCommand(script).mapCatching { output ->
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            json.decodeFromString(LateralParams.serializer(), output.trim())
        }
    }

    /** 写入单个参数到 C3 */
    suspend fun writeParam(key: String, value: Float): Result<Boolean> {
        val script = when (key) {
            "ki" -> writeIfaceAssignScript("""torque\.ki""", "torque.ki", value)
            "kp" -> writeTorqueTuneOverrideScript("kp", value)
            "kf" -> writeTorqueTuneOverrideScript("kf", value)
            "steeringAngleDeadzoneDeg" -> writeIfaceAssignScript(
                """torque\.steeringAngleDeadzoneDeg""", "torque.steeringAngleDeadzoneDeg", value
            )
            "steerRatio" -> writeIfaceRetScript("steerRatio", value)
            "steerActuatorDelay" -> writeIfaceRetScript("steerActuatorDelay", value)
            "steerLimitTimer" -> writeIfaceRetScript("steerLimitTimer", value)
            "friction" -> writeOverrideTomlScript(2, value)   // 第3位(0-indexed=2)
            "latAccelFactor" -> writeOverrideTomlScript(0, value)  // 第1位
            else -> return Result.failure(IllegalArgumentException("不支持的参数: $key"))
        }

        return sshManager.executeCommand(script).mapCatching { output ->
            val success = output.trim().contains("\"success\": true")
            if (!success) {
                Log.w(TAG, "写入 $key=$value 返回: $output")
            }
            success
        }
    }

    /** 验证写入是否生效（重新读取并与期望值比较） */
    suspend fun verify(expected: Map<String, Float>): Result<Map<String, Boolean>> {
        return readParams().mapCatching { actual ->
            fun eq(a: Float, b: Float?) = b == null || kotlin.math.abs(a - b) < 0.001f
            mapOf(
                "ki" to eq(actual.ki, expected["ki"]),
                "kp" to eq(actual.kp, expected["kp"]),
                "kf" to eq(actual.kf, expected["kf"]),
                "friction" to eq(actual.friction, expected["friction"]),
                "latAccelFactor" to eq(actual.latAccelFactor, expected["latAccelFactor"]),
                "steeringAngleDeadzoneDeg" to eq(actual.steeringAngleDeadzoneDeg, expected["steeringAngleDeadzoneDeg"]),
                "steerRatio" to eq(actual.steerRatio, expected["steerRatio"]),
                "steerActuatorDelay" to eq(actual.steerActuatorDelay, expected["steerActuatorDelay"]),
                "steerLimitTimer" to eq(actual.steerLimitTimer, expected["steerLimitTimer"])
            )
        }
    }

    // ─── 写入脚本生成 ───

    /** 修改 interface.py 中 `xxx.attr = 数值` 形式 (如 torque.ki = 0.1) */
    private fun writeIfaceAssignScript(pattern: String, replaceName: String, value: Float): String {
        return """
            $BASE python3 -c "
import re
with open('$IFACE', 'r') as f:
    c = f.read()
new, n = re.subn(r'$pattern\s*=\s*[\d.]+', '$replaceName = $value', c)
if n > 0:
    with open('$IFACE', 'w') as f:
        f.write(new)
    print('{\"success\": true}')
else:
    print('{\"success\": false, \"error\": \"pattern not found\"}')
"
        """.trimIndent()
    }

    /** 修改 interface.py 中 `ret.attr = 数值` 形式 (如 ret.steerRatio = 19.0) */
    private fun writeIfaceRetScript(attr: String, value: Float): String {
        return """
            $BASE python3 -c "
import re
with open('$IFACE', 'r') as f:
    c = f.read()
new, n = re.subn(r'ret\.$attr\s*=\s*[\d.]+', 'ret.$attr = $value', c)
if n > 0:
    with open('$IFACE', 'w') as f:
        f.write(new)
    print('{\"success\": true}')
else:
    print('{\"success\": false, \"error\": \"$attr not found\"}')
"
        """.trimIndent()
    }

    /**
     * 覆写 torque.kp / torque.kf。
     * 因为 configure_torque_tune 默认设 1.0 且 interface.py 未显式写,
     * 这里保证在 configure_torque_tune(...) 调用行之后存在一行覆写:
     *   ret.lateralTuning.torque.kp = <value>
     * 若已存在则直接替换数值; 否则在 configure_torque_tune 行后插入。
     */
    private fun writeTorqueTuneOverrideScript(attr: String, value: Float): String {
        return """
            $BASE python3 -c "
import re
with open('$IFACE', 'r') as f:
    c = f.read()
assign = 'ret.lateralTuning.torque.$attr'
pat = re.escape(assign) + r'\s*=\s*[\d.]+'
if re.search(pat, c):
    c = re.sub(pat, assign + ' = $value', c)
    ok = True
else:
    m = re.search(r'(\n(\s*)CarInterfaceBase\.configure_torque_tune\([^\n]*\)\n)', c)
    if m:
        indent = m.group(2)
        ins = m.group(1) + indent + assign + ' = $value\n'
        c = c[:m.start()] + ins + c[m.end():]
        ok = True
    else:
        ok = False
if ok:
    with open('$IFACE', 'w') as f:
        f.write(c)
    print('{\"success\": true}')
else:
    print('{\"success\": false, \"error\": \"configure_torque_tune anchor not found\"}')
"
        """.trimIndent()
    }

    /**
     * 修改 override.toml 中 BYD_TANG_DM 数组的第 [index] 位
     * index: 0=latAccelFactor, 1=maxLatAccel, 2=friction
     */
    private fun writeOverrideTomlScript(index: Int, value: Float): String {
        return """
            $BASE python3 -c "
import re
with open('$OVERRIDE', 'r') as f:
    c = f.read()
m = re.search(r'\"BYD_TANG_DM\"\s*=\s*\[([^\]]+)\]', c)
if m:
    vals = [x.strip() for x in m.group(1).split(',')]
    vals[$index] = str($value)
    new = '\"BYD_TANG_DM\" = [' + ', '.join(vals) + ']'
    c = re.sub(r'\"BYD_TANG_DM\"\s*=\s*\[[^\]]+\]', new, c)
    with open('$OVERRIDE', 'w') as f:
        f.write(c)
    print('{\"success\": true}')
else:
    print('{\"success\": false, \"error\": \"BYD_TANG_DM not found\"}')
"
        """.trimIndent()
    }
}
