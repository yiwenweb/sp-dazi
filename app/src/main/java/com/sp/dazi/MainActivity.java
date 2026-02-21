package com.sp.dazi;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.sp.dazi.model.NaviData;
import com.sp.dazi.receiver.AmapNaviReceiver;
import com.sp.dazi.receiver.BroadcastSniffer;
import com.sp.dazi.service.BridgeService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvConnectionState, tvC3Ip, tvPacketCount;
    private TextView tvNaviInfo, tvRoadName, tvSpeedLimit;
    private TextView tvSdiInfo, tvTbtInfo, tvGpsInfo, tvDebugInfo;
    private EditText etManualIp;
    private Button btnConnect, btnStartStop, btnExportLog;

    private BridgeService bridgeService;
    private boolean serviceBound = false;
    private boolean serviceRunning = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable uiUpdateRunnable;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BridgeService.LocalBinder binder = (BridgeService.LocalBinder) service;
            bridgeService = binder.getService();
            serviceBound = true;
            bridgeService.setStateCallback(new BridgeService.StateCallback() {
                @Override
                public void onStateChanged(BridgeService.ConnectionState state, String c3Ip) {
                    uiHandler.post(() -> updateConnectionUI(state, c3Ip));
                }
                @Override
                public void onDataSent(int packetCount) {}
            });
            updateConnectionUI(bridgeService.getConnectionState(), bridgeService.getC3IpAddress());
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            bridgeService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        requestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startUIUpdate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUIUpdate();
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    private void initViews() {
        tvConnectionState = findViewById(R.id.tv_connection_state);
        tvC3Ip = findViewById(R.id.tv_c3_ip);
        tvPacketCount = findViewById(R.id.tv_packet_count);
        tvNaviInfo = findViewById(R.id.tv_navi_info);
        tvRoadName = findViewById(R.id.tv_road_name);
        tvSpeedLimit = findViewById(R.id.tv_speed_limit);
        tvSdiInfo = findViewById(R.id.tv_sdi_info);
        tvTbtInfo = findViewById(R.id.tv_tbt_info);
        tvGpsInfo = findViewById(R.id.tv_gps_info);
        tvDebugInfo = findViewById(R.id.tv_debug_info);
        etManualIp = findViewById(R.id.et_manual_ip);
        btnConnect = findViewById(R.id.btn_connect);
        btnStartStop = findViewById(R.id.btn_start_stop);
        btnExportLog = findViewById(R.id.btn_export_log);

        btnConnect.setOnClickListener(v -> onConnectClicked());
        btnStartStop.setOnClickListener(v -> onStartStopClicked());
        btnExportLog.setOnClickListener(v -> onExportLogClicked());
    }

    private void onConnectClicked() {
        String ip = etManualIp.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "请输入 C3 IP 地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (serviceBound && bridgeService != null) {
            bridgeService.setC3Ip(ip);
            Toast.makeText(this, "已设置 C3 IP: " + ip, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "服务未启动，请先启动服务", Toast.LENGTH_SHORT).show();
        }
    }

    private void onStartStopClicked() {
        if (serviceRunning) {
            stopBridgeService();
        } else {
            startBridgeService();
        }
    }

    private void onExportLogClicked() {
        String path = BroadcastSniffer.exportLogs(this);
        if (path != null) {
            Toast.makeText(this, "已导出到: " + path, Toast.LENGTH_LONG).show();
            // 尝试分享文件
            try {
                File file = new File(path);
                Uri uri = FileProvider.getUriForFile(this, "com.sp.dazi.fileprovider", file);
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "分享广播日志"));
            } catch (Exception e) {
                Log.w(TAG, "分享失败", e);
                Toast.makeText(this, "文件路径: " + path, Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "导出失败，可能没有捕获到数据", Toast.LENGTH_SHORT).show();
        }
    }

    private void startBridgeService() {
        Intent intent = new Intent(this, BridgeService.class);
        String ip = etManualIp.getText().toString().trim();
        if (!ip.isEmpty()) {
            intent.putExtra("c3_ip", ip);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        serviceRunning = true;
        btnStartStop.setText("停止服务");
        Toast.makeText(this, "桥接服务已启动", Toast.LENGTH_SHORT).show();
    }

    private void stopBridgeService() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        stopService(new Intent(this, BridgeService.class));
        serviceRunning = false;
        bridgeService = null;
        btnStartStop.setText("启动服务");
        tvConnectionState.setText("未启动");
        tvConnectionState.setTextColor(0xFF999999);
    }

    private void startUIUpdate() {
        uiUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateNaviDataUI();
                uiHandler.postDelayed(this, 1000);
            }
        };
        uiHandler.post(uiUpdateRunnable);
    }

    private void stopUIUpdate() {
        if (uiUpdateRunnable != null) {
            uiHandler.removeCallbacks(uiUpdateRunnable);
        }
    }

    private void updateConnectionUI(BridgeService.ConnectionState state, String c3Ip) {
        switch (state) {
            case SEARCHING:
                tvConnectionState.setText("🔍 搜索设备中...");
                tvConnectionState.setTextColor(0xFFFF9800);
                break;
            case CONNECTED:
                tvConnectionState.setText("✅ 已连接");
                tvConnectionState.setTextColor(0xFF4CAF50);
                break;
            case DISCONNECTED:
                tvConnectionState.setText("❌ 未连接");
                tvConnectionState.setTextColor(0xFFF44336);
                break;
        }
        tvC3Ip.setText(c3Ip != null ? c3Ip : "--");
    }

    private void updateNaviDataUI() {
        if (serviceBound && bridgeService != null) {
            tvPacketCount.setText(String.valueOf(bridgeService.getPacketCount()));
        }

        NaviData data = AmapNaviReceiver.getCurrentData();
        long lastUpdate = AmapNaviReceiver.getLastUpdateTime();
        int recvCount = AmapNaviReceiver.getReceiveCount();
        boolean fresh = (System.currentTimeMillis() - lastUpdate) < 5000;

        if (lastUpdate == 0) {
            tvNaviInfo.setText("等待高德导航数据... (广播计数: " + recvCount + ")");
        } else if (!fresh) {
            tvNaviInfo.setText("数据已过期 (" + ((System.currentTimeMillis() - lastUpdate) / 1000) + "秒前, 共" + recvCount + "条)");
        } else {
            tvNaviInfo.setText("数据正常 (共" + recvCount + "条)");
        }

        tvRoadName.setText(data.szPosRoadName.isEmpty() ? "--" : data.szPosRoadName);
        tvSpeedLimit.setText(data.nRoadLimitSpeed > 0 ? data.nRoadLimitSpeed + " km/h" : "--");

        if (data.nSdiType >= 0 && data.nSdiSpeedLimit > 0) {
            tvSdiInfo.setText("类型:" + data.nSdiType + " 限速:" + data.nSdiSpeedLimit + "km/h 距离:" + (int) data.nSdiDist + "m");
        } else {
            tvSdiInfo.setText("无");
        }

        if (data.nTBTDist > 0 && data.nTBTTurnType > 0) {
            tvTbtInfo.setText(getTurnName(data.nTBTTurnType) + " " + (int) data.nTBTDist + "m");
        } else {
            tvTbtInfo.setText("无");
        }

        if (data.vpPosPointLat != 0 || data.vpPosPointLon != 0) {
            tvGpsInfo.setText(String.format("%.6f, %.6f", data.vpPosPointLat, data.vpPosPointLon));
        } else {
            tvGpsInfo.setText("无定位");
        }

        // 调试信息 — 显示嗅探器捕获的广播
        int sniffCount = BroadcastSniffer.getCaptureCount();
        String dbg = AmapNaviReceiver.getDebugInfo();
        String sniffLogs = BroadcastSniffer.getLatestLogs(5);

        if (sniffCount > 0) {
            tvDebugInfo.setText("捕获广播: " + sniffCount + "条\n" + sniffLogs);
        } else if (!dbg.isEmpty()) {
            tvDebugInfo.setText(dbg);
        } else {
            tvDebugInfo.setText("暂无广播数据（嗅探器监听中...共" + sniffCount + "条）");
        }
    }

    private String getTurnName(int type) {
        switch (type) {
            case 1: return "直行";
            case 2: return "左转";
            case 3: return "右转";
            case 4: return "掉头";
            case 5: return "进匝道";
            default: return "转弯(" + type + ")";
        }
    }

    // ---- 权限 ----
    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (!allGranted) {
                Toast.makeText(this, "部分权限未授予，可能影响GPS数据", Toast.LENGTH_LONG).show();
            }
        }
    }
}
