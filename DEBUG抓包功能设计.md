# Debug 故障抓取分析功能 — 设计文档

> 目标：App 里加一个 Debug 页面，一个开关开启后，C3 常驻监测横向/纵向故障；
> 发现问题时自动保留出现前后各 1 分钟的全量 CAN + 关键状态，并做初步分类分析；
> App 负责启停、查状态、下载事件、展示分析结果。
>
> 版本背景：sunnypilot 0.10.1 适配未完善，跑着会报错/锁死/失去转向/ACC报错，
> 需要"现场黑匣子"来抓证据供事后（人工/AI）定位。

---

## 一、架构总览（关键认知）

**手机 App 不能实时抓 CAN。** CAN 信号只存在于 C3 的 cereal 消息总线，手机只有一条 SSH。
因此唯一正确架构：

```
┌─────────────┐   SSH(临时)   ┌──────────────────────────────────┐
│  Android App │ ────────────> │  C3: byd_debug_watchdog.py (常驻) │
│              │  启停/查状态   │   - 订阅 can / carState / ...      │
│  Debug 页面  │ <──────────── │   - 环形缓冲最近 60s               │
│  事件列表    │  下载dump/报告 │   - 检测故障 → 存前60s+后60s       │
│  分析展示    │               │   - 自动分类 + 生成 report.txt     │
└─────────────┘               └──────────────────────────────────┘
                                        │ 独立运行, 手机不在场也抓
                                        v
                              /data/media/0/debug_events/<ts>_<type>/
```

**开关语义**：不是"手机盯着抓"，而是"让 C3 常驻黑匣子监测"。开一次后手机可断开，
开车时照抓；事后连上 App 取证据。这一点是整个功能的产品定义核心。

---

## 二、C3 侧守护脚本 `byd_debug_watchdog.py`

在现有 `byd_lock_watchdog.py`（已实现环形缓冲+锁死存证）基础上扩展为多故障监测。

### 2.1 环形缓冲
- 订阅：`can`(全量) + `carState` + `carControl` + `controlsState` + `onroadEvents` + `longitudinalPlan` + `carParams`(低频)
- 缓冲：`collections.deque`，按时间保留最近 **60s**（100Hz can ≈ 全量帧）
- 触发后：再继续录 **60s**，然后把 [前60s + 后60s] 一起 dump

### 2.2 故障检测判据（横向 + 纵向）

所有判据的信号来源均已在技术笔记/代码中验证过，非臆测。

#### 横向故障

| 编号 | 故障 | 判据 | 来源 |
|---|---|---|---|
| LAT1 | EPS 锁死 | `ACC_EPS_STATE.TorqueFailed` 0→1 (0x318 src<128) | 笔记3.3/12, 已实测 |
| LAT2 | EPS 单方面切断/失去转向力 | 接管中(`LKAS_Active`=1) `CruiseActivated` 1→0 | 笔记12.5, 已实测 |
| LAT3 | EPS 请求退出(LOCK3前兆) | 接管中 `LKAS_Prepared` 0→1 上升沿 | 笔记13.8, 已实测 |
| LAT4 | 命令-执行错配 | latActive 且 `|OP扭矩|>=30` 但 `|MainTorque|<=5` 持续>3帧 | 笔记13.4 |
| LAT5 | 横向长时间大偏差 | latActive 且 `|desiredCurv - curvature|` 超阈值持续 | 通用 |

#### 纵向故障

| 编号 | 故障 | 判据 | 来源 |
|---|---|---|---|
| LON1 | ACC 报错 | `ACC_HUD_ADAS.AccState == 7` (ERROR) | carstate.py 6.2 已确认 |
| LON2 | ACC 意外退出 | longActive=1 → cruiseState.enabled 突然 0 (非人为cancel) | car_specific/carstate |
| LON3 | 加速度指令饱和 | `actuators.accel` 持续贴 ACCEL_MIN(-3.5)/ACCEL_MAX(2.0) | values.py |
| LON4 | 加速度剧烈跳变 | 相邻帧 `accel` 跳变超阈值(参考Exp Mode锁死时-106→-198现象) | 笔记8.1 |
| LON5 | ACC_CMD 计数器断裂 | `ACC_CMD.Counter` 不连续(发送时序抖动) | 笔记8.1 |
| LON6 | 意外急减速 | longActive 且 accel<-3.0 而前车距离(mrr_leaddist/x0)充足 | 通用安全 |
| LON7 | 起步/停车异常 | standstill 状态与 accel 指令矛盾(该停不停/该走不走) | interface starting/stopping |

