# SunnyPilot Android Toolbox 功能总结

> 对应 C3（comma three）上的文件与数据路径
> 更新时间：2026-07-05

## 一、项目概述

SunnyPilot Android Toolbox 是一个 Android 平板/手机端应用，通过 SSH 连接到 Comma Three（C3）车机，实现对 openpilot/sunnypilot 系统的远程管理、监控、调试和数据查看。

**连接方式**
- 协议：SSH（端口 22）
- 默认用户：`comma`
- 认证方式：密码 或 私钥（PPK → PEM 自动转换）
- 默认私钥：内置 `app/src/main/assets/menmen.ppk`
- 自动连接：扫描局域网 22 端口设备，用 menmen.ppk 自动尝试登录

**C3 运行目录**
- 实际运行系统：`/data/openpilot/`
- 编译工厂：`/data/panda_build/`
- 参数存储：`/data/params/d/`
- 行车记录仪数据：`/data/media/0/realdata/`
- 日志目录：`/data/log/`
- 崩溃日志：`/data/community/crashes/error.log`

---

## 二、左侧导航功能清单

### 2.1 已完成并可用功能

| 功能 | Android 实现文件 | C3 对应文件/命令/路径 | 说明 |
|------|-----------------|---------------------|------|
| **连接中心** | `ConnectionScreen.kt` | C3 SSH 服务（22/tcp） | 手动输入 IP/密码/密钥连接，或一键自动扫描连接 |
| **设备管家** | `DeviceManagerScreen.kt` | `/proc/cpuinfo`, `/sys/class/thermal/thermal_zone0/temp`, `/sys/class/thermal/thermal_zone1/temp`, `/sys/class/power_supply/bms/temp`, `/proc/loadavg`, `free -m`, `df -h /data`, `/proc/sys/kernel/hostname`, `/data/params/d/HardwareSerial`, `/data/params/d/DongleId`, `ps -A` | 设备健康分、体检、清理缓存、导出日志 |
| **硬件管理** | `DeviceDashboardScreen.kt` | 同设备管家 + `Params().put_bool('OffroadMode', True)` / `remove('OffroadMode')`, `/data/community/crashes/error.log`, `sudo reboot`, `sudo poweroff` | 设备主控台、重启/关机、离线模式、错误日志 |
| **终端** | `TerminalScreen.kt` + `QuickCommandsPanel.kt` | C3 交互式 Shell（bash） | 实时 SSH Shell、快捷命令、本地 Web 服务同步 |
| **数据中台** | `DataCenterScreen.kt` + `DriveStatsRepository.kt` | 本地 Room 数据库（TODO：对接 C3 `/data/media/0/realdata/` qlog 解析） | 驾驶统计、安全评分、数据导入导出 |
| **记录仪预览** | `RecorderScreen.kt` + `RecorderRepository.kt` | `/data/media/0/realdata/<segment>/qcamera.ts`, `overlay.json`, `qlog.zst/bz2/qlog`, `/data/openpilot/c3_scripts/preprocess_recorder.py` | 分段列表、下载视频与叠加层、预处理 |
| **驾驶设置** | `SettingsScreen.kt` + `SettingsRepository.kt` | `/data/openpilot/c3_scripts/settings_bridge.py` 读写 `/data/params/d/` 下参数 | 读写 openpilot/sunnypilot 参数 |

### 2.2 未适配/未开发功能（已置后灰显）

- 视频预览
- 文件
- 智能计算
- 一键下发
- 分享中心
- 备份
- 恢复刷机
- 需求中心
- 信息中心
- 设置
- 关于

---

## 三、各功能详细对应关系

### 3.1 连接中心

**Android 文件**
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/ConnectionScreen.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/SshManager.kt`
- `app/src/main/java/com/sunnypilot/toolbox/network/AutoDiscovery.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/PpkToPemConverter.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/ConnectionConfigRepository.kt`
- `app/src/main/assets/menmen.ppk`

**C3 对应**
- SSH 服务：`sshd`（端口 22）
- 默认登录用户：`comma`
- 私钥授权：C3 `~comma/.ssh/authorized_keys` 中需包含 menmen.ppk 对应的公钥

**功能点**
1. 手动连接：输入 IP、端口、用户名、密码或私钥
2. 自动发现：扫描局域网 22 端口开放设备
3. 自动连接：用内置 menmen.ppk 自动尝试登录发现的设备
4. 启动自动重连：保存配置后下次打开应用自动连接
5. 连接成功后自动跳转到「硬件管理」界面
6. TopBar 已连接旁新增断开按钮，点击断开 SSH 回到连接中心

---

### 3.2 设备管家

**Android 文件**
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/DeviceManagerScreen.kt`
- `app/src/main/java/com/sunnypilot/toolbox/model/DeviceStatus.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/SshManager.kt`（`getDeviceStatus()`）

