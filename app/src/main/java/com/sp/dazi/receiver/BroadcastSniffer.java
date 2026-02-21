package com.sp.dazi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 广播嗅探器 — 监听所有可能的高德相关广播
 * 用于调试：确认高德车机版到底发了什么 action
 */
public class BroadcastSniffer {
    private static final String TAG = "BroadcastSniffer";

    // 所有可能的高德广播 action（覆盖各版本）
    private static final String[] AMAP_ACTIONS = {
        "autonavi_standard_broadcast_send",
        "AUTONAVI_STANDARD_BROADCAST_SEND",
        "com.autonavi.minimap.broadcast.CYCLIC_NAVI_INFO",
        "com.autonavi.minimap.broadcast.NAVI_INFO",
        "AUTONAVI_STANDARD_BROADCAST_RECV",
        "com.autonavi.amapauto.broadcast.CYCLIC_NAVI_INFO",
        "com.autonavi.amapauto.broadcast.NAVI_INFO",
        "com.autonavi.amapauto.navi.broadcast",
        "com.autonavi.amapauto.CYCLIC_NAVI_INFO",
    };

    public interface SnifferCallback {
        void onBroadcastCaptured(String info);
    }

    private static SnifferCallback sCallback;
    private static final List<String> sCapturedLogs = new ArrayList<>();
    private static int sCaptureCount = 0;

    private final List<BroadcastReceiver> receivers = new ArrayList<>();
    private boolean registered = false;

    public static void setCallback(SnifferCallback cb) { sCallback = cb; }
    public static List<String> getCapturedLogs() { return new ArrayList<>(sCapturedLogs); }
    public static int getCaptureCount() { return sCaptureCount; }

    public static String getLatestLogs(int maxLines) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, sCapturedLogs.size() - maxLines);
        for (int i = start; i < sCapturedLogs.size(); i++) {
            sb.append(sCapturedLogs.get(i)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 注册所有高德相关广播监听
     */
    public void startSniffing(Context context) {
        if (registered) return;

        for (String action : AMAP_ACTIONS) {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    handleBroadcast(action, intent);
                }
            };

            try {
                IntentFilter filter = new IntentFilter(action);
                filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
                receivers.add(receiver);
                Log.i(TAG, "已注册监听: " + action);
            } catch (Exception e) {
                Log.e(TAG, "注册失败: " + action, e);
            }
        }

        // 额外注册一个通配监听 — 监听所有包含 "autonavi" 或 "amap" 的广播
        // 注意：Android 不支持通配 action，所以这里用一个空 filter 来捕获
        // 我们只能靠上面的具体 action 列表

        registered = true;
        Log.i(TAG, "广播嗅探器已启动，监听 " + AMAP_ACTIONS.length + " 个 action");
    }

    public void stopSniffing(Context context) {
        if (!registered) return;
        for (BroadcastReceiver receiver : receivers) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception e) {
                Log.w(TAG, "注销异常", e);
            }
        }
        receivers.clear();
        registered = false;
        Log.i(TAG, "广播嗅探器已停止");
    }

    private void handleBroadcast(String registeredAction, Intent intent) {
        sCaptureCount++;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String actualAction = intent.getAction();
        String pkg = intent.getPackage();
        Bundle bundle = intent.getExtras();

        StringBuilder info = new StringBuilder();
        info.append("[").append(time).append("] #").append(sCaptureCount).append("\n");
        info.append("  Action: ").append(actualAction).append("\n");
        info.append("  注册Action: ").append(registeredAction).append("\n");
        if (pkg != null) info.append("  Package: ").append(pkg).append("\n");

        if (bundle != null) {
            info.append("  Bundle(").append(bundle.size()).append("个字段):\n");
            for (String key : bundle.keySet()) {
                Object val = bundle.get(key);
                String valStr = val != null ? val.toString() : "null";
                if (valStr.length() > 50) valStr = valStr.substring(0, 50) + "...";
                info.append("    ").append(key).append(" = ").append(valStr).append("\n");
            }
        } else {
            info.append("  Bundle: null\n");
        }

        String logEntry = info.toString();
        Log.i(TAG, "捕获广播:\n" + logEntry);

        // 保留最近 50 条
        if (sCapturedLogs.size() >= 50) {
            sCapturedLogs.remove(0);
        }
        sCapturedLogs.add(logEntry);

        if (sCallback != null) {
            sCallback.onBroadcastCaptured(logEntry);
        }
    }
}