#### 通用/系统故障

| 编号 | 故障 | 判据 |
|---|---|---|
| SYS1 | 关键进程崩溃 | controlsd/card/plannerd/pandad 进程消失 (pgrep) |
| SYS2 | 主循环卡顿 | `carState.cumLagMs` 超阈值 |
| SYS3 | panda 断连 | pandaStates 无心跳 / controlsState.canValid=false |
| SYS4 | 报错事件 | onroadEvents 出现指定 EventName(如 controlsMismatch) |

### 2.3 触发后动作
1. 标记触发时刻 `t0`、故障编号、简要原因
2. 继续录 60s（后窗口）
3. dump 到 `/data/media/0/debug_events/<yyyymmdd_hhmmss>_<TYPE>/`：
   - `dump.jsonl`：前后各60s全量 can + 状态（复用 lock_watchdog 格式）
   - `meta.json`：故障类型/触发时刻/车速/关键信号快照
   - `report.txt`：自动初步分析（见2.4）
4. 冷却期（避免同一故障刷屏），如 30s 内同类型不重复触发
5. 磁盘保护：只保留最近 N 个事件（如 20 个），超出删最旧

### 2.4 自动初步分析（务实定位，不画大饼）
脚本触发时跑一个轻量分析，输出到 report.txt。**只做"分类 + 一级线索"，不做根因定论**：
- 触发前 2s 各关键信号的时序表（哪个信号先变）
- 例：`LAT1 锁死: 触发前0.50s LKAS_Prepared 0→1, 0.46s MainTorque 75→0, drvTq≈0` → 指向LOCK3型
- 例：`LON1 AccState=7: 触发前 accel 从 -0.5 跳到 -3.5, Counter连续` → 指向减速指令异常
- 深度根因仍需把 dump 拉回 App/AI 分析（诚实说明）

### 2.5 常驻方式
```bash
nohup python /data/byd_debug_watchdog.py > /data/debug_watchdog.log 2>&1 &
```
- 脱离 SSH 会话，手机断开不影响
- 写 PID 到 `/data/debug_watchdog.pid` 供 App 查状态/停止
- 可选：开机自启（写入 launch 脚本，与看门狗一致思路）

---

## 三、App 侧设计

### 3.1 新增文件（对齐现有工程结构）
```
model/DebugEvent.kt            # 事件数据模型
data/repository/DebugRepository.kt   # 启停/查状态/列事件/下载 (复用 SshManager)
ui/screens/DebugScreen.kt      # 页面
c3_scripts/byd_debug_watchdog.py     # 守护脚本(随App打包, 首次开启时上传C3)
```

### 3.2 DebugRepository 能力（全部复用现有 SshManager，无新技术）
| 方法 | 实现 | 复用 |
|---|---|---|
| `ensureScriptDeployed()` | 上传 watchdog 脚本到 /data | `uploadFile`/`writeTextFile` |
| `startWatchdog()` | nohup 启动, 写 pid | `executeCommand` |
| `stopWatchdog()` | `kill $(cat pid)` / pkill | `executeCommand` |
| `queryStatus()` | 查 pid存活 + 心跳log + 事件数 | `executeCommand` |
| `listEvents()` | `ls /data/media/0/debug_events/` | `executeCommand` (仿 RecorderRepository.listSegments) |
| `downloadEvent(id)` | 拉 dump.jsonl+meta+report | `downloadFile` |
| `deleteEvent(id)` | 删远程事件目录 | `deleteRemote` |

