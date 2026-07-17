# SunnyPilot Android Toolbox 功能总结

> 对应 C3（comma three）上的文件与数据路径
> 更新时间：2026-07-16

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
| **记录仪预览** | `RecorderScreen.kt` + `RecorderRepository.kt` | `/data/media/0/realdata/<segment>/qcamera.ts`, `overlay.json`, `qlog.zst/bz2/qlog`, `/data/openpilot/c3_scripts/preprocess_recorder.py` | 分段列表、下载视频与叠加层、预处理、多种排序/查看/筛选方式 |
| **实时摄像头+HUD** | `VideoScreen.kt` + `VideoStreamRepository.kt` + `HudDataRepository.kt` + `HudOverlay.kt` | `/data/mjpeg_stream.py`, `/data/hud_data_server.py`, `stream_encoderd`, cereal消息（carState/controlsState/modelV2等） | MJPEG视频流（480p@8fps）+ HUD信息叠加（车速/档位/前车距/车道线等） |
| **文件管理** | `FileScreen.kt` + `FileRepository.kt` | C3 SFTP 服务（22/tcp），文件系统路径 | 浏览目录、上传/下载、编辑/预览、删除/重命名、搜索、新建目录、Web 扫码管理 |
| **驾驶设置** | `SettingsScreen.kt` + `SettingsRepository.kt` | `/data/openpilot/c3_scripts/settings_bridge.py` 读写 `/data/params/d/` 下参数 | 读写 openpilot/sunnypilot 参数 |

### 2.2 未适配/未开发功能（已置后灰显）

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
- **排序方式**：时间倒序/正序、名称升序/降序、就绪优先、已缓存优先
- **查看方式**：列表视图、网格视图、紧凑视图
- **筛选功能**：全部、就绪（有视频+叠加）、已缓存、未就绪
- 筛选后显示匹配数量（如"显示 15 / 50 个"）

---

### 3.7 文件管理

**Android 文件**
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/FileScreen.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/repository/FileRepository.kt`
- `app/src/main/java/com/sunnypilot/toolbox/model/FileEntry.kt`
- `app/src/main/java/com/sunnypilot/toolbox/service/WebManagerServer.kt`

**C3 对应**
- SFTP 服务：C3 SSH 服务（端口 22）支持 SFTP 协议
- 文件系统：任意路径（如 `/data`, `/data/openpilot`, `/data/media`, `/tmp` 等）

**C3 对应命令/路径**

```bash
# SFTP 操作（通过 JSch ChannelSftp）
sftp comma@192.168.43.1

# 常用操作目录
/data/openpilot/              # sunnypilot 源码
/data/params/d/               # 参数存储
/data/media/0/realdata/       # 行车记录
/data/log/                    # 日志文件
/data/community/              # 社区版配置
/tmp/                         # 临时文件