**C3 对应命令/路径**

```bash
# 硬件信息
cat /proc/cpuinfo | grep 'Hardware' | head -1

# 温度
cat /sys/class/thermal/thermal_zone0/temp      # CPU 温度（毫摄氏度）
cat /sys/class/thermal/thermal_zone1/temp      # 设备温度（毫摄氏度）
cat /sys/class/power_supply/bms/temp           # BMS 温度

# 负载与资源
cat /proc/loadavg | awk '{print $1}'             # CPU 负载
free -m | grep Mem | awk '{print $3" "$2}'     # 内存使用
df -h /data | tail -1 | awk '{print $4}'        # 存储剩余

# 设备标识
cat /proc/sys/kernel/hostname
cat /data/params/d/HardwareSerial
cat /data/params/d/DongleId

# 服务状态
ps -A | grep -E 'manager|openpilot' | grep -v grep | wc -l

# 清理命令（点击"垃圾清理"时执行）
echo 3 | sudo tee /proc/sys/vm/drop_caches
find /data/log -maxdepth 1 -type f -mtime +7 -delete
find /tmp -type f -atime +1 -delete

# 日志导出
tar -czf /data/log/export_<timestamp>.tar.gz /data/log /data/community/crashes
```

**功能点**
- 显示设备健康分（基于温度、内存、存储、服务状态）
- 一键体检并生成风险建议
- 清理系统缓存和过期日志
- 导出日志到 C3 `/data/log/`

---

### 3.3 硬件管理

**Android 文件**
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/DeviceDashboardScreen.kt`

**C3 对应命令/路径**

```bash
# 刷新状态（同设备管家）
sshManager.getDeviceStatus()

# 离线模式切换
python -c "from openpilot.common.params import Params; Params().put_bool('OffroadMode', True)"
python -c "from openpilot.common.params import Params; Params().remove('OffroadMode')"

# 错误日志
cat /data/community/crashes/error.log

# 重启/关机
sudo reboot
sudo poweroff
```

**功能点**
- 设备主控台展示硬件身份、CPU、温度、软件版本
- 重启、关机
- 切换离线模式（OffroadMode）
- 查看错误日志
- 刷新状态

---

### 3.4 终端

**Android 文件**
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/TerminalScreen.kt`
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/QuickCommandsPanel.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/SshManager.kt`（`openShell()`）
- `app/src/main/java/com/sunnypilot/toolbox/service/QuickCommandWebServer.kt`

**C3 对应**
- C3 交互式 Shell 通道（`ChannelShell`，`xterm-256color`）
- 在 C3 上执行任意 bash 命令

**功能点**
- 实时 SSH Shell 终端
- 支持粘贴、Tab、Ctrl+C、Esc、上下方向键
- 实时输入模式（逐字发送）
- 快捷命令面板：保存常用命令、生成二维码分享、本地 Web 服务

---

### 3.5 数据中台

**Android 文件**
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/DataCenterScreen.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/repository/DriveStatsRepository.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/db/AppDatabase.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/db/DriveStatsDao.kt`
- `app/src/main/java/com/sunnypilot/toolbox/model/DriveStats.kt`
- `app/src/main/java/com/sunnypilot/toolbox/model/AggregatedStats.kt`

**C3 对应**
- 当前：本地 Room 数据库存储示例/导入数据
- TODO：从 C3 `/data/media/0/realdata/` 的 qlog 解析驾驶数据
- 当前同步占位：
  ```bash
  ls /data/media/0/realdata/ | head -20
  ```

**功能点**
- 总报：智驾里程、人工里程、时长、安全评分
- 接管次数、碰撞预警、跟车过近、前车急刹等事件统计
- 数据导入导出（JSON）
- 日期范围筛选
- 示例数据填充

