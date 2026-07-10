package com.sunnypilot.toolbox.ui.util

/**
 * 生成用于 WebView 的 WebRTC 客户端页面。
 *
 * WebView 原生支持 RTCPeerConnection，因此无需引入笨重的 org.webrtc 原生库。
 * 本页面只接收视频（recvonly），不调用 getUserMedia，故不需要摄像头权限。
 *
 * 握手协议（对接 C3 端 system/webrtc/webrtcd.py，端口 5001）：
 *   POST http://<C3_IP>:5001/stream
 *   body: {"sdp": <offer_sdp>, "cameras": ["road"],
 *          "bridge_services_in": [], "bridge_services_out": ["carState"]}
 *   resp: {"sdp": <answer_sdp>, "type": "answer"}
 *
 * HUD 数据通过 WebRTC DataChannel 实时推送（C3 的 CerealOutgoingMessageProxy），
 * 包含 carState（车速、ACC 状态、档位等），在画面上方叠加显示。
 */
object WebrtcHtml {

    /**
     * @param host C3 设备 IP
     * @param port webrtcd 端口（默认 5001）
     * @param camera 摄像头名："road"(前视) / "wideRoad"(广角) / "driver"(驾驶员)
     */
    fun build(host: String, port: Int = 5001, camera: String = "road"): String {
        val streamUrl = "http://$host:$port/stream"
        return """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:100%;height:100%;overflow:hidden;background:#0a0a1a;-webkit-user-select:none;user-select:none}
.wrap{display:flex;align-items:stretch;width:100%;height:100%}

/* 左侧 HUD 面板 */
.hud{width:85px;min-width:85px;background:rgba(15,23,42,0.92);border-right:1px solid rgba(13,148,136,0.2);
  display:flex;flex-direction:column;justify-content:center;gap:10px;padding:12px 4px}
.hud .item{text-align:center;font-family:sans-serif}
.hud .lbl{color:#64748b;font-size:9px;display:block;margin-bottom:1px}
.hud .val{color:#f8fafc;font-weight:600;font-size:13px;display:block}
.hud .val.acc-on{color:#34d399}
.hud .val.acc-off{color:#64748b}
.hud .val.warn{color:#fbbf24}

/* 扭矩条 */
.torque-bar{width:100%;height:3px;background:#1e293b;border-radius:2px;margin-top:3px;overflow:hidden}
.torque-bar .fill{height:100%;border-radius:2px;transition:width 0.15s}

/* 中间视频区 */
.vidzone{flex:1;display:flex;align-items:center;justify-content:center;position:relative;overflow:hidden;background:#0a0a1a}
#v{max-width:100%;max-height:100%;object-fit:contain}

/* 车速居中顶部 */
.speedo{position:absolute;top:16px;left:50%;transform:translateX(-50%);text-align:center;z-index:10}
.speedo .speed{font-size:48px;font-weight:700;color:#f8fafc;font-family:sans-serif;line-height:1;
  text-shadow:0 2px 8px rgba(0,0,0,0.6);letter-spacing:-2px}
.speedo .unit{font-size:14px;color:#94a3b8;display:block;margin-top:2px}
.speedo .set{font-size:12px;color:#64748b;margin-top:2px}
.speedo .set span{color:#fbbf24}

/* 前车距离指示(速度下方) */
.lead-ind{position:absolute;top:130px;left:50%;transform:translateX(-50%);text-align:center;z-index:8}
.lead-ind .dist{font-size:18px;color:#f8fafc;font-weight:600;font-family:sans-serif}
.lead-ind .gap{font-size:11px;color:#94a3b8;display:block}

/* 加速指示条(底部) */
.accel-bar{position:absolute;bottom:8px;left:10%;right:10%;height:6px;background:rgba(30,41,59,0.8);border-radius:3px;overflow:hidden;z-index:10}
.accel-bar .fill{height:100%;border-radius:3px;transition:all 0.2s}
.accel-bar .mark{position:absolute;top:-2px;bottom:-2px;width:1px;background:rgba(255,255,255,0.3)}

/* 右侧信息面板 */
.info{width:85px;min-width:85px;background:rgba(15,23,42,0.85);border-left:1px solid rgba(100,116,139,0.15);
  display:flex;flex-direction:column;justify-content:center;gap:7px;padding:12px 4px}
.info .kv{text-align:center;font-family:monospace}
.info .kv .k{color:#475569;font-size:9px;display:block}
.info .kv .v{color:#94a3b8;font-size:10px;display:block;margin-top:1px}

.status{position:fixed;bottom:4px;left:0;right:0;text-align:center;color:#475569;font-size:10px;font-family:sans-serif;z-index:5}
.err{position:fixed;top:50%;left:0;right:0;transform:translateY(-50%);text-align:center;color:#f87171;font-size:14px;font-family:sans-serif;padding:0 24px;line-height:1.6;z-index:20}
.loader{position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);width:28px;height:28px;border:2.5px solid #1e293b;border-top-color:#0d9488;border-radius:50%;animation:spin .7s linear infinite;z-index:20}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
</head>
<body>
<div class="wrap" id="wrap">

<!-- 左侧 HUD 面板 -->
<div class="hud" id="hud" style="display:none">
  <div class="item"><span class="lbl">ACC</span> <span class="val" id="hudAcc">--</span></div>
  <div class="item"><span class="lbl">档位</span> <span class="val" id="hudGear" style="font-size:16px;color:#fbbf24">--</span></div>
  <div class="item"><span class="lbl">扭矩</span> <span class="val" id="hudTrq">--</span></div>
  <div class="torque-bar"><div class="fill" id="trqFill" style="width:0%;background:#64748b"></div></div>
  <div class="item"><span class="lbl">前车</span> <span class="val" id="hudLead">--</span></div>
  <div class="item"><span class="lbl">设定</span> <span class="val" id="hudCruise">--</span></div>
  <div class="item"><span class="lbl">转向</span> <span class="val" id="hudSteer">--</span></div>
</div>

<!-- 中间视频区 -->
<div class="vidzone">
  <div class="loader" id="ld"></div>
  <video id="v" autoplay playsinline muted style="display:none"></video>

  <!-- 车速 -->
  <div class="speedo" id="speedo" style="display:none">
    <div style="display:flex;align-items:baseline;gap:4px">
      <span class="speed" id="hudSpeed">0</span>
      <span style="color:#fbbf24;font-weight:600;font-size:20px;font-family:sans-serif" id="hudGearMini"></span>
    </div>
    <div class="unit">KM/H</div>
    <div class="set">巡航 <span id="hudCruiseTop">--</span></div>
  </div>

  <!-- 前车距离 -->
  <div class="lead-ind" id="leadInd" style="display:none">
    <span class="dist" id="leadDist">--</span>
    <span class="gap" id="leadGap">--</span>
  </div>

  <!-- 加速指示条 -->
  <div class="accel-bar" id="accelBar" style="display:none">
    <div class="fill" id="accelFill" style="width:50%;background:#475569"></div>
    <div class="mark" style="left:50%"></div>
  </div>
</div>

<!-- 右侧信息面板 -->
<div class="info" id="info" style="display:none">
  <div class="kv"><span class="k">分辨率</span> <span class="v" id="iRes">--</span></div>
  <div class="kv"><span class="k">帧率</span> <span class="v" id="iFps">--</span></div>
  <div class="kv"><span class="k">码率</span> <span class="v" id="iBr">--</span></div>
  <div class="kv"><span class="k">延迟</span> <span class="v" id="iLat">--</span></div>
  <div class="kv"><span class="k">丢包</span> <span class="v" id="iLoss">--</span></div>
  <div class="kv"><span class="k">编码</span> <span class="v" id="iCodec">--</span></div>
</div>

</div>

<div class="status" id="st">正在协商 WebRTC 连接...</div>
<script>
var host="$host", port=$port, camera="$camera", streamUrl="$streamUrl";
var v=document.getElementById('v'), ld=document.getElementById('ld'), st=document.getElementById('st');
var hud=document.getElementById('hud'), info=document.getElementById('info');
var speedo=document.getElementById('speedo'), leadInd=document.getElementById('leadInd');
var accelBar=document.getElementById('accelBar');
var pc=null, dc=null, retryTimer=null;
var fpsFrames=0, fpsLastTime=0, fpsVal=0;

var GEAR_MAP = {park:'P', reverse:'R', neutral:'N', drive:'D', sport:'S', low:'L', brake:'B', unknown:'-'};

// ===== 从 carState 更新基础 HUD =====
var curEgo = 0;
function updateCarState(s){
  hud.style.display=''; speedo.style.display='';
  if(s.vEgo != null){ curEgo = s.vEgo; document.getElementById('hudSpeed').textContent = (s.vEgo*3.6).toFixed(0); }
  var accEl = document.getElementById('hudAcc');
  if(s.cruiseState && (s.cruiseState.enabled || s.cruiseState.standstill)){
    accEl.textContent='ON'; accEl.className='val acc-on';
  } else { accEl.textContent='OFF'; accEl.className='val acc-off'; }
  if(s.gearShifter != null){
    var g = GEAR_MAP[s.gearShifter] || s.gearShifter;
    document.getElementById('hudGear').textContent = g;
    document.getElementById('hudGearMini').textContent = g;
  }
  if(s.cruiseState && s.cruiseState.speed > 0){
    var c = (s.cruiseState.speed*3.6).toFixed(0);
    document.getElementById('hudCruise').textContent = c;
    document.getElementById('hudCruiseTop').textContent = c;
  }
  if(s.steeringAngleDeg != null)
    document.getElementById('hudSteer').textContent = s.steeringAngleDeg.toFixed(1)+'°';
  // 加速指示条
  if(s.aEgo != null){
    accelBar.style.display='';
    var pct = 50 + s.aEgo * 20; // -2 m/s² → 10%, 0 → 50%, +2 → 90%
    pct = Math.max(5, Math.min(95, pct));
    var fill = document.getElementById('accelFill');
    fill.style.width = pct+'%';
    if(s.aEgo > 0.3) fill.style.background = '#34d399';       // 加速→绿
    else if(s.aEgo < -0.5) fill.style.background = '#ef4444';  // 减速→红
    else fill.style.background = '#f59e0b';                     // 滑行→黄
  }
  // 转向扭矩
  if(s.steeringTorqueEps != null || s.steeringTorque != null){
    var trq = s.steeringTorqueEps || s.steeringTorque || 0;
    document.getElementById('hudTrq').textContent = trq.toFixed(0);
    var trqPct = 50 + trq * 0.03; // map roughly
    trqPct = Math.max(0, Math.min(100, trqPct));
    document.getElementById('trqFill').style.width = trqPct+'%';
    document.getElementById('trqFill').style.background = Math.abs(trq)>800 ? '#fbbf24' : '#64748b';
  }
}

// ===== 从 modelV2 更新前车信息 =====
function updateModelV2(m){
  var lead = (m.leadsV3 && m.leadsV3.length>0 && m.leadsV3[0].prob>0.5) ? m.leadsV3[0] : null;
  if(lead && lead.x && lead.x.length>0){
    leadInd.style.display='';
    var d = lead.x[0];
    document.getElementById('leadDist').textContent = d.toFixed(0)+' m';
    document.getElementById('leadGap').textContent = curEgo>0 ? (d/curEgo).toFixed(1)+' s' : '';
    if(d>0) document.getElementById('hudLead').textContent = d.toFixed(0)+'m';
  } else {
    leadInd.style.display='none';
  }
}

// ===== 从 longitudinalPlan 更新 =====
var lastATarget = 0;
function updateLongPlan(p){
  if(p.aTarget != null) lastATarget = p.aTarget;
}

// ===== 消息分发 =====
function onMessage(evt){
  try{
    var msg = JSON.parse(evt.data);
    if(!msg.data) return;
    if(msg.type==='carState') updateCarState(msg.data);
    else if(msg.type==='modelV2') updateModelV2(msg.data);
    else if(msg.type==='longitudinalPlan') updateLongPlan(msg.data);
  }catch(e){}
}

// ===== 视频统计更新 =====
function updateStats(){
  if(!pc) return;
  pc.getStats(null).then(function(report){
    var bw=0, pktLoss=0, codec='', rtt=0;
    report.forEach(function(stat){
      if(stat.type==='inbound-rtp' && stat.kind==='video'){
        bw = stat.bytesReceived || 0;
        pktLoss = stat.packetsLost || 0;
        codec = stat.codecId || '';
        if(stat.framesPerSecond) fpsVal = stat.framesPerSecond;
        else {
          var now=Date.now();
          if(fpsLastTime>0 && now-fpsLastTime>=1000){
            fpsVal = Math.round(fpsFrames*1000/(now-fpsLastTime));
            fpsFrames=0; fpsLastTime=now;
          }
          if(fpsLastTime===0) fpsLastTime=now;
          fpsFrames++;
        }
      }
      if(stat.type==='candidate-pair' && stat.state==='succeeded'){
        rtt = Math.round(stat.currentRoundTripTime*1000);
      }
    });
    // 更新显示
    info.style.display='';
    // 分辨率
    if(v.videoWidth) document.getElementById('iRes').textContent = v.videoWidth+'x'+v.videoHeight;
    // 帧率
    document.getElementById('iFps').textContent = fpsVal+' fps';
    // 码率 (kbps)
    document.getElementById('iBr').textContent = bw>0 ? (bw/125).toFixed(0)+' kbps' : '--';
    // 延迟
    document.getElementById('iLat').textContent = rtt>0 ? rtt+' ms' : '--';
    // 丢包
    document.getElementById('iLoss').textContent = pktLoss>0 ? pktLoss+' pkts' : '0';
    // 编码
    document.getElementById('iCodec').textContent = codec ? 'H264' : '--';
  }).catch(function(){});
}

function setStatus(t){ if(st) st.textContent=t; }
function showError(msg){
  ld.style.display='none'; v.style.display='none'; hud.style.display='none'; info.style.display='none';
  speedo.style.display='none'; leadInd.style.display='none'; accelBar.style.display='none';
  var e=document.getElementById('err');
  if(!e){ e=document.createElement('div'); e.id='err'; e.className='err'; document.getElementById('wrap').appendChild(e); }
  e.textContent=msg; setStatus('');
}
function clearError(){ var e=document.getElementById('err'); if(e) e.remove(); }

function waitIceGathering(pc){
  return new Promise(function(resolve){
    if(pc.iceGatheringState==='complete'){ resolve(); return; }
    var done=false;
    function finish(){ if(done) return; done=true; pc.removeEventListener('icegatheringstatechange',check); resolve(); }
    function check(){ if(pc.iceGatheringState==='complete') finish(); }
    pc.addEventListener('icegatheringstatechange', check);
    setTimeout(finish, 2000);
  });
}

// 定时刷新视频统计
var statsTimer = null;

async function connect(){
  clearError();
  ld.style.display=''; info.style.display='none';
  setStatus('正在协商 WebRTC 连接...');
  try{
    pc=new RTCPeerConnection({sdpSemantics:'unified-plan'});
    pc.addTransceiver('video', {direction:'recvonly'});

    pc.addEventListener('track', function(evt){
      if(evt.track.kind==='video'){
        v.srcObject=evt.streams[0];
        v.style.display=''; ld.style.display='none';
        setStatus('');
        // 启动统计定时器
        if(statsTimer) clearInterval(statsTimer);
        statsTimer = setInterval(updateStats, 1000);
      }
    });
    pc.addEventListener('connectionstatechange', function(){
      if(pc.connectionState==='connected') setStatus('');
      else setStatus('状态: '+pc.connectionState);
      if(pc.connectionState==='failed' || pc.connectionState==='disconnected'){
        if(statsTimer){ clearInterval(statsTimer); statsTimer=null; }
        scheduleRetry('连接断开，正在重连...');
      }
    });

    // ===== DataChannel：接收 HUD 数据 =====
    dc = pc.createDataChannel('data', {ordered:true});
    dc.onopen = function(){ setStatus('HUD 已连接'); };
    dc.onclose = function(){ dc=null; };
    dc.onmessage = onMessage;

    var offer=await pc.createOffer();
    await pc.setLocalDescription(offer);
    await waitIceGathering(pc);

    var resp=await fetch(streamUrl,{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({
        sdp: pc.localDescription.sdp,
        cameras: [camera],
        bridge_services_in: [],
        bridge_services_out: ["carState","modelV2","longitudinalPlan"]
      })
    });
    if(!resp.ok) throw new Error('HTTP '+resp.status);
    var answer=await resp.json();
    await pc.setRemoteDescription(answer);
  }catch(e){
    scheduleRetry('无法连接摄像头流: '+e.message+'\\n（确认车辆已启动且流开关已开启）');
  }
}

function scheduleRetry(msg){
  showError(msg); cleanup();
  if(retryTimer) clearTimeout(retryTimer);
  retryTimer=setTimeout(connect, 3000);
}
function cleanup(){
  if(statsTimer){ clearInterval(statsTimer); statsTimer=null; }
  if(dc){ try{ dc.close(); }catch(e){} dc=null; }
  if(pc){ try{ pc.close(); }catch(e){} pc=null; }
}

connect();
</script>
</body>
</html>
""".trimIndent()
    }
}