# Shell 搜索命令（FileRepository 调用）
find /data -maxdepth 4 -iname "*keyword*" -not -path '*/\.*' 2>/dev/null | head -200
```

**功能点**
1. **目录浏览**：
   - 面包屑导航，快速跳转父目录
   - 文件/目录排序（目录优先，名称字母序）
   - 文件类型图标（文件夹、文本、配置、日志、压缩包等）
   - 显示文件大小、修改时间、权限
   - 识别符号链接

2. **文件操作**：
   - **预览**：前 200 行文本预览（适用于日志、配置等）
   - **编辑**：在线编辑小于 200KB 的文本文件，保存回 C3
   - **下载**：下载文件到 Android 设备下载目录
   - **上传**：从 Android 选择文件上传到当前 C3 目录
   - **删除**：删除文件或递归删除整个目录（带确认）
   - **重命名/移动**：重命名文件或移动到新路径
   - **文件信息**：查看详细信息（名称、路径、类型、大小、修改时间、权限）

3. **高级功能**：
   - **搜索**：在当前目录树（最深 4 层）搜索文件名，显示最多 200 个结果
   - **新建目录**：在当前路径创建新文件夹
   - **快捷跳转**：一键跳转到 `/data` 目录
   - **Web 扫码管理**：生成二维码，扫码后用手机/电脑浏览器访问本地 Web 服务器（端口 8080），远程管理 C3 文件和快捷命令
   - **刷新**：重新加载当前目录

4. **排序方式**（新增）：
   - 类型分组（默认，目录优先+按扩展名）
   - 名称 A-Z / Z-A
   - 大小升序/降序
   - 时间最新/最旧

5. **查看方式**（新增）：
   - 列表视图（默认，显示完整操作按钮）
   - 网格视图（3 列卡片，适合快速浏览）
   - 紧凑视图（单行显示，节省空间）

6. **筛选功能**（新增）：
   - 全部（默认）
   - 仅目录
   - 仅文件
   - 可编辑（小于 200KB 的文本文件）

7. **用户体验**：
   - 加载中进度提示
   - 操作失败错误提示
   - 文件过大编辑提醒（推荐使用桌面工具）
   - 删除目录的递归删除警告
   - 搜索结果计数显示
   - 筛选时显示"显示 X / Y 项"

**技术细节**
- 基于 SFTP 协议（比 shell 命令更快更可靠）
- 仅搜索功能使用 shell `find` 命令（SFTP 无原生搜索）
- 文件编辑限制 200KB（防止 OOM）
- 支持 Android SAF（Storage Access Framework）选择文件上传
- Web 服务器使用 NanoHTTPD，提供局域网访问

---

### 3.8 实时摄像头+HUD叠加

**Android 文件**
- `app/src/main/java/com/sunnypilot/toolbox/ui/screens/VideoScreen.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/repository/VideoStreamRepository.kt`
- `app/src/main/java/com/sunnypilot/toolbox/data/repository/HudDataRepository.kt`
- `app/src/main/java/com/sunnypilot/toolbox/ui/components/HudOverlay.kt`
- `app/src/main/assets/mjpeg_stream.py`（已有）
- `app/src/main/assets/hud_data_server.py`（新增）

**C3 对应文件/命令**

```bash
# MJPEG视频流服务（已有）
/data/mjpeg_stream.py
# 启动命令
cd /data/openpilot && . /usr/local/venv/bin/activate && \
export PYTHONPATH=/data/openpilot && \
nohup python /data/mjpeg_stream.py --camera road --port 5002 > /tmp/mjpeg_stream.log 2>&1 &

# HUD数据服务（新增）
/data/hud_data_server.py
# 启动命令
cd /data/openpilot && . /usr/local/venv/bin/activate && \
export PYTHONPATH=/data/openpilot && \
nohup python /data/hud_data_server.py --port 5003 > /tmp/hud_data_server.log 2>&1 &

# 视频流端点
http://<C3_IP>:5002/frame          # 获取单帧JPEG
http://<C3_IP>:5002/stream         # MJPEG流（multipart）
http://<C3_IP>:5002/health         # 健康检查

# HUD数据端点
http://<C3_IP>:5003/hud            # 获取HUD JSON数据
http://<C3_IP>:5003/health         # 健康检查

# 依赖的C3服务
stream_encoderd                     # 硬件编码H264帧
# 启用命令
echo -n '1' > /data/params/d/WebrtcStreamEnabled

