package com.sp.dazi;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.sp.dazi.model.NaviData;
import com.sp.dazi.receiver.AmapNaviReceiver;
import com.sp.dazi.service.BridgeService;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;

    // UI 组件
    private TextView tvConnectionState;
    private TextView tvC3Ip;
    private TextView tvPacketCount;
    private TextView tvNaviInfo;
    private TextView tvRoadName;
    private TextView tvSpeedLimit;
    private TextView tvSdiInfo;
    private TextView tvTbtInfo;
    private TextView tvGpsInfo;
    private EditText etManualIp;
    private Button btnConnect;
    private Button btnStartStop;

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
                public void onDataSent(int packetCount) {
                    // UI 由定时刷新处理
                }
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
        etManualIp = findViewById(R.id.et_manual_ip);
        btnConnect = findViewById(R.id.btn_connect);
        btnStartStop = findViewById(R.id.btn_start_stop);

        btnConnect.setOnClickListener(v -> onConnectClicked());
        btnStartStop.setOnClickListener(v -> onStartStopClicked());
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

    private void startBridgeService() {
        Intent intent = new Intent(this, BridgeService.class);
        // 如果有手动输入的 IP，传给服务
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
        Toast.makeText(this, "桥接服务已停止", Toast.LENGTH_SHORT).show();
    }

    /** 定时刷新 UI（1秒一次） */
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
        boolean fresh = (System.currentTimeMillis() - lastUpdate) < 5000;

        if (lastUpdate == 0) {
            tvNaviInfo.setText("等待高德导航数据... (已收到广播: " + AmapNaviReceiver.getReceiveCount() + ")");
        } else if (!fresh) {
            tvNaviInfo.setText("导航数据已过期 (" + ((System.currentTimeMillis() - lastUpdate) / 1000) + "秒前)");
        } else {
            tvNaviInfo.setText("导航数据正常");
        }

        // 道路信息
        String roadName = data.szPosRoadName;
        tvRoadName.setText(roadName.isEmpty() ? "--" : roadName);

        // 限速
        tvSpeedLimit.setText(data.nRoadLimitSpeed > 0 ? data.nRoadLimitSpeed + " km/h" : "--");

        // 测速摄像头
        if (data.nSdiType >= 0 && data.nSdiSpeedLimit > 0) {
            tvSdiInfo.setText("类型:" + data.nSdiType
                + " 限速:" + data.nSdiSpeedLimit + "km/h"
                + " 距离:" + (int) data.nSdiDist + "m");
        } else {
            tvSdiInfo.setText("无");
        }

        // 转弯
        if (data.nTBTDist > 0 && data.nTBTTurnType > 0) {
            String turnName = getTurnTypeName(data.nTBTTurnType);
            tvTbtInfo.setText(turnName + " " + (int) data.nTBTDist + "m");
        } else {
            tvTbtInfo.setText("无");
        }

        // GPS
        if (data.vpPosPointLat != 0 || data.vpPosPointLon != 0) {
            tvGpsInfo.setText(String.format("%.6f, %.6f", data.vpPosPointLat, data.vpPosPointLon));
        } else {
            tvGpsInfo.setText("无定位");
        }
    }

    private String getTurnTypeName(int type) {
        switch (type) {
            case 1: return "左转";
            case 2: return "右转";
            case 3: return "左前方";
            case 4: return "右前方";
            case 5: return "左后方";
            case 6: return "右后方";
            case 7: return "掉头";
            case 8: return "环岛";
            default: return "转弯(" + type + ")";
        }
    }

    // ---- 权限处理 ----

    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
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
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "部分权限未授予，可能影响功能", Toast.LENGTH_LONG).show();
            }
        }
    }
}
