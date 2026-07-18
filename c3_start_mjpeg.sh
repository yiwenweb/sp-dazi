#!/bin/bash
# C3 MJPEG服务启动脚本
# 用法: ssh comma@<C3_IP> "bash -s" < c3_start_mjpeg.sh

echo "====== 启动 MJPEG 视频流服务 ======"

# 1. 清理旧进程
echo "[1/5] 清理旧进程..."
pkill -9 -f mjpeg_stream 2>/dev/null
sleep 1
echo "✓ 清理完成"

# 2. 检查端口
echo ""
echo "[2/5] 检查端口..."
if netstat -tuln 2>/dev/null | grep -q ":5002 "; then
    echo "✗ 端口 5002 仍被占用，尝试强制清理..."
    fuser -k 5002/tcp 2>/dev/null
    sleep 1
fi
echo "✓ 端口 5002 空闲"

# 3. 激活环境
echo ""
echo "[3/5] 激活虚拟环境..."
cd /data/openpilot
. /usr/local/venv/bin/activate
export PYTHONPATH=/data/openpilot
echo "✓ 环境已激活"

# 4. 启动服务
echo ""
echo "[4/5] 启动 MJPEG 服务..."
nohup python3 /data/spapp/spyl/mjpeg_stream.py \
    --camera road \
    --port 5002 \
    --host 0.0.0.0 \
    > /tmp/mjpeg_stream.log 2>&1 &

MJPEG_PID=$!
echo "✓ 服务已启动 (PID: $MJPEG_PID)"

# 5. 验证
echo ""
echo "[5/5] 验证服务状态..."
sleep 2

if ps -p $MJPEG_PID > /dev/null 2>&1; then
    echo "✓ 进程运行中 (PID: $MJPEG_PID)"
else
    echo "✗ 进程启动失败"
    echo "  查看日志: tail -n 50 /tmp/mjpeg_stream.log"
    exit 1
fi

if netstat -tuln 2>/dev/null | grep -q ":5002 "; then
    echo "✓ 端口 5002 正在监听"
else
    echo "✗ 端口 5002 未监听"
    exit 1
fi

if curl -s -m 2 http://localhost:5002/health > /dev/null 2>&1; then
    response=$(curl -s -m 2 http://localhost:5002/health)
    echo "✓ HTTP 服务响应正常"
    echo "  响应: $response"
else
    echo "✗ HTTP 服务无响应"
    exit 1
fi

echo ""
echo "====== 启动成功！ ======"
echo ""
echo "服务信息:"
echo "  进程ID: $MJPEG_PID"
echo "  端口: 5002"
echo "  摄像头: road"
echo "  日志文件: /tmp/mjpeg_stream.log"
echo ""
echo "停止服务: pkill -f mjpeg_stream"
echo "查看日志: tail -f /tmp/mjpeg_stream.log"
echo ""
echo "从Android访问:"
echo "  健康检查: http://<C3_IP>:5002/health"
echo "  获取帧: http://<C3_IP>:5002/frame"
echo "  MJPEG流: http://<C3_IP>:5002/stream"
