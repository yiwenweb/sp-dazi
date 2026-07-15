"""C3 验证脚本 v3 - 深入检查"""
import paramiko, io, time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
pkey = paramiko.RSAKey.from_private_key_file('menmen.pem')
ssh.connect('192.168.43.4', username='comma', pkey=pkey, timeout=5, allow_agent=False, look_for_keys=False)

def cmd(c):
    stdin, stdout, stderr = ssh.exec_command(c)
    out = stdout.read().decode().strip()
    err = stderr.read().decode().strip()
    if err:
        print(f"  [stderr] {err[:300]}")
    return out

print("=" * 60)
print("C3 看门狗深入验证")
print("=" * 60)

# 1. 上传最新脚本
with open('app/src/main/assets/byd_debug_watchdog.py', 'rb') as f:
    script = f.read()
sftp = ssh.open_sftp()
sftp.putfo(io.BytesIO(script), '/data/byd_debug_watchdog.py')
print("\n>>> 脚本已更新到 C3")

# 上传 dry-run 测试脚本
dry_run = b"""#!/usr/bin/env python3
import sys, os
sys.path.insert(0, '/data/openpilot')
print('1. Importing cereal...')
import cereal.messaging as messaging
print('   cereal OK')
print('2. Creating SubMaster...')
sm = messaging.SubMaster(['can', 'carState', 'carControl', 'onroadEvents'])
print('   SubMaster created')
print('3. Test update...')
sm.update(0)
print('   update(0) OK')
print('4. Available services:')
for k in sorted(sm.data.keys()):
    v = sm.data.get(k)
    status = 'has data' if v is not None else 'waiting'
    print('   %s: %s' % (k, status))
print('DRY_RUN_COMPLETE')
"""
sftp.putfo(io.BytesIO(dry_run), '/tmp/dry_run_test.py')
sftp.close()

# 2. 语法检查
print("\n>>> 语法检查")
out = cmd("/usr/local/venv/bin/python -m py_compile /data/byd_debug_watchdog.py 2>&1 && echo SYNTAX_OK || echo SYNTAX_ERROR")
print(f"  {out}")

# 3. Dry-run
print("\n>>> Dry-run（导入+SubMaster测试）")
out = cmd("cd /data/openpilot && /usr/local/venv/bin/python /tmp/dry_run_test.py 2>&1")
print(f"  {out}")

# 4. enabled 文件
print("\n>>> enabled 文件")
out = cmd("test -f /data/byd_debug/enabled && echo EXISTS || echo NOT_FOUND")
print(f"  {out}")

# 5. watchdog 进程
print("\n>>> watchdog 进程")
out = cmd("ps aux | grep byd_debug_watchdog | grep -v grep | awk '{print $2, $11, $12, $13}'")
print(f"  PID CMD: {out}")

# 6. status.json
print("\n>>> status.json")
out = cmd("cat /data/byd_debug/status.json 2>/dev/null")
print(f"  {out}")

# 7. 最新日志
print("\n>>> 日志（最后15行）")
out = cmd("tail -15 /data/byd_debug/watchdog.log 2>/dev/null || echo '(无日志)'")
print(f"  {out}")

# 8. 重启 watchdog 加载新脚本
print("\n>>> 重启 watchdog...")
out = cmd("pkill -f byd_debug_watchdog 2>/dev/null; sleep 2; pgrep -f byd_debug_watchdog > /dev/null && echo STILL_ALIVE || echo KILLED")
print(f"  Kill: {out}")

# Start with new script
start_cmd = "cd /data/openpilot && source /usr/local/venv/bin/activate 2>/dev/null; export PYTHONPATH=/data/openpilot; nohup python /data/byd_debug_watchdog.py > /data/byd_debug/watchdog.log 2>&1 &"
ssh.exec_command(start_cmd)
time.sleep(3)
out = cmd("pgrep -f byd_debug_watchdog > /dev/null && echo RESTART_OK || echo RESTART_FAILED")
print(f"  Restart: {out}")

# 9. 重启后检查
print("\n>>> 重启后状态")
time.sleep(2)
out = cmd("cat /data/byd_debug/status.json 2>/dev/null")
print(f"  {out}")

out = cmd("tail -8 /data/byd_debug/watchdog.log 2>/dev/null")
print(f"  Log tail:")
for line in out.split('\n'):
    print(f"    {line}")

ssh.close()
print("\n" + "=" * 60)
print("验证完成!")
