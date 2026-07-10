package com.sunnypilot.toolbox.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.FileRepository
import com.sunnypilot.toolbox.model.FileEntry
import com.sunnypilot.toolbox.ui.theme.*
import com.sunnypilot.toolbox.ui.util.QrCodeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileScreen(
    sshManager: SshManager,
    initialPath: String = "/",
    modifier: Modifier = Modifier
) {
    val repo = remember { FileRepository(sshManager) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var currentPath by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 搜索状态
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FileEntry>?>(null) }

    // 预览/操作弹窗
    var selectedFile by remember { mutableStateOf<FileEntry?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    var previewContent by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showFileInfo by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    // 编辑状态
    var showEdit by remember { mutableStateOf(false) }
    var editContent by remember { mutableStateOf("") }
    var editFile by remember { mutableStateOf<FileEntry?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // 重命名状态
    var showRename by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var renameNewName by remember { mutableStateOf("") }

    // 新建目录状态
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    // 文件太大不可编辑的提示
    var showEditTooLarge by remember { mutableStateOf(false) }
    var tooLargeFile by remember { mutableStateOf<FileEntry?>(null) }

    // 二维码弹窗
    var showQrDialog by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrUrl by remember { mutableStateOf("") }

    LaunchedEffect(showQrDialog) {
        if (showQrDialog && qrBitmap == null) {
            val ip = QrCodeUtil.getLocalIpAddress()
            val url = ip?.let { "http://$it:8080" } ?: ""
            qrUrl = url
            qrBitmap = withContext(Dispatchers.Default) {
                if (url.isNotEmpty()) QrCodeUtil.generateQrCode(url, 300) else null
            }
        }
    }

    fun loadDir(path: String) {
        scope.launch {
            isLoading = true
            errorMsg = null
            repo.listFiles(path).fold(
                onSuccess = { entries = it },
                onFailure = { errorMsg = it.message }
            )
            isLoading = false
        }
    }

    fun doSearch(query: String) {
        scope.launch {
            isLoading = true
            searchResults = null
            repo.searchFiles(query, currentPath).fold(
                onSuccess = {
                    searchResults = it
                    if (it.isEmpty()) errorMsg = "未找到匹配的文件"
                },
                onFailure = { errorMsg = it.message }
            )
            isLoading = false
        }
    }

    // 上传启动器
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val fileName = getFileName(context, uri) ?: "uploaded_file"
            val tempFile = java.io.File(context.cacheDir, fileName)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                val remotePath = if (currentPath == "/") "/$fileName" else "$currentPath/$fileName"
                repo.uploadFile(tempFile.absolutePath, remotePath).fold(
                    onSuccess = { loadDir(currentPath) },
                    onFailure = { errorMsg = "上传失败: ${it.message}" }
                )
            } finally {
                tempFile.delete()
            }
        }
    }

    LaunchedEffect(currentPath) { loadDir(currentPath) }

    Column(modifier = modifier.fillMaxSize()) {
        // ── 导航栏：面包屑 + 搜索切换 ──
        Surface(
            shape = RoundedCornerShape(0.dp, 0.dp, 16.dp, 16.dp),
            color = Panel,
            shadowElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 后退按钮
                    if (currentPath != "/" && !isSearching) {
                        IconButton(onClick = {
                            val parent = currentPath.substringBeforeLast("/", "/").let {
                                if (it.isEmpty()) "/" else it
                            }
                            currentPath = parent
                        }) {
                            Icon(Icons.Default.ArrowBack, "返回", tint = Slate700)
                        }
                    }

                    // 面包屑或搜索
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索文件…", color = Slate400, fontSize = 14.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                doSearch(searchQuery)
                                isSearching = false
                            }),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Teal500,
                                unfocusedBorderColor = Slate200
                            ),
                            trailingIcon = {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    isSearching = false
                                    searchResults = null
                                    errorMsg = null
                                }) {
                                    Icon(Icons.Default.Close, "取消搜索", tint = Slate500)
                                }
                            }
                        )
                    } else {
                        Breadcrumb(
                            path = currentPath,
                            onNavigate = { currentPath = it },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 搜索按钮
                    IconButton(onClick = {
                        isSearching = !isSearching
                        if (!isSearching) {
                            searchResults = null
                            errorMsg = null
                        }
                    }) {
                        Icon(
                            if (isSearching) Icons.Default.Close else Icons.Default.Search,
                            "搜索",
                            tint = if (isSearching) Red500 else Teal500
                        )
                    }

                    // 二维码 - 扫描进入 Web 管理页面
                    IconButton(onClick = {
                        qrBitmap = null
                        showQrDialog = true
                    }) {
                        Icon(
                            Icons.Default.QrCode,
                            "扫码管理",
                            tint = Teal500
                        )
                    }

                    // 刷新
                    IconButton(onClick = { loadDir(currentPath) }) {
                        Icon(Icons.Default.Refresh, "刷新", tint = Teal500)
                    }

                    // 新建目录
                    IconButton(onClick = { showNewFolder = true; newFolderName = "" }) {
                        Icon(Icons.Default.CreateNewFolder, "新建目录", tint = Teal500)
                    }

                    // 上传文件
                    IconButton(onClick = { uploadLauncher.launch("*/*") }) {
                        Icon(Icons.Default.Upload, "上传文件", tint = Blue500)
                    }

                    // 跳转到 /data
                    IconButton(onClick = { currentPath = "/data" }) {
                        Icon(Icons.Default.FolderOpen, "/data", tint = Amber500)
                    }
                }

                // 搜索结果标签
                if (searchResults != null) {
                    Text(
                        "搜索「$searchQuery」: ${searchResults?.size ?: 0} 个结果",
                        fontSize = 12.sp,
                        color = Slate500
                    )
                }
                if (errorMsg != null && !isLoading) {
                    Text(errorMsg!!, fontSize = 12.sp, color = Red500)
                }
            }
        }

        // ── 文件列表 ──
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isLoading) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Teal500, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("加载中…", color = Slate500, fontSize = 13.sp)
                    }
                }
            } else {
                val displayList = searchResults ?: entries
                if (displayList.isEmpty()) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FolderOff, null, tint = Slate400, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("此目录为空", color = Slate500)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(displayList, key = { it.path }) { entry ->
                            FileItemRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) {
                                        currentPath = entry.path
                                        searchResults = null
                                    } else {
                                        selectedFile = entry
                                        scope.launch {
                                            previewContent = repo.getFilePreview(entry.path).getOrElse { "无法读取文件" }
                                            showPreview = true
                                        }
                                    }
                                },
                                onEdit = {
                                    selectedFile = entry
                                    if (entry.isEditable) {
                                        scope.launch {
                                            editContent = repo.readFileContent(entry.path).getOrElse { "读取失败" }
                                            editFile = entry
                                            showEdit = true
                                        }
                                    } else if (entry.isTextFile) {
                                        tooLargeFile = entry
                                        showEditTooLarge = true
                                    }
                                },
                                onRename = {
                                    renameTarget = entry
                                    renameNewName = entry.name
                                    showRename = true
                                },
                                onDownload = {
                                    scope.launch {
                                        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                                            ?: context.filesDir
                                        val local = java.io.File(dir, entry.name)
                                        repo.downloadFile(entry.path, local.absolutePath).fold(
                                            onSuccess = {
                                                Toast.makeText(context, "已下载到 ${local.absolutePath}", Toast.LENGTH_LONG).show()
                                            },
                                            onFailure = { errorMsg = "下载失败: ${it.message}" }
                                        )
                                    }
                                },
                                onDelete = {
                                    selectedFile = entry
                                    showDeleteConfirm = true
                                },
                                onInfo = {
                                    selectedFile = entry
                                    showFileInfo = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 文件预览对话框 ──
    if (showPreview && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FileTypeIcon(selectedFile!!, size = 20)
                    Spacer(Modifier.width(8.dp))
                    Text(selectedFile!!.name, fontWeight = FontWeight.Bold, color = Slate900)
                }
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        previewContent,
                        fontSize = 12.sp,
                        color = Slate700,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showPreview = false }) { Text("关闭", color = Teal500) } },
            dismissButton = {}
        )
    }

    // ── 删除确认 ──
    if (showDeleteConfirm && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除", fontWeight = FontWeight.Bold, color = Red500) },
            text = {
                Column {
                    Text("确定删除？", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(selectedFile!!.name, fontSize = 14.sp, color = Slate600)
                    if (selectedFile!!.isDirectory) {
                        Text("（目录及其所有内容将被递归删除）", fontSize = 12.sp, color = Red500)
                    }
                    if (isDeleting) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(color = Red500, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isDeleting = true
                            val f = selectedFile!!
                            repo.deleteFile(f.path, f.isDirectory).fold(
                                onSuccess = {
                                    showDeleteConfirm = false
                                    loadDir(currentPath)
                                },
                                onFailure = { errorMsg = it.message }
                            )
                            isDeleting = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Red500),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    // ── 文件信息 ──
    if (showFileInfo && selectedFile != null) {
        val f = selectedFile!!
        AlertDialog(
            onDismissRequest = { showFileInfo = false },
            title = { Text("文件信息", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("名称", f.name)
                    InfoRow("路径", f.path)
                    InfoRow("类型", if (f.isDirectory) "目录" else "文件")
                    InfoRow("大小", f.sizeHuman)
                    InfoRow("修改时间", f.lastModified)
                    InfoRow("权限", f.permissions)
                    if (f.isSymlink) InfoRow("符号链接", "是")
                }
            },
            confirmButton = { TextButton(onClick = { showFileInfo = false }) { Text("关闭", color = Teal500) } }
        )
    }

    // ── 编辑文件对话框 ──
    if (showEdit && editFile != null) {
        AlertDialog(
            onDismissRequest = { if (!isSaving) { showEdit = false } },
            title = { Text("编辑 ${editFile!!.name}", fontWeight = FontWeight.Bold, color = Slate900) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 13.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Teal500,
                            unfocusedBorderColor = Slate200
                        )
                    )
                    if (isSaving) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(color = Teal500, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            repo.saveFile(editFile!!.path, editContent).fold(
                                onSuccess = {
                                    showEdit = false
                                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { errorMsg = "保存失败: ${it.message}" }
                            )
                            isSaving = false
                        }
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEdit = false }, enabled = !isSaving) { Text("取消") }
            }
        )
    }

    // ── 文件过大提示 ──
    if (showEditTooLarge && tooLargeFile != null) {
        AlertDialog(
            onDismissRequest = { showEditTooLarge = false },
            title = { Text("文件过大", fontWeight = FontWeight.Bold) },
            text = {
                Text("${tooLargeFile!!.name} 为 ${tooLargeFile!!.sizeHuman}，超过编辑上限 200KB。\n建议使用 WinSCP 等桌面工具编辑。")
            },
            confirmButton = {
                TextButton(onClick = { showEditTooLarge = false }) { Text("知道了", color = Teal500) }
            }
        )
    }

    // ── 重命名对话框 ──
    if (showRename && renameTarget != null) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameNewName,
                    onValueChange = { renameNewName = it },
                    singleLine = true,
                    label = { Text("新名称") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Teal500,
                        unfocusedBorderColor = Slate200
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val t = renameTarget!!
                            val parent = t.path.substringBeforeLast("/", "/")
                            val newPath = if (parent == "/") "/$renameNewName" else "$parent/$renameNewName"
                            repo.renameFile(t.path, newPath).fold(
                                onSuccess = {
                                    showRename = false
                                    loadDir(currentPath)
                                },
                                onFailure = { errorMsg = "重命名失败: ${it.message}" }
                            )
                        }
                    },
                    enabled = renameNewName.isNotBlank() && renameNewName != renameTarget?.name,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } }
        )
    }

    // ── 新建目录对话框 ──
    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建目录", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text("目录名称") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Teal500,
                        unfocusedBorderColor = Slate200
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val dirPath = if (currentPath == "/") "/$newFolderName" else "$currentPath/$newFolderName"
                            repo.createDirectory(dirPath).fold(
                                onSuccess = {
                                    showNewFolder = false
                                    loadDir(currentPath)
                                },
                                onFailure = { errorMsg = "创建失败: ${it.message}" }
                            )
                        }
                    },
                    enabled = newFolderName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("取消") } }
        )
    }

    // ── 二维码弹窗 ──
    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = {
                showQrDialog = false
                qrBitmap = null
            },
            title = {
                Text("扫码访问 Web 管理", fontWeight = FontWeight.Bold, color = Slate900)
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "Web管理二维码",
                            modifier = Modifier.size(220.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = qrUrl,
                            color = Slate600,
                            fontSize = 13.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "用手机或电脑浏览器扫码，即可在同一局域网内",
                            color = Slate500,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "管理 C3 文件、快捷命令",
                            color = Slate500,
                            fontSize = 12.sp
                        )
                    } else if (qrUrl.isEmpty()) {
                        Text(
                            text = "未获取到局域网 IP，请确认已连接 WiFi",
                            color = Slate500,
                            fontSize = 14.sp
                        )
                    } else {
                        CircularProgressIndicator(
                            color = Teal500,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showQrDialog = false
                    qrBitmap = null
                }) {
                    Text("关闭", color = Teal500)
                }
            }
        )
    }
}

