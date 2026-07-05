package com.sunnypilot.toolbox

import android.app.Application
import android.util.Log
import java.security.Security

class SunnyPilotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initSecurityProvider()
    }

    /**
     * 在部分车机/定制 Android 系统上，BouncyCastle 提供程序被精简，
     * 导致 JSch 使用 EC 算法时抛出 NoSuchAlgorithmException。
     * 尝试注册 SpongyCastle 提供程序以补充 EC 等算法支持。
     *
     * 注意：整个初始化过程用 try-catch 包裹，任何异常都不能让 App 崩溃，
     * 即使注册失败，JSch 仍可通过算法偏好配置回退到 RSA。
     */
    private fun initSecurityProvider() {
        try {
            val providerName = "BC"
            if (Security.getProvider(providerName) == null) {
                // 反射加载 SpongyCastle，避免在缺失设备上 ClassNotFoundException
                val clazz = Class.forName("org.spongycastle.jce.provider.BouncyCastleProvider")
                val provider = clazz.getDeclaredConstructor().newInstance() as java.security.Provider
                Security.addProvider(provider)
                Log.i("SunnyPilotApp", "SpongyCastle provider registered")
            }
        } catch (e: Throwable) {
            // ClassNotFoundException / NoClassDefFoundError 等均吞掉
            Log.w("SunnyPilotApp", "SpongyCastle unavailable, JSch will use RSA fallback", e)
        }
    }
}
