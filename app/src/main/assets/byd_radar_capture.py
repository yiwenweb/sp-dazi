#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BYD 雷达距离标定抓取脚本 (C3 常驻) —— 配合手机 App「雷达导航」模块

用途: 方案1(卷尺对照法)破解前向雷达距离编码。
原理: 车静止, 前方摆一个已知距离的反射目标(车/角反射器), 雷达对该静止目标
      输出稳定数据。App 端在每个已知距离(卷尺量)按"打点", 脚本把当前雷达帧
      和这个精确距离绑定, 生成 (真实距离 -> 雷达字节) 的标定表。之后离线即可
      解出距离编码(哪怕非线性)。

实时订阅 can, 内存缓冲全部 bus1 雷达帧(0x340~0x3DF)。
通过标志文件和 App 通信(不改任何 openpilot 参数):
  /data/byd_radar/enabled        存在=正在采集(App建/删)
  /data/byd_radar/mark           App写入一行"距离(米)"即打一个标定点
  /data/byd_radar/status.json    心跳(App读: 是否有目标/当前帧计数)
  /data/byd_radar/marks/<n>_<dist>m.jsonl   每个标定点的雷达帧快照

用法(App自动执行, 也可手动):
  cd /data/openpilot && source /usr/local/venv/bin/activate && export PYTHONPATH=/data/openpilot
  nohup python /data/byd_radar_capture.py > /data/byd_radar/capture.log 2>&1 &
  # 打点: echo "20.0" > /data/byd_radar/mark
  pkill -f byd_radar_capture
"""
import os, time, json, glob
from collections import defaultdict, Counter

STATE_DIR   = "/data/byd_radar"
ENABLED     = os.path.join(STATE_DIR, "enabled")
MARK        = os.path.join(STATE_DIR, "mark")
STATUS      = os.path.join(STATE_DIR, "status.json")
MARKS_DIR   = os.path.join(STATE_DIR, "marks")

# bus1 上所有前向雷达地址
RADAR_LO, RADAR_HI = 0x340, 0x3DF
# 打点时抓取多少秒的雷达帧(静止目标, 这段时间数据应稳定)
MARK_WINDOW_SEC = 3.0


def hx(b):
    return b.hex()


def write_status(**kw):
    d = {"ts": time.time(), "time": time.strftime("%H:%M:%S")}
    d.update(kw)
    try:
        tmp = STATUS + ".tmp"
        with open(tmp, "w") as f:
            json.dump(d, f, ensure_ascii=False)
        os.replace(tmp, STATUS)
    except Exception:
        pass


def main():
    os.makedirs(STATE_DIR, exist_ok=True)
    os.makedirs(MARKS_DIR, exist_ok=True)
    print("=== BYD 雷达距离标定抓取 启动 ===", flush=True)

    import cereal.messaging as messaging
    sm = messaging.SubMaster(['can'])

    # 滚动缓冲最近 MARK_WINDOW 秒的雷达帧
    buf = []  # (t, addr, hex)
    mark_count = 0
    last_beat = 0

    while True:
        if not os.path.exists(ENABLED):
            write_status(running=True, active=False, note="待命(未开始采集)",
                         marks=mark_count)
            time.sleep(1.0)
            continue

        sm.update(100)
        now = time.time()

        # 采集 bus1 雷达帧
        if sm.updated['can']:
            for c in sm['can']:
                if c.src != 1:
                    continue
                if not (RADAR_LO <= c.address <= RADAR_HI):
                    continue
                try:
                    b = bytes(c.dat)
                except Exception:
                    continue
                buf.append((now, int(c.address), hx(b)))
            # 修剪缓冲
            cutoff = now - MARK_WINDOW_SEC
            if len(buf) > 20000:
                buf = [x for x in buf if x[0] >= cutoff]

        # 检查打点请求
        if os.path.exists(MARK):
            try:
                with open(MARK) as f:
                    dist_str = f.read().strip()
                os.remove(MARK)
                dist = float(dist_str)
                mark_count += 1
                # 抓取最近 MARK_WINDOW 秒的帧
                cutoff = now - MARK_WINDOW_SEC
                snap = [x for x in buf if x[0] >= cutoff]
                fn = os.path.join(MARKS_DIR, "%02d_%.1fm.jsonl" % (mark_count, dist))
                with open(fn, "w") as f:
                    for (t, addr, h) in snap:
                        f.write(json.dumps({"t": int(t*1e9), "addr": addr,
                                            "hex": h, "dist_m": dist}) + "\n")
                # 统计这段有目标的地址(后6字节非全FF)
                addrs = Counter(addr for (t, addr, h) in snap if h[6:] != 'ffffffffff')
                print("*** 打点#%d: %.1fm, 抓取%d帧, 有目标地址=%s -> %s ***"
                      % (mark_count, dist, len(snap),
                         [hex(a) for a, _ in addrs.most_common(8)], fn), flush=True)
            except Exception as e:
                print("打点失败: %s" % e, flush=True)

        # 心跳
        if now - last_beat >= 1.0:
            last_beat = now
            cutoff = now - 1.0
            recent = [x for x in buf if x[0] >= cutoff]
            has_target = sum(1 for (t, a, h) in recent if h[6:] != 'ffffffffff')
            target_addrs = sorted(set(a for (t, a, h) in recent if h[6:] != 'ffffffffff'))
            write_status(running=True, active=True, marks=mark_count,
                         recent_frames=len(recent), target_frames=has_target,
                         target_addrs=[hex(a) for a in target_addrs[:12]],
                         note="采集中" + ("(有目标)" if has_target > 3 else "(未见目标)"))


if __name__ == "__main__":
    main()