// ── 面包屑导航 ──
@Composable
private fun Breadcrumb(
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val parts = path.split("/").filter { it.isNotBlank() }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 根目录
        Text(
            "/",
            color = Teal500,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.clickable { onNavigate("/") }
        )
        parts.forEachIndexed { i, part ->
            Text("/", color = Slate400, fontSize = 13.sp)
            val fullPath = "/" + parts.take(i + 1).joinToString("/")
            Text(
                part,
                color = if (i == parts.lastIndex) Slate900 else Teal500,
                fontWeight = if (i == parts.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (i < parts.lastIndex) Modifier.clickable { onNavigate(fullPath) } else Modifier
            )
        }
    }
}

// ── 文件列表行 ──
@Composable
private fun FileItemRow(
    entry: FileEntry,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileTypeIcon(entry)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Slate900,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (entry.isDirectory) "" else entry.sizeHuman,
                        fontSize = 12.sp,
                        color = Slate500
                    )
                    if (entry.lastModified.isNotBlank()) {
                        Text(entry.lastModified, fontSize = 12.sp, color = Slate400)
                    }
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "操作", tint = Slate500, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("信息", fontSize = 13.sp) },
                        onClick = { showMenu = false; onInfo() },
                        leadingIcon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp)) }
                    )
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("预览", fontSize = 13.sp) },
                            onClick = { showMenu = false; onClick() },
                            leadingIcon = { Icon(Icons.Default.Visibility, null, modifier = Modifier.size(18.dp)) }
                        )
                        if (entry.isEditable) {
                            DropdownMenuItem(
                                text = { Text("编辑", fontSize = 13.sp) },
                                onClick = { showMenu = false; onEdit() },
                                leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("下载", fontSize = 13.sp) },
                            onClick = { showMenu = false; onDownload() },
                            leadingIcon = { Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重命名", fontSize = 13.sp) },
                        onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", fontSize = 13.sp, color = Red500) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Red500, modifier = Modifier.size(18.dp)) }
                    )
                }
            }
        }
    }
}

