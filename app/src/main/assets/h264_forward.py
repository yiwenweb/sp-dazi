#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C3 H264 转发服务器（零 CPU 占用）

只负责转发 stream_encoderd 的 H264 帧，不做任何解码。
客户端（Android）负责硬件解码 H264，清晰流畅。

原理：
  stream_encoderd（C++ 硬件编码）→ cereal 消息（H264）→ 本脚本转发 → WebSocket → Android MediaCodec 硬解

优势：
  - C3 端零 CPU 占用（只转发，不解码）
  - 可以发原分辨率 H264 帧（1280x720）
  - Android 端用硬件解码器，流畅清晰
  - 延迟极低（少了解码 + 编码 JPEG 的步骤）

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


class H264Forwarder:
    """转发 H264 帧，不做解码（零 CPU 占用）"""
    
    def __init__(self, camera_type: str):
        self.camera_type = camera_type
        self.sock_name = CAMERA_SOCK.get(camera_type, CAMERA_SOCK["road"])
        self.sock = messaging.sub_sock(self.sock_name, conflate=True)
        self._latest_frame = None
        self._frame_count = 0
        self._running = True
        self._last_frame_time = 0.0
        
        # 后台线程：只收集最新 H264 帧，不做解码
        self._thread = threading.Thread(target=self._collect_loop, daemon=True)
        self._thread.start()
        print(f"[h264] forwarder started for {camera_type}", flush=True)
    
    def _collect_loop(self):
        """后台线程：收集最新 H264 帧（不解码）"""
        while self._running:
            try:
                msg = messaging.recv_one_or_none(self.sock)
                if msg is None:
                    time.sleep(0.01)
                    continue
                
                # 取出消息体（roadEncodeData 或 wideRoadEncodeData）
                evta = getattr(msg, msg.which())
                if evta is None:
                    continue
                
                # 直接取 H264 数据（header + data），不做解码
                h264_data = evta.header + evta.data
                
                if len(h264_data) < 10:
                    continue
                
                self._latest_frame = h264_data
                self._frame_count += 1
                
                now = time.time()
                if now - self._last_frame_time >= 2.0:
                    # 每 2 秒输出一次统计
                    fps = self._frame_count / (now - self._last_frame_time) if (now - self._last_frame_time) > 0 else 0
                    size_kb = len(h264_data) / 1024
                    print(f"[h264] {self.camera_type}: frame #{self._frame_count}, {size_kb:.1f}KB, {fps:.1f}fps", flush=True)
                    self._frame_count = 0
                    self._last_frame_time = now
                
            except Exception as e:
                print(f"[h264] {self.camera_type} collect error: {e}", flush=True)
                traceback.print_exc()
                time.sleep(0.1)
    
    def get_latest_frame(self) -> bytes | None:
        """获取最新 H264 帧"""
        return self._latest_frame
    
    def stop(self):
        self._running = False
        if self._thread.is_alive():
            self._thread.join(timeout=2.0)


# ---- 全局状态 ----
forwarders = {}  # camera_type -> H264Forwarder
connected_clients = 0  # 当前连接的客户端数


async def websocket_handler(request: web.Request):
    """
    WebSocket 连接处理
    
    协议：
    1. 客户端连接：ws://C3_IP:5004/ws?camera=road
    2. 服务器推送：
       - 先发送 JSON 消息头：{"type":"frame","camera":"road","size":12345}
       - 再发送 H264 数据（二进制）
    3. 客户端解析 JSON 头，知道接下来要收多少字节 H264 数据
    """
    global connected_clients
    
    camera_type = request.query.get("camera", "road")
    if camera_type not in CAMERA_SOCK:
        camera_type = "road"
    
    # 确保编码器已创建
    if camera_type not in forwarders:
        forwarders[camera_type] = H264Forwarder(camera_type)
    
    connected_clients += 1
    print(f"[h264] client connected: {camera_type}, total clients: {connected_clients}", flush=True)
    
    ws = web.WebSocketResponse(heartbeat=30.0)  # 30 秒心跳，防止连接超时
    await ws.prepare(request)
    
    try:
        while True:
            frame = forwarders[camera_type].get_latest_frame()
            if frame is not None and len(frame) > 0:
                try:
                    # 发送帧头（JSON）
                    header = json.dumps({
                        "type": "frame",
                        "camera": camera_type,
                        "size": len(frame)
                    })
                    await ws.send_str(header)
                    
                    # 发送 H264 数据（二进制）
                    await ws.send_bytes(frame)
                except Exception as e:
                    print(f"[h264] send error: {e}", flush=True)
                    break
            else:
                # 没有帧时发心跳
                try:
                    await ws.send_str(json.dumps({"type": "heartbeat", "camera": camera_type}))
                except Exception:
                    break
            
            # 控制推送频率（~12fps）
            await asyncio.sleep(0.08)
    
    except asyncio.CancelledError:
        pass
    except Exception as e:
        print(f"[h264] client error: {e}", flush=True)
    finally:
        connected_clients -= 1
        print(f"[h264] client disconnected: {camera_type}, total clients: {connected_clients}", flush=True)


async def health_handler(request: web.Request):
    """健康检查"""
    return web.json_response({
        "ok": True,
        "forwarders": list(forwarders.keys()),
        "clients": connected_clients,
        "cameras": list(CAMERA_SOCK.keys())
    })


def create_app() -> web.Application:
    """创建 aiohttp 应用"""
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
        # 清理
        for f in forwarders.values():
            f.stop()
        print("[h264] server stopped", flush=True)
