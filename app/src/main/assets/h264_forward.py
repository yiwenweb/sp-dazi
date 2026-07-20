#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C3 H264 转发服务器（零 CPU 占用）

只负责转发 stream_encoderd 的 H264 帧，不做任何解码。
客户端（Android）负责硬件解码 H264，清晰流畅。

原理：
  stream_encoderd（C++ 硬件编码）→ cereal 消息（H264）→ 本脚本转发 → WebSocket → Android MediaCodec 硬解

关键设计（避免运动时花屏/马赛克）：
  - H264 是帧间差分编码：丢一个 P 帧, 后续画面就会花屏, 直到下一个关键帧。
  - 因此这里【不丢帧】: conflate=False 收全部帧, 每个客户端一条有序队列, 按编码器原速转发。
  - 缓存 SPS/PPS 头, 保证任何时刻连入的客户端第一帧都是【带头的关键帧】, 解码器能干净起步。
  - 队列溢出(客户端跟不上)时, 清空并要求客户端重新等待关键帧, 避免持续花屏。

用法：
  cd /data/openpilot && . /usr/local/venv/bin/activate && export PYTHONPATH=/data/openpilot
  nohup python3 /data/spapp/spyl/h264_forward.py --port 5004 --host 0.0.0.0 > /data/spapp/spyl/log/h264_forward.log 2>&1 &