// ── 文件类型图标 ──
@Composable
private fun FileTypeIcon(entry: FileEntry, size: Int = 40) {
    val (icon, color, bg) = when (entry.icon) {
        "folder" -> Triple(Icons.Default.Folder, Amber500, Amber100)
        "image" -> Triple(Icons.Default.Image, Purple500, Purple100)
        "video" -> Triple(Icons.Default.Videocam, Red500, Red100)
        "audio" -> Triple(Icons.Default.AudioFile, Blue500, Blue100)
        "archive" -> Triple(Icons.Default.Archive, Orange500, Orange100)
        "code" -> Triple(Icons.Default.Code, Teal500, Teal50)
        "doc" -> Triple(Icons.Default.Article, Blue500, Blue100)
        "config" -> Triple(Icons.Default.Settings, Slate600, Slate200)
        else -> Triple(Icons.Default.InsertDriveFile, Slate500, Slate100)
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size((size * 0.6).dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Slate700, modifier = Modifier.width(70.dp))
        Text(value, fontSize = 13.sp, color = Slate600)
    }
}

/** 从 content URI 提取文件名 */
private fun getFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    if (uri.scheme == "file") {
        name = uri.lastPathSegment?.substringAfterLast('/')
    }
    if (name == null) {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
    }
    return name
}
