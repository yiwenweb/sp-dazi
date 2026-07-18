// 模型信息视图（只读）
@Composable
private fun ModelSelectionView(sshManager: SshManager) {
    val scope = rememberCoroutineScope()
    
    var activeBundle by remember { mutableStateOf<String?>(null) }
    var downloadedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    
    fun loadModelInfo() {
        scope.launch {
            isLoading = true
            
            // 读取当前激活的模型包
            sshManager.executeCommand("cat /data/params/d/ModelManager_ActiveBundle 2>/dev/null || echo ''").fold(
                onSuccess = { output -> 
                    activeBundle = output.trim().ifEmpty { "Default (Stock)" }
                },
                onFailure = { 
                    activeBundle = "Default (Stock)"
                }
            )
            
            // 列出已下载的模型文件
            sshManager.executeCommand("ls /data/media/0/models/*.onnx /data/media/0/models/*.dlc 2>/dev/null | xargs -n1 basename 2>/dev/null || echo ''").fold(
                onSuccess = { output ->
                    downloadedModels = output.trim().split("\n")
                        .filter { it.isNotBlank() }
                        .sorted()
                },
                onFailure = {
                    downloadedModels = emptyList()
                }
            )
            
            isLoading = false
        }
    }
    
    LaunchedEffect(Unit) {
        loadModelInfo()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 提示信息卡片
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)), // Amber100
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color(0xFFD97706), // Amber600
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "模型管理功能请在 C3 设备上操作",
                    fontSize = 13.sp,
                    color = Color(0xFF92400E), // Amber900
                    lineHeight = 18.sp
                )
            }
        }
        
        // 当前激活模型包
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)), // Blue50
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF0284C7), // Sky600
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "当前激活模型包",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Spacer(Modifier.weight(1f))
                    if (isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFF0284C7)
                        )
                    } else {
                        IconButton(onClick = { loadModelInfo() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Refresh, "刷新", tint = Color(0xFF0284C7))
                        }
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                if (activeBundle != null) {
                    Text(
                        activeBundle!!,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0284C7)
                    )
                } else {
                    Text(
                        "读取中...",
                        fontSize = 14.sp,
                        color = Slate500
                    )
                }
            }
        }
        
        // 已下载模型列表
        Text(
            "已下载模型文件 (${downloadedModels.size})",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Slate900
        )
        
        if (downloadedModels.isEmpty()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Slate50)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = Slate400,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "未找到已下载的模型文件",
                            fontSize = 14.sp,
                            color = Slate500,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "模型存储路径: /data/media/0/models/",
                            fontSize = 11.sp,
                            color = Slate400,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            downloadedModels.forEach { model ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = null,
                            tint = Color(0xFF9333EA), // Purple600
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                model,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Slate900
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                when {
                                    model.endsWith(".onnx") -> "ONNX 模型"
                                    model.endsWith(".dlc") -> "DLC 模型"
                                    else -> "模型文件"
                                },
                                fontSize = 11.sp,
                                color = Slate500
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
    }
}
