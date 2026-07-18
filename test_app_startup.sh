#!/bin/bash
# 模拟App启动MJPEG服务的完整流程
# 用于调试App为什么无法启动服务

echo "====== 模拟 App 启动 MJPEG 服务 ======"
echo ""

OPENPILOT="/data/openpilot"
REMOTE_SCRIPT="/data/spapp/spyl/mjpeg_stream.py"
LOG_DIR="/data/spapp/spyl/log"
MJPEG_PORT=5002

echo "[1/6] 创建日志目录..."
mkdir -p $LOG_DIR
echo "✓ 完成"

echo ""
echo "[2/6] 清理旧进程..."
pkill -9 -f mjpeg_stream 2>/dev/null
sleep 1
if pgrep -f mjpeg_stream > /dev/null; then
    echo "✗ 仍有旧进程残留"
    ps aux | grep mjpeg_stream | grep -v grep
    exit 1
else
    echo "✓ 旧进程已清理"
fi

echo ""
echo "[3/6] 检查脚本文件..."
if [ -f "$REMOTE_SCRIPT" ]; then
    size=$(stat -c%s "$REMOTE_SCRIPT" 2>/dev/null || stat -f%z "$REMOTE_SCRIPT" 2>/dev/null)
    echo "✓ 脚本存在: $REMOTE_SCRIPT (${size} bytes)"
else
    echo "✗ 脚本不存在: $REMOTE_SCRIPT"
    exit 1
fi

echo ""
echo "[4/6] 检查虚拟环境..."
if [ -f /usr/local/venv/bin/activate ]; then
    echo "✓ 虚拟环境存在"
else
    echo "✗ 虚拟环境不存在"
    exit 1
fi

echo ""
echo "[5/6] 启动服务（后台）..."
cd $OPENPILOT
. /usr/local/venv/bin/activate
export PYTHONPATH=$OPENPILOT

nohup python3 $REMOTE_SCRIPT --camera road --port $MJPEG_PORT --host 0.0.0.0 \
    > $LOG_DIR/mjpeg_stream.log 2>&1 &

MJPEG_PID=$!
echo "✓ 进程已启动 (PID: $MJPEG_PID)"

echo ""
echo "[6/6] 验证服务状态（等待3秒）..."
sleep 3

# 检查进程
if ps -p $MJPEG_PID > /dev/null 2>&1; then
    echo "✓ 进程仍在运行 (PID: $MJPEG_PID)"
else
    echo "✗ 进程已退出！"
    echo ""
    echo "=== 查看日志 (最后30行) ==="
    tail -n 30 $LOG_DIR/mjpeg_stream.log
    exit 1
fi

# 检查端口
if netstat -tuln 2>/dev/null | grep -q ":$MJPEG_PORT " || ss -tuln 2>/dev/null | grep -q ":$MJPEG_PORT "; then
    echo "✓ 端口 $MJPEG_PORT 正在监听"
else
    echo "✗ 端口 $MJPEG_PORT 未监听"
    echo ""
    echo "=== 查看日志 (最后30行) ==="
    tail -n 30 $LOG_DIR/mjpeg_stream.log
    exit 1
fi

# 检查HTTP接口
if curl -s -m 2 http://localhost:$MJPEG_PORT/health > /dev/null 2>&1; then
    response=$(curl -s -m 2 http://localhost:$MJPEG_PORT/health)
    echo "✓ HTTP 接口响应正常"
    echo "  响应: $response"
else
    echo "✗ HTTP 接口无响应"
    echo ""
    echo "=== 查看日志 (最后30行) ==="
    tail -n 30 $LOG_DIR/mjpeg_stream.log
    exit 1
fi

# 测试获取帧
if curl -s -m 3 http://localhost:$MJPEG_PORT/frame > /tmp/test_frame.jpg 2>&1; then
    size=$(stat -c%s /tmp/test_frame.jpg 2>/dev/null || stat -f%z /tmp/test_frame.jpg 2>/dev/null)
    if [ "$size" -gt 1000 ]; then
        echo "✓ 视频帧获取成功 (${size} bytes)"
    else
        echo "⚠ 视频帧太小 (${size} bytes)"
    fi
else
    echo "✗ 视频帧获取失败"
fi

echo ""
echo "====== 启动成功！ ======"
echo ""
echo "服务信息:"
echo "  PID: $MJPEG_PID"
echo "  端口: $MJPEG_PORT"
echo "  日志: $LOG_DIR/mjpeg_stream.log"
echo ""
echo "实时查看日志:"
echo "  tail -f $LOG_DIR/mjpeg_stream.log"
echo ""
echo "停止服务:"
echo "  pkill -f mjpeg_stream"
