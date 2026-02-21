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
 * 广播包含的 extras 字段：
 * - KEY_TYPE: 数据类型 (1=GPS, 2=限速, 3=SDI摄像头, 4=TBT转弯, 5=目的地)
 * - 各类型对应的具体字段
 */
public class AmapNaviReceiver extends BroadcastReceiver {
    private static final String TAG = "AmapNaviReceiver";

    // 高德广播 KEY_TYPE 类型定义
    public static final int TYPE_GPS = 1;
    public static final int TYPE_ROAD_LIMIT = 2;
    public static final int TYPE_SDI = 3;
    public static final int TYPE_TBT = 4;
    public static final int TYPE_DEST = 5;
    public static final int TYPE_TRAFFIC_LIGHT = 6;

    // 回调接口
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

        Log.d(TAG, "收到广播: " + action);

        try {
            parseAmapBroadcast(extras);
            sLastUpdateTime = System.currentTimeMillis();
            if (sCallback != null) {
                sCallback.onNaviDataUpdated(sData);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析广播数据失败", e);
        }
    }

    private void parseAmapBroadcast(Bundle extras) {
        // 高德标准广播格式：通过 KEY_TYPE 区分数据类型
        int keyType = extras.getInt("KEY_TYPE", -1);

        if (keyType > 0) {
            parseByKeyType(extras, keyType);
            return;
        }

        // 兼容模式：直接从 extras 中读取所有可用字段
        parseDirectFields(extras);
    }

    private void parseByKeyType(Bundle extras, int keyType) {
        switch (keyType) {
            case TYPE_GPS:
                parseGps(extras);
                break;
            case TYPE_ROAD_LIMIT:
                parseRoadLimit(extras);
                break;
            case TYPE_SDI:
                parseSdi(extras);
                break;
            case TYPE_TBT:
                parseTbt(extras);
                break;
            case TYPE_DEST:
                parseDest(extras);
                break;
            case TYPE_TRAFFIC_LIGHT:
                parseTrafficLight(extras);
                break;
        }
    }

    private void parseGps(Bundle extras) {
        sData.vpPosPointLat = extras.getDouble("Latitude", sData.vpPosPointLat);
        sData.vpPosPointLon = extras.getDouble("Longitude", sData.vpPosPointLon);
        sData.nPosAngle = extras.getFloat("Direction", sData.nPosAngle);
        // 兼容不同字段名
        if (sData.vpPosPointLat == 0) {
            sData.vpPosPointLat = extras.getDouble("lat", 0);
            sData.vpPosPointLon = extras.getDouble("lon", 0);
        }
    }

    private void parseRoadLimit(Bundle extras) {
        sData.nRoadLimitSpeed = extras.getInt("LimitSpeed", sData.nRoadLimitSpeed);
        sData.szPosRoadName = extras.getString("RoadName", sData.szPosRoadName);
        sData.roadcate = extras.getInt("RoadType", sData.roadcate);
        // 兼容
        if (sData.nRoadLimitSpeed == 0) {
            sData.nRoadLimitSpeed = extras.getInt("nRoadLimitSpeed", 0);
        }
    }

    private void parseSdi(Bundle extras) {
        sData.nSdiType = extras.getInt("SdiType", sData.nSdiType);
        sData.nSdiSpeedLimit = extras.getInt("SdiLimitSpeed", sData.nSdiSpeedLimit);
        sData.nSdiDist = extras.getDouble("SdiDist", sData.nSdiDist);
        sData.nSdiBlockType = extras.getInt("SdiBlockType", sData.nSdiBlockType);
        sData.nSdiBlockSpeed = extras.getInt("SdiBlockSpeed", sData.nSdiBlockSpeed);
        sData.nSdiBlockDist = extras.getDouble("SdiBlockDist", sData.nSdiBlockDist);
        // 兼容
        if (sData.nSdiType == -1) {
            sData.nSdiType = extras.getInt("nSdiType", -1);
            sData.nSdiSpeedLimit = extras.getInt("nSdiSpeedLimit", 0);
            sData.nSdiDist = extras.getDouble("nSdiDist", 0);
        }
    }

    private void parseTbt(Bundle extras) {
        sData.nTBTDist = extras.getDouble("SegRemainDist", sData.nTBTDist);
        sData.nTBTTurnType = extras.getInt("Icon", sData.nTBTTurnType);
        String roadName = extras.getString("CurRoadName", null);
        if (roadName != null) sData.szPosRoadName = roadName;
        // 兼容
        if (sData.nTBTDist == 0) {
            sData.nTBTDist = extras.getDouble("nTBTDist", 0);
            sData.nTBTTurnType = extras.getInt("nTBTTurnType", 0);
        }
    }

    private void parseDest(Bundle extras) {
        sData.nGoPosDist = extras.getInt("RouteRemainDist", sData.nGoPosDist);
        sData.nGoPosTime = extras.getInt("RouteRemainTime", sData.nGoPosTime);
    }

    private void parseTrafficLight(Bundle extras) {
        sData.nTrafficLight = extras.getInt("TrafficLight", 0);
        sData.nTrafficLightDist = extras.getInt("TrafficLightDist", 0);
        sData.nTrafficLightSec = extras.getInt("TrafficLightSec", 0);
    }

    /**
     * 兼容模式：直接从 extras 读取所有可能的字段
     * 某些版本的高德地图可能不使用 KEY_TYPE，而是直接放所有字段
     */
    private void parseDirectFields(Bundle extras) {
        // GPS
        if (extras.containsKey("Latitude")) {
            sData.vpPosPointLat = extras.getDouble("Latitude", 0);
            sData.vpPosPointLon = extras.getDouble("Longitude", 0);
            sData.nPosAngle = extras.getFloat("Direction", 0);
        }

        // 限速
        if (extras.containsKey("LimitSpeed")) {
            sData.nRoadLimitSpeed = extras.getInt("LimitSpeed", 0);
        }

        // 路名
        if (extras.containsKey("CurRoadName")) {
            sData.szPosRoadName = extras.getString("CurRoadName", "");
        }

        // SDI
        if (extras.containsKey("SdiType")) {
            sData.nSdiType = extras.getInt("SdiType", -1);
            sData.nSdiSpeedLimit = extras.getInt("SdiLimitSpeed", 0);
            sData.nSdiDist = extras.getDouble("SdiDist", 0);
        }

        // TBT
        if (extras.containsKey("SegRemainDist")) {
            sData.nTBTDist = extras.getDouble("SegRemainDist", 0);
            sData.nTBTTurnType = extras.getInt("Icon", 0);
        }

        // 目的地
        if (extras.containsKey("RouteRemainDist")) {
            sData.nGoPosDist = extras.getInt("RouteRemainDist", 0);
            sData.nGoPosTime = extras.getInt("RouteRemainTime", 0);
        }

        // 打印所有 extras 用于调试
        for (String key : extras.keySet()) {
            Object val = extras.get(key);
            Log.d(TAG, "  " + key + " = " + val + " (" + (val != null ? val.getClass().getSimpleName() : "null") + ")");
        }
    }
}
