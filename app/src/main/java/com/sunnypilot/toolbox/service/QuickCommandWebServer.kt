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



class QuickCommandWebServer(
    port: Int,
    private val dao: QuickCommandDao
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri.trim('/')

        return when {
            method == Method.GET && uri.isEmpty() -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", indexHtml())
            method == Method.GET && uri == "api/commands" -> {
                val commands = dao.getAllSync()
                newFixedLengthResponse(Response.Status.OK, "application/json", json.encodeToString(commands))
            }
            method == Method.POST && uri == "api/commands" -> {
                val body = parseBody(session)
                val cmd = parseCommand(body)
                val id = runBlocking { dao.insert(cmd) }
                newFixedLengthResponse(Response.Status.CREATED, "application/json", json.encodeToString(cmd.copy(id = id)))
            }
            method == Method.PUT && uri.startsWith("api/commands/") -> {
                val id = uri.removePrefix("api/commands/").toLongOrNull()
                    ?: return badRequest("Invalid id")
                val body = parseBody(session)
                val cmd = parseCommand(body).copy(id = id)
                runBlocking { dao.update(cmd) }
                newFixedLengthResponse(Response.Status.OK, "application/json", json.encodeToString(cmd))
            }
            method == Method.DELETE && uri.startsWith("api/commands/") -> {
                val id = uri.removePrefix("api/commands/").toLongOrNull()
                    ?: return badRequest("Invalid id")
                val existing = runBlocking { dao.getById(id) }
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
                runBlocking { dao.delete(existing) }
                newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
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
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"$msg\"}")
    }

    private fun indexHtml(): String {
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>快捷命令管理</title>
<style>
  * { box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    margin: 0; padding: 16px;
    background: #f8fafc; color: #1e293b;
    max-width: 640px; margin: 0 auto;
  }
  h1 { font-size: 22px; margin: 0 0 16px; }
  .card {
    background: #fff; border-radius: 12px; padding: 16px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08); margin-bottom: 12px;
  }
  .form-group { margin-bottom: 12px; }
  label { display: block; font-size: 13px; color: #64748b; margin-bottom: 4px; }
  input, textarea {
    width: 100%; padding: 10px 12px; border: 1px solid #e2e8f0;
    border-radius: 8px; font-size: 14px; background: #fff;
  }
  textarea { resize: vertical; min-height: 60px; }
  .btn-row { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
  button {
    border: none; border-radius: 8px; padding: 10px 14px;
    font-size: 14px; cursor: pointer; font-weight: 500;
  }
  .primary { background: #0d9488; color: #fff; }
  .secondary { background: #e2e8f0; color: #334155; }
  .danger { background: #fee2e2; color: #ef4444; }
  .item { border-bottom: 1px solid #f1f5f9; padding: 12px 0; }
  .item:last-child { border-bottom: none; }
  .item-title { font-weight: 600; margin-bottom: 4px; }
  .item-desc { font-size: 13px; color: #64748b; margin-bottom: 4px; }
  .item-cmd { font-family: monospace; font-size: 12px; color: #475569; background: #f1f5f9; padding: 4px 8px; border-radius: 4px; overflow-wrap: break-word; }
  .empty { text-align: center; color: #94a3b8; padding: 24px; }
  .top-actions { display: flex; gap: 8px; margin-bottom: 16px; }
  #importFile { display: none; }
  .toast {
    position: fixed; bottom: 16px; left: 50%; transform: translateX(-50%);
    background: #0f172a; color: #fff; padding: 10px 18px; border-radius: 20px;
    font-size: 13px; opacity: 0; transition: opacity .3s; pointer-events: none;
  }
  .toast.show { opacity: 1; }
</style>
</head>
<body>
<h1>快捷命令管理</h1>
<div class="top-actions">
  <button class="primary" onclick="showForm()">+ 新增命令</button>
  <button class="secondary" onclick="exportJson()">导出 JSON</button>
  <button class="secondary" onclick="document.getElementById('importFile').click()">导入 JSON</button>
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
    <textarea id="command" placeholder="例如：reboot"></textarea>
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

<script>
let commands = [];
async function load() {
  const res = await fetch('/api/commands');
  commands = await res.json();
  render();
}
function render() {
  const list = document.getElementById('list');
  if (commands.length === 0) {
    list.innerHTML = '<div class="empty">暂无快捷命令，点击上方新增</div>';
    return;
  }
  list.innerHTML = commands.map(c => `
    <div class="item">
      <div class="item-title">${escapeHtml(c.title)}</div>
      <div class="item-desc">${escapeHtml(c.description || '')}</div>
      <div class="item-cmd">${escapeHtml(c.command)}</div>
      <div class="btn-row">
        <button class="secondary" onclick="editCommand(${c.id})">编辑</button>
        <button class="danger" onclick="deleteCommand(${c.id})">删除</button>
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
    command: document.getElementById('command').value,
    description: document.getElementById('description').value.trim()
  };
  if (!body.title || !body.command) { toast('名称和命令不能为空'); return; }
  if (id) {
    await fetch('/api/commands/' + id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  } else {
    await fetch('/api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  }
  hideForm();
  await load();
  toast('保存成功');
}
function editCommand(id) {
  const c = commands.find(x => x.id === id);
  if (c) showForm(c);
}
async function deleteCommand(id) {
  if (!confirm('确定删除这条命令？')) return;
  await fetch('/api/commands/' + id, { method: 'DELETE' });
  await load();
  toast('已删除');
}
async function exportJson() {
  const blob = new Blob([JSON.stringify(commands, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'quick_commands.json';
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
    for (const c of arr) {
      await fetch('/api/commands', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: c.title || '', command: c.command || '', description: c.description || '' })
      });
    }
    await load();
    toast('导入成功');
  } catch (e) {
    toast('导入失败：' + e.message);
  }
  input.value = '';
}
function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 2000);
}
load();
</script>
</body>
</html>
        """.trimIndent()
    }

    override fun newFixedLengthResponse(
        status: Response.IStatus, mimeType: String, message: String
    ): Response {
        return super.newFixedLengthResponse(status, mimeType, message).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
            addHeader("Access-Control-Allow-Headers", "Content-Type")
        }
    }
}
