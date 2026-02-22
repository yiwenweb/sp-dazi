package com.sp.dazi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.sp.dazi.model.NaviData;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 高德车机版 (AmapAuto) 导航广播接收器
 *
 * Action: AUTONAVI_STANDARD_BROADCAST_SEND (全大写!)
 *
 * 高德车机版通过 KEY_TYPE 区分不同类型的广播：
 *   10001 = 导航主数据（道路、限速、摄像头、转弯、GPS等，66个字段）
 *   60073 = 红绿灯数据（状态、倒计时）
 *   12205 = 地理位置
 *   13011 = 路况TMC数据
 *   10019 = 导航状态
 */
public class AmapNaviReceiver extends BroadcastReceiver {
    private static final String TAG = "AmapNaviReceiver";

    // 注意：全大写！之前用小写是错的
    public static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    public interface NaviDataCallback {
        void onNaviDataUpdated(NaviData data);
    }

    private static NaviDataCallback sCallback;
    private static final NaviData sData = new NaviData();
    private static long sLastUpdateTime = 0;
    private static int sReceiveCount = 0;
    private static String sDebugInfo = "";

    // 自定义限速映射：原始限速 → 目标限速 (km/h)
    // 例如 {120: 110} 表示导航限速120时实际按110控制
    private static final java.util.Map<Integer, Integer> sSpeedMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static int sOriginalSpeed = 0; // 记录映射前的原始限速，用于 HUD 显示

    public static void setCallback(NaviDataCallback cb) { sCallback = cb; }
    public static NaviData getCurrentData() { return sData; }
    public static long getLastUpdateTime() { return sLastUpdateTime; }
    public static int getReceiveCount() { return sReceiveCount; }
    public static String getDebugInfo() { return sDebugInfo; }
    public static int getOriginalSpeed() { return sOriginalSpeed; }

    /** 设置限速映射：原始限速 → 目标限速 */
    public static void setSpeedMapping(int originalKph, int targetKph) {
        if (targetKph > 0 && targetKph != originalKph) {
            sSpeedMap.put(originalKph, targetKph);
        } else {
            sSpeedMap.remove(originalKph);
        }
    }

    /** 清除所有限速映射 */
    public static void clearSpeedMappings() {
        sSpeedMap.clear();
    }

    /** 获取当前映射表（用于 UI 显示） */
    public static java.util.Map<Integer, Integer> getSpeedMappings() {
        return new java.util.HashMap<>(sSpeedMap);
    }

    /** 应用限速映射 */
    private static int applySpeedMapping(int speedKph) {
        sOriginalSpeed = speedKph;
        if (speedKph <= 0 || sSpeedMap.isEmpty()) return speedKph;
        Integer mapped = sSpeedMap.get(speedKph);
        if (mapped != null) {
            Log.d(TAG, "限速映射: " + speedKph + " → " + mapped + " km/h");
            return mapped;
        }
        return speedKph;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            Log.w(TAG, "onReceive: intent为空");
            return;
        }

        sReceiveCount++;
        Bundle bundle = intent.getExtras();

        if (bundle == null) {
            Log.d(TAG, "收到广播 #" + sReceiveCount + " bundle=null");
            return;
        }

        int keyType = bundle.getInt("KEY_TYPE", -1);

        switch (keyType) {
            case 10001:
                parseNaviData(bundle);
                break;
            case 60073:
                parseTrafficLight(bundle);
                break;
            case 13011:
                parseTmcData(bundle);
                break;
            default:
                // 12205(地理位置), 10019(状态) 等暂不处理
                break;
        }

