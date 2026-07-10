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
html,body{width:100%;height:100%;overflow:hidden;background:#1a1a2e;-webkit-user-select:none;user-select:none}
.wrap{display:flex;align-items:center;justify-content:center;width:100%;height:100%;position:relative}
#v{max-width:100%;max-height:100%;object-fit:contain;background:#0a0a1a}

/* ===== HUD 叠加面板（顶部） ===== */
.hud{position:absolute;top:8px;left:8px;right:8px;
  background:rgba(15,23,42,0.88);border:1px solid rgba(13,148,136,0.35);
  border-radius:8px;padding:8px 12px;color:#e2e8f0;font-family:sans-serif;
  font-size:12px;line-height:1.4;display:flex;justify-content:space-between;flex-wrap:wrap;gap:4px 12px}
.hud .item{white-space:nowrap}
.hud .lbl{color:#94a3b8;font-size:10px}
.hud .val{color:#f8fafc;font-weight:600}
.hud .val.acc-on{color:#34d399}
.hud .val.acc-off{color:#64748b}
.hud .val.gear{color:#fbbf24}

/* ===== 视频信息面板（底部） ===== */
.info{position:absolute;bottom:8px;left:8px;right:8px;
  background:rgba(15,23,42,0.75);border:1px solid rgba(100,116,139,0.25);
  border-radius:6px;padding:5px 10px;color:#94a3b8;font-family:monospace;
  font-size:10px;display:flex;justify-content:space-between;flex-wrap:wrap;gap:2px 16px}
.info .kv{white-space:nowrap}
.info .kv .k{color:#64748b}
.info .kv .v{color:#94a3b8}

.status{position:absolute;bottom:26px;left:0;right:0;text-align:center;color:#64748b;font-size:11px;font-family:sans-serif}
.err{position:absolute;top:50%;left:0;right:0;transform:translateY(-50%);text-align:center;color:#f87171;font-size:14px;font-family:sans-serif;padding:0 24px;line-height:1.6}
.loader{position:absolute;width:28px;height:28px;border:2.5px solid #1e293b;border-top-color:#0d9488;border-radius:50%;animation:spin .7s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
</head>
<body>
<div class="wrap" id="wrap">
<div class="loader" id="ld"></div>
<video id="v" autoplay playsinline muted style="display:none"></video>

<!-- HUD 叠加面板 -->
<div class="hud" id="hud" style="display:none">
  <div class="item"><span class="lbl">车速</span> <span class="val" id="hudSpeed">--</span> km/h</div>
  <div class="item"><span class="lbl">ACC</span> <span class="val" id="hudAcc">--</span></div>
  <div class="item"><span class="lbl">档位</span> <span class="val gear" id="hudGear">--</span></div>
  <div class="item"><span class="lbl">设定</span> <span class="val" id="hudCruise">--</span> km/h</div>
  <div class="item"><span class="lbl">转向</span> <span class="val" id="hudSteer">--</span>&deg;</div>
</div>

<!-- 视频信息面板（底部） -->
<div class="info" id="info" style="display:none">
  <div class="kv"><span class="k">分辨率</span> <span class="v" id="iRes">--</span></div>
  <div class="kv"><span class="k">帧率</span> <span class="v" id="iFps">--</span></div>
  <div class="kv"><span class="k">码率</span> <span class="v" id="iBr">--</span></div>
  <div class="kv"><span class="k">延迟</span> <span class="v" id="iLat">--</span></div>
  <div class="kv"><span class="k">丢包</span> <span class="v" id="iLoss">--</span></div>
  <div class="kv"><span class="k">编码</span> <span class="v" id="iCodec">--</span></div>
</div>

<div class="status" id="st">正在协商 WebRTC 连接...</div>
</div>
<script>
var host="$host", port=$port, camera="$camera", streamUrl="$streamUrl";
var v=document.getElementById('v'), ld=document.getElementById('ld'), st=document.getElementById('st');
var hud=document.getElementById('hud'), info=document.getElementById('info');
var pc=null, dc=null, retryTimer=null;
var fpsFrames=0, fpsLastTime=0, fpsVal=0;

// Capnp enum → 显示文字映射
var GEAR_MAP = {park:'P', reverse:'R', neutral:'N', drive:'D', sport:'S', low:'L', brake:'B', unknown:'-'};

// ===== HUD 显示更新 =====
function updateHud(data){
  hud.style.display='';
  var s=data;
  // 车速
  if(s.vEgo != null) document.getElementById('hudSpeed').textContent = (s.vEgo*3.6).toFixed(0);
  // ACC (cruiseState.enabled 是 capnp 字段)
  var accEl = document.getElementById('hudAcc');
  if(s.cruiseState && (s.cruiseState.enabled || s.cruiseState.standstill)){
    accEl.textContent='开'; accEl.className='val acc-on';
  } else {
    accEl.textContent='关'; accEl.className='val acc-off';
  }
  // 档位 (capnp: gearShifter 是枚举，to_dict后为字符串)
  if(s.gearShifter != null){
    document.getElementById('hudGear').textContent = GEAR_MAP[s.gearShifter] || s.gearShifter;
  }
  // 设定速度
  if(s.cruiseState && s.cruiseState.speed > 0){
    document.getElementById('hudCruise').textContent = (s.cruiseState.speed*3.6).toFixed(0);
  }
  // 方向盘转角
  if(s.steeringAngleDeg != null){
    document.getElementById('hudSteer').textContent = s.steeringAngleDeg.toFixed(1);
  }
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
    dc.onopen = function(){
      setStatus('HUD 已连接');
    };
    dc.onclose = function(){ dc=null; };
    dc.onmessage = function(evt){
      try{
        var msg = JSON.parse(evt.data);
        if (msg.type === 'carState' && msg.data){
          updateHud(msg.data);
        }
      }catch(e){}
    };

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
        bridge_services_out: ["carState"]
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
