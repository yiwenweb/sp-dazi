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
 *          "bridge_services_in": [], "bridge_services_out": []}
 *   resp: {"sdp": <answer_sdp>, "type": "answer"}
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
#v{max-width:100%;max-height:100%;object-fit:contain;background:#1a1a2e}
.status{position:absolute;bottom:10px;left:0;right:0;text-align:center;color:#64748b;font-size:12px;font-family:sans-serif}
.err{position:absolute;top:50%;left:0;right:0;transform:translateY(-50%);text-align:center;color:#f87171;font-size:14px;font-family:sans-serif;padding:0 24px;line-height:1.6}
.loader{position:absolute;width:28px;height:28px;border:2.5px solid #1e293b;border-top-color:#0d9488;border-radius:50%;animation:spin .7s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
</head>
<body>
<div class="wrap" id="wrap">
<div class="loader" id="ld"></div>
<video id="v" autoplay playsinline muted style="display:none"></video>
<div class="status" id="st">正在协商 WebRTC 连接...</div>
</div>
<script>
var host="$host", port=$port, camera="$camera", streamUrl="$streamUrl";
var v=document.getElementById('v'), ld=document.getElementById('ld'), st=document.getElementById('st');
var pc=null, retryTimer=null;

function setStatus(t){ if(st) st.textContent=t; }
function showError(msg){
  ld.style.display='none';
  v.style.display='none';
  var e=document.getElementById('err');
  if(!e){ e=document.createElement('div'); e.id='err'; e.className='err'; document.getElementById('wrap').appendChild(e); }
  e.textContent=msg;
  setStatus('');
}
function clearError(){ var e=document.getElementById('err'); if(e) e.remove(); }

function waitIceGathering(pc){
  return new Promise(function(resolve){
    if(pc.iceGatheringState==='complete'){ resolve(); return; }
    // 最多等 2 秒，避免部分网络下 ICE 永不 complete
    var done=false;
    function finish(){ if(done) return; done=true; pc.removeEventListener('icegatheringstatechange',check); resolve(); }
    function check(){ if(pc.iceGatheringState==='complete') finish(); }
    pc.addEventListener('icegatheringstatechange', check);
    setTimeout(finish, 2000);
  });
}

async function connect(){
  clearError();
  ld.style.display='';
  setStatus('正在协商 WebRTC 连接...');
  try{
    pc=new RTCPeerConnection({sdpSemantics:'unified-plan'});
    // 只收不发：接收一路视频
    pc.addTransceiver('video', {direction:'recvonly'});

    pc.addEventListener('track', function(evt){
      if(evt.track.kind==='video'){
        v.srcObject=evt.streams[0];
        v.style.display='';
        ld.style.display='none';
        setStatus('');
      }
    });
    pc.addEventListener('connectionstatechange', function(){
      setStatus('连接状态: '+pc.connectionState);
      if(pc.connectionState==='failed' || pc.connectionState==='disconnected'){
        scheduleRetry('连接断开，正在重连...');
      }
    });

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
        bridge_services_out: []
      })
    });
    if(!resp.ok) throw new Error('HTTP '+resp.status);
    var answer=await resp.json();
    await pc.setRemoteDescription(answer);
  }catch(e){
    scheduleRetry('无法连接摄像头流: '+e.message+'\n（确认车辆已启动且流开关已开启）');
  }
}

function scheduleRetry(msg){
  showError(msg);
  cleanup();
  if(retryTimer) clearTimeout(retryTimer);
  retryTimer=setTimeout(connect, 3000);
}
function cleanup(){
  if(pc){ try{ pc.close(); }catch(e){} pc=null; }
}

connect();
</script>
</body>
</html>
""".trimIndent()
    }
}