        sLastUpdateTime = System.currentTimeMillis();
        if (sCallback != null) {
            sCallback.onNaviDataUpdated(sData);
        }
    }

    /**
     * 解析 KEY_TYPE=10001 导航主数据（66个字段）
     */
    private void parseNaviData(Bundle b) {
        // 道路信息
        String roadName = b.getString("CUR_ROAD_NAME", null);
        if (roadName != null) sData.szPosRoadName = roadName;
        sData.roadcate = b.getInt("ROAD_TYPE", sData.roadcate);

        // 限速 (-1 表示无限速)，应用自定义映射
        int limitSpeed = b.getInt("LIMITED_SPEED", -1);
        int mappedSpeed = limitSpeed > 0 ? applySpeedMapping(limitSpeed) : 0;
        sData.nRoadLimitSpeed = mappedSpeed;

        // GPS (高德车机版可能返回 0.0，表示无定位)
        double lat = b.getDouble("CAR_LATITUDE", 0);
        double lon = b.getDouble("CAR_LONGITUDE", 0);
        if (lat != 0) sData.vpPosPointLat = lat;
        if (lon != 0) sData.vpPosPointLon = lon;
        sData.nPosAngle = b.getInt("CAR_DIRECTION", (int) sData.nPosAngle);

        // 转弯 (ICON: 转弯类型, SEG_REMAIN_DIS: 到转弯点距离)
        sData.nTBTTurnType = b.getInt("ICON", sData.nTBTTurnType);
        sData.nTBTDist = b.getInt("SEG_REMAIN_DIS", (int) sData.nTBTDist);

        // 测速摄像头 / 区间测速
        // CAMERA_TYPE: 0=测速, 1=监控, 2=闯红灯, 3=违章拍照, 4=公交车道,
        //              5=区间测速起点, 6=区间测速终点, 7=应急车道, 8=非机动车道
        //              12=其他摄像头
        int cameraType = b.getInt("CAMERA_TYPE", sData.nSdiType);
        int cameraSpeed = b.getInt("CAMERA_SPEED", sData.nSdiSpeedLimit);
        int cameraDist = b.getInt("CAMERA_DIST", (int) sData.nSdiDist);

        if (cameraType == 5 || cameraType == 6) {
            // 区间测速（起点或终点）
            sData.nSdiBlockType = cameraType;
            sData.nSdiBlockSpeed = cameraSpeed;
            sData.nSdiBlockDist = cameraDist;
            // 同时清除普通测速
            sData.nSdiType = -1;
            sData.nSdiSpeedLimit = 0;
            sData.nSdiDist = 0;
        } else {
            // 普通测速摄像头
            sData.nSdiType = cameraType;
            sData.nSdiSpeedLimit = cameraSpeed;
            sData.nSdiDist = cameraDist;
            // 清除区间测速
            sData.nSdiBlockType = -1;
            sData.nSdiBlockSpeed = 0;
            sData.nSdiBlockDist = 0;
        }

        // 目的地
        sData.nGoPosDist = b.getInt("ROUTE_REMAIN_DIS", sData.nGoPosDist);
        sData.nGoPosTime = b.getInt("ROUTE_REMAIN_TIME", sData.nGoPosTime);

        // Feature 3: 服务区/收费站
        String sapaName = b.getString("SAPA_NAME", null);
        sData.sapaDist = b.getInt("SAPA_DIST", sData.sapaDist);
        sData.sapaType = b.getInt("SAPA_TYPE", sData.sapaType);
        if (sapaName != null) sData.sapaName = sapaName;
        String nextSapaName = b.getString("NEXT_SAPA_NAME", null);
        sData.nextSapaDist = b.getInt("NEXT_SAPA_DIST", sData.nextSapaDist);
        sData.nextSapaType = b.getInt("NEXT_SAPA_TYPE", sData.nextSapaType);
        if (nextSapaName != null) sData.nextSapaName = nextSapaName;

        // Feature 4: ETA 到达时间
        String etaText = b.getString("ETA_TEXT", null);
        if (etaText != null && !etaText.isEmpty()) sData.etaText = etaText;

        // Feature 6: 连续转弯预告
        sData.nextNextTurnIcon = b.getInt("NEXT_NEXT_TURN_ICON", sData.nextNextTurnIcon);
        String nnRoad = b.getString("NEXT_NEXT_ROAD_NAME", null);
        if (nnRoad != null) sData.nextNextRoadName = nnRoad;

        // 下一条路名
        String nextRoad = b.getString("NEXT_ROAD_NAME", null);

        // 调试信息
        String limitInfo = limitSpeed > 0 ? limitSpeed + "km/h" : "无";
        if (limitSpeed > 0 && mappedSpeed != limitSpeed) {
            limitInfo += "→" + mappedSpeed;
        }
        sDebugInfo = "道路:" + sData.szPosRoadName
            + " 限速:" + limitInfo
            + " 摄像头:" + sData.nSdiType + "/" + sData.nSdiSpeedLimit + "km/h/" + (int)sData.nSdiDist + "m"
            + "\n转弯:" + sData.nTBTTurnType + " " + (int)sData.nTBTDist + "m"
            + " 下条路:" + (nextRoad != null ? nextRoad : "--")
            + "\n剩余:" + sData.nGoPosDist + "m " + sData.nGoPosTime + "s"
            + " GPS:" + String.format("%.4f,%.4f", sData.vpPosPointLat, sData.vpPosPointLon);

        Log.d(TAG, "导航数据 #" + sReceiveCount + " road=" + sData.szPosRoadName
            + " limit=" + limitSpeed + " camera=" + sData.nSdiType + "/" + sData.nSdiDist + "m");
    }

    /**
     * 解析 KEY_TYPE=60073 红绿灯数据
     */
    private void parseTrafficLight(Bundle b) {
        // trafficLightStatus: 1=红灯, 2=绿灯, 3=黄灯
        sData.nTrafficLight = b.getInt("trafficLightStatus", 0);
        sData.nTrafficLightSec = b.getInt("redLightCountDownSeconds", 0);

        Log.d(TAG, "红绿灯 #" + sReceiveCount + " status=" + sData.nTrafficLight
            + " countdown=" + sData.nTrafficLightSec + "s");
    }

    /**
     * 解析 KEY_TYPE=13011 路况TMC数据
     * tmc_status: 0=未知, 1=畅通, 2=缓行, 3=拥堵, 4=严重拥堵, 10=无路况
     */
    private void parseTmcData(Bundle b) {
        String tmcJson = b.getString("EXTRA_TMC_SEGMENT", null);
        if (tmcJson == null || tmcJson.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(tmcJson);
            JSONArray segments = obj.optJSONArray("tmc_info");
            if (segments == null) return;

            int slowDist = 0, jamDist = 0, blockDist = 0;
            for (int i = 0; i < segments.length(); i++) {
                JSONObject seg = segments.getJSONObject(i);
                String status = seg.optString("tmc_status", "0");
                int dist = seg.optInt("tmc_segment_distance", 0);
                switch (status) {
                    case "2": slowDist += dist; break;   // 缓行
                    case "3": jamDist += dist; break;    // 拥堵
                    case "4": blockDist += dist; break;  // 严重拥堵
                }
            }
            sData.tmcSlowDist = slowDist;
            sData.tmcJamDist = jamDist;
            sData.tmcBlockDist = blockDist;

            Log.d(TAG, "TMC路况 缓行:" + slowDist + "m 拥堵:" + jamDist + "m 严重:" + blockDist + "m");
        } catch (Exception e) {
            Log.w(TAG, "TMC解析失败", e);
        }
    }
}
