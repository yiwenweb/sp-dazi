#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C3 HUD数据服务器 - 订阅openpilot cereal消息并通过HTTP提供JSON

订阅的消息:
- carState: 车速、档位、转向角
- controlsState: 巡航状态、横向控制状态
- modelV2: 车道线、前车距离
- liveCalibration: 俯仰角、偏航角
- gpsLocationExternal: GPS坐标、海拔

用法:
  cd /data/openpilot && . /usr/local/venv/bin/activate
  export PYTHONPATH=/data/openpilot
  nohup python /data/hud_data_server.py --port 5003 > /tmp/hud_data.log 2>&1 &
"""
import json
import time
import threading
from aiohttp import web
from cereal import messaging, log


class HudDataCollector:
    """后台线程订阅cereal消息, 缓存最新HUD数据"""
    
    def __init__(self):
        self.sm = messaging.SubMaster([
            'carState', 
            'controlsState', 
            'modelV2',
            'liveCalibration',
            'gpsLocationExternal',
            'liveParameters'
        ])
        self._data = {}
        self._lock = threading.Lock()
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
    
    def _loop(self):
        """后台更新循环, 10Hz采样"""
        print("[hud] data collector started", flush=True)
        while self._running:
            try:
                self.sm.update(100)  # 100ms timeout
                
                # 提取关键HUD数据
                data = {
                    # 车辆状态
                    "speed": round(self.sm['carState'].vEgo * 3.6, 1),  # m/s → km/h
                    "gear": self._parse_gear(self.sm['carState'].gearShifter),
                    "steeringAngle": round(self.sm['carState'].steeringAngleDeg, 1),
                    "brakeLights": self.sm['carState'].brakePressed,  # 使用brakePressed代替brakeLights
                    "leftBlinker": self.sm['carState'].leftBlinker,
                    "rightBlinker": self.sm['carState'].rightBlinker,
                    
                    # 控制状态
                    "enabled": self.sm['controlsState'].enabled,
                    "alertText1": self.sm['controlsState'].alertText1,
                    "alertText2": self.sm['controlsState'].alertText2,
                    "alertStatus": str(self.sm['controlsState'].alertStatus),
                    
                    # 模型数据
                    "leadDistance": self._get_lead_distance(),
                    "laneLeft": self._get_lane_position("left"),
                    "laneRight": self._get_lane_position("right"),
                    
                    # 定位数据
                    "gps": {
                        "lat": round(self.sm['gpsLocationExternal'].latitude, 6),
                        "lon": round(self.sm['gpsLocationExternal'].longitude, 6),
                        "altitude": round(self.sm['gpsLocationExternal'].altitude, 1)
                    },
                    
                    # 时间戳
                    "timestamp": time.time()
                }
                
                with self._lock:
                    self._data = data
                    
            except Exception as e:
                print(f"[hud] error: {e}", flush=True)
                time.sleep(0.1)
        
        print("[hud] data collector stopped", flush=True)
    
    def _parse_gear(self, gear_enum):
        """解析档位枚举"""
        gear_map = {
            0: "P", 1: "R", 2: "N", 3: "D",
            4: "B", 5: "L", 6: "S", 7: "M"
        }
        return gear_map.get(gear_enum, "?")
    
    def _get_lead_distance(self):
        """获取前车距离(米)"""
        try:
            leads = self.sm['modelV2'].leadsV3
            if leads and len(leads) > 0 and leads[0].prob > 0.5:
                return round(leads[0].x[0], 1)
        except:
            pass
        return None
    
    def _get_lane_position(self, side):
        """获取车道线位置"""
        try:
            lanes = self.sm['modelV2'].laneLines
            if side == "left" and len(lanes) > 0:
                return round(lanes[0].y[0], 2)
            elif side == "right" and len(lanes) > 3:
                return round(lanes[3].y[0], 2)
        except:
            pass
        return None
    
    def get_data(self):
        """获取最新HUD数据"""
        with self._lock:
            return self._data.copy() if self._data else {}
    
    def stop(self):
        self._running = False


# 全局实例
collector = None

async def handle_hud(request):
    """GET /hud → 返回JSON格式的HUD数据"""
    data = collector.get_data() if collector else {}
    return web.json_response(data, headers={
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "no-store"
    })

async def handle_health(request):
    """GET /health → 健康检查"""
    has_data = collector is not None and bool(collector.get_data())
    return web.json_response({"ok": has_data})


def main():
    import argparse
    import sys
    import traceback
    
    parser = argparse.ArgumentParser(description="C3 HUD Data Server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=5003)
    args = parser.parse_args()
    
    global collector
    print(f"[hud] starting on port {args.port}", flush=True)
    
    try:
        # 检查端口是否被占用
        import socket
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        result = sock.connect_ex((args.host, args.port))
        sock.close()
        if result == 0:
            print(f"[hud] ERROR: port {args.port} already in use!", flush=True)
            sys.exit(1)
        
        print(f"[hud] port {args.port} is available", flush=True)
        
        # 启动数据收集器
        collector = HudDataCollector()
        time.sleep(0.5)  # 给collector一点时间初始化
        
        # 创建 aiohttp app
        app = web.Application()
        app.router.add_get("/hud", handle_hud)
        app.router.add_get("/health", handle_health)
        
        print(f"[hud] starting HTTP server on {args.host}:{args.port}", flush=True)
        web.run_app(app, host=args.host, port=args.port, print=None)
        
    except KeyboardInterrupt:
        print("[hud] interrupted by user", flush=True)
    except Exception as e:
        print(f"[hud] FATAL ERROR: {e}", flush=True)
        traceback.print_exc()
        sys.exit(1)
    finally:
        if collector:
            print("[hud] stopping data collector", flush=True)
            collector.stop()
        print("[hud] server stopped", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"[hud] TOP LEVEL ERROR: {e}", flush=True)
        import traceback
        traceback.print_exc()
        import sys
        sys.exit(1)
