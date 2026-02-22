package com.sp.dazi.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 导航数据模型 — 与 navi_bridge.py 的 JSON 格式完全对应
 */
public class NaviData {
    // 道路限速 km/h
    public int nRoadLimitSpeed = 0;

    // 测速摄像头
    public int nSdiType = -1;          // 测速类型
    public int nSdiSpeedLimit = 0;     // 测速限速 km/h
    public double nSdiDist = 0;        // 到测速点距离 m
    public int nSdiBlockType = -1;     // 区间测速类型
    public int nSdiBlockSpeed = 0;     // 区间测速限速 km/h
    public double nSdiBlockDist = 0;   // 区间测速距离 m

    // GPS
    public double vpPosPointLat = 0;   // 纬度
    public double vpPosPointLon = 0;   // 经度
    public float nPosAngle = 0;        // 航向角

    // 道路信息
    public String szPosRoadName = "";  // 道路名称
    public int roadcate = 0;           // 道路类型

    // 转弯 (TBT)
    public double nTBTDist = 0;        // 到转弯点距离 m
    public int nTBTTurnType = 0;       // 转弯类型

    // 目的地
    public int nGoPosDist = 0;         // 到目的地距离 m
    public int nGoPosTime = 0;         // 到目的地时间 s

    // 交通灯
    public int nTrafficLight = 0;      // 0=无, 1=红, 2=绿, 3=黄
    public int nTrafficLightDist = 0;  // 到红绿灯距离 m
    public int nTrafficLightSec = 0;   // 倒计时秒数

    // Feature 3: 服务区/收费站
    public String sapaName = "";       // 服务区名称
    public int sapaDist = -1;          // 到服务区距离 m (-1=无)
    public int sapaType = -1;          // 服务区类型
    public String nextSapaName = "";   // 下一个服务区名称
    public int nextSapaDist = -1;      // 下一个服务区距离 m
    public int nextSapaType = -1;      // 下一个服务区类型

    // Feature 4: ETA 到达时间
    public String etaText = "";        // 预计到达时间文本 (如 "预计明天00:40到达")

    // Feature 5: 路况拥堵
    public int tmcSlowDist = 0;        // 缓行距离 m (tmc_status=2)
    public int tmcJamDist = 0;         // 拥堵距离 m (tmc_status=3)
    public int tmcBlockDist = 0;       // 严重拥堵距离 m (tmc_status=4)

    // Feature 6: 连续转弯预告
    public int nextNextTurnIcon = 0;   // 下下个转弯类型
    public String nextNextRoadName = ""; // 下下条路名

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("nRoadLimitSpeed", nRoadLimitSpeed);
            j.put("nSdiType", nSdiType);
            j.put("nSdiSpeedLimit", nSdiSpeedLimit);
            j.put("nSdiDist", nSdiDist);
            j.put("nSdiBlockType", nSdiBlockType);
            j.put("nSdiBlockSpeed", nSdiBlockSpeed);
            j.put("nSdiBlockDist", nSdiBlockDist);
            j.put("vpPosPointLat", vpPosPointLat);
            j.put("vpPosPointLon", vpPosPointLon);
            j.put("nPosAngle", nPosAngle);
            j.put("szPosRoadName", szPosRoadName);
            j.put("roadcate", roadcate);
            j.put("nTBTDist", nTBTDist);
            j.put("nTBTTurnType", nTBTTurnType);
            j.put("nGoPosDist", nGoPosDist);
            j.put("nGoPosTime", nGoPosTime);
            j.put("nTrafficLight", nTrafficLight);
            j.put("nTrafficLightSec", nTrafficLightSec);
            j.put("sapaName", sapaName);
            j.put("sapaDist", sapaDist);
            j.put("sapaType", sapaType);
            j.put("nextSapaName", nextSapaName);
            j.put("nextSapaDist", nextSapaDist);
            j.put("nextSapaType", nextSapaType);
            j.put("etaText", etaText);
            j.put("tmcSlowDist", tmcSlowDist);
            j.put("tmcJamDist", tmcJamDist);
            j.put("tmcBlockDist", tmcBlockDist);
            j.put("nextNextTurnIcon", nextNextTurnIcon);
            j.put("nextNextRoadName", nextNextRoadName);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return j;
    }
}
