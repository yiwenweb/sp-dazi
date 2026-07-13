#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C3 端驾驶统计计算脚本 (v3 — 支持 BYD 视觉前车 + 增量同步 + C3 端缓存)

功能：
  - 扫描 /data/media/0/realdata/ 下所有 segment
  - 解析 qlog，提取里程、时长、事件
  - 按日期聚合输出 JSON，供 Android App 数据中台导入
  - 支持 modelV2.leads 视觉前车（BYD 无雷达降级方案）
  - 增量同步：记录上次扫描位置，仅处理新 segment
  - C3 端缓存：结果写入 /data/appdata/drive_stats.json，标记写入 /data/appdata/last_sync.txt

用法（在 C3 上）：
    # 首次全量扫描
    python3 c3_scripts/calc_drive_stats.py --full

    # 增量同步（App 调用，仅处理新 segment）
    python3 c3_scripts/calc_drive_stats.py --incremental

输出（stdout，JSON 数组 — 仅返回本次新增/更新的日期）：
    [{
      "date": "2026-07-08",
      "totalDistanceKm": 45.2,
      ...
    }, ...]
"""

import sys
import os
import json
import glob
from datetime import datetime, timezone, timedelta

sys.path.insert(0, "/data/openpilot")

try:
    from tools.lib.logreader import LogReader
except Exception as e:
    print("无法导入 LogReader，请确认在 /data/openpilot 下运行", file=sys.stderr)
    print(f"错误: {e}", file=sys.stderr)
    sys.exit(1)

REALDATA = "/data/media/0/realdata"
QLOG_NAMES = ("qlog.zst", "qlog.bz2", "qlog")

# C3 端应用数据目录
APP_DATA_DIR = "/data/appdata"
LAST_SYNC_FILE = os.path.join(APP_DATA_DIR, "last_sync.txt")
DRIVE_STATS_FILE = os.path.join(APP_DATA_DIR, "drive_stats.json")

# 跟车/前车判定阈值
TAILGATE_DIST_M = 8.0        # 跟车距离 < 8m 视为过近
TAILGATE_MIN_EGO_MS = 3.0    # 自身车速需 > 3m/s
LEAD_STATIONARY_MS = 0.5     # 前车速度 < 0.5m/s 视为静止
LEAD_SLOW_MS = 3.0           # 前车速度 < 3m/s 视为龟速
LEAD_SLOW_GAP_MS = 1.0       # 且自身比前车快 > 1m/s
MODEL_LEAD_PROB_MIN = 0.5    # 视觉前车置信度阈值

# ---------------------------------------------------------------------------
# Event name → DriveStats field mapping
# ---------------------------------------------------------------------------
EVENT_MAP = {
    "fcw":                "collisionWarning",
    "stockFcw":           "collisionWarning",
    "aeb":                "leadCarEmergencyBrake",
    "stockAeb":           "leadCarEmergencyBrake",
    "laneChange":         "laneChangeAssist",
    "preLaneChangeLeft":  "laneChangeAssist",
    "preLaneChangeRight": "laneChangeAssist",
    "startup":            "startReminder",
}

# 接管计数：仅当 engaged 持续 ≥2 秒后 disengage 才算一次接管
TAKEOVER_MIN_DURATION_S = 2.0


# ---------------------------------------------------------------------------
# Per-segment accumulator
# ---------------------------------------------------------------------------
class SegmentAccum:
    __slots__ = ("total_m", "assisted_m", "assisted_duration_s", "max_v_ego",
                 "driving_time_s", "start_ns", "last_ns",
                 "events", "edge_state", "segment_date",
                 "takeover_count", "_last_engaged", "_last_ts", "_engage_ts",
                 "has_radar_leads")

    def __init__(self, date_str):
        self.segment_date = date_str
        self.total_m = 0.0
        self.assisted_m = 0.0
        self.assisted_duration_s = 0.0
        self.max_v_ego = 0.0
        self.driving_time_s = 0.0
        self.start_ns = None
        self.last_ns = None
        self.events = {}
        self.edge_state = {}
        self.takeover_count = 0
        self._last_engaged = None
        self._last_ts = None
        self._engage_ts = None
        self.has_radar_leads = False   # True = 雷达可用，跳过 modelV2 前车

    def integrate(self, v_ego, engaged, ts_ns):
        """Integrate distance from vEgo (m/s) × dt."""
        if self.start_ns is None:
            self.start_ns = ts_ns
            self.last_ns = ts_ns
            return

        dt_s = (ts_ns - self.last_ns) * 1e-9
        if dt_s <= 0 or dt_s > 5.0:
            self.last_ns = ts_ns
            return

        ds_m = v_ego * dt_s
        self.driving_time_s += dt_s
        self.total_m += ds_m
        if engaged:
            self.assisted_m += ds_m
            self.assisted_duration_s += dt_s
        if v_ego > self.max_v_ego:
            self.max_v_ego = v_ego
        self.last_ns = ts_ns

    def register_edge(self, key, active):
        """Count only on rising edge (False→True)."""
        was = self.edge_state.get(key, False)
        if active and not was:
            self.events[key] = self.events.get(key, 0) + 1
        self.edge_state[key] = active

    def record_engaged_transition(self, engaged, ts_ns):
        """Count takeover: engaged ≥2s then disengage."""
        if self._last_ts != ts_ns:
            if engaged and (self._last_engaged is None or not self._last_engaged):
                self._engage_ts = ts_ns
            elif not engaged and self._last_engaged:
                elapsed = (ts_ns - self._engage_ts) * 1e-9 if self._engage_ts is not None else 0.0
                if elapsed >= TAKEOVER_MIN_DURATION_S:
                    self.takeover_count += 1
            self._last_engaged = engaged
            self._last_ts = ts_ns

    def register_event(self, event_name):
        self.events[event_name] = self.events.get(event_name, 0) + 1

    def duration_min(self):
        return int(self.driving_time_s / 60)

    def to_daily_drive_stats(self):
        manual_m = max(0.0, self.total_m - self.assisted_m)
        takeover_count = self.takeover_count

        col_warn = self.events.get("fcw", 0) + self.events.get("stockFcw", 0)
        aeb_cnt = self.events.get("aeb", 0) + self.events.get("stockAeb", 0)
        lc_cnt = (self.events.get("laneChange", 0) +
                  self.events.get("preLaneChangeLeft", 0) +
                  self.events.get("preLaneChangeRight", 0))
        startup_cnt = self.events.get("startup", 0)

        return {
            "date": self.segment_date,
            "totalDistanceKm": round(self.total_m / 1000.0, 1),
            "assistedDistanceKm": round(self.assisted_m / 1000.0, 1),
            "manualDistanceKm": round(manual_m / 1000.0, 1),
            "durationMinutes": self.duration_min(),
            "assistedDurationMinutes": int(self.assisted_duration_s / 60),
            "maxSpeedKmh": round(self.max_v_ego * 3.6, 1),
            "takeovers": takeover_count,
            "collisionWarning": col_warn,
            "tailgating": self.events.get("tailgating", 0),
            "leadCarStationary": self.events.get("leadCarStationary", 0),
            "leadCarEmergencyBrake": aeb_cnt,
            "leadCarSlow": self.events.get("leadCarSlow", 0),
            "startReminder": startup_cnt,
            "laneChangeAssist": lc_cnt,
            "maxSegmentDistanceKm": round(self.total_m / 1000.0, 1),
            "longestSegmentMinutes": self.duration_min(),
            "safetyScore": 0,
        }


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def find_qlog(seg_dir):
    for name in QLOG_NAMES:
        p = os.path.join(seg_dir, name)
        if os.path.isfile(p):
            return p
    return None


def _read_date_from_qlog(qlog):
    """读取 qlog 中真正的录制日期（clocksState / gpsLocation / mtime 降级）。"""
    try:
        lr = LogReader(qlog)
        for i, msg in enumerate(lr):
            if i >= 5000:
                break
            w = msg.which()
            ts_ns = None
            if w == "clocksState":
                cs = msg.clocksState
                if hasattr(cs, "wallTimeNanos"):
                    ts_ns = cs.wallTimeNanos
            elif w == "gpsLocationExternal":
                gps = msg.gpsLocationExternal
                if hasattr(gps, "timestamp"):
                    ts_ns = int(gps.timestamp * 1e9)
            if ts_ns is not None and ts_ns >= 1262304000_000_000_000:
                return datetime.fromtimestamp(ts_ns / 1e9, tz=timezone.utc).strftime("%Y-%m-%d")
    except Exception:
        pass
    try:
        mtime = os.path.getmtime(qlog)
        if mtime >= 1262304000:
            return datetime.fromtimestamp(mtime, tz=timezone.utc).strftime("%Y-%m-%d")
    except Exception:
        pass
    return None


def parse_segment_date(seg_dir):
    """返回 segment 日期(YYYY-MM-DD)，优先目录名时间戳 → qlog 墙钟 → mtime。"""
    seg_name = os.path.basename(seg_dir)
    try:
        ts_str = seg_name.split("--")[0]
        ts = None
        for base in (10, 16):
            try:
                ts = int(ts_str, base)
                break
            except ValueError:
                continue
        if ts is not None and ts >= 1262304000:
            return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%d")
    except (ValueError, IndexError, OSError):
        pass
    qlog = find_qlog(seg_dir)
    if qlog is not None:
        return _read_date_from_qlog(qlog)
    return None


def parse_segment_timestamp(seg_dir):
    """返回 segment 的 Unix 时间戳(int)，用于增量过滤。"""
    seg_name = os.path.basename(seg_dir)
    try:
        ts_str = seg_name.split("--")[0]
        for base in (10, 16):
            try:
                ts = int(ts_str, base)
                if ts >= 1262304000:
                    return ts
            except ValueError:
                continue
    except (ValueError, IndexError):
        pass
    return None


def _lead_active_radar(lead, v_ego):
    """从 radarState lead 提取 (tailgate, stationary, slow)。"""
    if lead is None:
        return False, False, False
    status = getattr(lead, "status", False)
    if not status:
        return False, False, False
    d_rel = getattr(lead, "dRel", 1e9)
    v_lead = getattr(lead, "vLead", 0.0)
    tailgate = d_rel < TAILGATE_DIST_M and v_ego > TAILGATE_MIN_EGO_MS
    stationary = v_lead < LEAD_STATIONARY_MS
    slow = v_lead < LEAD_SLOW_MS and v_ego > v_lead + LEAD_SLOW_GAP_MS
    return tailgate, stationary, slow


def _process_radar_leads(rs, v_ego):
    """处理 radarState 前车数据，返回 (tailgate, stationary, slow, has_any_lead)。"""
    tailgate = stationary = slow = False
    has_any = False
    for lead_attr in ("leadOne", "leadTwo"):
        lead = getattr(rs, lead_attr, None)
        if lead is not None and getattr(lead, "status", False):
            has_any = True
            t, s, sl = _lead_active_radar(lead, v_ego)
            tailgate = tailgate or t
            stationary = stationary or s
            slow = slow or sl
    return tailgate, stationary, slow, has_any


def _process_modelv2_leads(mv, v_ego):
    """处理 modelV2 视觉前车数据（BYD 无雷达降级），返回 (tailgate, stationary, slow)。"""
    leads = getattr(mv, "leads", None)
    if leads is None or len(leads) == 0:
        return False, False, False

    tailgate = stationary = slow = False
    for lead in leads:
        prob = getattr(lead, "prob", 0.0)
        if prob < MODEL_LEAD_PROB_MIN:
            continue
        x = getattr(lead, "x", 1e9)
        if isinstance(x, (list, tuple)):
            x = x[0] if len(x) > 0 else 1e9
        v_lead = getattr(lead, "v", 0.0)
        if isinstance(v_lead, (list, tuple)):
            v_lead = v_lead[0] if len(v_lead) > 0 else 0.0

        tailgate = tailgate or (x < TAILGATE_DIST_M and v_ego > TAILGATE_MIN_EGO_MS)
        stationary = stationary or (v_lead < LEAD_STATIONARY_MS)
        slow = slow or (v_lead < LEAD_SLOW_MS and v_ego > v_lead + LEAD_SLOW_GAP_MS)

    return tailgate, stationary, slow


def _segment_is_plausible(seg_stats):
    """过滤物理上不合理的数据（平均速度 > 150 km/h）。"""
    dur_h = seg_stats.get("durationMinutes", 0) / 60.0
    dist = seg_stats.get("totalDistanceKm", 0)
    if dur_h > 0 and dist / dur_h > 150:
        return False
    return True


# ---------------------------------------------------------------------------
# AppData 管理（C3 端持久化）
# ---------------------------------------------------------------------------
def ensure_appdata_dir():
    os.makedirs(APP_DATA_DIR, exist_ok=True)


def read_last_sync_ts():
    """读取上次同步的 segment 时间戳（10 进制 Unix ts）。"""
    try:
        with open(LAST_SYNC_FILE) as f:
            val = f.read().strip()
            return int(val) if val else 0
    except Exception:
        return 0


def write_last_sync_ts(ts):
    """写入最新已处理的 segment 时间戳。"""
    ensure_appdata_dir()
    try:
        with open(LAST_SYNC_FILE, 'w') as f:
            f.write(str(int(ts)))
    except Exception as e:
        print(f"警告：无法写入 last_sync.txt: {e}", file=sys.stderr)


def load_cached_stats():
    """加载 C3 端缓存的累积统计。"""
    try:
        if os.path.isfile(DRIVE_STATS_FILE):
            with open(DRIVE_STATS_FILE) as f:
                data = json.load(f)
            result = {}
            for item in data:
                date_str = item.get("date", "")
                if date_str:
                    result[date_str] = {k: v for k, v in item.items() if k != "date"}
            return result
    except Exception:
        pass
    return {}


def save_cached_stats(accum_by_date):
    """将累积统计写入 C3 缓存文件。"""
    ensure_appdata_dir()
    try:
        # 现有缓存 + 新数据合并
        existing = load_cached_stats()
        for date_str, daily in accum_by_date.items():
            if date_str in existing:
                # 合并：累加型字段求和，max型字段取最大
                for k, v in daily.items():
                    if k in ("maxSpeedKmh", "maxSegmentDistanceKm", "longestSegmentMinutes"):
                        existing[date_str][k] = max(existing[date_str].get(k, 0), v)
                    elif isinstance(v, (int, float)):
                        existing[date_str][k] = existing[date_str].get(k, 0) + v
            else:
                existing[date_str] = dict(daily)

        # 转 JSON 数组输出
        results = []
        for date_str in sorted(existing.keys(), reverse=True):
            item = {"date": date_str}
            item.update(existing[date_str])
            for k, v in item.items():
                if k == "date":
                    continue
                if isinstance(v, float):
                    item[k] = round(v, 1)
            results.append(item)

        with open(DRIVE_STATS_FILE, 'w') as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
    except Exception as e:
        print(f"警告：无法写入 drive_stats.json: {e}", file=sys.stderr)


# ---------------------------------------------------------------------------
# Segment 处理
# ---------------------------------------------------------------------------
def process_segment(seg_dir, accum_by_date, seg_date):
    """解析单个 segment 的 qlog，按日期聚合。"""
    qlog = find_qlog(seg_dir)
    if qlog is None:
        raise FileNotFoundError(f"未找到 qlog: {seg_dir}")

    acc = SegmentAccum(seg_date)
    v_ego = 0.0
    engaged = False

    lr = LogReader(qlog)
    for msg in lr:
        w = msg.which()
        ts_ns = msg.logMonoTime

        if w == "carState":
            cs = msg.carState
            if hasattr(cs, "vEgo"):
                v_ego = cs.vEgo
            cs_en = getattr(cs, "cruiseState", None)
            if cs_en is not None and hasattr(cs_en, "enabled"):
                engaged = cs_en.enabled
            acc.integrate(v_ego, engaged, ts_ns)

        elif w == "carControl":
            cc = msg.carControl
            if hasattr(cc, "enabled"):
                engaged = cc.enabled

        elif w == "selfdriveState":
            ss = msg.selfdriveState
            if hasattr(ss, "enabled"):
                engaged = ss.enabled

        elif w == "onroadEvents":
            cur = set()
            for ev in msg.onroadEvents:
                try:
                    cur.add(str(ev.name))
                except Exception:
                    continue
            for name in EVENT_MAP:
                acc.register_edge(name, name in cur)

        elif w == "radarState":
            rs = msg.radarState
            tailgate, stationary, slow, has_any = _process_radar_leads(rs, v_ego)
            if has_any:
                acc.has_radar_leads = True
            acc.register_edge("tailgating", tailgate)
            acc.register_edge("leadCarStationary", stationary)
            acc.register_edge("leadCarSlow", slow)

        elif w == "modelV2":
            # BYD 无雷达降级：仅当 radarState 从未提供有效 lead 时才使用视觉前车
            if not acc.has_radar_leads:
                mv = msg.modelV2
                tailgate, stationary, slow = _process_modelv2_leads(mv, v_ego)
                acc.register_edge("tailgating", tailgate)
                acc.register_edge("leadCarStationary", stationary)
                acc.register_edge("leadCarSlow", slow)

        acc.record_engaged_transition(engaged, ts_ns)

    seg_stats = acc.to_daily_drive_stats()
    if seg_stats["totalDistanceKm"] > 0 and _segment_is_plausible(seg_stats):
        date_key = seg_stats["date"]
        if date_key not in accum_by_date:
            accum_by_date[date_key] = {k: 0 for k in seg_stats if k != "date"}
        daily = accum_by_date[date_key]
        for k, v in seg_stats.items():
            if k == "date":
                continue
            if k in ("maxSpeedKmh", "maxSegmentDistanceKm", "longestSegmentMinutes"):
                daily[k] = max(daily.get(k, 0), v)
            elif isinstance(v, (int, float)):
                daily[k] = daily.get(k, 0) + v


def compute_safety_score(daily):
    """计算安全评分，与 App 端 AggregatedStats.calculateScore 逻辑一致。"""
    total = daily.get("totalDistanceKm", 0)
    takeovers = daily.get("takeovers", 0)
    warnings = (daily.get("collisionWarning", 0) +
                daily.get("tailgating", 0) +
                daily.get("leadCarEmergencyBrake", 0) +
                daily.get("leadCarSlow", 0) +
                daily.get("startReminder", 0) +
                daily.get("laneChangeAssist", 0))
    score = 100
    if total > 0:
        score -= int((takeovers / (total / 1000.0)) * 2)
        score -= int(warnings / (total / 100.0))
    return max(0, min(100, score))


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    import argparse
    parser = argparse.ArgumentParser(description="C3 驾驶统计计算 (v3)")
    parser.add_argument("--days", type=int, default=30,
                        help="仅统计最近 N 天的 segment（默认 30，--full 时生效）")
    parser.add_argument("--full", action="store_true",
                        help="全量扫描（忽略增量标记）")
    parser.add_argument("--incremental", action="store_true",
                        help="增量同步：仅处理新 segment，记录进度到 /data/appdata/")
    parser.add_argument("--src", type=str, default=REALDATA,
                        help="指定 realdata 目录")
    args = parser.parse_args()

    seg_dirs = sorted(
        d for d in glob.glob(os.path.join(args.src, "*--*"))
        if os.path.isdir(d)
    )

    if not seg_dirs:
        print(json.dumps([]))
        return

    # ── 增量过滤 ──
    last_sync_ts = 0
    if args.incremental and not args.full:
        last_sync_ts = read_last_sync_ts()
        if last_sync_ts > 0:
            print(f"[增量模式] 上次同步: {datetime.fromtimestamp(last_sync_ts, tz=timezone.utc).strftime('%Y-%m-%d %H:%M')}", file=sys.stderr)

    # 日期窗口过滤（仅 --full 或非增量模式使用）
    now = datetime.now(tz=timezone.utc)
    cutoff = (now - timedelta(days=args.days)).strftime("%Y-%m-%d")

    # ── 处理 segments ──
    accum_by_date = {}
    ok = 0
    skipped = 0
    new_segments = 0
    latest_processed_ts = last_sync_ts

    for d in seg_dirs:
        seg_name = os.path.basename(d)

        # 增量模式：跳过已处理的 segment
        if args.incremental and not args.full:
            seg_ts = parse_segment_timestamp(d)
            if seg_ts is not None and seg_ts <= last_sync_ts:
                skipped += 1
                continue

        seg_date = parse_segment_date(d)
        if seg_date is None:
            skipped += 1
            continue

        # 全量/首次模式：日期窗口过滤
        if not args.incremental or args.full:
            if seg_date < cutoff:
                skipped += 1
                continue

        try:
            process_segment(d, accum_by_date, seg_date)
            ok += 1
            new_segments += 1
            # 追踪最新处理的 segment 时间戳
            seg_ts = parse_segment_timestamp(d)
            if seg_ts is not None and seg_ts > latest_processed_ts:
                latest_processed_ts = seg_ts
        except Exception as e:
            print(f"跳过 {seg_name}: {e}", file=sys.stderr)
            continue

    # ── 更新增量标记 ──
    if args.incremental and latest_processed_ts > last_sync_ts:
        write_last_sync_ts(latest_processed_ts)

    # ── 生成输出 ──
    results = []
    for date_str in sorted(accum_by_date.keys(), reverse=True):
        daily = accum_by_date[date_str]
        daily["date"] = date_str
        for k, v in daily.items():
            if k == "date":
                continue
            if isinstance(v, float):
                daily[k] = round(v, 1)
        daily["safetyScore"] = compute_safety_score(daily)
        results.append(daily)

    # ── 输出到 stdout（App 读取） ──
    print(json.dumps(results, ensure_ascii=False))

    # ── 缓存到 C3 端文件 ──
    if accum_by_date:
        save_cached_stats(accum_by_date)

    if args.incremental:
        print(f"[增量同步完成] 处理 {new_segments} 个新 segment, 跳过 {skipped} 个已处理", file=sys.stderr)
    else:
        print(f"[全量扫描完成] 处理 {ok} 个 segment", file=sys.stderr)


if __name__ == "__main__":
    main()
