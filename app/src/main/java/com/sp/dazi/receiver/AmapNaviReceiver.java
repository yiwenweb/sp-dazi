package com.sp.dazi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.sp.dazi.model.NaviData;

/**
 * 高德地图（车机版/手机版）导航广播接收器
 *
 * 高德在导航过程中通过以下 Action 发送广播：
 *   autonavi_standard_broadcast_send
 *
 * 数据以 key-value 键值对存储在 Bundle 中。
 * 字段名基于高德车机版 9.x 实测协议。
 */
public class AmapNaviReceiver extends BroadcastReceiver {
    private static final String TAG = "AmapNaviReceiver";

    // 高德广播 Action（实测）
    public static final String ACTION_AMAP_SEND = "autonavi_standard_broadcast_send";
    // 兼容其他可能的 Action
    public static final String ACTION_NAVI_INFO = "com.autonavi.minimap.broadcast.NAVI_INFO";
    public static final String ACTION_CYCLIC = "com.autonavi.minimap.broadcast.CYCLIC_NAVI_INFO";
    public static final String ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV";

    public interface NaviDataCallback {
        void onNaviDataUpdated(NaviData data);
    }

    private static NaviDataCallback sCallback;
    private static final NaviData sData = new NaviData();
    private static long sLastUpdateTime = 0;
    private static int sReceiveCount = 0;

    public static void setCallback(NaviDataCallback callback) {
        sCallback = callback;
    }

    public static NaviData getCurrentData() {
        return sData;
    }

    public static long getLastUpdateTime() {
        return sLastUpdateTime;
    }

    public static int getReceiveCount() {
        return sReceiveCount;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Bundle extras = intent.getExtras();
        if (extras == null) return;

        sReceiveCount++;
        Log.d(TAG, "收到广播 #" + sReceiveCount + ": " + action + " keys=" + extras.keySet().size());

        try {
            // 打印所有 key 用于调试（前 30 个）
            int count = 0;
            for (String key : extras.keySet()) {
                Object val = extras.get(key);
                Log.d(TAG, "  [" + key + "] = " + val + " (" + (val != null ? val.getClass().getSimpleName() : "null") + ")");
                if (++count >= 30) {
                    Log.d(TAG, "  ... 还有 " + (extras.keySet().size() - 30) + " 个字段");
                    break;
                }
            }

            parseAmapFields(extras);
            sLastUpdateTime = System.currentTimeMillis();
            if (sCallback != null) {
                sCallback.onNaviDataUpdated(sData);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析广播数据失败", e);
        }
    }

    /**
     * 解析高德广播字段
     * 同时兼容两套字段名：
     *   - 高德车机版原始字段名 (current_speed_limit, tbt_type, gps_lon 等)
     *   - CP搭子风格字段名 (nRoadLimitSpeed, nTBTTurnType 等)
     */
    private void parseAmapFields(Bundle extras) {
        // ---- 导航状态 ----
        // navi_state: 0=未导航, 1=导航中, 2=已到达

        // ---- 道路限速 ----
        sData.nRoadLimitSpeed = firstInt(extras, 0,
            "current_speed_limit", "nRoadLimitSpeed", "LimitSpeed");

        // ---- 道路名称 ----
        String roadName = firstString(extras,
            "current_road_name", "szPosRoadName", "CurRoadName", "RoadName");
        if (roadName != null && !roadName.isEmpty()) {
            sData.szPosRoadName = roadName;
        }

        // ---- 道路类型 ----
        sData.roadcate = firstInt(extras, sData.roadcate,
            "road_type", "roadcate", "RoadType");

        // ---- GPS ----
        double lat = firstDouble(extras, 0,
            "gps_lat", "latitude", "vpPosPointLat", "Latitude");
        double lon = firstDouble(extras, 0,
            "gps_lon", "longitude", "vpPosPointLon", "Longitude");
        if (lat != 0) sData.vpPosPointLat = lat;
        if (lon != 0) sData.vpPosPointLon = lon;

        float heading = firstFloat(extras, 0,
            "gps_heading", "heading", "nPosAngle", "Direction");
        if (heading != 0) sData.nPosAngle = heading;

        // ---- TBT 转弯 ----
        int tbtType = firstInt(extras, 0,
            "tbt_type", "nTBTTurnType", "Icon");
        if (tbtType != 0) sData.nTBTTurnType = tbtType;

        double tbtDist = firstDouble(extras, 0,
            "tbt_distance", "nTBTDist", "SegRemainDist");
        if (tbtDist != 0) sData.nTBTDist = tbtDist;

        // ---- 测速摄像头 ----
        int camType = firstInt(extras, -1,
            "camera_type", "nSdiType", "SdiType");
        if (camType != -1) sData.nSdiType = camType;

        int camDist = firstInt(extras, 0,
            "camera_distance", "nSdiDist");
        if (camDist != 0) sData.nSdiDist = camDist;

        int camSpeed = firstInt(extras, 0,
            "camera_speed_limit", "nSdiSpeedLimit", "SdiLimitSpeed");
        if (camSpeed != 0) sData.nSdiSpeedLimit = camSpeed;

        // ---- 区间测速 ----
        sData.nSdiBlockType = firstInt(extras, sData.nSdiBlockType,
            "nSdiBlockType", "SdiBlockType");
        sData.nSdiBlockSpeed = firstInt(extras, sData.nSdiBlockSpeed,
            "nSdiBlockSpeed", "SdiBlockSpeed");
        double blockDist = firstDouble(extras, 0,
            "nSdiBlockDist", "SdiBlockDist");
        if (blockDist != 0) sData.nSdiBlockDist = blockDist;

        // ---- 交通灯 ----
        sData.nTrafficLight = firstInt(extras, sData.nTrafficLight,
            "traffic_light_state", "nTrafficLight", "TrafficLight");
        sData.nTrafficLightDist = firstInt(extras, sData.nTrafficLightDist,
            "traffic_light_distance", "nTrafficLightDist", "TrafficLightDist");
        sData.nTrafficLightSec = firstInt(extras, sData.nTrafficLightSec,
            "traffic_light_countdown", "nTrafficLightSec", "TrafficLightSec");

        // ---- 目的地 ----
        int destDist = firstInt(extras, 0,
            "total_distance", "nGoPosDist", "RouteRemainDist");
        if (destDist != 0) sData.nGoPosDist = destDist;
        int destTime = firstInt(extras, 0,
            "total_time", "nGoPosTime", "RouteRemainTime");
        if (destTime != 0) sData.nGoPosTime = destTime;
    }

    // ---- 工具方法：尝试多个 key，返回第一个存在的值 ----

    private static int firstInt(Bundle b, int def, String... keys) {
        for (String key : keys) {
            if (b.containsKey(key)) {
                return getInt(b, key, def);
            }
        }
        return def;
    }

    private static double firstDouble(Bundle b, double def, String... keys) {
        for (String key : keys) {
            if (b.containsKey(key)) {
                return getDouble(b, key, def);
            }
        }
        return def;
    }

    private static float firstFloat(Bundle b, float def, String... keys) {
        for (String key : keys) {
            if (b.containsKey(key)) {
                return getFloat(b, key, def);
            }
        }
        return def;
    }

    private static String firstString(Bundle b, String... keys) {
        for (String key : keys) {
            if (b.containsKey(key)) {
                Object val = b.get(key);
                if (val != null) return val.toString();
            }
        }
        return null;
    }

    // ---- 类型安全读取（高德广播值类型不固定） ----

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
