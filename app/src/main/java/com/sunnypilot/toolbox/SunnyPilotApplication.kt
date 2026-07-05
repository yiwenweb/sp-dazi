package com.sunnypilot.toolbox

import android.app.Application
import org.spongycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class SunnyPilotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 在部分车机/定制 Android 系统上，BouncyCastle 提供程序被精简，
        // 导致 JSch 使用 EC 算法时抛出 NoSuchAlgorithmException。
        // 注册 SpongyCastle 提供程序以补充 EC 等算法支持。
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