# cereal消息订阅（hud_data_server.py）
carState                           # 车速、档位、转向角、转向灯、刹车灯
controlsState                      # 横向控制状态、警告信息
modelV2                           # 前车距离、车道线位置
gpsLocationExternal               # GPS坐标、海拔
liveCalibration                   # 俯仰角、偏航角
liveParameters                    # 其他实时参数
```

**功能点**

1. **视频流查看**：
   - 选择摄像头：主视角（road）或广角（wideRoad）
   - MJPEG 480p 分辨率，10fps解码，8fps显示
   - 延迟：100-200ms
   - CPU开销：C3端<5%
   - 带宽：~0.5Mbps

2. **HUD信息叠加**：
   - **车速显示**（左上角）：km/h，白色大字体
   - **档位显示**（左上角下方）：P/R/N/D等，青色
   - **转向角度**（右上角）：方向盘转向角度
   - **转向灯指示**（两侧）：绿色圆圈+箭头
   - **前车距离**（中上方）：距离+颜色提示（红<15m/黄15-30m/绿>30m）
   - **横向控制状态**（中央）：绿色"✓ 横向控制"
   - **车道线位置**（底部）：黄色竖线
   - **警告信息**（顶部中央）：系统警告（颜色根据严重程度）
   - **刹车指示**（右下角）：红色圆圈

3. **HUD开关**：
   - 左上角HUD按钮切换显示/隐藏
   - 👁️ 图标：HUD显示中
   - 👁️‍🗨️ 图标：HUD已隐藏

4. **性能特性**：
   - **Android 7.0完美兼容**：使用OkHttp 4.9.3（最后支持Android 5+的版本）
   - **低开销**：视频轮询120ms（8fps），HUD轮询500ms（2Hz）
   - **原生渲染**：Bitmap + Canvas，无WebView开销
   - **自动管理**：进入页面自动启动服务，退出自动停止

**技术细节**
- 视频传输：MJPEG over HTTP，避免WebRTC兼容性问题
- HUD数据：JSON over HTTP，轻量级传输
- 视频解码：C3端PyAV软解H264→JPEG，10fps限频控制CPU
- Android显示：HTTP轮询 + Bitmap解码 + Canvas叠加绘制
- 颜色编码：根据数据值动态调整HUD元素颜色
- 自动清理：离开界面时pkill两个Python进程

---

### 3.9 驾驶设置

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

### 4.1 记录仪预览增强（2026-07-16）
- **排序方式**：新增 6 种排序选项
  - 时间倒序（默认，最新优先）
  - 时间正序（最旧优先）
  - 名称升序 A-Z
  - 名称降序 Z-A
  - 就绪优先（有视频+叠加数据的排前面）
  - 已缓存优先（本地已缓存的排前面）
- **查看方式**：新增 3 种视图模式
  - 列表视图（默认，显示完整信息）
  - 网格视图（2 列卡片布局，适合快速浏览）
  - 紧凑视图（单行显示，节省空间）
- **筛选功能**：新增 4 种筛选器
  - 全部（默认）
  - 就绪（只显示有视频+叠加的 segment）
  - 已缓存（只显示本地已缓存的）
  - 未就绪（只显示缺少视频或叠加的）
- **智能统计**：状态栏显示"显示 X / Y 个"，X 为筛选后数量，Y 为总数
- **UI 优化**：顶部工具栏新增筛选图标、排序图标、视图切换按钮

### 4.2 文件管理增强（2026-07-16）
- **排序方式**：新增 7 种排序选项
  - 类型分组（默认，目录优先+按扩展名分组）
  - 名称 A-Z / Z-A
  - 大小升序/降序
  - 时间最新/最旧
- **查看方式**：新增 3 种视图模式
  - 列表视图（默认，显示所有操作按钮）
  - 网格视图（3 列卡片，图标+文件名+大小）
  - 紧凑视图（单行，仅图标+文件名+大小）
- **筛选功能**：新增 4 种筛选器
  - 全部（默认）
  - 仅目录
  - 仅文件
  - 可编辑（小于 200KB 的文本文件）
- **智能统计**：筛选时显示"显示 X / Y 项"
- **UI 优化**：顶部工具栏新增筛选图标、排序图标、视图切换按钮（与记录仪保持一致）

### 4.3 自动连接 C3
- 内置私钥 `menmen.ppk` 到 `app/src/main/assets/`
- 连接中心新增「自动连接」按钮
- 扫描局域网 22 端口设备，用 menmen.ppk 自动尝试 SSH 登录
- 首次连接成功后保存 IP、端口、用户名、私钥，并开启自动重连
- 下次启动应用时自动连回 C3

### 4.3 断开连接
- TopBar 在「已连接」状态旁显示断开图标（`LinkOff`）
- 点击后断开 SSH 连接并回到连接中心

### 4.4 连接后跳转
- 无论是手动连接还是自动连接，成功后自动跳转到「硬件管理」界面

### 4.5 左侧导航重新排序
- 已完成的 9 个功能前置显示（连接中心、设备管家、硬件管理、终端、数据中台、记录仪预览、实时摄像头+HUD、文件管理、驾驶设置）
- 未完成的 9 个功能置后并灰显禁用

### 4.6 实时摄像头+HUD叠加（2026-07-16）
- **视频流**：MJPEG 480p @ 8fps，延迟100-200ms
  - 自动部署并启动C3端`mjpeg_stream.py`
  - HTTP轮询获取JPEG帧
  - 选择摄像头：主视角（road）/ 广角（wideRoad）
- **HUD数据服务**：2Hz更新，JSON格式
  - 自动部署并启动C3端`hud_data_server.py`
  - 订阅cereal消息（carState/controlsState/modelV2等）
  - HTTP轮询获取HUD数据
- **HUD叠加显示**：Canvas原生绘制
  - 车速、档位、转向角、转向灯
  - 前车距离（带颜色警示）
  - 车道线位置
  - 横向控制状态
  - 系统警告信息
  - 刹车指示
- **一键切换**：HUD开关按钮随时显示/隐藏叠加层
- **兼容性**：完美支持Android 7.0（使用OkHttp 4.9.3）
- **低开销**：C3端CPU增加<8%，不影响驾驶

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
    │   ├── VideoStreamRepository（C3 视频流MJPEG）
    │   ├── HudDataRepository（C3 HUD数据JSON）
    │   ├── FileRepository（C3 文件系统 SFTP）
    │   ├── DriveStatsRepository（本地统计 + TODO 对接 qlog）
    │   └── ConnectionConfigRepository（DataStore 持久化）
    │
    └── UI 层：Jetpack Compose
        ├── SideNavBar（左侧导航）
        ├── TopBar（顶部状态栏 + 断开按钮）
        ├── HudOverlay（HUD叠加绘制组件）
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
│       ├── VideoStreamRepository.kt                   # 视频流MJPEG
│       ├── HudDataRepository.kt                       # HUD数据JSON
│       ├── FileRepository.kt                          # 文件系统 SFTP
│       └── DriveStatsRepository.kt                    # 驾驶统计
├── model/
│   ├── ConnectionConfig.kt                            # 连接配置数据类
│   ├── AuthType.kt                                    # 认证类型枚举
│   ├── DeviceStatus.kt                                # 设备状态数据类
│   ├── DriveStats.kt                                  # 驾驶统计数据类
│   ├── AggregatedStats.kt                             # 聚合统计数据类
│   ├── C3SettingMeta.kt                               # C3 设置项元数据
│   ├── RecorderOverlay.kt                             # 记录仪叠加层数据
│   ├── QuickCommand.kt                                # 快捷命令数据
│   └── FileEntry.kt                                   # 文件条目数据
├── network/
│   └── AutoDiscovery.kt                                 # 局域网 SSH 设备发现
├── service/
│   ├── QuickCommandWebServer.kt                       # 快捷命令本地 Web 服务
│   └── WebManagerServer.kt                            # 文件管理 Web 服务
├── ui/
│   ├── components/
│   │   ├── SideNavBar.kt                              # 左侧导航栏
│   │   ├── TopBar.kt                                  # 顶部状态栏
│   │   └── HudOverlay.kt                              # HUD叠加绘制
│   ├── screens/
│   │   ├── ConnectionScreen.kt                        # 连接中心
│   │   ├── DeviceManagerScreen.kt                     # 设备管家
│   │   ├── DeviceDashboardScreen.kt                   # 硬件管理
│   │   ├── TerminalScreen.kt                          # 终端
│   │   ├── QuickCommandsPanel.kt                      # 快捷命令面板
│   │   ├── DataCenterScreen.kt                        # 数据中台
│   │   ├── RecorderScreen.kt                          # 记录仪预览
│   │   ├── VideoScreen.kt                             # 实时摄像头+HUD
│   │   ├── FileScreen.kt                              # 文件管理
│   │   └── SettingsScreen.kt                          # 驾驶设置
│   ├── theme/
│   │   ├── Color.kt                                   # 主题颜色
│   │   └── Theme.kt                                   # 主题配置
│   └── util/
│       ├── AnsiParser.kt                              # ANSI 颜色解析
│       └── QrCodeUtil.kt                              # 二维码生成
└── main/assets/
    ├── menmen.ppk                                     # 内置默认 SSH 私钥
    ├── mjpeg_stream.py                                # C3 视频流服务脚本
    └── hud_data_server.py                             # C3 HUD数据服务脚本
```