---

### 3.6 记录仪预览

**Android 文件**
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/RecorderScreen.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/repository/RecorderRepository.kt`
- `app/src/main/java/com/sunnypilot/toolbox/model/RecorderOverlay.kt`

**C3 对应路径/命令**

```bash
# 默认记录仪数据目录
/data/media/0/realdata/

# 每个 segment 目录命名格式
<timestamp>--<route_name>--<segment_index>

# segment 内文件
/data/media/0/realdata/<segment>/qcamera.ts
/data/media/0/realdata/<segment>/overlay.json
/data/media/0/realdata/<segment>/qlog.zst
/data/media/0/realdata/<segment>/qlog.bz2
/data/media/0/realdata/<segment>/qlog

# 预处理脚本
python3 /data/openpilot/c3_scripts/preprocess_recorder.py /data/media/0/realdata/<segment>
```

**功能点**
- 列出 C3 上所有 segment
- 判断 segment 是否有 qlog、视频、叠加层
- 下载 `overlay.json` 到本地缓存
- 下载 `qcamera.ts` 视频到本地缓存
- 调用 C3 预处理脚本
- 本地播放视频与叠加层

---

### 3.7 驾驶设置

**Android 文件**
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/SettingsScreen.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/repository/SettingsRepository.kt`
- `app/src/main/java/com/sunnypilot/toolbox/model/C3SettingMeta.kt`

**C3 对应文件/命令**

```bash
# 桥接脚本（C3 上必须存在）
/data/openpilot/c3_scripts/settings_bridge.py

# 实际参数存储目录
/data/params/d/

# 命令示例
python3 /data/openpilot/c3_scripts/settings_bridge.py list
python3 /data/openpilot/c3_scripts/settings_bridge.py set <key> <value>
python3 /data/openpilot/c3_scripts/settings_bridge.py get <key>
```

**当前支持参数**

| 参数 Key | 类型 | 说明 | C3 对应 |
|---------|------|------|--------|
| `OpenpilotEnabledToggle` | bool | Enable openpilot | `/data/params/d/OpenpilotEnabledToggle` |
| `ExperimentalMode` | bool | Experimental longitudinal control | `/data/params/d/ExperimentalMode` |
| `DisengageOnAccelerator` | bool | 踩油门取消 openpilot | `/data/params/d/DisengageOnAccelerator` |
| `IsLdwEnabled` | bool | 车道偏离警告 | `/data/params/d/IsLdwEnabled` |
| `AlwaysOnDM` | bool | 常开驾驶员监控 | `/data/params/d/AlwaysOnDM` |
| `RecordFront` | bool | 录制并上传驾驶员摄像头 | `/data/params/d/RecordFront` |
| `IsMetric` | bool | 使用公制单位 | `/data/params/d/IsMetric` |
| `RecordAudio` | bool | 录制麦克风音频 | `/data/params/d/RecordAudio` |
| `AccelBar` | bool | 加减速条（RocketFuel 风格） | `/data/params/d/AccelBar` |
| `LongitudinalPersonality` | int | 驾驶性格 | `/data/params/d/LongitudinalPersonality` |
| `DevUIInfo` | int | 开发者 UI 显示模式 | `/data/params/d/DevUIInfo` |

**C3 端源码参考（Qt UI 显示 AccelBar 等）**
- `selfdrive/ui/sunnypilot/qt/onroad/hud.cc`（`drawAccelBar()`）
- `selfdrive/ui/sunnypilot/qt/onroad/hud.h`
- `selfdrive/ui/sunnypilot/ui_scene.h`（`accel_bar` 字段）
- `selfdrive/ui/sunnypilot/ui.cc`（`ui_update_params_sp()` 读取 AccelBar）
- `common/params_keys.h`（`AccelBar` 参数注册）
- `selfdrive/ui/sunnypilot/qt/offroad/settings/visuals_panel.cc`（Visuals 面板）
- `selfdrive/ui/sunnypilot/qt/offroad/settings/sunny_features_panel.cc`（SP Features 面板）

---

## 四、最近新增功能

