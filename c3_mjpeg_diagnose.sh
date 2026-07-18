#!/bin/bash
# C3 MJPEG服务诊断脚本
# 用法: ssh comma@<C3_IP> "bash -s" < c3_mjpeg_diagnose.sh

echo "====== C3 MJPEG 服务诊断 ======"
echo ""

# 1. 检查虚拟环境
echo "[1/8] 检查虚拟环境..."
if [ -f /usr/local/venv/bin/activate ]; then
    echo "✓ 虚拟环境存在"
    source /usr/local/venv/bin/activate
else
    echo "✗ 虚拟环境不存在: /usr/local/venv/bin/activate"
    exit 1
fi

# 2. 检查Python依赖
echo ""
echo "[2/8] 检查Python依赖..."
python3 << 'EOF'
import sys
deps_ok = True

try:
    import av
    print("✓ PyAV")
except Exception as e:
    print(f"✗ PyAV: {e}")
    deps_ok = False

try:
    import aiohttp
    print("✓ aiohttp")
except Exception as e:
    print(f"✗ aiohttp: {e}")
    deps_ok = False

try:
    from cereal import messaging
    print("✓ cereal")
except Exception as e:
    print(f"✗ cereal: {e}")
    deps_ok = False

sys.exit(0 if deps_ok else 1)
EOF

if [ $? -ne 0 ]; then
    echo "✗ 依赖检查失败"
    exit 1
fi

# 3. 检查脚本文件
echo ""
echo "[3/8] 检查脚本文件..."
for script in /data/spapp/spyl/mjpeg_stream.py /data/spapp/spyl/hud_data_server.py; do
    if [ -f "$script" ]; then
        size=$(stat -f%z "$script" 2>/dev/null || stat -c%s "$script" 2>/dev/null)
        echo "✓ $script (${size} bytes)"
    else
        echo "✗ $script 不存在"
    fi
done

# 4. 检查端口占用
echo ""
echo "[4/8] 检查端口占用..."
for port in 5002 5003; do
    if netstat -tuln 2>/dev/null | grep -q ":$port "; then
        echo "⚠ 端口 $port 已被占用:"
        netstat -tuln | grep ":$port "
        ps aux | grep -E "mjpeg|hud_data" | grep -v grep
    else
        echo "✓ 端口 $port 空闲"
    fi
done

# 5. 检查stream_encoderd
echo ""
echo "[5/8] 检查stream_encoderd..."
if ps aux | grep -q "[s]tream_encoderd"; then
    echo "✓ stream_encoderd 正在运行"
else
    echo "⚠ stream_encoderd 未运行（需要车辆启动）"
fi

# 6. 检查WebRTC参数
echo ""
echo "[6/8] 检查WebRTC参数..."
if [ -f /data/params/d/WebrtcStreamEnabled ]; then
    value=$(cat /data/params/d/WebrtcStreamEnabled)
    if [ "$value" = "1" ]; then
        echo "✓ WebrtcStreamEnabled = 1"
    else
        echo "⚠ WebrtcStreamEnabled = $value (应该为1)"
    fi
else
    echo "✗ WebrtcStreamEnabled 参数不存在"
fi

# 7. 测试启动MJPEG服务
echo ""
echo "[7/8] 测试启动MJPEG服务..."
pkill -9 -f mjpeg_stream 2>/dev/null
sleep 1

cd /data/openpilot
export PYTHONPATH=/data/openpilot
timeout 3 python3 /data/spapp/spyl/mjpeg_stream.py --camera road --port 5002 --host 0.0.0.0 &
MJPEG_PID=$!

sleep 2

if ps -p $MJPEG_PID > /dev/null 2>&1; then
    echo "✓ MJPEG服务启动成功 (PID: $MJPEG_PID)"
    
    # 测试HTTP接口
    if curl -s -m 2 http://localhost:5002/health > /dev/null 2>&1; then
        echo "✓ HTTP接口响应正常"
        response=$(curl -s -m 2 http://localhost:5002/health)
        echo "  响应: $response"
    else
        echo "✗ HTTP接口无响应"
    fi
    
    # 清理
    kill $MJPEG_PID 2>/dev/null
else
    echo "✗ MJPEG服务启动失败或立即退出"
    echo "  查看日志: tail /tmp/mjpeg_stream.log"
fi

# 8. 检查系统资源
echo ""
echo "[8/8] 检查系统资源..."
echo "CPU负载: $(uptime | awk -F'load average:' '{print $2}')"
echo "内存使用: $(free -h | grep Mem | awk '{print $3 "/" $2}')"
echo "磁盘空间: $(df -h /data | tail -1 | awk '{print $3 "/" $2 " (" $5 ")"}')"

echo ""
echo "====== 诊断完成 ======"
echo ""
echo "如果服务启动失败，运行以下命令查看详细错误:"
echo "  cd /data/openpilot && . /usr/local/venv/bin/activate && export PYTHONPATH=/data/openpilot"
echo "  python3 -u /data/spapp/spyl/mjpeg_stream.py --camera road --port 5002 --host 0.0.0.0 2>&1 | tee /tmp/mjpeg_debug.log"
