package com.sp.dazi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.sp.dazi.model.NaviData;

/**
 * 高德地图导航广播接收器
 *
 * 高德地图在导航过程中会发送以下广播：
 * - com.autonavi.minimap.broadcast.NAVI_INFO (单次导航信息)
 * - com.autonavi.minimap.broadcast.CYCLIC_NAVI_INFO (周期性导航信息)
 * - AUTONAVI_STANDARD_BROADCAST_RECV (标准广播)
 *
 * 广播 extras 直接包含所有导航字段（不使用 KEY_TYPE 分类），
 * 字段名与 CP搭子 实测一致。
 */
public class AmapNaviReceiver extends BroadcastReceiver {
    private static final String TAG = "AmapNaviReceiver";

    public interface NaviDataCallback {
        void onNaviDataUpdated(NaviData data);
    }

    private static NaviDataCallback sCallback;
    private static final NaviData sData = new NaviData();
    private static long sLastUpdateTime = 0;

    public static void setCallback(NaviDataCallback callback) {
        sCallback = callback;
    }

    public static NaviData getCurrentData() {
        return sData;
    }

    public static long getLastUpdateTime() {
        return sLastUpdateTime;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Bundle extras = intent.getExtras();
        if (extras == null) return;

        Log.d(TAG, "收到广播: " + action + " keys=" + extras.keySet());

        try {
            parseFields(extras);
            sLastUpdateTime = System.currentTimeMillis();
            if (sCallback != null) {
                sCallback.onNaviDataUpdated(sData);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析广播数据失败", e);
        }
    }

    /**
     * 解析高德广播 extras — 字段名基于 CP搭子 实测数据
     *
     * 高德广播把所有导航数据平铺在 extras 里，不分 KEY_TYPE。
     * 每次广播可能只包含部分字段，所以用 containsKey 判断后再更新。
     */
    private void parseFields(Bundle extras) {
        // ---- 道路限速 ----
        if (extras.containsKey("nRoadLimitSpeed")) {
            sData.nRoadLimitSpeed = getInt(extras, "nRoadLimitSpeed", sData.nRoadLimitSpeed);
        }

        // ---- 道路信息 ----
        if (extras.containsKey("szPosRoadName")) {
            String name = extras.getString("szPosRoadName", "");
            if (!name.isEmpty()) sData.szPosRoadName = name;
        }
        if (extras.containsKey("roadcate")) {
            sData.roadcate = getInt(extras, "roadcate", sData.roadcate);
        }

        // ---- GPS ----
        if (extras.containsKey("latitude")) {
            sData.vpPosPointLat = getDouble(extras, "latitude", sData.vpPosPointLat);
        }
        if (extras.containsKey("longitude")) {
            sData.vpPosPointLon = getDouble(extras, "longitude", sData.vpPosPointLon);
        }
        if (extras.containsKey("heading")) {
            sData.nPosAngle = getFloat(extras, "heading", sData.nPosAngle);
        }

        // ---- TBT 转弯 ----
        if (extras.containsKey("nTBTDist")) {
            sData.nTBTDist = getDouble(extras, "nTBTDist", sData.nTBTDist);
        }
        if (extras.containsKey("nTBTTurnType")) {
            sData.nTBTTurnType = getInt(extras, "nTBTTurnType", sData.nTBTTurnType);
        }

        // ---- SDI 测速摄像头 ----
        if (extras.containsKey("nSdiType")) {
            sData.nSdiType = getInt(extras, "nSdiType", sData.nSdiType);
        }
        if (extras.containsKey("nSdiSpeedLimit")) {
            sData.nSdiSpeedLimit = getInt(extras, "nSdiSpeedLimit", sData.nSdiSpeedLimit);
        }
        if (extras.containsKey("nSdiDist")) {
            sData.nSdiDist = getDouble(extras, "nSdiDist", sData.nSdiDist);
        }

        // ---- 区间测速 ----
        if (extras.containsKey("nSdiBlockType")) {
            sData.nSdiBlockType = getInt(extras, "nSdiBlockType", sData.nSdiBlockType);
        }
        if (extras.containsKey("nSdiBlockSpeed")) {
            sData.nSdiBlockSpeed = getInt(extras, "nSdiBlockSpeed", sData.nSdiBlockSpeed);
        }
        if (extras.containsKey("nSdiBlockDist")) {
            sData.nSdiBlockDist = getDouble(extras, "nSdiBlockDist", sData.nSdiBlockDist);
        }

        // ---- 目的地 ----
        if (extras.containsKey("nGoPosDist")) {
            sData.nGoPosDist = getInt(extras, "nGoPosDist", sData.nGoPosDist);
        }
        if (extras.containsKey("nGoPosTime")) {
            sData.nGoPosTime = getInt(extras, "nGoPosTime", sData.nGoPosTime);
        }

        // ---- 调试：打印前 20 个 key ----
        int count = 0;
        for (String key : extras.keySet()) {
            Object val = extras.get(key);
            Log.d(TAG, "  " + key + " = " + val);
            if (++count >= 20) break;
        }
    }

    // ---- 类型安全的 extras 读取工具方法 ----
    // 高德广播的值类型不固定，可能是 int/long/float/double/String，需要兼容

    private static int getInt(Bundle b, String key, int def) {
        Object val = b.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Exception e) { /* ignore */ }
        }
        return def;
    }

    private static double getDouble(Bundle b, String key, double def) {
        Object val = b.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (Exception e) { /* ignore */ }
        }
        return def;
    }

    private static float getFloat(Bundle b, String key, float def) {
        Object val = b.get(key);
        if (val instanceof Number) return ((Number) val).floatValue();
        if (val instanceof String) {
            try { return Float.parseFloat((String) val); } catch (Exception e) { /* ignore */ }
        }
        return def;
    }
}
