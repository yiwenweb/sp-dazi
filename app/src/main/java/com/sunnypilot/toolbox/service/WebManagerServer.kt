package com.sunnypilot.toolbox.service

import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.db.QuickCommandDao
import com.sunnypilot.toolbox.data.repository.FileRepository
import com.sunnypilot.toolbox.model.QuickCommand
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference

/**
 * 统一 Web 管理服务 — 基于 NanoHTTPD。
 * 提供带标签页的 Web 页面：首页、文件管理、快捷命令管理。
 *
 * 文件管理通过 SshManager (SFTP) 操作 C3 设备文件：
 *  - 上传：浏览器 → HTTP multipart → 暂存 Android 临时文件 → SFTP → C3
 *  - 下载：C3 → SFTP → 暂存 Android 临时文件 → HTTP → 浏览器
 */
class WebManagerServer(
    port: Int,
    private val dao: QuickCommandDao,
    private val sshManager: SshManager
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

        // CORS 预检
        if (method == Method.OPTIONS) {
            return corsOptions()
        }

        return when {
            // ── 主页面 ──
            method == Method.GET && uri.isEmpty() ->
                respondHtml(indexHtml())

            // ── 快捷命令 API ──
            uri == "api/terminal" && method == Method.GET ->
                respondText(getTerminalSnapshot())

            uri == "api/commands" && method == Method.GET -> {
                val commands = dao.getAllSync()
                respondJson(json.encodeToString(commands))
            }

            uri == "api/commands" && method == Method.POST -> {
                val body = parseBody(session)
                val cmd = parseCommand(body)
                val id = runBlocking { dao.insert(cmd) }
                respondJson(json.encodeToString(cmd.copy(id = id)))
            }

            uri.startsWith("api/commands/") && method == Method.PUT -> {
                val id = uri.removePrefix("api/commands/").toLongOrNull()
                    ?: return badRequest("Invalid id")
                val body = parseBody(session)
                val cmd = parseCommand(body).copy(id = id)
                runBlocking { dao.update(cmd) }
                respondJson(json.encodeToString(cmd))
            }

            uri.startsWith("api/commands/") && method == Method.DELETE -> {
                val id = uri.removePrefix("api/commands/").toLongOrNull()
                    ?: return badRequest("Invalid id")
                val existing = runBlocking { dao.getById(id) }
                    ?: return respondJson("{}", Status.NOT_FOUND)
                runBlocking { dao.delete(existing) }
                respondJson("{}")
            }

            // ── 文件管理 API ──
            uri == "api/files" && method == Method.GET -> {
                val rawPath = session.parameters["path"]?.firstOrNull() ?: "/"
                handleListFiles(rawPath)
            }

            uri == "api/files/download" && method == Method.GET -> {
                val rawPath = session.parameters["path"]?.firstOrNull()
                    ?: return badRequest("Missing path")
                handleDownload(rawPath)
            }

            uri == "api/files/upload" && method == Method.POST -> {
                val targetDir = session.parameters["path"]?.firstOrNull() ?: "/"
                handleUpload(session, targetDir)
            }

            uri == "api/files" && method == Method.DELETE -> {
                val rawPath = session.parameters["path"]?.firstOrNull()
                    ?: return badRequest("Missing path")
                val isDir = session.parameters["isDir"]?.firstOrNull() == "true"
                handleDelete(rawPath, isDir)
            }

            uri == "api/files/mkdir" && method == Method.POST -> {
                val body = parseBody(session)
                val obj = json.decodeFromString(JsonObject.serializer(), body.ifEmpty { "{}" })
                val dirPath = obj["path"]?.jsonPrimitive?.contentOrNull
                    ?: return badRequest("Missing path")
                handleMkdir(dirPath)
            }

            // ── 404 ──
            else -> respondText("Not Found", Status.NOT_FOUND)
        }
    }

    // ========== 文件操作实现 ==========

    private fun handleListFiles(rawPath: String): Response {
        val path = URLDecoder.decode(rawPath, "UTF-8")
        val result = runBlocking {
            sshManager.withSftp { channel ->
                @Suppress("UNCHECKED_CAST")
                val raw = channel.ls(path) as java.util.Vector<com.jcraft.jsch.ChannelSftp.LsEntry>
                raw.asSequence()
                    .filter { it.filename !in setOf(".", "..") }
                    .sortedWith(compareByDescending<com.jcraft.jsch.ChannelSftp.LsEntry> { it.attrs.isDir }
                        .thenBy { it.filename.lowercase() })
                    .map { entry ->
                        val a = entry.attrs
                        buildJsonObject {
                            put("name", entry.filename)
                            put("path", if (path == "/") "/${entry.filename}" else "$path/${entry.filename}")
                            put("isDirectory", a.isDir)
                            put("size", a.size)
                            put("sizeHuman", formatFileSize(a.size))
                            put("lastModified", if (a.mTime > 0) a.mTime * 1000L else 0L)
                            put("permissions", FileRepository.formatSftpPermissions(a.permissions))
                        }
                    }
                    .toList()
            }
        }
        return result.fold(
            onSuccess = { respondJson(json.encodeToString(it)) },
            onFailure = { respondJson("""{"error":"${it.message}"}""", Status.INTERNAL_ERROR) }
        )
    }

    private fun handleDownload(rawPath: String): Response {
        val path = URLDecoder.decode(rawPath, "UTF-8")
        val fileName = path.substringAfterLast("/")
        val tempFile = File.createTempFile("dl_", "_$fileName")

        val success = runBlocking {
            sshManager.downloadFile(path, tempFile).isSuccess
        }

        if (!success || !tempFile.exists()) {
            tempFile.delete()
            return respondText("Download failed", Status.INTERNAL_ERROR)
        }

        val mimeType = guessMimeType(fileName)
        val resp = newChunkedResponse(Status.OK, mimeType, FileInputStream(tempFile))
        resp.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
        resp.addHeader("X-Delete-After", "true") // 标记需要清理
        // 响应完成后删除临时文件
        return object : Response(Status.OK, mimeType, FileInputStream(tempFile), tempFile.length()) {
            init {
                addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
            }

            override fun close() {
                try { data.close() } catch (_: Exception) {}
                try { tempFile.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun handleUpload(session: IHTTPSession, targetDir: String): Response {
        try {
            session.parseBody(HashMap())
            val files = session.parameters.entries
                .filter { it.key.startsWith("file") && it.value.isNotEmpty() }

            if (files.isEmpty()) {
                return respondJson("""{"error":"No file uploaded"}""", Status.BAD_REQUEST)
            }

            val results = mutableListOf<String>()
            for ((paramName, values) in files) {
                val fileName = session.parameters.getOrDefault("${paramName}_name", listOf("uploaded_file")).first()
                val fileBytes = values.first().toByteArray(Charsets.ISO_8859_1)
                val tempFile = File.createTempFile("ul_", "_$fileName")
                tempFile.writeBytes(fileBytes)

                val remotePath = if (targetDir.endsWith("/")) "$targetDir$fileName" else "$targetDir/$fileName"
                val result = runBlocking {
                    sshManager.uploadFile(tempFile.absolutePath, remotePath)
                }
                tempFile.delete()
                results.add(if (result.isSuccess) "OK: $fileName" else "FAIL: $fileName - ${result.exceptionOrNull()?.message}")
            }

            return respondJson(json.encodeToString(mapOf("results" to results)))
        } catch (e: Exception) {
            return respondJson("""{"error":"Upload error: ${e.message}"}""", Status.INTERNAL_ERROR)
        }
    }

    private fun handleDelete(rawPath: String, isDir: Boolean): Response {
        val path = URLDecoder.decode(rawPath, "UTF-8")
        val result = runBlocking {
            sshManager.deleteRemote(path, isDir)
        }
        return result.fold(
            onSuccess = { respondJson("""{"ok":true}""") },
            onFailure = { respondJson("""{"error":"${it.message}"}""", Status.INTERNAL_ERROR) }
        )
    }

    private fun handleMkdir(dirPath: String): Response {
        val result = runBlocking {
            sshManager.createDirectory(dirPath)
        }
        return result.fold(
            onSuccess = { respondJson("""{"ok":true}""") },
            onFailure = { respondJson("""{"error":"${it.message}"}""", Status.INTERNAL_ERROR) }
        )
    }

    // ========== 工具方法 ==========

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

    private fun badRequest(msg: String): Response =
        respondJson("{\"error\":\"$msg\"}", Status.BAD_REQUEST)

    private fun respondJson(body: String, status: Status = Status.OK): Response {
        val resp = newFixedLengthResponse(status, "application/json", body)
        addCors(resp)
        return resp
    }

    private fun respondText(body: String, status: Status = Status.OK): Response {
        val resp = newFixedLengthResponse(status, "text/plain; charset=utf-8", body)
        addCors(resp)
        return resp
    }

    private fun respondHtml(body: String): Response {
        val resp = newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", body)
        addCors(resp)
        return resp
    }

    private fun corsOptions(): Response {
        val resp = newFixedLengthResponse(Status.OK, "text/plain", "")
        addCors(resp)
        return resp
    }

    private fun addCors(resp: Response) {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type")
    }

    private fun guessMimeType(name: String): String = when {
        name.endsWith(".log") || name.endsWith(".txt") -> "text/plain"
        name.endsWith(".json") || name.endsWith(".jsonl") -> "application/json"
        name.endsWith(".html") -> "text/html"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".gif") -> "image/gif"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".pdf") -> "application/pdf"
        name.endsWith(".zip") || name.endsWith(".gz") || name.endsWith(".tar") -> "application/octet-stream"
        name.endsWith(".mp4") -> "video/mp4"
        name.endsWith(".mp3") -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
    }

    // ========== HTML 页面 ==========

    private fun indexHtml(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>SunnyPilot 工具箱</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  background: #f1f5f9; color: #0f172a; min-height: 100vh;
}
/* 标签导航 */
.tabs {
  display: flex; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,0.08);
  position: sticky; top: 0; z-index: 10;
}
.tab {
  flex: 1; text-align: center; padding: 14px 0; cursor: pointer;
  font-size: 15px; font-weight: 500; color: #64748b; border-bottom: 3px solid transparent;
  transition: all .2s; user-select: none;
}
.tab.active { color: #0d9488; border-bottom-color: #0d9488; }
.tab:hover { background: #f8fafc; }

.page { display: none; padding: 16px; max-width: 800px; margin: 0 auto; }
.page.active { display: block; }

/* 通用卡片 */
.card {
  background: #fff; border-radius: 12px; padding: 16px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06); margin-bottom: 12px;
}

/* 首页 */
.hero { text-align: center; padding: 40px 16px; }
.hero h1 { font-size: 28px; color: #0f172a; margin-bottom: 8px; }
.hero .device { font-size: 14px; color: #64748b; margin-bottom: 16px; }
.hero .features { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; margin-top: 20px; }
.feature-item {
  background: #f0fdfa; border: 1px solid #ccfbf1; border-radius: 10px;
  padding: 14px 20px; font-size: 14px; color: #0d9488; font-weight: 500;
  cursor: pointer; transition: all .2s;
}
.feature-item:hover { background: #ccfbf1; }

/* 文件管理 */
.breadcrumb { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px; }
.breadcrumb a { color: #0d9488; text-decoration: none; cursor: pointer; }
.breadcrumb a:hover { text-decoration: underline; }
.breadcrumb span { color: #94a3b8; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; align-items: center; }
.file-table { width: 100%; border-collapse: collapse; }
.file-table th { text-align: left; font-size: 12px; color: #64748b; padding: 8px 10px; border-bottom: 1px solid #e2e8f0; }
.file-table td { padding: 8px 10px; font-size: 13px; border-bottom: 1px solid #f1f5f9; cursor: pointer; }
.file-table tr:hover td { background: #f8fafc; }
.file-icon { margin-right: 8px; }
.file-actions button { margin-left: 4px; }
.loading { text-align: center; color: #94a3b8; padding: 24px; }

/* 表单 */
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
.small { padding: 6px 10px; font-size: 12px; }

/* 命令列表 */
.item { border-bottom: 1px solid #f1f5f9; padding: 12px 0; }
.item:last-child { border-bottom: none; }
.item-title { font-weight: 600; margin-bottom: 4px; }
.item-desc { font-size: 13px; color: #64748b; margin-bottom: 4px; }
.item-cmd { font-family: monospace; font-size: 12px; color: #475569; background: #f1f5f9; padding: 4px 8px; border-radius: 4px; overflow-wrap: break-word; }
.empty { text-align: center; color: #94a3b8; padding: 24px; }

/* 隐藏的文件上传 input */
#fileInput { display: none; }

/* Toast */
.toast {
  position: fixed; bottom: 16px; left: 50%; transform: translateX(-50%);
  background: #0f172a; color: #fff; padding: 10px 18px; border-radius: 20px;
  font-size: 13px; opacity: 0; transition: opacity .3s; pointer-events: none; z-index: 100;
}
.toast.show { opacity: 1; }
</style>
</head>
<body>

<!-- 标签导航 -->
<div class="tabs">
  <div class="tab active" data-page="home">首页</div>
  <div class="tab" data-page="files">文件管理</div>
  <div class="tab" data-page="commands">快捷命令</div>
</div>

<!-- ====== 首页 ====== -->
<div id="page-home" class="page active">
  <div class="card hero">
    <h1>🚗 SunnyPilot 工具箱</h1>
    <div class="device">远程管理 C3 车机设备</div>
    <div class="features">
      <div class="feature-item" onclick="switchTab('files')">📁 文件管理</div>
      <div class="feature-item" onclick="switchTab('commands')">⚡ 快捷命令</div>
    </div>
  </div>
</div>

<!-- ====== 文件管理 ====== -->
<div id="page-files" class="page">
  <div class="card">
    <div class="toolbar">
      <button class="primary small" onclick="uploadFile()">📤 上传文件</button>
      <button class="secondary small" onclick="showMkdir()">📁 新建目录</button>
      <button class="secondary small" onclick="refreshFiles()">🔄 刷新</button>
      <input type="file" id="fileInput" multiple onchange="doUpload(this)">
      <span style="font-size:12px;color:#94a3b8;margin-left:auto;" id="fileCount"></span>
    </div>
    <div class="breadcrumb" id="breadcrumb"></div>
    <div id="fileList" class="loading">加载中...</div>
  </div>
</div>

<!-- ====== 快捷命令 ====== -->
<div id="page-commands" class="page">
  <div class="card">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
      <h2 style="font-size:18px;">快捷命令管理</h2>
      <div>
        <button class="primary small" onclick="showCmdForm()">+ 新增</button>
        <button class="secondary small" onclick="exportCmdJson()">导出</button>
        <button class="secondary small" onclick="document.getElementById('importCmdFile').click()">导入</button>
        <input type="file" id="importCmdFile" accept="application/json" onchange="importCmdJson(this)" style="display:none">
      </div>
    </div>
    <div id="cmdFormCard" style="display:none; margin-bottom:12px; padding:16px; background:#f8fafc; border-radius:10px;">
      <input type="hidden" id="cmdId">
      <div class="form-group"><label>命令名称</label><input type="text" id="cmdTitle" placeholder="例如：重启 openpilot"></div>
      <div class="form-group"><label>命令内容</label><textarea id="cmdCommand" placeholder="例如：reboot"></textarea></div>
      <div class="form-group"><label>作用说明</label><input type="text" id="cmdDesc" placeholder="简短描述用途"></div>
      <div class="btn-row">
        <button class="primary" onclick="saveCommand()">保存</button>
        <button class="secondary" onclick="hideCmdForm()">取消</button>
      </div>
    </div>
    <div id="cmdList" class="loading">加载中...</div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
// ====== 标签切换 ======
function switchTab(name) {
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelector(`.tab[data-page="${'$'}{name}"]`).classList.add('active');
  document.getElementById(`page-${'$'}{name}`).classList.add('active');
  if (name === 'files') loadFiles(currentFilePath);
  if (name === 'commands') loadCommands();
}

document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => switchTab(tab.dataset.page));
});

// ====== Toast ======
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 2000);
}

// ====== 文件管理 ======
let currentFilePath = '/';
async function loadFiles(path) {
  currentFilePath = path;
  const el = document.getElementById('fileList');
  el.innerHTML = '<div class="loading">加载中...</div>';
  try {
    const res = await fetch('/api/files?path=' + encodeURIComponent(path));
    const files = await res.json();
    if (files.error) { el.innerHTML = `<div class="loading">错误: ${'$'}{files.error}</div>`; return; }
    document.getElementById('fileCount').textContent = `${'$'}{files.length} 项`;

    // 面包屑
    let bc = '<a onclick="loadFiles(\'/\')">/</a>';
    const parts = path.split('/').filter(Boolean);
    let cum = '';
    parts.forEach((p, i) => {
      cum += '/' + p;
      bc += '<span> / </span>';
      if (i === parts.length - 1) {
        bc += `<strong style="color:#0f172a">${'$'}{p}</strong>`;
      } else {
        bc += `<a onclick="loadFiles('${'$'}{cum}')">${'$'}{p}</a>`;
      }
    });
    document.getElementById('breadcrumb').innerHTML = bc;

    if (files.length === 0) {
      el.innerHTML = '<div class="loading">此目录为空</div>';
      return;
    }
    let html = '<table class="file-table"><tr><th>名称</th><th>大小</th><th>修改时间</th><th style="width:120px">操作</th></tr>';
    files.forEach(f => {
      const icon = f.isDirectory ? '📁' : '📄';
      html += `<tr>
        <td onclick="${'$'}{f.isDirectory ? `loadFiles('${'$'}{f.path}')` : `downloadFile('${'$'}{f.path}')`}">
          <span class="file-icon">${'$'}{icon}</span>${'$'}{escapeHtml(f.name)}
        </td>
        <td style="color:#64748b">${'$'}{f.isDirectory ? '-' : f.sizeHuman}</td>
        <td style="color:#64748b;font-size:12px">${'$'}{f.lastModified > 0 ? new Date(f.lastModified).toLocaleString() : ''}</td>
        <td class="file-actions">
          <button class="secondary small" onclick="event.stopPropagation();downloadFile('${'$'}{f.path}')" ${'$'}{f.isDirectory?'disabled':''}>下载</button>
          <button class="danger small" onclick="event.stopPropagation();deleteFile('${'$'}{f.path}',${'$'}{f.isDirectory})">删除</button>
        </td>
      </tr>`;
    });
    html += '</table>';
    el.innerHTML = html;
  } catch (e) {
    el.innerHTML = `<div class="loading">加载失败: ${'$'}{e.message}</div>`;
  }
}

function refreshFiles() { loadFiles(currentFilePath); }

function downloadFile(path) {
  const a = document.createElement('a');
  a.href = '/api/files/download?path=' + encodeURIComponent(path);
  a.download = path.split('/').pop();
  a.click();
}

function uploadFile() { document.getElementById('fileInput').click(); }
async function doUpload(input) {
  const files = input.files;
  if (!files.length) return;
  const formData = new FormData();
  for (const f of files) formData.append('file', f);

  try {
    const res = await fetch('/api/files/upload?path=' + encodeURIComponent(currentFilePath), {
      method: 'POST', body: formData
    });
    const data = await res.json();
    toast(data.results ? data.results.join(', ') : '上传完成');
    loadFiles(currentFilePath);
  } catch (e) { toast('上传失败'); }
  input.value = '';
}

async function deleteFile(path, isDir) {
  if (!confirm(`确定删除 ${'$'}{path}？${'$'}{isDir?'（目录及所有内容）':''}`)) return;
  try {
    await fetch(`/api/files?path=${'$'}{encodeURIComponent(path)}&isDir=${'$'}{isDir}`, { method: 'DELETE' });
    toast('已删除');
    loadFiles(currentFilePath);
  } catch (e) { toast('删除失败'); }
}

function showMkdir() {
  const name = prompt('目录名称：');
  if (!name) return;
  const newPath = currentFilePath === '/' ? '/' + name : currentFilePath + '/' + name;
  fetch('/api/files/mkdir', {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({path: newPath})
  }).then(r => r.json()).then(d => {
    toast(d.ok ? '创建成功' : (d.error || '创建失败'));
    loadFiles(currentFilePath);
  });
}

// ====== 快捷命令 ======
let commands = [];
async function loadCommands() {
  try {
    const res = await fetch('/api/commands');
    commands = await res.json();
    renderCommands();
  } catch (e) {}
}
function renderCommands() {
  const el = document.getElementById('cmdList');
  if (commands.length === 0) { el.innerHTML = '<div class="empty">暂无快捷命令</div>'; return; }
  el.innerHTML = commands.map(c => `
    <div class="item">
      <div class="item-title">${'$'}{escapeHtml(c.title)}</div>
      <div class="item-desc">${'$'}{escapeHtml(c.description || '')}</div>
      <div class="item-cmd">${'$'}{escapeHtml(c.command)}</div>
      <div class="btn-row">
        <button class="secondary small" onclick="editCmd(${'$'}{c.id})">编辑</button>
        <button class="danger small" onclick="deleteCmd(${'$'}{c.id})">删除</button>
      </div>
    </div>
  `).join('');
}
function showCmdForm(cmd) {
  document.getElementById('cmdFormCard').style.display = 'block';
  document.getElementById('cmdId').value = cmd ? cmd.id : '';
  document.getElementById('cmdTitle').value = cmd ? cmd.title : '';
  document.getElementById('cmdCommand').value = cmd ? cmd.command : '';
  document.getElementById('cmdDesc').value = cmd ? cmd.description : '';
  document.getElementById('cmdTitle').focus();
}
function hideCmdForm() {
  document.getElementById('cmdFormCard').style.display = 'none';
  document.getElementById('cmdId').value = '';
  document.getElementById('cmdTitle').value = '';
  document.getElementById('cmdCommand').value = '';
  document.getElementById('cmdDesc').value = '';
}
async function saveCommand() {
  const id = document.getElementById('cmdId').value;
  const body = {
    title: document.getElementById('cmdTitle').value.trim(),
    command: document.getElementById('cmdCommand').value,
    description: document.getElementById('cmdDesc').value.trim()
  };
  if (!body.title || !body.command) { toast('名称和命令不能为空'); return; }
  if (id) {
    await fetch('/api/commands/' + id, { method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body) });
  } else {
    await fetch('/api/commands', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body) });
  }
  hideCmdForm(); await loadCommands(); toast('保存成功');
}
function editCmd(id) { const c = commands.find(x => x.id === id); if (c) showCmdForm(c); }
async function deleteCmd(id) {
  if (!confirm('确定删除？')) return;
  await fetch('/api/commands/' + id, { method: 'DELETE' });
  await loadCommands(); toast('已删除');
}
async function exportCmdJson() {
  const blob = new Blob([JSON.stringify(commands, null, 2)], { type: 'application/json' });
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob);
  a.download = 'quick_commands.json'; a.click(); URL.revokeObjectURL(a.href);
}
async function importCmdJson(input) {
  const file = input.files[0]; if (!file) return;
  try {
    const text = await file.text(); const arr = JSON.parse(text);
    if (!Array.isArray(arr)) throw new Error('格式错误');
    for (const c of arr) {
      await fetch('/api/commands', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({title: c.title||'', command: c.command||'', description: c.description||''})
      });
    }
    await loadCommands(); toast('导入成功');
  } catch (e) { toast('导入失败: ' + e.message); }
  input.value = '';
}
function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// 初始化
loadFiles('/');
loadCommands();
</script>
</body>
</html>
    """.trimIndent()
}
