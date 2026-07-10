package com.sunnypilot.toolbox.data.repository

import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.LateralParams

/**
 * 通过 SSH 在 C3 上读写横向控制参数
 *
 * 涉及 C3 上的两个文件:
 *   - opendbc_repo/opendbc/car/byd/interface.py  → ki, kp, deadzone
 *   - opendbc_repo/opendbc/car/torque_data/override.toml → latAccelFactor, friction
 */
class LateralParamsRepository(
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "LateralParamsRepo"
        private const val BASE = "cd /data/openpilot &&"
    }

    /** 读取 C3 上当前所有的横向参数 */
    suspend fun readParams(): Result<LateralParams> {
        val script = """
            $BASE python3 -c "
import re, json

# 读取 interface.py 中的 ki / kp / deadzone
with open('opendbc_repo/opendbc/car/byd/interface.py') as f:
    iface = f.read()

ki_match = re.search(r'torque\.ki\s*=\s*([\d.]+)', iface)
kp_match = re.search(r'configure_torque_tune.*?\n.*?kp\s*=\s*([\d.]+)', iface, re.DOTALL)
deadzone_match = re.search(r'steeringAngleDeadzoneDeg\s*=\s*([\d.]+)', iface)

ki = float(ki_match.group(1)) if ki_match else 0.1
kp = 1.0  # kp 未在 interface.py 中显式覆写，取 configure_torque_tune 默认值
deadzone = float(deadzone_match.group(1)) if deadzone_match else 0.1

# 读取 override.toml 中的 LAT_ACCEL_FACTOR / FRICTION
lat_accel = 2.75
friction = 0.1
try:
    with open('opendbc_repo/opendbc/car/torque_data/override.toml') as f:
        toml = f.read()
    m = re.search(r'\"BYD_TANG_DM\"\s*=\s*\[([^\]]+)\]', toml)
    if m:
        vals = [float(x.strip()) for x in m.group(1).split(',')]
        lat_accel = vals[0]
        friction = vals[2] if len(vals) > 2 else 0.1
except:
    pass

print(json.dumps({'ki': ki, 'kp': kp, 'friction': friction,
                  'latAccelFactor': lat_accel, 'steeringAngleDeadzoneDeg': deadzone}))
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
            "ki" -> writeKiScript(value)
            "friction" -> writeOverrideTomlScript(2, value)  // 第3位(0-indexed=2)
            "latAccelFactor" -> writeOverrideTomlScript(0, value)  // 第1位
            "steeringAngleDeadzoneDeg" -> writeDeadzoneScript(value)
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
            mapOf(
                "ki" to (kotlin.math.abs(actual.ki - (expected["ki"] ?: actual.ki)) < 0.001f),
                "friction" to (kotlin.math.abs(actual.friction - (expected["friction"] ?: actual.friction)) < 0.001f),
                "latAccelFactor" to (kotlin.math.abs(actual.latAccelFactor - (expected["latAccelFactor"] ?: actual.latAccelFactor)) < 0.001f),
                "steeringAngleDeadzoneDeg" to (kotlin.math.abs(actual.steeringAngleDeadzoneDeg - (expected["steeringAngleDeadzoneDeg"] ?: actual.steeringAngleDeadzoneDeg)) < 0.001f)
            )
        }
    }

    // ─── 写入脚本生成 ───

    private fun writeKiScript(value: Float): String {
        return """
            $BASE python3 -c "
import re
with open('opendbc_repo/opendbc/car/byd/interface.py', 'r') as f:
    c = f.read()
c = re.sub(r'torque\.ki\s*=\s*[\d.]+', 'torque.ki = $value', c)
with open('opendbc_repo/opendbc/car/byd/interface.py', 'w') as f:
    f.write(c)
print('{\"success\": true}')
"
        """.trimIndent()
    }

    private fun writeDeadzoneScript(value: Float): String {
        return """
            $BASE python3 -c "
import re
with open('opendbc_repo/opendbc/car/byd/interface.py', 'r') as f:
    c = f.read()
c = re.sub(r'steeringAngleDeadzoneDeg\s*=\s*[\d.]+', 'steeringAngleDeadzoneDeg = $value', c)
with open('opendbc_repo/opendbc/car/byd/interface.py', 'w') as f:
    f.write(c)
print('{\"success\": true}')
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
with open('opendbc_repo/opendbc/car/torque_data/override.toml', 'r') as f:
    c = f.read()
m = re.search(r'\"BYD_TANG_DM\"\s*=\s*\[([^\]]+)\]', c)
if m:
    vals = [x.strip() for x in m.group(1).split(',')]
    vals[$index] = str($value)
    new = '\"BYD_TANG_DM\" = [' + ', '.join(vals) + ']'
    c = re.sub(r'\"BYD_TANG_DM\"\s*=\s*\[[^\]]+\]', new, c)
    with open('opendbc_repo/opendbc/car/torque_data/override.toml', 'w') as f:
        f.write(c)
    print('{\"success\": true}')
else:
    print('{\"success\": false, \"error\": \"BYD_TANG_DM not found\"}')
"
        """.trimIndent()
    }
}