### 4.1 自动连接 C3
- 内置私钥 `menmen.ppk` 到 `app/src/main/assets/`
- 连接中心新增「自动连接」按钮
- 扫描局域网 22 端口设备，用 menmen.ppk 自动尝试 SSH 登录
- 首次连接成功后保存 IP、端口、用户名、私钥，并开启自动重连
- 下次启动应用时自动连回 C3

### 4.2 断开连接
- TopBar 在「已连接」状态旁显示断开图标（`LinkOff`）
- 点击后断开 SSH 连接并回到连接中心

### 4.3 连接后跳转
- 无论是手动连接还是自动连接，成功后自动跳转到「硬件管理」界面

### 4.4 左侧导航重新排序
- 已完成的 7 个功能前置显示
- 未完成的 11 个功能置后并灰显禁用

---

## 五、技术架构

```
Android 端（Kotlin + Jetpack Compose）
    │
    ├── SSH 连接层：JSch（SshManager.kt）
    │   ├── 密码认证
    │   ├── PPK 私钥认证（自动转 PEM）
    │   ├── 命令执行（exec channel）
    │   ├── 文件下载（SFTP）
    │   └── 交互式 Shell（shell channel）
    │
    ├── 数据层：Repository + Room 数据库
    │   ├── SettingsRepository（C3 参数桥接）
    │   ├── RecorderRepository（C3 记录仪数据）
    │   ├── DriveStatsRepository（本地统计 + TODO 对接 qlog）
    │   └── ConnectionConfigRepository（DataStore 持久化）
    │
    └── UI 层：Jetpack Compose
        ├── SideNavBar（左侧导航）
        ├── TopBar（顶部状态栏 + 断开按钮）
        └── 各 Screen 页面

C3 端（sunnypilot / openpilot）
    │
    ├── SSH 服务：sshd
    ├── 参数系统：/data/params/d/
    ├── 设置桥接脚本：/data/openpilot/c3_scripts/settings_bridge.py
    ├── 记录仪数据：/data/media/0/realdata/
    ├── 预处理脚本：/data/openpilot/c3_scripts/preprocess_recorder.py
    ├── 日志：/data/log/, /data/community/crashes/error.log
    └── Qt UI 显示：selfdrive/ui/sunnypilot/qt/onroad/hud.cc 等
```

---

## 六、C3 端前置依赖

在 C3 上必须存在以下文件/服务，Android 工具箱才能正常工作：

1. **SSH 服务已开启**（`sshd` 监听 22 端口）
2. **menmen.ppk 对应公钥已加入 C3 授权**：
   - 通常位于 `~comma/.ssh/authorized_keys`
3. **设置桥接脚本存在**：
   - `/data/openpilot/c3_scripts/settings_bridge.py`
4. **openpilot 库可用**（用于 Params 读写）：
   - `/data/openpilot/common/params.py`
   - 或者脚本会回退到直接读写 `/data/params/d/`
5. **记录仪预处理脚本存在**（可选）：
   - `/data/openpilot/c3_scripts/preprocess_recorder.py`
6. **数据目录存在**（用于记录仪和后期数据中台）：
   - `/data/media/0/realdata/`

---

## 七、文件索引

### Android 项目关键文件

