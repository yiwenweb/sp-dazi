package com.sp.dazi;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    // Views
    private WebView wvVideo;
    private View tvVideoHint;
    private View statusDot;
    private TextView tvConnectionState, tvC3Ip, tvPacketCount;
    private TextView tvNaviInfo, tvRoadName, tvSpeedLimit;
    private TextView tvSdiInfo, tvTbtInfo, tvGpsInfo, tvDebugInfo;
    private EditText etManualIp;
    private Button btnConnect, btnStartStop, btnDebug, btnExportLog;
    private LinearLayout debugPanel, hudOverlay;

    // HUD views
    private TextView tvHudSpeed, tvHudCruise, tvHudGear, tvHudGap;
    private TextView tvHudTlight, tvHudTlightSec;
    private LinearLayout hudTlight, hudNaviBar;
    private TextView tvHudRoad, tvHudRemain;

    // WebSocket for carstate
    private OkHttpClient wsClient;
    private WebSocket carStateWs;
    private boolean wsConnected = false;

    private BridgeService bridgeService;
    private boolean serviceBound = false;
    private boolean serviceRunning = false;
    private boolean debugVisible = false;
    private boolean videoLoaded = false;

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
                    uiHandler.post(() -> {
                        updateConnectionUI(state, c3Ip);
                        // C3 连接成功后自动加载视频
                        if (state == BridgeService.ConnectionState.CONNECTED && c3Ip != null) {
                            loadVideo(c3Ip);
                        }
                    });
                }
                @Override
                public void onDataSent(int packetCount) {}
            });
            updateConnectionUI(bridgeService.getConnectionState(), bridgeService.getC3IpAddress());
            if (bridgeService.getConnectionState() == BridgeService.ConnectionState.CONNECTED) {
                loadVideo(bridgeService.getC3IpAddress());
            }
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
        disconnectCarStateWs();
        if (wvVideo != null) {
            wvVideo.destroy();
        }
        super.onDestroy();
    }

    private void initViews() {
        wvVideo = findViewById(R.id.wv_video);
        tvVideoHint = findViewById(R.id.tv_video_hint);
        statusDot = findViewById(R.id.status_dot);
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
        btnDebug = findViewById(R.id.btn_debug);
        btnExportLog = findViewById(R.id.btn_export_log);
        debugPanel = findViewById(R.id.debug_panel);
        hudOverlay = findViewById(R.id.hud_overlay);

        // HUD views
        tvHudSpeed = findViewById(R.id.tv_hud_speed);
        tvHudCruise = findViewById(R.id.tv_hud_cruise);
        tvHudGear = findViewById(R.id.tv_hud_gear);
        tvHudGap = findViewById(R.id.tv_hud_gap);
        tvHudTlight = findViewById(R.id.tv_hud_tlight);
        tvHudTlightSec = findViewById(R.id.tv_hud_tlight_sec);
        hudTlight = findViewById(R.id.hud_tlight);
        hudNaviBar = findViewById(R.id.hud_navi_bar);
        tvHudRoad = findViewById(R.id.tv_hud_road);
        tvHudRemain = findViewById(R.id.tv_hud_remain);

        // WebView 设置
        WebSettings ws = wvVideo.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDomStorageEnabled(true);
        wvVideo.setWebViewClient(new WebViewClient());

        btnConnect.setOnClickListener(v -> onConnectClicked());
        btnStartStop.setOnClickListener(v -> onStartStopClicked());
        btnDebug.setOnClickListener(v -> toggleDebug());
        btnExportLog.setOnClickListener(v -> onExportLogClicked());
    }

    private void loadVideo(String c3Ip) {
        if (c3Ip == null || videoLoaded) return;
        String url = "http://" + c3Ip + ":8099";
        wvVideo.loadUrl(url);
        tvVideoHint.setVisibility(View.GONE);
        videoLoaded = true;
        Log.i(TAG, "加载视频: " + url);
        // 连接 carstate WebSocket
        connectCarStateWs(c3Ip);
    }

    /** 连接 C3 carstate WebSocket，获取速度/ACC状态 */
    private void connectCarStateWs(String c3Ip) {
        if (wsConnected) return;
        if (wsClient == null) {
            wsClient = new OkHttpClient();
        }
        String wsUrl = "ws://" + c3Ip + ":7000/ws/carstate";
        Request request = new Request.Builder().url(wsUrl).build();
        carStateWs = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                wsConnected = true;
                Log.i(TAG, "CarState WebSocket 已连接");
                uiHandler.post(() -> hudOverlay.setVisibility(View.VISIBLE));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject j = new JSONObject(text);
                    double vEgo = j.optDouble("vEgo", 0);
                    double vSet = j.optDouble("vSetKph", 0);
                    String gear = j.optString("gear", "P");
                    int tfGap = j.optInt("tfGap", 2);
                    String tlight = j.optString("tlight", "off");
                    int tlightCountdown = j.optInt("tlightCountdown", 0);
                    String naviRoad = j.optString("naviRoad", "");
                    int naviRemainDist = j.optInt("naviRemainDist", 0);
                    int naviRemainTime = j.optInt("naviRemainTime", 0);

                    int speedKph = (int) Math.round(vEgo * 3.6);
                    int cruiseKph = (int) Math.round(vSet);

                    // 跟车距离用圆点表示
                    StringBuilder gapDots = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        gapDots.append(i < tfGap ? "●" : "○");
                    }

                    // 红绿灯颜色
                    final int tlightColor;
                    final boolean tlightVisible;
                    final String tlightSecText;
                    switch (tlight) {
                        case "red":
                            tlightColor = 0xFFFF5252;
                            tlightVisible = true;
                            tlightSecText = tlightCountdown > 0 ? String.valueOf(tlightCountdown) : "";
                            break;
                        case "green":
                            tlightColor = 0xFF00E5A0;
                            tlightVisible = true;
                            tlightSecText = tlightCountdown > 0 ? String.valueOf(tlightCountdown) : "";
                            break;
                        case "yellow":
                            tlightColor = 0xFFFFB74D;
                            tlightVisible = true;
                            tlightSecText = tlightCountdown > 0 ? String.valueOf(tlightCountdown) : "";
                            break;
                        default:
                            tlightColor = 0xFF666666;
                            tlightVisible = false;
                            tlightSecText = "";
                            break;
                    }

                    // 导航剩余信息格式化
                    final String remainText;
                    if (naviRemainDist > 0) {
                        String distStr = naviRemainDist >= 1000
                            ? String.format("%.1fkm", naviRemainDist / 1000.0)
                            : naviRemainDist + "m";
                        String timeStr = "";
                        if (naviRemainTime > 0) {
                            int mins = naviRemainTime / 60;
                            if (mins >= 60) {
                                timeStr = String.format(" %dh%dmin", mins / 60, mins % 60);
                            } else {
                                timeStr = " " + mins + "min";
                            }
                        }
                        remainText = distStr + timeStr;
                    } else {
                        remainText = "";
                    }
                    final String roadText = naviRoad;

                    uiHandler.post(() -> {
                        tvHudSpeed.setText(String.valueOf(speedKph));
                        tvHudCruise.setText(cruiseKph > 0 ? String.valueOf(cruiseKph) : "--");
                        tvHudCruise.setTextColor(cruiseKph > 0 ? 0xFF00E5A0 : 0x66FFFFFF);
                        tvHudGear.setText(gear);
                        tvHudGap.setText(gapDots.toString());
                        // 红绿灯
                        hudTlight.setVisibility(tlightVisible ? View.VISIBLE : View.GONE);
                        tvHudTlight.setTextColor(tlightColor);
                        tvHudTlightSec.setText(tlightSecText);
                        // 导航信息条
                        if (!roadText.isEmpty() || !remainText.isEmpty()) {
                            hudNaviBar.setVisibility(View.VISIBLE);
                            tvHudRoad.setText(roadText);
                            tvHudRemain.setText(remainText);
                        } else {
                            hudNaviBar.setVisibility(View.GONE);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "解析 carstate 失败", e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                wsConnected = false;
                Log.w(TAG, "CarState WebSocket 断开: " + t.getMessage());
                // 5秒后重连
                uiHandler.postDelayed(() -> {
                    if (serviceBound && bridgeService != null) {
                        String ip = bridgeService.getC3IpAddress();
                        if (ip != null) connectCarStateWs(ip);
                    }
                }, 5000);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                wsConnected = false;
                Log.i(TAG, "CarState WebSocket 关闭");
            }
        });
    }

    private void disconnectCarStateWs() {
        wsConnected = false;
        if (carStateWs != null) {
            carStateWs.cancel();
            carStateWs = null;
        }
    }

    private void toggleDebug() {
        debugVisible = !debugVisible;
        debugPanel.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
        btnDebug.setText(debugVisible ? "隐藏调试" : "调试");
    }

    private void onConnectClicked() {
        String ip = etManualIp.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "请输入 C3 IP 地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (serviceBound && bridgeService != null) {
            bridgeService.setC3Ip(ip);
            loadVideo(ip);
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
            Toast.makeText(this, "已导出: " + path, Toast.LENGTH_LONG).show();
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
            }
        } else {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
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
        btnStartStop.setBackgroundResource(R.drawable.btn_stop);
        btnStartStop.setTextColor(0xFFFFFFFF);
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
    }

    private void stopBridgeService() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        stopService(new Intent(this, BridgeService.class));
        serviceRunning = false;
        bridgeService = null;
        videoLoaded = false;
        disconnectCarStateWs();
        btnStartStop.setText("启动服务");
        btnStartStop.setBackgroundResource(R.drawable.btn_primary);
        btnStartStop.setTextColor(0xFF0D0D1A);
        tvConnectionState.setText("未启动");
        tvConnectionState.setTextColor(0x66FFFFFF);
        setStatusDotColor(0x66FFFFFF);
        tvVideoHint.setVisibility(View.VISIBLE);
        hudOverlay.setVisibility(View.GONE);
        wvVideo.loadUrl("about:blank");
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
                tvConnectionState.setText("搜索中...");
                tvConnectionState.setTextColor(0xFFFFB74D);
                setStatusDotColor(0xFFFFB74D);
                break;
            case CONNECTED:
                tvConnectionState.setText("已连接");
                tvConnectionState.setTextColor(0xFF00E5A0);
                setStatusDotColor(0xFF00E5A0);
                break;
            case DISCONNECTED:
                tvConnectionState.setText("断开");
                tvConnectionState.setTextColor(0xFFFF5252);
                setStatusDotColor(0xFFFF5252);
                break;
        }
        tvC3Ip.setText(c3Ip != null ? c3Ip : "");
    }

    private void setStatusDotColor(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(dp(8), dp(8));
        statusDot.setBackground(dot);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
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
            tvNaviInfo.setText("等待数据...");
            tvNaviInfo.setTextColor(0xFFFFB74D);
        } else if (!fresh) {
            tvNaviInfo.setText("过期 " + ((System.currentTimeMillis() - lastUpdate) / 1000) + "s");
            tvNaviInfo.setTextColor(0xFFFF5252);
        } else {
            tvNaviInfo.setText("正常 (" + recvCount + ")");
            tvNaviInfo.setTextColor(0xFF00E5A0);
        }

        tvRoadName.setText(data.szPosRoadName.isEmpty() ? "--" : data.szPosRoadName);
        tvSpeedLimit.setText(data.nRoadLimitSpeed > 0 ? data.nRoadLimitSpeed + " km/h" : "--");

        if (data.nSdiType >= 0 && data.nSdiSpeedLimit > 0) {
            tvSdiInfo.setText("限" + data.nSdiSpeedLimit + "km/h " + (int) data.nSdiDist + "m");
        } else {
            tvSdiInfo.setText("无");
        }

        if (data.nTBTDist > 0 && data.nTBTTurnType > 0) {
            tvTbtInfo.setText(getTurnName(data.nTBTTurnType) + " " + (int) data.nTBTDist + "m");
        } else {
            tvTbtInfo.setText("无");
        }

        if (data.vpPosPointLat != 0 || data.vpPosPointLon != 0) {
            tvGpsInfo.setText(String.format("%.5f, %.5f", data.vpPosPointLat, data.vpPosPointLon));
        } else {
            tvGpsInfo.setText("无定位");
        }

        // 调试信息只在面板可见时更新
        if (debugVisible) {
            int sniffCount = BroadcastSniffer.getCaptureCount();
            String dbg = AmapNaviReceiver.getDebugInfo();
            String sniffLogs = BroadcastSniffer.getLatestLogs(3);
            if (sniffCount > 0) {
                tvDebugInfo.setText("嗅探:" + sniffCount + "条\n" + sniffLogs);
            } else if (!dbg.isEmpty()) {
                tvDebugInfo.setText(dbg);
            } else {
                tvDebugInfo.setText("暂无数据");
            }
        }
    }

    private String getTurnName(int type) {
        switch (type) {
            case 2: return "左转";
            case 3: return "右转";
            case 4: return "左前方";
            case 5: return "右前方";
            case 6: return "左后方";
            case 7: return "右后方";
            case 8: return "掉头";
            case 9: return "直行";
            case 10: return "到达目的地";
            case 11: return "进环岛";
            case 12: return "出环岛";
            case 13: return "途经点";
            case 14: return "进匝道";
            case 15: return "出匝道";
            case 16: return "收费站";
            default: return "导航(" + type + ")";
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
                Toast.makeText(this, "部分权限未授予", Toast.LENGTH_LONG).show();
            }
        }
    }
}
