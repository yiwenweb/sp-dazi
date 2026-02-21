package com.sp.dazi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.sp.dazi.model.NaviData;

/**
 * 高德车机版 (AmapAuto) 导航广播接收器
 *
 * Action: autonavi_standard_broadcast_send
 * 高德车机版 9.x+ 在导航中会以 1Hz 推送广播，接近转向点时 10Hz。
 */
public class AmapNaviReceiver extends BroadcastReceiver {
    private static final String TAG = "AmapNaviReceiver";
    public static final String AMAP_ACTION = "autonavi_standard_broadcast_send";

    public interface NaviDataCallback {
        void onNaviDataUpdated(NaviData data);
    }

    private static NaviDataCallback sCallback;
    private static final NaviData sData = new NaviData();
    private static long sLastUpdateTime = 0;
    private static int sReceiveCount = 0;
    private static String sDebugInfo = "";

    public static void setCallback(NaviDataCallback cb) { sCallback = cb; }
    public static NaviData getCurrentData() { return sData; }
    public static long getLastUpdateTime() { return sLastUpdateTime; }
    public static int getReceiveCount() { return sReceiveCount; }
    public static String getDebugInfo() { return sDebugInfo; }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            Log.w(TAG, "onReceive: intent为空");
            return;
        }

        sReceiveCount++;
        String action = intent.getAction();
        String pkg = intent.getPackage();
        Bundle bundle = intent.getExtras();

        Log.d(TAG, "收到广播 #" + sReceiveCount + " action=" + action + " package=" + pkg);

        if (bundle == null) {
            Log.d(TAG, "Bundle为空");
            sDebugInfo = "#" + sReceiveCount + " action=" + action + " bundle=null";
            return;
        }

        Log.d(TAG, "Bundle size=" + bundle.size());

        // 打印所有字段（调试）
        StringBuilder dbg = new StringBuilder();
        for (String key : bundle.keySet()) {
            Object val = bundle.get(key);
            Log.d(TAG, "  " + key + " = " + val);
            if (dbg.length() < 200) {
                dbg.append(key).append("=").append(val).append("\n");
            }
        }
        sDebugInfo = dbg.toString();

        // 解析字段
        sData.nRoadLimitSpeed = bundle.getInt("current_speed_limit", sData.nRoadLimitSpeed);
        String road = bundle.getString("current_road_name", null);
        if (road != null) sData.szPosRoadName = road;
        sData.roadcate = bundle.getInt("road_type", sData.roadcate);

        double lat = bundle.getDouble("gps_lat", 0);
        double lon = bundle.getDouble("gps_lon", 0);
        if (lat != 0) sData.vpPosPointLat = lat;
        if (lon != 0) sData.vpPosPointLon = lon;
        sData.nPosAngle = bundle.getFloat("gps_heading", sData.nPosAngle);

        sData.nTBTTurnType = bundle.getInt("tbt_type", sData.nTBTTurnType);
        sData.nTBTDist = bundle.getInt("tbt_distance", (int) sData.nTBTDist);

        sData.nSdiType = bundle.getInt("camera_type", sData.nSdiType);
        sData.nSdiDist = bundle.getInt("camera_distance", (int) sData.nSdiDist);
        sData.nSdiSpeedLimit = bundle.getInt("camera_speed_limit", sData.nSdiSpeedLimit);

        sData.nTrafficLight = bundle.getInt("traffic_light_state", sData.nTrafficLight);
        sData.nTrafficLightSec = bundle.getInt("traffic_light_countdown", sData.nTrafficLightSec);

        int totalDist = bundle.getInt("total_distance", 0);
        if (totalDist != 0) sData.nGoPosDist = totalDist;
        int totalTime = bundle.getInt("total_time", 0);
        if (totalTime != 0) sData.nGoPosTime = totalTime;

        sLastUpdateTime = System.currentTimeMillis();
        if (sCallback != null) {
            sCallback.onNaviDataUpdated(sData);
        }
    }
}
