package com.sunnypilot.toolbox.service

import fi.iki.elonen.NanoHTTPD
import com.sunnypilot.toolbox.data.db.QuickCommandDao
import com.sunnypilot.toolbox.model.QuickCommand
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicReference





class QuickCommandWebServer(
    port: Int,
    private val dao: QuickCommandDao,
    private val onExecuteCommand: ((String) -> Unit)? = null
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val terminalContent = AtomicReference("")

    fun updateTerminal(text: String) {
        terminalContent.set(text)
    }

    private fun getTerminalSnapshot(): String = terminalContent.get()


    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri.trim('/')

        return when {
            method == Method.GET && uri == "api/terminal" -> {
                respondWithCors(Response.Status.OK, "text/plain; charset=utf-8", getTerminalSnapshot())
            }
            method == Method.POST && uri == "api/execute" -> {
                val body = parseBody(session)
                val map = try {
                    json.decodeFromString(JsonObject.serializer(), body.ifEmpty { "{}" })
                } catch (e: Exception) {
                    return badRequest("Invalid JSON")
                }
                val command = map["command"]?.jsonPrimitive?.contentOrNull
                    ?: return badRequest("Missing command")
                
                // 执行命令
                onExecuteCommand?.invoke(command)
                respondWithCors(Response.Status.OK, "application/json; charset=utf-8", "{\"ok\":true,\"command\":\"${command.replace("\"", "\\\\\"")}\"}")
            }
            method == Method.GET && uri.isEmpty() -> respondWithCors(Response.Status.OK, "text/html; charset=utf-8", indexHtml())
            method == Method.GET && uri == "api/commands" -> {
                val commands = dao.getAllSync()
                respondWithCors(Response.Status.OK, "application/json; charset=utf-8", json.encodeToString(commands))
            }
            method == Method.POST && uri == "api/commands" -> {
                val body = parseBody(session)
                val cmd = parseCommand(body)
                val id = runBlocking { dao.insert(cmd) }
                respondWithCors(Response.Status.CREATED, "application/json; charset=utf-8", json.encodeToString(cmd.copy(id = id)))
            }
            method == Method.PUT && uri.startsWith("api/commands/") -> {
                val id = uri.removePrefix("api/commands/").toLongOrNull()
                    ?: return badRequest("Invalid id")
                val body = parseBody(session)
                val cmd = parseCommand(body).copy(id = id)
                runBlocking { dao.update(cmd) }
                respondWithCors(Response.Status.OK, "application/json; charset=utf-8", json.encodeToString(cmd))
            }
            method == Method.DELETE && uri.startsWith("api/commands/") -> {
                val id = uri.removePrefix("api/commands/").toLongOrNull()
                    ?: return badRequest("Invalid id")
                val existing = runBlocking { dao.getById(id) }
                    ?: return respondWithCors(Response.Status.NOT_FOUND, "application/json", "{}")
                runBlocking { dao.delete(existing) }
                respondWithCors(Response.Status.OK, "application/json", "{}")
            }
            else -> respondWithCors(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun parseBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseCommand(body: String): QuickCommand {
        val map = json.decodeFromString(JsonObject.serializer(), body.ifEmpty { "{}" })
        return QuickCommand(
            id = map["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            title = map["title"]?.jsonPrimitive?.contentOrNull ?: "",
            command = map["command"]?.jsonPrimitive?.contentOrNull ?: "",
            description = map["description"]?.jsonPrimitive?.contentOrNull ?: "",
            sortOrder = map["sortOrder"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    private fun badRequest(msg: String): Response {
        return respondWithCors(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"$msg\"}")
    }

    private fun indexHtml(): String {
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>快捷命令管理</title>
<style>
  * { box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    margin: 0; padding: 12px;
    background: #f8fafc; color: #1e293b;
  }
  .container { max-width: 100%; margin: 0 auto; }
  h1 { font-size: 20px; margin: 0 0 12px; }
  
  /* 终端区域 */
  .terminal-section { margin-bottom: 16px; }
  .terminal { 
    background: #0f172a; color: #e2e8f0; border-radius: 12px;
    padding: 12px; margin-bottom: 12px;
  }
  .terminal-header { 
    display: flex; justify-content: space-between; align-items: center; 
    margin-bottom: 10px; font-size: 14px; font-weight: 600;
  }
  .terminal-actions { display: flex; gap: 6px; }
  .terminal pre { 
    margin: 0; white-space: pre-wrap; word-break: break-all; 
    font-family: 'Courier New', monospace; font-size: 12px; 
    max-height: 300px; overflow-y: auto; line-height: 1.4;
    background: #1e293b; padding: 10px; border-radius: 6px;
  }
  .terminal-input {
    display: flex; gap: 8px; margin-top: 10px;
  }
  .terminal-input input {
    flex: 1; padding: 8px 10px; border: 1px solid #334155;
    border-radius: 6px; font-size: 13px; background: #1e293b;
    color: #e2e8f0; font-family: 'Courier New', monospace;
  }
  .terminal-input input::placeholder { color: #64748b; }
  
  .card {
    background: #fff; border-radius: 12px; padding: 12px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08); margin-bottom: 12px;
  }
  .form-group { margin-bottom: 10px; }
  label { display: block; font-size: 12px; color: #64748b; margin-bottom: 4px; }
  input, textarea {
    width: 100%; padding: 8px 10px; border: 1px solid: #e2e8f0;
    border-radius: 6px; font-size: 13px; background: #fff;
  }
  textarea { resize: vertical; min-height: 50px; font-family: 'Courier New', monospace; }
  .btn-row { display: flex; gap: 6px; margin-top: 8px; flex-wrap: wrap; }
  button {
    border: none; border-radius: 6px; padding: 8px 12px;
    font-size: 13px; cursor: pointer; font-weight: 500; white-space: nowrap;
  }
  .primary { background: #0d9488; color: #fff; }
  .secondary { background: #e2e8f0; color: #334155; }
  .danger { background: #fee2e2; color: #ef4444; }
  .small { padding: 6px 10px; font-size: 12px; }
  
  .item { border-bottom: 1px solid #f1f5f9; padding: 10px 0; }
  .item:last-child { border-bottom: none; }
  .item-title { font-weight: 600; margin-bottom: 4px; font-size: 14px; }
  .item-desc { font-size: 12px; color: #64748b; margin-bottom: 4px; }
  .item-cmd { 
    font-family: 'Courier New', monospace; font-size: 11px; 
    color: #475569; background: #f1f5f9; padding: 6px 8px; 
    border-radius: 4px; overflow-wrap: break-word; white-space: pre-wrap;
  }
  .empty { text-align: center; color: #94a3b8; padding: 20px; font-size: 13px; }
  .top-actions { display: flex; gap: 6px; margin-bottom: 12px; flex-wrap: wrap; }
  #importFile { display: none; }
  .toast {
    position: fixed; bottom: 16px; left: 50%; transform: translateX(-50%);
    background: #0f172a; color: #fff; padding: 8px 16px; border-radius: 20px;
    font-size: 12px; opacity: 0; transition: opacity .3s; pointer-events: none;
    max-width: 90%; z-index: 1000;
  }
  .toast.show { opacity: 1; }
  
  @media (max-width: 640px) {
    body { padding: 8px; }
    h1 { font-size: 18px; }
    .terminal pre { font-size: 11px; max-height: 250px; }
    .btn-row button { flex: 1; min-width: 0; }
  }
</style>
</head>
<body>
<div class="container">
<h1>⚡ 快捷命令管理</h1>

<!-- 终端区域 -->
<div class="terminal-section">
  <div class="terminal">
    <div class="terminal-header">
      <span>🖥️ 终端输出</span>
      <div class="terminal-actions">
        <button class="secondary small" onclick="exportTerminal()">💾 导出</button>
        <button class="secondary small" onclick="copyTerminal()">📋 复制</button>
        <button class="secondary small" onclick="clearTerminal()">🗑️ 清空</button>
      </div>
    </div>
    <pre id="terminalContent">等待终端输出...</pre>
    <div class="terminal-input">
      <input type="text" id="cmdInput" placeholder="输入命令回车执行..." onkeypress="handleCmdEnter(event)">
      <button class="primary small" onclick="sendCommand()">▶ 执行</button>
    </div>
  </div>
</div>

<!-- 命令管理 -->
<div class="top-actions">
  <button class="primary small" onclick="showForm()">+ 新增命令</button>
  <button class="secondary small" onclick="exportJson()">💾 导出</button>
  <button class="secondary small" onclick="document.getElementById('importFile').click()">📥 导入</button>
  <input type="file" id="importFile" accept="application/json" onchange="importJson(this)">
</div>

<div id="formCard" class="card" style="display:none">
  <input type="hidden" id="cmdId">
  <div class="form-group">
    <label>命令名称</label>
    <input type="text" id="title" placeholder="例如：重启 openpilot">
  </div>
  <div class="form-group">
    <label>命令内容</label>
    <textarea id="command" placeholder="例如：sudo reboot"></textarea>
  </div>
  <div class="form-group">
    <label>作用说明</label>
    <input type="text" id="description" placeholder="例如：重启车机上的 openpilot 服务">
  </div>
  <div class="btn-row">
    <button class="primary" onclick="saveCommand()">保存</button>
    <button class="secondary" onclick="hideForm()">取消</button>
  </div>
</div>

<div id="list" class="card">加载中...</div>

<div class="toast" id="toast"></div>
</div>

<script>
let commands = [];
let terminalHistory = [];

// 加载命令列表
async function load() {
  try {
    const res = await fetch('/api/commands');
    commands = await res.json();
    render();
  } catch(e) {
    toast('加载失败: ' + e.message);
  }
}

function render() {
  const list = document.getElementById('list');
  if (commands.length === 0) {
    list.innerHTML = '<div class="empty">暂无快捷命令，点击上方新增</div>';
    return;
  }
  list.innerHTML = commands.map(c => `
    <div class="item">
      <div class="item-title">${'$'}{escapeHtml(c.title)}</div>
      <div class="item-desc">${'$'}{escapeHtml(c.description || '')}</div>
      <div class="item-cmd">${'$'}{escapeHtml(c.command)}</div>
      <div class="btn-row">
        <button class="secondary small" onclick="executeCmd('${'$'}{escapeHtml(c.command)}')">▶ 执行</button>
        <button class="secondary small" onclick="editCommand(${'$'}{c.id})">✏️ 编辑</button>
        <button class="danger small" onclick="deleteCommand(${'$'}{c.id})">🗑️ 删除</button>
      </div>
    </div>
  `).join('');
}

function showForm(cmd) {
  document.getElementById('formCard').style.display = 'block';
  document.getElementById('cmdId').value = cmd ? cmd.id : '';
  document.getElementById('title').value = cmd ? cmd.title : '';
  document.getElementById('command').value = cmd ? cmd.command : '';
  document.getElementById('description').value = cmd ? cmd.description : '';
  document.getElementById('title').focus();
}

function hideForm() {
  document.getElementById('formCard').style.display = 'none';
  document.getElementById('cmdId').value = '';
  document.getElementById('title').value = '';
  document.getElementById('command').value = '';
  document.getElementById('description').value = '';
}

async function saveCommand() {
  const id = document.getElementById('cmdId').value;
  const body = {
    title: document.getElementById('title').value.trim(),
    command: document.getElementById('command').value.trim(),
    description: document.getElementById('description').value.trim()
  };
  if (!body.title || !body.command) { 
    toast('名称和命令不能为空'); 
    return; 
  }
  
  try {
    if (id) {
      await fetch('/api/commands/' + id, { 
        method: 'PUT', 
        headers: { 'Content-Type': 'application/json; charset=utf-8' }, 
        body: JSON.stringify(body) 
      });
    } else {
      await fetch('/api/commands', { 
        method: 'POST', 
        headers: { 'Content-Type': 'application/json; charset=utf-8' }, 
        body: JSON.stringify(body) 
      });
    }
    hideForm();
    await load();
    toast('保存成功');
  } catch(e) {
    toast('保存失败: ' + e.message);
  }
}

function editCommand(id) {
  const c = commands.find(x => x.id === id);
  if (c) showForm(c);
}

async function deleteCommand(id) {
  if (!confirm('确定删除这条命令？')) return;
  try {
    await fetch('/api/commands/' + id, { method: 'DELETE' });
    await load();
    toast('已删除');
  } catch(e) {
    toast('删除失败: ' + e.message);
  }
}

async function exportJson() {
  const blob = new Blob([JSON.stringify(commands, null, 2)], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'quick_commands_' + Date.now() + '.json';
  a.click();
  URL.revokeObjectURL(url);
  toast('已导出');
}

async function importJson(input) {
  const file = input.files[0];
  if (!file) return;
  try {
    const text = await file.text();
    const arr = JSON.parse(text);
    if (!Array.isArray(arr)) throw new Error('格式错误');
    
    let success = 0;
    for (const c of arr) {
      try {
        await fetch('/api/commands', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json; charset=utf-8' },
          body: JSON.stringify({ 
            title: c.title || '', 
            command: c.command || '', 
            description: c.description || '' 
          })
        });
        success++;
      } catch(e) {
        console.error('导入失败:', c, e);
      }
    }
    await load();
    toast(`导入成功 ${'$'}{success}/${'$'}{arr.length} 条`);
  } catch (e) {
    toast('导入失败: ' + e.message);
  }
  input.value = '';
}

// 终端相关功能
async function loadTerminal() {
  try {
    const res = await fetch('/api/terminal');
    const text = await res.text();
    const el = document.getElementById('terminalContent');
    if (text && text !== el.textContent) {
      el.textContent = text;
      // 自动滚动到底部
      el.scrollTop = el.scrollHeight;
    }
  } catch (e) {
    console.error('终端加载失败:', e);
  }
}

function copyTerminal() {
  const text = document.getElementById('terminalContent').textContent;
  if (!text || text === '等待终端输出...') {
    toast('终端无内容');
    return;
  }
  navigator.clipboard.writeText(text).then(() => {
    toast('已复制到剪贴板');
  }).catch(() => {
    toast('复制失败');
  });
}

function exportTerminal() {
  const text = document.getElementById('terminalContent').textContent;
  if (!text || text === '等待终端输出...') {
    toast('终端无内容');
    return;
  }
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'terminal_output_' + Date.now() + '.txt';
  a.click();
  URL.revokeObjectURL(url);
  toast('已导出终端内容');
}

function clearTerminal() {
  if (!confirm('确定清空终端显示？')) return;
  document.getElementById('terminalContent').textContent = '终端已清空';
  toast('已清空');
}

// 执行命令
async function executeCmd(cmd) {
  if (!cmd || !cmd.trim()) return;
  try {
    const res = await fetch('/api/execute', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ command: cmd.trim() })
    });
    const data = await res.json();
    if (data.error) {
      toast('执行失败: ' + data.error);
    } else {
      toast('命令已发送');
      // 立即刷新终端
      setTimeout(() => loadTerminal(), 500);
    }
  } catch(e) {
    toast('执行失败: ' + e.message);
  }
}

function sendCommand() {
  const input = document.getElementById('cmdInput');
  const cmd = input.value.trim();
  if (!cmd) return;
  
  executeCmd(cmd);
  input.value = '';
  input.focus();
}

function handleCmdEnter(event) {
  if (event.key === 'Enter') {
    sendCommand();
  }
}

function escapeHtml(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg; 
  t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 2500);
}

// 初始化
load();
loadTerminal();
// 每秒刷新终端
setInterval(loadTerminal, 1000);
</script>
</body>
</html>
        """.trimIndent()
    }


    private fun respondWithCors(
        status: Response.IStatus, mimeType: String, message: String
    ): Response {
        val resp = NanoHTTPD.newFixedLengthResponse(status, mimeType, message)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return resp
    }
}
