#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BYD DEBUG 多故障看门狗 (在 C3 上常驻运行) —— 自带录制, 不依赖 loggerd

在 byd_lock_watchdog.py 基础上扩展为「多故障类型」监测黑匣子。
实时订阅 live 消息, 内存滚动缓冲最近 ~90 秒的:
  - 全部 CAN 报文 (所有总线/地址, 原始字节 hex)
  - carState / carControl / onroadEvents 关键字段
一旦命中任一故障判据:
  1) 继续再录 POST_TRIGGER_SEC 秒 (前90s + 后60s ≈ 前后各1分钟量级)
  2) dump 成 /data/byd_debug/events/<ts>_<类型>/dump.jsonl (自录)
  3) 同目录写 event.json (故障类型/触发时刻/一级线索) + report.txt (人类可读)
  4) 复制 loggerd 最近段做双保险
  5) 自动限量清理, 只保留最近 MAX_EVENTS 个事件目录, 防磁盘满

一期覆盖判据 (只做已在技术笔记验证过的可靠信号):
  横向:
    LAT1  EPS 锁死          真EPS(0x318,src<128) TorqueFailed(bit2) 0->1
    LAT2  转向被单方面切断   接管中(316 Active=1) 而 EPS CruiseActivated(bit1) 1->0
  纵向:
    LON2  ACC 意外退出       cruiseEnabled 1->0 (仅记录, 供分析是否人为cancel)
  系统:
    SYS1  关键进程消失       controlsd 进程数 0 (曾>0 后掉0)
  报警:
    ALERT onroadEvents 出现 alertStatus 高级别告警时也存证 (兜底, 覆盖 ACC 报错等)

State/开关文件:
    /data/byd_debug/enabled     存在=应运行 (App 建/删)
    /data/byd_debug/status.json 本脚本每2s写心跳(App 读)
    /data/byd_debug/events/<ts>_<类型>/  每个事件一个目录

用法 (App 或手动):
  cd /data/openpilot && source /usr/local/venv/bin/activate && export PYTHONPATH=/data/openpilot
  nohup python /data/byd_debug_watchdog.py > /data/byd_debug/watchdog.log 2>&1 &
  tail -f /data/byd_debug/watchdog.log
  pkill -f byd_debug_watchdog
