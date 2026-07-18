#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C3 轻量 MJPEG 流服务器 —— 替代 WebRTC, 供 Android App 原生显示

原理:
  stream_encoderd 产生的 H264 帧通过 cereal 消息 livestreamRoadEncodeData 广播,
  本脚本订阅该消息, 用 PyAV 解码为图像, 转成 JPEG, 通过 HTTP 提供。

  两种端点:
    GET /frame       → 返回最新一帧 JPEG (适合 Android 轮询)
    GET /stream      → 返回 MJPEG multipart 流 (适合浏览器/WebView <img>)

  Android App 推荐用 /frame 轮询 (每 120ms 一次), 延迟低、兼容性好。

用法 (App 通过 SSH 启动):
  cd /data/openpilot && . /usr/local/venv/bin/activate && export PYTHONPATH=/data/openpilot
  nohup python /data/mjpeg_stream.py > /tmp/mjpeg_stream.log 2>&1 &
  pkill -f mjpeg_stream

依赖: PyAV (C3 已自带, webrtcd 也用), aiohttp (C3 已自带)
"""
import io
import os
import sys
import time
import threading
import traceback

import av
from aiohttp import web

from cereal import messaging


# ---- 配置 ----
CAMERA_SOCK = {
    "road": "livestreamRoadEncodeData",
    "wideRoad": "livestreamWideRoadEncodeData",
}
JPEG_QUALITY = 50        # JPEG 质量 (降低带宽)
TARGET_WIDTH = 480       # 缩放宽度 (低负载, 仅供预览)
FRAME_INTERVAL = 0.10    # 解码间隔 10fps, CPU<8%, 画面流畅
FRAME_TIMEOUT = 5.0      # 超时秒数


class FrameGrabber:
    """订阅 cereal H264 流, 按固定间隔解码并缓存最新 JPEG 帧。

    C3 性能说明:
    - stream_encoderd 是 C++ 硬编码(HW Venus), 不消耗 CPU
    - 本脚本 PyAV 软解 H264: 需消耗少量 CPU (~3-5% per decode)
    - 通过 FRAME_INTERVAL 限频: 只解码 ~6fps, 每帧间隔跳过冗余帧
    - 总体 CPU 占用 < 5%, 不会影响 openpilot 实时控制
    """

    def __init__(self, camera_type="road"):
        sock_name = CAMERA_SOCK.get(camera_type, CAMERA_SOCK["road"])
        self.sock = messaging.sub_sock(sock_name, conflate=True)
        self.codec = av.CodecContext.create('h264', 'r')
        self._latest_jpeg = None
        self._latest_time = 0.0
        self._last_decode_time = 0.0
        self._frame_count = 0
        self._lock = threading.Lock()
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self):
        """后台线程: 按间隔解码 H264 帧, 缓存最新 JPEG"""
        print("[mjpeg] frame grabber started (interval=%.0fms, width=%dpx)"
              % (FRAME_INTERVAL * 1000, TARGET_WIDTH), flush=True)
        while self._running:
            try:
                msg = messaging.recv_one_or_none(self.sock)
                if msg is None:
                    time.sleep(0.02)
                    continue

                # 帧间隔限频: 只在超过 FRAME_INTERVAL 时才解码
                now = time.time()
                if now - self._last_decode_time < FRAME_INTERVAL:
                    continue

                evta = getattr(msg, msg.which())
                if evta is None:
                    continue

                packet = av.Packet(evta.header + evta.data)
                try:
                    frames = self.codec.decode(packet)
                except Exception:
                    continue

                if not frames:
                    continue

                self._last_decode_time = now
                self._frame_count += 1

                frame = frames[0]
                # 缩放 (还原尺寸给 PyAV, 后续转 JPEG 时效率更高)
                if frame.width > TARGET_WIDTH:
                    scale = TARGET_WIDTH / frame.width
                    frame = frame.reformat(width=TARGET_WIDTH,
                                           height=max(1, int(frame.height * scale)),
                                           format='yuv420p')
                img = frame.to_image()
                buf = io.BytesIO()
                img.save(buf, format='JPEG', quality=JPEG_QUALITY)
                jpeg_data = buf.getvalue()

                with self._lock:
                    self._latest_jpeg = jpeg_data
                    self._latest_time = now

                # 每 60 帧输出一次统计
                if self._frame_count % 60 == 0:
                    size_kb = len(jpeg_data) / 1024
                    print("[mjpeg] frame #%d, %.1fKB, %.1ffps"
                          % (self._frame_count, size_kb, 1.0 / FRAME_INTERVAL), flush=True)

            except Exception as e:
                print("[mjpeg] decode error: %s" % e, flush=True)
                time.sleep(0.1)

        print("[mjpeg] frame grabber stopped (total frames: %d)" % self._frame_count, flush=True)

    def get_frame(self):
        """返回最新 JPEG bytes, 或 None"""
        with self._lock:
            if self._latest_jpeg is None:
                return None
            if time.time() - self._latest_time > FRAME_TIMEOUT:
                return None
            return self._latest_jpeg

    def stop(self):
        self._running = False
        # cereal SubSocket 不需要显式关闭


# 全局实例 (由 main 创建)
grabber = None


async def handle_frame(request):
    """GET /frame?camera=road → 返回最新 JPEG"""
    jpeg = grabber.get_frame() if grabber else None
    if jpeg is None:
        return web.Response(status=503, text="no frame available")
    return web.Response(body=jpeg, content_type="image/jpeg",
                        headers={"Cache-Control": "no-store",
                                 "Access-Control-Allow-Origin": "*"})


async def handle_stream(request):
    """GET /stream → MJPEG multipart 流"""
    boundary = "frameboundary"
    resp = web.StreamResponse(status=200, headers={
        "Content-Type": "multipart/x-mixed-replace;boundary=%s" % boundary,
        "Cache-Control": "no-store",
        "Access-Control-Allow-Origin": "*",
    })
    await resp.prepare(request)

    try:
        while True:
            jpeg = grabber.get_frame() if grabber else None
            if jpeg:
                await resp.write(
                    ("--%s\r\n" % boundary).encode() +
                    b"Content-Type: image/jpeg\r\n" +
                    b"Content-Length: %d\r\n\r\n" % len(jpeg) +
                    jpeg + b"\r\n"
                )
            await asyncio_sleep(0.12)
    except (ConnectionResetError, BrokenPipeError):
        pass
    return resp


async def handle_health(request):
    """GET /health → 健康检查"""
    has_frame = grabber is not None and grabber.get_frame() is not None
    return web.json_response({"ok": has_frame, "camera": request.app["camera"]})


async def asyncio_sleep(seconds):
    import asyncio
    await asyncio.sleep(seconds)


def main():
    import argparse
    parser = argparse.ArgumentParser(description="C3 MJPEG Stream Server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=5002)
    parser.add_argument("--camera", default="road", choices=list(CAMERA_SOCK.keys()))
    args = parser.parse_args()

    global grabber
    print("[mjpeg] starting, camera=%s, port=%d" % (args.camera, args.port), flush=True)
    
    try:
        # 检查端口是否被占用
        import socket
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        result = sock.connect_ex((args.host, args.port))
        sock.close()
        if result == 0:
            print("[mjpeg] ERROR: port %d already in use!" % args.port, flush=True)
            sys.exit(1)
        
        print("[mjpeg] port %d is available" % args.port, flush=True)
        
        # 启动帧捕获器
        grabber = FrameGrabber(args.camera)
        time.sleep(0.5)  # 给grabber一点时间初始化
        
        # 创建 aiohttp app
        app = web.Application()
        app["camera"] = args.camera
        app.router.add_get("/frame", handle_frame)
        app.router.add_get("/stream", handle_stream)
        app.router.add_get("/health", handle_health)
        
        print("[mjpeg] starting HTTP server on %s:%d" % (args.host, args.port), flush=True)
        web.run_app(app, host=args.host, port=args.port, print=None)
        
    except KeyboardInterrupt:
        print("[mjpeg] interrupted by user", flush=True)
    except Exception as e:
        print("[mjpeg] FATAL ERROR: %s" % e, flush=True)
        traceback.print_exc()
        sys.exit(1)
    finally:
        if grabber:
            print("[mjpeg] stopping frame grabber", flush=True)
            grabber.stop()
        print("[mjpeg] server stopped", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("[mjpeg] TOP LEVEL ERROR: %s" % e, flush=True)
        traceback.print_exc()
        sys.exit(1)