依赖：aiohttp（C3 已自带）、cereal（openpilot 自带）
"""
import asyncio
import json
import threading
import time
import traceback

import aiohttp
from aiohttp import web

from cereal import messaging


# ---- 配置 ----
CAMERA_SOCK = {
    "road": "livestreamRoadEncodeData",
    "wideRoad": "livestreamWideRoadEncodeData",
}
DEFAULT_PORT = 5004
QUEUE_MAXSIZE = 240   # 每客户端队列上限(帧). 约十几秒缓冲, 溢出则重新同步到关键帧

# 队列里的"重新同步"标记: 发生丢帧后通知客户端重新等待关键帧
GAP = object()


def contains_idr(data: bytes) -> bool:
    """扫描 H264 Annex-B 码流, 判断是否含 IDR 关键帧 (NAL type 5) 或 SPS (type 7)。"""
    n = len(data)
    i = 0
    while i + 4 < n:
        if data[i] == 0 and data[i + 1] == 0:
            if data[i + 2] == 1:
                nt = data[i + 3] & 0x1F
                if nt == 5 or nt == 7:
                    return True
                i += 3
                continue
            elif data[i + 2] == 0 and data[i + 3] == 1:
                if i + 4 < n and ((data[i + 4] & 0x1F) == 5 or (data[i + 4] & 0x1F) == 7):
                    return True
                i += 4
                continue
        i += 1
    return False


class CameraBroadcaster:
    """订阅某路摄像头 H264, 按序广播给所有订阅的 WebSocket 客户端(不丢帧)。"""

    def __init__(self, camera_type: str, loop: asyncio.AbstractEventLoop):
        self.camera_type = camera_type
        self.loop = loop
        sock_name = CAMERA_SOCK.get(camera_type, CAMERA_SOCK["road"])
        self.sock = messaging.sub_sock(sock_name, conflate=False)  # 不丢帧: 收全部
        self.subscribers = set()      # set[asyncio.Queue]
        self.header = b""             # 缓存的 SPS/PPS 编码头
        self._lock = threading.Lock()
        self._running = True
        self._frame_count = 0
        self._last_stat = time.time()
        self._thread = threading.Thread(target=self._collect_loop, daemon=True)
        self._thread.start()
        print(f"[h264] broadcaster started for {camera_type}", flush=True)

    def add_subscriber(self, q: "asyncio.Queue"):
        with self._lock:
            self.subscribers.add(q)

    def remove_subscriber(self, q: "asyncio.Queue"):
        with self._lock:
            self.subscribers.discard(q)

    def _push(self, q: "asyncio.Queue", item):
        """在事件循环线程上执行: 入队; 满则清空并插入 GAP 标记(要求重新同步)。"""
        if q.full():
            try:
                while True:
                    q.get_nowait()
            except asyncio.QueueEmpty:
                pass
            try:
                q.put_nowait(GAP)
            except asyncio.QueueFull:
                return
        try:
            q.put_nowait(item)
        except asyncio.QueueFull:
            pass

    def _collect_loop(self):
        """后台线程: 收 H264 帧, 缓存头, 按序分发给所有客户端队列。"""
        while self._running:
            try:
                msg = messaging.recv_one_or_none(self.sock)
                if msg is None:
                    time.sleep(0.003)
                    continue

                evta = getattr(msg, msg.which())
                if evta is None:
                    continue

                data = bytes(evta.data)
                hdr = bytes(evta.header) if evta.header else b""
                if hdr:
                    self.header = hdr  # 缓存最新编码头(SPS/PPS)

                if len(data) < 4:
                    continue

                # 关键帧判定: 带编码头 或 码流含 IDR
                keyframe = bool(hdr) or contains_idr(data)

                # 关键帧一律拼上缓存头, 保证客户端从任意关键帧都能干净起步
                if keyframe and self.header:
                    frame = self.header + data
                else:
                    frame = data

                item = (frame, keyframe)

                self._frame_count += 1
                now = time.time()
                if now - self._last_stat >= 2.0:
                    fps = self._frame_count / (now - self._last_stat)
                    with self._lock:
                        nsub = len(self.subscribers)
                    print(f"[h264] {self.camera_type}: {fps:.1f}fps, {len(frame)/1024:.1f}KB/frame, clients={nsub}", flush=True)
                    self._frame_count = 0
                    self._last_stat = now

                with self._lock:
                    subs = list(self.subscribers)
                for q in subs:
                    self.loop.call_soon_threadsafe(self._push, q, item)

            except Exception as e:
                print(f"[h264] {self.camera_type} collect error: {e}", flush=True)
                traceback.print_exc()
                time.sleep(0.05)

    def stop(self):
        self._running = False
        if self._thread.is_alive():
            self._thread.join(timeout=2.0)


# ---- 全局状态 ----
broadcasters = {}       # camera_type -> CameraBroadcaster
connected_clients = 0


def get_broadcaster(camera_type: str, loop: asyncio.AbstractEventLoop) -> CameraBroadcaster:
    if camera_type not in broadcasters:
        broadcasters[camera_type] = CameraBroadcaster(camera_type, loop)
    return broadcasters[camera_type]


async def websocket_handler(request: web.Request):
    """
    WebSocket 连接处理

    协议：
      1. 客户端连接：ws://C3_IP:5004/ws?camera=road
      2. 服务器对每一帧: 先发 JSON 头 {"type":"frame","size":N}, 再发 N 字节 H264 二进制
      3. 客户端先收满第一个【关键帧】再开始解码, 中途丢帧会收到 gap 提示后重新等待关键帧
    """
    global connected_clients

    camera_type = request.query.get("camera", "road")
    if camera_type not in CAMERA_SOCK:
        camera_type = "road"

    loop = asyncio.get_running_loop()
    bc = get_broadcaster(camera_type, loop)

    ws = web.WebSocketResponse(heartbeat=30.0)
    await ws.prepare(request)

    q: "asyncio.Queue" = asyncio.Queue(maxsize=QUEUE_MAXSIZE)
    bc.add_subscriber(q)
    connected_clients += 1
    print(f"[h264] client connected: {camera_type}, total: {connected_clients}", flush=True)

    got_keyframe = False
    try:
        while not ws.closed:
            item = await q.get()

            if item is GAP:
                # 发生过丢帧, 重新等待下一个关键帧, 避免持续花屏
                got_keyframe = False
                continue

            frame, keyframe = item

            # 起步/重同步: 必须从关键帧开始, 丢弃之前的 P 帧
            if not got_keyframe:
                if not keyframe:
                    continue
                got_keyframe = True

            try:
                await ws.send_str(json.dumps({
                    "type": "frame",
                    "camera": camera_type,
                    "size": len(frame),
                }))
                await ws.send_bytes(frame)
            except Exception as e:
                print(f"[h264] send error: {e}", flush=True)
                break

    except asyncio.CancelledError:
        pass
    except Exception as e:
        print(f"[h264] client error: {e}", flush=True)
    finally:
        bc.remove_subscriber(q)
        connected_clients -= 1
        print(f"[h264] client disconnected: {camera_type}, total: {connected_clients}", flush=True)

    return ws


async def health_handler(request: web.Request):
    """健康检查"""
    return web.json_response({
        "ok": True,
        "broadcasters": list(broadcasters.keys()),
        "clients": connected_clients,
        "cameras": list(CAMERA_SOCK.keys()),
    })


def create_app() -> web.Application:
    app = web.Application()
    app.router.add_get("/ws", websocket_handler)
    app.router.add_get("/health", health_handler)
    return app


def main():
    import argparse
    parser = argparse.ArgumentParser(description="C3 H264 Forward Server")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="监听端口")
    args = parser.parse_args()

    print(f"[h264] starting server on {args.host}:{args.port}", flush=True)
    print(f"[h264] cameras: {list(CAMERA_SOCK.keys())}", flush=True)

    # 检查端口是否被占用
    import socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    result = sock.connect_ex((args.host, args.port))
    sock.close()
    if result == 0:
        print(f"[h264] ERROR: port {args.port} already in use!", flush=True)
        exit(1)

    print(f"[h264] port {args.port} is available", flush=True)

    app = create_app()
    web.run_app(app, host=args.host, port=args.port, print=None)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("[h264] interrupted by user", flush=True)
    except Exception as e:
        print(f"[h264] FATAL ERROR: {e}", flush=True)
        traceback.print_exc()
        exit(1)
    finally:
        for b in broadcasters.values():
            b.stop()
        print("[h264] server stopped", flush=True)