### C3 端关键文件

```
/data/openpilot/
├── c3_scripts/
│   ├── settings_bridge.py                             # 参数桥接脚本
│   └── preprocess_recorder.py                         # 记录仪预处理脚本
├── common/params.py                                   # Params 参数读写库
└── common/params_keys.h                               # 参数注册表

/data/
├── mjpeg_stream.py                                    # 视频流服务（App自动部署）
├── hud_data_server.py                                 # HUD数据服务（App自动部署）
├── params/d/                                          # 参数实际存储目录
├── media/0/realdata/                                  # 行车记录仪数据
├── log/                                               # 系统日志
└── community/crashes/error.log                        # 错误日志

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
2. **一键下发**：尚未实现批量命令/脚本下发。
3. **备份与恢复刷机**：尚未实现系统备份与刷机功能。
4. **分享中心**：尚未实现数据/视频分享。
5. **设置与关于**：应用自身设置和版本信息页面。

---

## 九、更新记录

| 日期 | 改动 | 提交/文件 |
|------|------|----------|
| 2026-07-17 | 添加视频流诊断功能：设置按钮、服务状态测试、脚本重新部署 | `VideoScreen.kt` (commit efa31b7) |
| 2026-07-17 | 优化文件网格视图显示密度：每行从3个增加到4个 | `FileScreen.kt` (commit b84f887) |
| 2026-07-17 | 修复SettingsScreen的XML标签语法错误 | `SettingsScreen.kt` (commit 11c8d4b) |
| 2026-07-17 | 修复mjpeg_stream.py的SubSocket.close()错误 | `mjpeg_stream.py` (commit 42ab3af) |
| 2026-07-17 | 添加SSH密钥文件到gitignore | `.gitignore` (commit 123ee99) |
| 2026-07-16 | 修改脚本部署路径到/data/spapp/spyl/，日志到/data/spapp/spyl/log/ | `VideoStreamRepository.kt`, `HudDataRepository.kt` (commit 2ff7154) |
| 2026-07-16 | 更新功能总结文档：添加C3摄像头HUD传输方案 | `SunnyPilotToolbox功能总结.md`, `C3摄像头HUD传输方案.md` (commit dea94cd) |
| 2026-07-16 | 实现C3摄像头+HUD叠加功能 | `VideoScreen.kt`, `VideoStreamRepository.kt`, `HudDataRepository.kt`, `HudOverlay.kt`, `hud_data_server.py`, `build.gradle.kts`, `libs.versions.toml` (commit d9ef84c) |
| 2026-07-16 | 修复RecorderScreen.kt的Modifier.weight作用域错误 | `RecorderScreen.kt` (commit 6f2baff) |
| 2026-07-16 | 修复FileScreen.kt编译错误：删除重复代码块 | `FileScreen.kt` (commit 9891804) |
| 2026-07-16 | 优化网格视图间距，提高显示密度（RecorderScreen/FileScreen） | `RecorderScreen.kt`, `FileScreen.kt` (commit 3f724ab) |
| 2026-07-16 | 驾驶设置模块现代化改版：卡片网格布局、Material Design风格 | `SettingsScreen.kt` (commit 55c42f4) |
| 2026-07-16 | 优化Web管理界面：移动端响应式、修复文件上传、增强终端功能 | `QuickCommandWebServer.kt`, `WebManagerServer.kt` |
| 2026-07-16 | 记录仪预览新增排序/查看/筛选功能 | `RecorderScreen.kt` |
| 2026-07-16 | 文件管理新增排序/查看/筛选功能 | `FileScreen.kt` |
| 2026-07-16 | 两个模块功能文档化 | `SunnyPilotToolbox功能总结.md` |
| 2026-07-05 | 修复 `CircleShape` 导入缺失 | `QuickCommandsPanel.kt` |
| 2026-07-05 | 左侧导航按完成状态重新排序 | `SideNavBar.kt` |
| 2026-07-05 | 新增自动连接、断开按钮、连接后跳转硬件管理 | `ConnectionScreen.kt`, `MainActivity.kt`, `TopBar.kt`, `SshManager.kt`, `ConnectionConfigRepository.kt`, `ConnectionConfig.kt` |
| 2026-07-05 | 修复 `loadDefaultPrivateKey` 编译错误 | `ConnectionScreen.kt` |
