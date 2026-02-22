package com.sp.dazi;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

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
    private static final String PREFS_NAME = "sp_dazi_prefs";
    private static final String KEY_C3_IP = "c3_ip";

    // Views
    private WebView wvVideo;
    private View tvVideoHint;
    private View statusDot;
    private TextView tvConnectionState, tvC3Ip, tvPacketCount;
    private TextView tvNaviInfo, tvRoadName, tvSpeedLimit;
    private TextView tvSdiInfo, tvTbtInfo, tvGpsInfo, tvDebugInfo;
    private EditText etManualIp;
    private Button btnConnect, btnStartStop, btnDebug, btnExportLog, btnSettings;
    private LinearLayout debugPanel, hudOverlay;
    private ScrollView controlPanel;
    private TextView tvLiveStatus, tvFps;

    // HUD views
    private TextView tvHudSpeed, tvHudCruise, tvHudGear, tvHudGap;
    private TextView tvHudTlight, tvHudTlightSec;
    private LinearLayout hudTlight, hudNaviBar, hudSdiBar;
    private TextView tvHudRoad, tvHudRemain;
    private TextView tvHudSdiSpeed, tvHudSdiDist;
    // Feature 3: 服务区
    private LinearLayout hudSapaBar;
    private TextView tvHudSapaIcon, tvHudSapaName, tvHudSapaDist;
    // Feature 4: ETA
    private TextView tvHudEta;
    // Feature 5: 路况
    private LinearLayout hudTmcBar;
    private TextView tvHudTmc;
    // Feature 6: 连续转弯
    private LinearLayout hudNextTurnBar;
    private TextView tvHudNextTurnIcon, tvHudNextTurnName;
    // Feature 8: 行程统计
    private LinearLayout hudTripBar;
    private TextView tvHudTrip;
    // 变道提醒
    private LinearLayout hudLaneBar;
    private TextView tvHudLaneIcon, tvHudLaneText, tvHudLaneDetail;

    // Feature 7: 超速提醒
    private Vibrator vibrator;
    private boolean overspeedAlerted = false;
    private int currentSpeedKph = 0;

    // Feature 8: 行程记录
    private static final String KEY_TRIP_START = "trip_start_time";
    private static final String KEY_TRIP_DIST = "trip_distance";
    private static final String KEY_TRIP_MAX_SPEED = "trip_max_speed";
    private static final String KEY_TRIP_OVERSPEED = "trip_overspeed_count";
    private long tripStartTime = 0;
    private double tripDistance = 0;
    private int tripMaxSpeed = 0;
    private int tripOverspeedCount = 0;
    private double lastLat = 0, lastLon = 0;

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
        // 沉浸式状态栏 — 内容延伸到状态栏下方
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        setContentView(R.layout.activity_main);
        initViews();
        loadSavedIp();
        applyOrientationLayout();
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
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientationLayout();
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
        hudSdiBar = findViewById(R.id.hud_sdi_bar);
        tvHudSdiSpeed = findViewById(R.id.tv_hud_sdi_speed);
        tvHudSdiDist = findViewById(R.id.tv_hud_sdi_dist);
        controlPanel = findViewById(R.id.control_panel);
        // 设置按钮 + LIVE/FPS
        btnSettings = findViewById(R.id.btn_settings);
        tvLiveStatus = findViewById(R.id.tv_live_status);
        tvFps = findViewById(R.id.tv_fps);
        // Feature 3
        hudSapaBar = findViewById(R.id.hud_sapa_bar);
        tvHudSapaIcon = findViewById(R.id.tv_hud_sapa_icon);
        tvHudSapaName = findViewById(R.id.tv_hud_sapa_name);
        tvHudSapaDist = findViewById(R.id.tv_hud_sapa_dist);
        // Feature 4
        tvHudEta = findViewById(R.id.tv_hud_eta);
        // Feature 5
        hudTmcBar = findViewById(R.id.hud_tmc_bar);
        tvHudTmc = findViewById(R.id.tv_hud_tmc);
        // Feature 6
        hudNextTurnBar = findViewById(R.id.hud_next_turn_bar);
        tvHudNextTurnIcon = findViewById(R.id.tv_hud_next_turn_icon);
        tvHudNextTurnName = findViewById(R.id.tv_hud_next_turn_name);
        // Feature 8
        hudTripBar = findViewById(R.id.hud_trip_bar);
        tvHudTrip = findViewById(R.id.tv_hud_trip);
        // 变道提醒
        hudLaneBar = findViewById(R.id.hud_lane_bar);
        tvHudLaneIcon = findViewById(R.id.tv_hud_lane_icon);
        tvHudLaneText = findViewById(R.id.tv_hud_lane_text);
        tvHudLaneDetail = findViewById(R.id.tv_hud_lane_detail);
        // Feature 7
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // WebView 设置
        WebSettings ws = wvVideo.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDomStorageEnabled(true);
        wvVideo.setWebViewClient(new WebViewClient());
        // JavaScript 接口：接收 iframe 的 fps postMessage
        wvVideo.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onFps(int fps) {
                uiHandler.post(() -> {
                    if (tvFps != null) tvFps.setText(fps + " fps");
                    if (tvLiveStatus != null) {
                        tvLiveStatus.setText(fps > 0 ? "● LIVE" : "");
                        tvLiveStatus.setTextColor(fps > 0 ? 0xFF00E5A0 : 0x66FFFFFF);
                    }
                });
            }
        }, "Android");

        btnConnect.setOnClickListener(v -> onConnectClicked());
        btnStartStop.setOnClickListener(v -> onStartStopClicked());
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnDebug.setOnClickListener(v -> toggleDebug());
        btnExportLog.setOnClickListener(v -> onExportLogClicked());
        loadSpeedMappings();
    }

    private void loadVideo(String c3Ip) {
        if (c3Ip == null || videoLoaded) return;
        String url = "http://" + c3Ip + ":8099?cam=road";
        wvVideo.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 注入 JS：监听 postMessage 中的 fps，转发给 Android
                view.evaluateJavascript(
                    "window.addEventListener('message', function(e) {" +
                    "  if (e.data && typeof e.data.fps === 'number') {" +
                    "    Android.onFps(e.data.fps);" +
                    "  }" +
                    "});", null);
            }
        });
        wvVideo.loadUrl(url);
        tvVideoHint.setVisibility(View.GONE);
        // 视频加载后立即显示 HUD（不等 WebSocket）
        hudOverlay.setVisibility(View.VISIBLE);
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
                    currentSpeedKph = speedKph;

                    // Feature 7: 超速检测
                    NaviData nd = AmapNaviReceiver.getCurrentData();
                    int roadLimit = nd.nRoadLimitSpeed;
                    boolean isOverspeed = roadLimit > 0 && speedKph > roadLimit + 5;

                    if (isOverspeed && !overspeedAlerted) {
                        overspeedAlerted = true;
                        // 震动提醒
                        if (vibrator != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createWaveform(
                                    new long[]{0, 200, 100, 200}, -1));
                            } else {
                                vibrator.vibrate(new long[]{0, 200, 100, 200}, -1);
                            }
                        }
                        // 记录超速次数
                        tripOverspeedCount++;
                    } else if (!isOverspeed) {
                        overspeedAlerted = false;
                    }

                    // Feature 8: 行程记录 — 更新最高速度
                    if (speedKph > tripMaxSpeed) {
                        tripMaxSpeed = speedKph;
                    }
                    // 通过 GPS 累计距离
                    if (nd.vpPosPointLat != 0 && nd.vpPosPointLon != 0) {
                        if (lastLat != 0 && lastLon != 0) {
                            double d = haversine(lastLat, lastLon, nd.vpPosPointLat, nd.vpPosPointLon);
                            if (d > 5 && d < 2000) { // 过滤GPS跳变
                                tripDistance += d;
                            }
                        }
                        lastLat = nd.vpPosPointLat;
                        lastLon = nd.vpPosPointLon;
                    }

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
                    final boolean speedOverLimit = isOverspeed;

                    uiHandler.post(() -> {
                        tvHudSpeed.setText(String.valueOf(speedKph));
                        // Feature 7: 超速时速度变红
                        tvHudSpeed.setTextColor(speedOverLimit ? 0xFFFF5252 : 0xFFFFFFFF);
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
        // 收起键盘、移除光标
        etManualIp.clearFocus();
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etManualIp.getWindowToken(), 0);

        saveIp(ip);
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
            saveIp(ip);
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
        // Feature 8: 开始行程记录
        tripStartTime = System.currentTimeMillis();
        tripDistance = 0;
        tripMaxSpeed = 0;
        tripOverspeedCount = 0;
        lastLat = 0;
        lastLon = 0;
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
        if (hudSapaBar != null) hudSapaBar.setVisibility(View.GONE);
        if (hudTmcBar != null) hudTmcBar.setVisibility(View.GONE);
        if (hudNextTurnBar != null) hudNextTurnBar.setVisibility(View.GONE);
        if (hudTripBar != null) hudTripBar.setVisibility(View.GONE);
        if (hudLaneBar != null) hudLaneBar.setVisibility(View.GONE);
        wvVideo.loadUrl("about:blank");
        // 重置 LIVE/fps 显示
        if (tvLiveStatus != null) tvLiveStatus.setText("");
        if (tvFps != null) tvFps.setText("");
        // Feature 8: 保存行程数据
        if (tripStartTime > 0 && tripDistance > 100) {
            saveTripData();
        }
        tripStartTime = 0;
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
        // 显示映射信息
        int origSpeed = AmapNaviReceiver.getOriginalSpeed();
        if (origSpeed > 0 && origSpeed != data.nRoadLimitSpeed) {
            tvSpeedLimit.setText(origSpeed + "→" + data.nRoadLimitSpeed + " km/h");
            tvSpeedLimit.setTextColor(0xFF00E5A0);
        } else {
            tvSpeedLimit.setTextColor(0xFFFFFFFF);
        }

        if (data.nSdiType >= 0 && data.nSdiSpeedLimit > 0) {
            tvSdiInfo.setText("限" + data.nSdiSpeedLimit + "km/h " + (int) data.nSdiDist + "m");
            // 更新 HUD 区间测速/测速条
            if (hudSdiBar != null) {
                hudSdiBar.setVisibility(View.VISIBLE);
                tvHudSdiSpeed.setText(data.nSdiSpeedLimit + "km/h");
                String distStr = data.nSdiDist >= 1000
                    ? String.format("%.1fkm", data.nSdiDist / 1000.0)
                    : (int) data.nSdiDist + "m";
                tvHudSdiDist.setText(distStr);
                // 区间测速用不同颜色
                if (data.nSdiBlockType >= 0 && data.nSdiBlockSpeed > 0) {
                    tvHudSdiSpeed.setText(data.nSdiBlockSpeed + "km/h 区间");
                    tvHudSdiSpeed.setTextColor(0xFFFF5252);
                } else {
                    tvHudSdiSpeed.setTextColor(0xFFFFB74D);
                }
            }
        } else if (data.nSdiBlockType >= 0 && data.nSdiBlockSpeed > 0) {
            tvSdiInfo.setText("区间" + data.nSdiBlockSpeed + "km/h " + (int) data.nSdiBlockDist + "m");
            if (hudSdiBar != null) {
                hudSdiBar.setVisibility(View.VISIBLE);
                tvHudSdiSpeed.setText(data.nSdiBlockSpeed + "km/h 区间");
                tvHudSdiSpeed.setTextColor(0xFFFF5252);
                String distStr = data.nSdiBlockDist >= 1000
                    ? String.format("%.1fkm", data.nSdiBlockDist / 1000.0)
                    : (int) data.nSdiBlockDist + "m";
                tvHudSdiDist.setText(distStr);
            }
        } else {
            tvSdiInfo.setText("无");
            if (hudSdiBar != null) {
                hudSdiBar.setVisibility(View.GONE);
            }
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

        // Feature 3: 服务区/收费站 HUD
        if (hudSapaBar != null) {
            // 优先显示最近的服务区
            int dist = data.sapaDist;
            String name = data.sapaName;
            int type = data.sapaType;
            if (dist <= 0 && data.nextSapaDist > 0) {
                dist = data.nextSapaDist;
                name = data.nextSapaName;
                type = data.nextSapaType;
            }
            if (dist > 0 && name != null && !name.isEmpty()) {
                hudSapaBar.setVisibility(View.VISIBLE);
                // type: 0=服务区, 1=收费站, 2=加油站
                String icon = type == 1 ? "🅿️" : type == 2 ? "⛽" : "🛑";
                tvHudSapaIcon.setText(icon);
                tvHudSapaName.setText(name);
                String distStr = dist >= 1000
                    ? String.format("%.1fkm", dist / 1000.0)
                    : dist + "m";
                tvHudSapaDist.setText(distStr);
            } else {
                hudSapaBar.setVisibility(View.GONE);
            }
        }

        // Feature 4: ETA 到达时间
        if (tvHudEta != null) {
            String eta = data.etaText;
            if (eta != null && !eta.isEmpty()) {
                tvHudEta.setVisibility(View.VISIBLE);
                // 简化显示：去掉"预计"前缀
                String shortEta = eta.replace("预计", "").trim();
                tvHudEta.setText(shortEta);
            } else {
                tvHudEta.setVisibility(View.GONE);
            }
        }

        // Feature 5: 路况拥堵提醒
        if (hudTmcBar != null) {
            int totalCongestion = data.tmcJamDist + data.tmcBlockDist;
            if (totalCongestion > 0 || data.tmcSlowDist > 1000) {
                hudTmcBar.setVisibility(View.VISIBLE);
                StringBuilder sb = new StringBuilder();
                if (data.tmcBlockDist > 0) {
                    sb.append("严重拥堵 ").append(formatDist(data.tmcBlockDist));
                }
                if (data.tmcJamDist > 0) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append("拥堵 ").append(formatDist(data.tmcJamDist));
                }
                if (data.tmcSlowDist > 1000) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append("缓行 ").append(formatDist(data.tmcSlowDist));
                }
                tvHudTmc.setText(sb.toString());
                // 严重拥堵用红色，普通拥堵用橙色
                tvHudTmc.setTextColor(data.tmcBlockDist > 0 ? 0xFFFF5252 : 0xFFFFB74D);
            } else {
                hudTmcBar.setVisibility(View.GONE);
            }
        }

        // Feature 6: 连续转弯预告
        if (hudNextTurnBar != null) {
            if (data.nextNextTurnIcon > 0 && data.nextNextRoadName != null && !data.nextNextRoadName.isEmpty()) {
                hudNextTurnBar.setVisibility(View.VISIBLE);
                tvHudNextTurnIcon.setText(getTurnEmoji(data.nextNextTurnIcon));
                tvHudNextTurnName.setText(getTurnName(data.nextNextTurnIcon) + " " + data.nextNextRoadName);
            } else {
                hudNextTurnBar.setVisibility(View.GONE);
            }
        }

        // Feature 8: 行程统计
        if (hudTripBar != null && tripStartTime > 0) {
            hudTripBar.setVisibility(View.VISIBLE);
            long elapsed = (System.currentTimeMillis() - tripStartTime) / 1000;
            int mins = (int) (elapsed / 60);
            String distStr = tripDistance >= 1000
                ? String.format("%.1fkm", tripDistance / 1000.0)
                : (int) tripDistance + "m";
            int avgSpeed = elapsed > 60 && tripDistance > 100
                ? (int) (tripDistance / elapsed * 3.6)
                : 0;
            String tripText = distStr + " · " + mins + "min";
            if (avgSpeed > 0) tripText += " · 均" + avgSpeed;
            if (tripMaxSpeed > 0) tripText += " · 峰" + tripMaxSpeed;
            if (tripOverspeedCount > 0) tripText += " · ⚠" + tripOverspeedCount;
            tvHudTrip.setText(tripText);
        }

        // 变道提醒：进匝道/出匝道/左转/右转/收费站，距离 2km 以内
        if (hudLaneBar != null) {
            int turnType = data.nTBTTurnType;
            int dist = (int) data.nTBTDist;
            boolean needLaneAlert = dist > 0 && dist <= 2000 && isLaneChangeScenario(turnType);

            if (needLaneAlert) {
                hudLaneBar.setVisibility(View.VISIBLE);
                String action = getLaneAction(turnType);
                String distStr = formatDist(dist);
                String nextRoad = "";
                // 从 AmapNaviReceiver 获取下条路名
                NaviData nd = AmapNaviReceiver.getCurrentData();
                // 用 szPosRoadName 以外的信息（下条路名在 debug 里）

                tvHudLaneText.setText("前方 " + distStr + " " + action);

                // 详细提示
                String detail;
                if (turnType == 14 || turnType == 15) {
                    detail = "请提前变道至最右车道";
                } else if (turnType == 2 || turnType == 4 || turnType == 6) {
                    detail = "请提前变道至最左车道";
                } else if (turnType == 3 || turnType == 5 || turnType == 7) {
                    detail = "请提前变道至最右车道";
                } else if (turnType == 16) {
                    detail = "请减速准备";
                } else {
                    detail = "请注意前方路况";
                }
                tvHudLaneDetail.setText(detail);

                // 距离越近越醒目：>1km 黄色, 500m-1km 橙色, <500m 红色
                if (dist <= 500) {
                    hudLaneBar.setBackgroundResource(R.drawable.hud_lane_alert);
                    tvHudLaneIcon.setText("🚨");
                    tvHudLaneText.setTextColor(0xFFFFFFFF);
                } else if (dist <= 1000) {
                    hudLaneBar.setBackgroundResource(R.drawable.hud_lane_warn);
                    tvHudLaneIcon.setText("⚠️");
                    tvHudLaneText.setTextColor(0xFFFFFFFF);
                } else {
                    hudLaneBar.setBackgroundResource(R.drawable.hud_lane_warn);
                    tvHudLaneIcon.setText("📍");
                    tvHudLaneText.setTextColor(0xFFFFFFFF);
                }
            } else {
                hudLaneBar.setVisibility(View.GONE);
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

    // ---- IP 记忆 ----
    private void saveIp(String ip) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_C3_IP, ip).apply();
    }

    private void loadSavedIp() {
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_C3_IP, "");
        if (!saved.isEmpty()) {
            etManualIp.setText(saved);
        }
    }

    // ---- 自定义限速 ----
    private static final int[] SPEED_LEVELS = {120, 100, 80, 60};

    private void loadSpeedMappings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        for (int original : SPEED_LEVELS) {
            int target = prefs.getInt("speed_map_" + original, 0);
            if (target > 0 && target != original) {
                AmapNaviReceiver.setSpeedMapping(original, target);
            }
        }
    }

    // 当前选中的摄像头
    private String currentCam = "road";

    /** 设置弹窗：摄像头切换 + 自定义限速 */
    @SuppressWarnings("deprecation")
    private void showSettingsDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 圆角背景
        GradientDrawable dialogBg = new GradientDrawable();
        dialogBg.setColor(0xF01A1A2E);
        dialogBg.setCornerRadius(dp(16));
        dialogBg.setStroke(dp(1), 0x336C63FF);

        // 根布局
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        root.setBackground(dialogBg);

        // ── 标题 ──
        TextView title = new TextView(this);
        title.setText("⚙️  设置");
        title.setTextSize(18);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        // ── 摄像头切换 ──
        TextView camTitle = new TextView(this);
        camTitle.setText("📷 摄像头切换");
        camTitle.setTextSize(13);
        camTitle.setTextColor(0xBBFFFFFF);
        camTitle.setPadding(0, 0, 0, dp(8));
        root.addView(camTitle);

        LinearLayout camRow = new LinearLayout(this);
        camRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] camLabels = {"前方", "广角", "驾驶员"};
        String[] camValues = {"road", "wideRoad", "driver"};
        Button[] camBtns = new Button[3];
        for (int i = 0; i < 3; i++) {
            Button btn = new Button(this);
            btn.setText(camLabels[i]);
            btn.setTextSize(13);
            btn.setAllCaps(false);
            btn.setStateListAnimator(null);
            btn.setElevation(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
            lp.setMargins(i == 0 ? 0 : dp(4), 0, i == 2 ? 0 : dp(4), 0);
            btn.setLayoutParams(lp);
            camBtns[i] = btn;

            // 选中状态
            boolean selected = camValues[i].equals(currentCam);
            applyCamBtnStyle(btn, selected);

            final int idx = i;
            final String camVal = camValues[i];
            btn.setOnClickListener(v -> {
                currentCam = camVal;
                // 更新所有按钮样式
                for (int j = 0; j < 3; j++) {
                    applyCamBtnStyle(camBtns[j], camValues[j].equals(currentCam));
                }
                // 切换摄像头：直接修改页面 cam 变量 + postMessage
                if (wvVideo != null) {
                    wvVideo.evaluateJavascript(
                        "cam='" + camVal + "'; window.postMessage({cam:'" + camVal + "'}, '*');",
                        null);
                }
                Toast.makeText(this, "已切换: " + camLabels[idx], Toast.LENGTH_SHORT).show();
            });
            camRow.addView(btn);
        }
        root.addView(camRow);

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(16), 0, dp(16));
        divider.setLayoutParams(divLp);
        root.addView(divider);

        // ── 自定义限速 ──
        TextView speedTitle = new TextView(this);
        speedTitle.setText("🚀 自定义限速映射");
        speedTitle.setTextSize(13);
        speedTitle.setTextColor(0xBBFFFFFF);
        speedTitle.setPadding(0, 0, 0, dp(4));
        root.addView(speedTitle);

        TextView speedHint = new TextView(this);
        speedHint.setText("留空=不修改  填入目标速度覆盖导航限速");
        speedHint.setTextSize(10);
        speedHint.setTextColor(0x77FFFFFF);
        speedHint.setPadding(0, 0, 0, dp(10));
        root.addView(speedHint);

        EditText[] speedFields = new EditText[SPEED_LEVELS.length];
        for (int i = 0; i < SPEED_LEVELS.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));

            TextView label = new TextView(this);
            label.setText("限速 " + SPEED_LEVELS[i] + " →");
            label.setTextSize(13);
            label.setTextColor(0xCCFFFFFF);
            LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                dp(90), LinearLayout.LayoutParams.WRAP_CONTENT);
            label.setLayoutParams(lblLp);
            row.addView(label);

            // 输入框带圆角
            GradientDrawable etBg = new GradientDrawable();
            etBg.setColor(0xFF2A2A4A);
            etBg.setCornerRadius(dp(6));

            EditText et = new EditText(this);
            et.setHint("" + SPEED_LEVELS[i]);
            et.setTextSize(14);
            et.setTextColor(0xFFFFFFFF);
            et.setHintTextColor(0x44FFFFFF);
            et.setBackground(etBg);
            et.setPadding(dp(10), dp(6), dp(10), dp(6));
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                dp(72), LinearLayout.LayoutParams.WRAP_CONTENT);
            etLp.setMargins(dp(8), 0, 0, 0);
            et.setLayoutParams(etLp);

            // 加载已保存的值
            int saved = prefs.getInt("speed_map_" + SPEED_LEVELS[i], 0);
            if (saved > 0 && saved != SPEED_LEVELS[i]) {
                et.setText(String.valueOf(saved));
            }
            speedFields[i] = et;
            row.addView(et);

            TextView unit = new TextView(this);
            unit.setText(" km/h");
            unit.setTextSize(11);
            unit.setTextColor(0x77FFFFFF);
            row.addView(unit);

            root.addView(row);
        }

        // 当前映射状态
        int mapCount = 0;
        StringBuilder mapInfo = new StringBuilder();
        for (int orig : SPEED_LEVELS) {
            int t = prefs.getInt("speed_map_" + orig, 0);
            if (t > 0 && t != orig) {
                mapCount++;
                if (mapInfo.length() > 0) mapInfo.append("  ");
                mapInfo.append(orig).append("→").append(t);
            }
        }
        if (mapCount > 0) {
            TextView statusTv = new TextView(this);
            statusTv.setText("✅ 当前: " + mapInfo);
            statusTv.setTextSize(11);
            statusTv.setTextColor(0xFF00E5A0);
            statusTv.setPadding(0, dp(10), 0, 0);
            root.addView(statusTv);
        }

        // ── 按钮行 ──
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowLp.setMargins(0, dp(18), 0, 0);
        btnRow.setLayoutParams(btnRowLp);

        Button btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setTextSize(13);
        btnCancel.setTextColor(0x99FFFFFF);
        btnCancel.setBackgroundColor(0x00000000);
        btnCancel.setStateListAnimator(null);
        btnCancel.setAllCaps(false);
        btnRow.addView(btnCancel);

        Button btnSave = new Button(this);
        btnSave.setText("保存");
        btnSave.setTextSize(13);
        btnSave.setTextColor(0xFF00E5A0);
        btnSave.setBackgroundColor(0x00000000);
        btnSave.setStateListAnimator(null);
        btnSave.setAllCaps(false);
        btnRow.addView(btnSave);

        root.addView(btnRow);

        // 创建 Dialog
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(root)
            .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            // 保存限速映射
            SharedPreferences.Editor editor = prefs.edit();
            AmapNaviReceiver.clearSpeedMappings();
            int count = 0;
            for (int i = 0; i < SPEED_LEVELS.length; i++) {
                String text = speedFields[i].getText().toString().trim();
                int original = SPEED_LEVELS[i];
                if (!text.isEmpty()) {
                    try {
                        int target = Integer.parseInt(text);
                        if (target > 0 && target != original) {
                            AmapNaviReceiver.setSpeedMapping(original, target);
                            editor.putInt("speed_map_" + original, target);
                            count++;
                        } else {
                            editor.remove("speed_map_" + original);
                        }
                    } catch (NumberFormatException e) {
                        editor.remove("speed_map_" + original);
                    }
                } else {
                    editor.remove("speed_map_" + original);
                }
            }
            editor.apply();
            Toast.makeText(this,
                count > 0 ? count + "条限速映射已保存" : "已清除所有映射",
                Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    /** 摄像头按钮样式：选中/未选中 */
    private void applyCamBtnStyle(Button btn, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (selected) {
            bg.setColor(0xFF6C63FF);
            btn.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0xFF2A2A4A);
            btn.setTextColor(0xAAFFFFFF);
        }
        btn.setBackground(bg);
    }

    // ---- 辅助方法 ----
    private String formatDist(int meters) {
        if (meters >= 1000) {
            return String.format("%.1fkm", meters / 1000.0);
        }
        return meters + "m";
    }

    private String getTurnEmoji(int type) {
        switch (type) {
            case 2: return "⬅️";
            case 3: return "➡️";
            case 4: return "↖️";
            case 5: return "↗️";
            case 6: return "↙️";
            case 7: return "↘️";
            case 8: return "↩️";
            case 9: return "⬆️";
            case 14: return "🔀";
            case 15: return "🔀";
            case 16: return "🅿️";
            default: return "↗️";
        }
    }

    /** 判断是否需要变道提醒的场景 */
    private boolean isLaneChangeScenario(int turnType) {
        switch (turnType) {
            case 2:  // 左转
            case 3:  // 右转
            case 4:  // 左前方
            case 5:  // 右前方
            case 6:  // 左后方
            case 7:  // 右后方
            case 14: // 进匝道
            case 15: // 出匝道
            case 16: // 收费站
                return true;
            default:
                return false;
        }
    }

    /** 获取变道动作描述 */
    private String getLaneAction(int turnType) {
        switch (turnType) {
            case 2: return "左转";
            case 3: return "右转";
            case 4: return "左前方转弯";
            case 5: return "右前方转弯";
            case 6: return "左后方转弯";
            case 7: return "右后方转弯";
            case 14: return "进入匝道";
            case 15: return "驶出匝道";
            case 16: return "收费站";
            default: return "转弯";
        }
    }

    /** Haversine 公式计算两点距离 (米) */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Feature 8: 保存行程数据到 SharedPreferences */
    private void saveTripData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long elapsed = (System.currentTimeMillis() - tripStartTime) / 1000;
        int avgSpeed = elapsed > 60 ? (int) (tripDistance / elapsed * 3.6) : 0;
        String summary = String.format("%.1fkm %dmin 均速%d 峰速%d 超速%d次",
            tripDistance / 1000.0, elapsed / 60, avgSpeed, tripMaxSpeed, tripOverspeedCount);
        // 追加到历史记录
        String history = prefs.getString("trip_history", "");
        String timestamp = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(new java.util.Date(tripStartTime));
        String entry = timestamp + " " + summary;
        if (!history.isEmpty()) {
            // 最多保留 20 条
            String[] lines = history.split("\n");
            if (lines.length >= 20) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 19; i < lines.length; i++) {
                    sb.append(lines[i]).append("\n");
                }
                history = sb.toString();
            }
        }
        prefs.edit().putString("trip_history", history + entry + "\n").apply();
        Log.i(TAG, "行程已保存: " + entry);
    }

    // ---- 横屏适配 ----
    private void applyOrientationLayout() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (controlPanel != null) {
            controlPanel.setVisibility(landscape ? View.GONE : View.VISIBLE);
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
