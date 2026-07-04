# SunnyPilot Toolbox - Android 车机控制 App

专为 sunnypilot 0.10.1 + Comma C3 设备开发的 Android 控制工具，适配比亚迪唐 2018 车机（Android 7.0+，横屏）。

## 技术栈

- **Kotlin** + **Jetpack Compose**
- **minSdk 24**（Android 7.0）
- **JSch**（SSH/SFTP 连接 C3）
- **强制横屏** `landscape`

## 项目结构

```
sunnypilot-android/
├── app/
│   ├── src/main/
│   │   ├── java/com/sunnypilot/toolbox/
│   │   │   ├── MainActivity.kt              # 入口
│   │   │   ├── data/
│   │   │   │   └── SshManager.kt            # SSH/SFTP 管理
│   │   │   ├── model/
│   │   │   │   └── DeviceStatus.kt          # 设备状态数据类
│   │   │   ├── ui/
│   │   │   │   ├── components/
│   │   │   │   │   ├── SideNavBar.kt        # 左侧导航栏
│   │   │   │   │   └── TopBar.kt            # 顶部状态栏
│   │   │   │   ├── screens/
│   │   │   │   │   ├── ConnectionScreen.kt  # SSH 连接页面
│   │   │   │   │   └── DeviceDashboardScreen.kt # 设备主控台
│   │   │   │   └── theme/
│   │   │   │       ├── Color.kt
│   │   │   │       ├── Theme.kt
│   │   │   │       └── Type.kt
│   │   └── res/                             # 布局/图标资源
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── gradle/wrapper/gradle-wrapper.properties
```

## 已实现功能

### v1.0.0 基础版

- [x] 横屏车机布局（左侧导航 + 顶部状态栏 + 主内容区）
- [x] SSH 密码/私钥连接 C3
- [x] 设备主控台（温度、内存、服务状态）
- [x] 左侧 18 项导航菜单（与模板一致）

## 如何构建 APK

### 方法 1：Android Studio（推荐）

1. 安装 Android Studio
2. 打开项目：`File → Open → c:\Users\yiwen\Desktop\1\sunnypilot-android`
3. 等待 Gradle Sync 完成
4. 连接手机或创建模拟器
5. 点击 `Run` 按钮

### 方法 2：命令行

需要已安装 Android SDK 和 JDK 17+：

```bash
cd c:\Users\yiwen\Desktop\1\sunnypilot-android
./gradlew assembleDebug
```

APK 输出位置：
```
app/build/outputs/apk/debug/app-debug.apk
```

## 安装到比亚迪唐车机

1. 开启车机 USB 调试：`设置 → 关于 → 连续点击版本号 → 开发者选项 → USB 调试`
2. 用 USB 连接电脑
3. 执行：
   ```bash
   adb install app-debug.apk
   ```
4. 或者把 APK 放 U 盘，在车机文件管理器里安装

## 连接 C3

### 默认连接信息

- **Host**：`192.168.43.1`（C3 WiFi 热点）或 `192.168.144.1`（USB 网络）
- **Port**：`22`
- **Username**：`comma`
- **认证**：密码或 GitHub 私钥

### C3 上需要做的准备

1. C3 连接手机热点或车机 WiFi
2. 在 C3 上设置 GitHub 用户名以导入 SSH 公钥
3. 确保 SSHD 服务运行

## 后续可扩展功能

按优先级：

1. SSH 终端（xterm.js / 自定义 TerminalView）
2. 文件管理器（SFTP 上传下载）
3. 参数管理（读写 `/data/params/d/`）
4. 行车视频预览（拉取 `/data/media/0/videos/`）
5. 系统备份与还原
6. 一键命令下发
7. 数据中台与智能计算

## 注意事项

- 首次连接需要在 AndroidManifest 中已声明 `INTERNET` 权限
- 私有密钥文件建议存在 App 私有目录，不要放公共存储
- 车机横屏模式下，所有页面按 1920×1080 设计