```
sunnypilot-android/app/src/main/java/com/sunnypilot/toolbox/
├── MainActivity.kt                                    # 主入口、自动连接逻辑
├── data/
│   ├── SshManager.kt                                  # SSH 连接、命令执行、SFTP、Shell
│   ├── SshShell.kt                                    # 交互式 Shell 封装（内联在 SshManager.kt）
│   ├── PpkToPemConverter.kt                           # PPK 转 PEM
│   ├── ConnectionConfigRepository.kt                  # 连接配置持久化（DataStore）
│   ├── db/
│   │   ├── AppDatabase.kt                             # Room 数据库
│   │   └── DriveStatsDao.kt                           # 驾驶统计 DAO
│   └── repository/
│       ├── SettingsRepository.kt                      # C3 参数桥接
│       ├── RecorderRepository.kt                      # 记录仪数据
│       └── DriveStatsRepository.kt                    # 驾驶统计
├── model/
│   ├── ConnectionConfig.kt                            # 连接配置数据类
│   ├── AuthType.kt                                    # 认证类型枚举
│   ├── DeviceStatus.kt                                # 设备状态数据类
│   ├── DriveStats.kt                                  # 驾驶统计数据类
│   ├── AggregatedStats.kt                             # 聚合统计数据类
│   ├── C3SettingMeta.kt                               # C3 设置项元数据
│   ├── RecorderOverlay.kt                             # 记录仪叠加层数据
│   └── QuickCommand.kt                                # 快捷命令数据
├── network/
│   └── AutoDiscovery.kt                                 # 局域网 SSH 设备发现
├── service/
│   └── QuickCommandWebServer.kt                       # 快捷命令本地 Web 服务
├── ui/
│   ├── components/
│   │   ├── SideNavBar.kt                              # 左侧导航栏
│   │   └── TopBar.kt                                  # 顶部状态栏
│   ├── screens/
│   │   ├── ConnectionScreen.kt                        # 连接中心
│   │   ├── DeviceManagerScreen.kt                     # 设备管家
│   │   ├── DeviceDashboardScreen.kt                   # 硬件管理
│   │   ├── TerminalScreen.kt                          # 终端
│   │   ├── QuickCommandsPanel.kt                      # 快捷命令面板
│   │   ├── DataCenterScreen.kt                        # 数据中台
│   │   ├── RecorderScreen.kt                          # 记录仪预览
│   │   └── SettingsScreen.kt                          # 驾驶设置
│   ├── theme/
│   │   ├── Color.kt                                   # 主题颜色
│   │   └── Theme.kt                                   # 主题配置
│   └── util/
│       ├── AnsiParser.kt                              # ANSI 颜色解析
│       └── QrCodeUtil.kt                              # 二维码生成
└── main/assets/
    └── menmen.ppk                                     # 内置默认 SSH 私钥
```

### C3 端关键文件

```
/data/openpilot/
├── c3_scripts/
│   ├── settings_bridge.py                             # 参数桥接脚本
│   └── preprocess_recorder.py                         # 记录仪预处理脚本
├── common/params.py                                   # Params 参数读写库
└── common/params_keys.h                               # 参数注册表

/data/params/d/                                        # 参数实际存储目录
/data/media/0/realdata/                                # 行车记录仪数据
/data/log/                                             # 系统日志
/data/community/crashes/error.log                      # 错误日志

# C3 系统信息路径
/proc/cpuinfo
/sys/class/thermal/thermal_zone0/temp
/sys/class/thermal/thermal_zone1/temp
/sys/class/power_supply/bms/temp
/proc/loadavg
/proc/sys/kernel/hostname

# C3 Qt UI 源码（用于显示 AccelBar 等设置效果）
selfdrive/ui/sunnypilot/qt/onroad/hud.cc
selfdrive/ui/sunnypilot/qt/onroad/hud.h
selfdrive/ui/sunnypilot/ui_scene.h
selfdrive/ui/sunnypilot/ui.cc
selfdrive/ui/sunnypilot/qt/offroad/settings/visuals_panel.cc
selfdrive/ui/sunnypilot/qt/offroad/settings/sunny_features_panel.cc
```

---

## 八、待完善事项

1. **数据中台对接真实 qlog**：当前使用本地示例数据，需从 C3 `/data/media/0/realdata/` 的 qlog 解析真实驾驶统计。
2. **视频预览**：尚未实现实时视频流查看。
3. **文件管理**：尚未实现 C3 文件浏览与传输。
4. **一键下发**：尚未实现批量命令/脚本下发。
5. **备份与恢复刷机**：尚未实现系统备份与刷机功能。
6. **分享中心**：尚未实现数据/视频分享。
7. **设置与关于**：应用自身设置和版本信息页面。

---

## 九、更新记录

| 日期 | 改动 | 提交/文件 |
|------|------|----------|
| 2026-07-05 | 修复 `CircleShape` 导入缺失 | `QuickCommandsPanel.kt` |
| 2026-07-05 | 左侧导航按完成状态重新排序 | `SideNavBar.kt` |
| 2026-07-05 | 新增自动连接、断开按钮、连接后跳转硬件管理 | `ConnectionScreen.kt`, `MainActivity.kt`, `TopBar.kt`, `SshManager.kt`, `ConnectionConfigRepository.kt`, `ConnectionConfig.kt` |
| 2026-07-05 | 修复 `loadDefaultPrivateKey` 编译错误 | `ConnectionScreen.kt` |