### 3.3 DebugScreen UI
```
┌─ Debug 故障黑匣子 ──────────────────┐
│ [●] 故障监测   ▶ 开启中 / ○ 已停止   │  ← 主开关(启停watchdog)
│ 心跳: 12s前 | 已捕获: 3 起          │  ← 状态卡片
│─────────────────────────────────────│
│ 监测项(可折叠):                      │
│  横向: 锁死/失力/错配/大偏差 ✓       │
│  纵向: ACC报错/急减速/退出/饱和 ✓    │
│  系统: 进程崩溃/卡顿/panda断连 ✓     │
│─────────────────────────────────────│
│ 事件列表:                            │
│  🔴 20260712_1830 LAT1 EPS锁死  [↓] │
│  🟠 20260712_1815 LON1 ACC报错  [↓] │
│  🟡 20260712_1750 SYS2 主循环卡顿[↓]│
│─────────────────────────────────────│
│ 点击事件 → 展示 report.txt + 关键时序 │
└─────────────────────────────────────┘
```

### 3.4 事件详情展示
- 直接显示 `report.txt`（脚本已做的初步分析）
- 关键信号触发前后时序表（从 meta.json 读）
- 「下载完整dump」按钮：拉 dump.jsonl 到手机，供后续深度分析/发给AI
- 复用 RecorderPlayerScreen 里 jsonl 逐帧解析的思路展示曲线（可选，二期）

### 3.5 导航接入
在主导航（MainActivity/DeviceDashboard 的入口列表）加一个 "Debug" tab，
和 LateralParams/Recorder 同级。

---

## 四、分期落地建议

**一期（核心闭环，最小可用）**
1. C3: byd_debug_watchdog.py — 缓冲 + LAT1/LAT2/LON1/SYS1 四个最关键故障 + dump
2. App: DebugRepository(启停/查状态/列事件/下载) + DebugScreen(开关+列表+report展示)
3. 目标：能自动抓到锁死/失力/ACC报错，事件能下载

**二期（覆盖 + 分析增强）**
4. 补齐 LAT3-5/LON2-7/SYS2-4 全部判据
5. report.txt 自动时序分析增强
6. 事件详情信号曲线可视化(复用Recorder解析)

**三期（体验）**
7. 开机自启、磁盘自动清理策略可配置
8. 事件推送提醒、一键打包发送

---

## 五、必须认清的约束（不画大饼）

1. **手机不在场也要抓** → 全靠 C3 守护独立完成，App 只是遥控+取证。
2. **磁盘会满** → 每事件前后2分钟全量CAN约几十MB，必须限量清理（笔记有磁盘满导致无法onroad的教训）。
3. **自动分析≠自动定论** → 只做"分类+一级线索"。真正根因（如LOCK3那种）仍需拉dump人工/AI深挖。这是能力边界，明确告知用户。
4. **watchdog 自身开销** → 订阅全量can+缓冲有CPU/内存开销，需确认不影响OP主进程（lock_watchdog已验证可接受，扩展后需再测）。
5. **不改 openpilot 主代码** → watchdog 是旁路只读订阅，不介入控制，零风险。

---

## 六、与现有资产的复用关系

| 需求 | 复用现有 |
|---|---|
| 环形缓冲+锁死存证引擎 | `byd_lock_watchdog.py` (技术笔记12.8) |
| 各故障判据 | 技术笔记 3/6/8/12/13 章已实测的信号 |
| SSH 启停/下载/查状态 | `SshManager` 全部现成 |
| "App调C3 python脚本"模式 | `RecorderRepository.preprocessSegment` |
| 列远程目录→列表 | `RecorderRepository.listSegments` |
| jsonl 逐帧解析展示 | `RecorderPlayerScreen` |
| 分析脚本库 | 本地 byd_*_analyze.py 系列 |

**本质**：把散落的 byd_lock_watchdog.py + byd_*_analyze.py 收编成
"C3常驻黑匣子 + App遥控取证"的闭环。约70%基础已有，新增主要是多故障判据 + App一个页面。