"""
import os, time, glob, shutil, datetime, json
from collections import deque

# ---- CAN 地址 ----
A_316 = 0x316   # ACC_MPC_STATE (OP->EPS, 横向命令; src>=128 是我们发的)
A_318 = 0x318   # ACC_EPS_STATE (EPS->; src<128 是真EPS)

# ---- 目录/参数 ----
STATE_DIR   = "/data/byd_debug"
ENABLED_FILE = os.path.join(STATE_DIR, "enabled")
STATUS_FILE  = os.path.join(STATE_DIR, "status.json")
KEEP_DIR    = os.path.join(STATE_DIR, "events")   # 事件目录 (与 App DebugRepository EVENTS_DIR 一致)
REALDATA    = "/data/media/0/realdata"

BUFFER_SEC       = 90     # 触发前保留秒数
POST_TRIGGER_SEC = 60.0   # 触发后再录秒数 (前90+后60 ≈ 前后各1分钟)
MAX_EVENTS       = 20     # 最多保留事件目录数 (防磁盘满)
COOLDOWN_SEC     = 8.0    # 同类故障触发后冷却, 避免刷屏


def bit(b, n):
    return (b[n // 8] >> (n % 8)) & 1


def latest_segments(n=3):
    segs = sorted(glob.glob(os.path.join(REALDATA, "0*--*")),
                  key=lambda p: os.path.getmtime(p), reverse=True)
    return segs[:n]


def copy_loggerd_segments(dst_root):
    saved = []
    for seg in latest_segments(3):
        name = os.path.basename(seg)
        dst = os.path.join(dst_root, "loggerd_" + name)
        try:
            os.makedirs(dst, exist_ok=True)
            for f in ("rlog.zst", "rlog", "qlog.zst", "qlog"):
                src = os.path.join(seg, f)
                if os.path.isfile(src):
                    shutil.copy2(src, os.path.join(dst, f))
            saved.append(name)
        except Exception as e:
            print("  [warn] 复制loggerd段 %s 失败: %s" % (name, e), flush=True)
    return saved


def cleanup_old_events():
    """只保留最近 MAX_EVENTS 个事件目录, 删掉更老的。"""
    try:
        dirs = sorted(glob.glob(os.path.join(KEEP_DIR, "2*")),
                      key=lambda p: os.path.getmtime(p), reverse=True)
        for old in dirs[MAX_EVENTS:]:
            shutil.rmtree(old, ignore_errors=True)
            print("  [cleanup] 删除旧事件 %s" % os.path.basename(old), flush=True)
    except Exception as e:
        print("  [warn] 清理失败: %s" % e, flush=True)


def count_proc(name):
    """返回匹配进程数 (不含自身)。"""
    try:
        out = os.popen("pgrep -f '%s' | wc -l" % name).read().strip()
        return int(out or "0")
    except Exception:
        return -1


FAULT_NAMES = {
    "LAT1": "EPS锁死(TorqueFailed)",
    "LAT2": "转向被单方面切断(Cru掉线)",
    "LON2": "ACC意外退出",
    "SYS1": "关键进程消失",
    "ALERT": "OP告警(onroadEvents)",
}


def write_status(running=True, **extra):
    data = {"running": running, "ts": time.time(),
            "time": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
    data.update(extra)
    try:
        tmp = STATUS_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(data, f, ensure_ascii=False)
        os.replace(tmp, STATUS_FILE)
    except Exception:
        pass


def write_event_files(dst, fault, trigger_time, clue, buf):
    """写 dump.jsonl / event.json / report.txt"""
    try:
        with open(os.path.join(dst, "dump.jsonl"), "w") as f:
            for (t, kind, payload) in list(buf):
                f.write(json.dumps({"t": t, "k": kind, "d": payload}) + "\n")
    except Exception as e:
        print("  [warn] 写dump失败: %s" % e, flush=True)

    n = len(buf)
    span = (buf[-1][0] - buf[0][0]) if buf else 0
    ev = {
        "fault": fault,
        "faultName": FAULT_NAMES.get(fault, fault),
        "triggerTime": trigger_time,
        "triggerTimeStr": datetime.datetime.fromtimestamp(trigger_time).strftime("%Y-%m-%d %H:%M:%S"),
        "clue": clue,
        "records": n,
        "spanSec": round(span, 1),
        "bufferSec": BUFFER_SEC,
        "postSec": POST_TRIGGER_SEC,
    }
    try:
        with open(os.path.join(dst, "event.json"), "w") as f:
            json.dump(ev, f, ensure_ascii=False, indent=2)
    except Exception:
        pass
    try:
        with open(os.path.join(dst, "report.txt"), "w") as f:
            f.write("=" * 50 + "\n")
            f.write("BYD DEBUG 故障事件报告\n")
            f.write("=" * 50 + "\n")
            f.write("故障类型 : %s (%s)\n" % (fault, FAULT_NAMES.get(fault, fault)))
            f.write("触发时刻 : %s\n" % ev["triggerTimeStr"])
            f.write("一级线索 : %s\n" % clue)
            f.write("数据跨度 : 前~%ds + 后~%ds, 共%d条记录(跨度%.0fs)\n"
                    % (BUFFER_SEC, POST_TRIGGER_SEC, n, span))
            f.write("\n数据文件 : dump.jsonl (全量CAN+carState+carControl)\n")
            f.write("分析建议 : 下载 dump.jsonl, 用 byd_dump_analyze.py 或AI逐帧复现触发前时序\n")
    except Exception:
        pass


def main():
    os.makedirs(STATE_DIR, exist_ok=True)
    os.makedirs(KEEP_DIR, exist_ok=True)
    print("=== BYD DEBUG 多故障看门狗 启动 ===", flush=True)
    print("缓冲%ds; 触发再录%ds; 最多留%d事件; 状态目录%s"
          % (BUFFER_SEC, POST_TRIGGER_SEC, MAX_EVENTS, STATE_DIR), flush=True)

    import cereal.messaging as messaging
    sm = messaging.SubMaster(['can', 'carState', 'carControl', 'onroadEvents'])

    buf = deque()
    def trim(now):
        while buf and buf[0][0] < now - BUFFER_SEC:
            buf.popleft()

    tqf_prev = None
    cur_tqf = None
    cru_prev = None
    lkas_active_now = 0
    last_out = 0
    cruise_en_prev = None
    sys_seen_alive = False
    last_proc_check = 0

    last_beat = 0
    trigger_count = 0
    dumping_until = 0
    pending_dst = None
    pending_fault = None
    pending_clue = None
    pending_trigtime = 0
    last_trigger_by_fault = {}

    cleanup_old_events()

    def can_trigger(fault, now):
        last = last_trigger_by_fault.get(fault, 0)
        return (dumping_until == 0) and (now - last > COOLDOWN_SEC)

    def start_dump(fault, clue, now):
        nonlocal trigger_count, dumping_until, pending_dst, pending_fault, pending_clue, pending_trigtime
        trigger_count += 1
        last_trigger_by_fault[fault] = now
        ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        pending_dst = os.path.join(KEEP_DIR, "%s_%s" % (ts, fault))
        os.makedirs(pending_dst, exist_ok=True)
        pending_fault = fault
        pending_clue = clue
        pending_trigtime = now
        dumping_until = now + POST_TRIGGER_SEC
        print("\n*** [%s] 故障 %s(%s)! 第%d次 | %s | 继续录%ds ***"
              % (datetime.datetime.now().strftime("%H:%M:%S"), fault,
                 FAULT_NAMES.get(fault, fault), trigger_count, clue, POST_TRIGGER_SEC), flush=True)

    while True:
        enabled = os.path.exists(ENABLED_FILE)
        if not enabled:
            write_status(running=True, active=False, triggers=trigger_count,
                         buffer=len(buf), note="待命(未开启监测)")
            time.sleep(1.0)
            continue

        sm.update(100)
        now = time.time()

        if sm.updated['can']:
            can_list = []
            for c in sm['can']:
                try:
                    b = bytes(c.dat)
                except Exception:
                    continue
                can_list.append((c.address, c.src, b.hex()))
                if len(b) < 8:
                    continue
                if c.address == A_318 and (c.src is None or c.src < 128):
                    tf = bit(b, 2)
                    cru = bit(b, 1)
                    cur_tqf = tf
                    if tqf_prev == 0 and tf == 1 and can_trigger("LAT1", now):
                        start_dump("LAT1", "TorqueFailed 0->1, 锁死前扭矩=%d" % last_out, now)
                    tqf_prev = tf
                    if cru_prev == 1 and cru == 0 and lkas_active_now == 1 and can_trigger("LAT2", now):
                        start_dump("LAT2", "接管中 CruiseActivated 1->0 (EPS单方面切断)", now)
                    cru_prev = cru
                elif c.address == A_316 and c.src >= 128:
                    lkas_active_now = bit(b, 28)
                    if lkas_active_now == 1:
                        o = (b[2] | (b[3] << 8)) & 0x7FF
                        if o > 1023:
                            o -= 2048
                        last_out = o
            if can_list:
                buf.append((now, 'can', can_list))

        if sm.updated['carState']:
            cs = sm['carState']
            cruise_en = bool(cs.cruiseState.enabled)
            buf.append((now, 'carState', {
                'vEgo': float(cs.vEgo),
                'aEgo': float(cs.aEgo),
                'cruiseEnabled': cruise_en,
                'cruiseAvailable': bool(cs.cruiseState.available),
                'steeringAngle': float(cs.steeringAngleDeg),
                'steeringTorque': float(cs.steeringTorque),
                'steerFaultTemporary': bool(cs.steerFaultTemporary),
                'steerFaultPermanent': bool(cs.steerFaultPermanent),
                'standstill': bool(cs.standstill),
            }))
            if cruise_en_prev is True and cruise_en is False and can_trigger("LON2", now):
                start_dump("LON2", "cruiseEnabled 1->0 (ACC退出, 需分析是否人为cancel)", now)
            cruise_en_prev = cruise_en

        if sm.updated['carControl']:
            cc = sm['carControl']
            buf.append((now, 'carControl', {
                'latActive': bool(cc.latActive),
                'longActive': bool(cc.longActive),
                'enabled': bool(cc.enabled),
                'accel': float(cc.actuators.accel),
            }))

        if sm.updated['onroadEvents']:
            names = []
            for e in sm['onroadEvents']:
                try:
                    names.append(str(e.name))
                except Exception:
                    pass
            if names:
                buf.append((now, 'onroadEvents', names))

        trim(now)

        if now - last_proc_check >= 2.0:
            last_proc_check = now
            n_ctrl = count_proc("controlsd")
            if n_ctrl > 0:
                sys_seen_alive = True
            if sys_seen_alive and n_ctrl == 0 and can_trigger("SYS1", now):
                start_dump("SYS1", "controlsd 进程消失 (曾存活)", now)

        if dumping_until and now >= dumping_until:
            dst = pending_dst
            write_event_files(dst, pending_fault, pending_trigtime, pending_clue, buf)
            saved = copy_loggerd_segments(dst)
            print("  >>> 事件已保存: %s (loggerd双保险:%s)" % (dst, saved), flush=True)
            cleanup_old_events()
            dumping_until = 0
            pending_dst = pending_fault = pending_clue = None

        if now - last_beat >= 2:
            last_beat = now
            tqf_s = {None: "?", 0: "OK", 1: "LOCKED"}.get(cur_tqf, "?")
            write_status(running=True, active=True, triggers=trigger_count,
                         buffer=len(buf), eps=tqf_s,
                         dumping=bool(dumping_until),
                         note="监测中")
            print("[%s] 监测中 EPS=%s | 缓冲%d条 | 已触发%d次 %s"
                  % (datetime.datetime.now().strftime("%H:%M:%S"), tqf_s, len(buf),
                     trigger_count, "(录制尾巴中)" if dumping_until else ""), flush=True)


if __name__ == "__main__":
    main()
