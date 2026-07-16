package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.RecorderRepository
import com.sunnypilot.toolbox.data.repository.SegmentSummary
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

enum class SortMode {
    TIME_DESC,      // 时间倒序（最新优先）
    TIME_ASC,       // 时间正序（最旧优先）
    NAME_ASC,       // 名称升序
    NAME_DESC,      // 名称降序
    READY_FIRST,    // 就绪优先（有视频+叠加）
    CACHED_FIRST    // 已缓存优先
}

enum class ViewMode {
    LIST,           // 列表视图
    GRID,           // 网格视图
    COMPACT         // 紧凑视图
}

enum class FilterMode {
    ALL,            // 全部
    READY,          // 就绪（有视频+叠加）
    CACHED,         // 已缓存
    NOT_READY       // 未就绪
}

@Composable
fun RecorderScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { RecorderRepository(context, sshManager) }

    var remotePath by remember { mutableStateOf(RecorderRepository.DEFAULT_REALDATA_PATH) }
    var pathInput by remember { mutableStateOf(remotePath) }
    var pathEditing by remember { mutableStateOf(false) }
    var segments by remember { mutableStateOf<List<SegmentSummary>>(emptyList()) }
    var selectedSegment by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf<com.sunnypilot.toolbox.model.RecorderOverlay?>(null) }
    var videoFile by remember { mutableStateOf<File?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("正在准备数据...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 新增：排序、查看、筛选模式
    var sortMode by remember { mutableStateOf(SortMode.TIME_DESC) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var filterMode by remember { mutableStateOf(FilterMode.ALL) }
    
    // 计算过滤和排序后的列表
    val displaySegments = remember(segments, sortMode, filterMode) {
        var filtered = when (filterMode) {
            FilterMode.ALL -> segments
            FilterMode.READY -> segments.filter { it.hasVideo && it.hasOverlay }
            FilterMode.CACHED -> segments.filter { it.cachedVideo || it.cachedOverlay }
            FilterMode.NOT_READY -> segments.filter { !it.hasVideo || !it.hasOverlay }
        }
        
        when (sortMode) {
            SortMode.TIME_DESC -> filtered.sortedByDescending { it.segmentId }
            SortMode.TIME_ASC -> filtered.sortedBy { it.segmentId }
            SortMode.NAME_ASC -> filtered.sortedBy { it.segmentId }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.segmentId }
            SortMode.READY_FIRST -> filtered.sortedByDescending { 
                (if (it.hasVideo && it.hasOverlay) 2 else 0) + 
                (if (it.cachedVideo || it.cachedOverlay) 1 else 0)
            }
            SortMode.CACHED_FIRST -> filtered.sortedByDescending { 
                (if (it.cachedVideo) 2 else 0) + (if (it.cachedOverlay) 1 else 0)
            }
        }
    }

    fun loadSegments() {
        scope.launch {
            isLoading = true
            errorMessage = null
            overlay = null
            videoFile = null
            repository.listSegments(remotePath).fold(
                onSuccess = { list ->
                    segments = list
                    if (selectedSegment == null || segments.none { it.segmentId == selectedSegment }) {
                        selectedSegment = segments.firstOrNull()?.segmentId
                    }
                },
                onFailure = { e ->
                    errorMessage = "读取目录失败: ${e.message}"
                    segments = emptyList()
                    selectedSegment = null
                }
            )
            isLoading = false
        }
    }

    fun applyPath() {
        remotePath = pathInput.trim().ifEmpty { RecorderRepository.DEFAULT_REALDATA_PATH }
        pathInput = remotePath
        pathEditing = false
        loadSegments()
    }

    LaunchedEffect(Unit) {
        loadSegments()
    }

    LaunchedEffect(selectedSegment, remotePath) {
        val segId = selectedSegment ?: return@LaunchedEffect
        isLoading = true
        errorMessage = null
        loadingMessage = "正在读取叠加数据..."
        overlay = repository.ensureOverlay(segId, remotePath).getOrElse {
            errorMessage = "读取 overlay 失败: ${it.message}"
            isLoading = false
            return@LaunchedEffect
        }
        loadingMessage = "正在下载视频..."
        videoFile = repository.ensureVideo(segId, remotePath).getOrElse {
            errorMessage = "下载视频失败: ${it.message}"
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        PreviewHeader(
            title = "记录仪预览",
            remotePath = pathInput,
            pathEditing = pathEditing,
            onPathChange = { pathInput = it },
            onEditPath = { pathEditing = true },
            onApplyPath = { applyPath() },
            onRefresh = { loadSegments() },
            onReplay = { selectedSegment?.let { selectedSegment = it } },
            sortMode = sortMode,
            onSortModeChange = { sortMode = it },
            viewMode = viewMode,
            onViewModeChange = { viewMode = it },
            filterMode = filterMode,
            onFilterModeChange = { filterMode = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        PlayingStatusBar(
            selectedSegment = selectedSegment,
            fileCount = segments.size,
            filteredCount = displaySegments.size
        )

        Spacer(modifier = Modifier.height(8.dp))

        CacheMessage(selectedSegment = selectedSegment, hasCache = selectedSegment?.let { repository.hasLocalCache(it) } ?: false)

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black, shape = RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
            ) {
                if (errorMessage != null) {
                    LoadingState("加载失败", errorMessage)
                } else if (overlay != null && videoFile != null) {
                    RecorderPlayer(
                        overlay = overlay!!,
                        videoFile = videoFile!!,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LoadingState(loadingMessage, null)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            VideoListPanel(
                segments = displaySegments,
                selected = selectedSegment,
                viewMode = viewMode,
                onSelect = { selectedSegment = it },
                onPreprocess = { segId ->
                    scope.launch {
                        isLoading = true
                        repository.preprocessSegment(segId, remotePath).fold(
                            onSuccess = {
                                Toast.makeText(context, "预处理完成", Toast.LENGTH_SHORT).show()
                                loadSegments()
                            },
                            onFailure = { e ->
                                Toast.makeText(context, "预处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                        isLoading = false
                    }
                },
                modifier = Modifier.width(280.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "行车记录仪预览",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = Slate500,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun PreviewHeader(
    title: String,
    remotePath: String,
    pathEditing: Boolean,
    onPathChange: (String) -> Unit,
    onEditPath: () -> Unit,
    onApplyPath: () -> Unit,
    onRefresh: () -> Unit,
    onReplay: () -> Unit,
    sortMode: SortMode,
    onSortModeChange: (SortMode) -> Unit,
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    filterMode: FilterMode,
    onFilterModeChange: (FilterMode) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Teal500,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 筛选按钮
                    Box {
                        IconButton(onClick = { showFilterMenu = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "筛选", tint = if (filterMode != FilterMode.ALL) Teal500 else Slate600)
                        }
                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部") },
                                onClick = { onFilterModeChange(FilterMode.ALL); showFilterMenu = false },
                                leadingIcon = { if (filterMode == FilterMode.ALL) Icon(Icons.Default.Check, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("就绪") },
                                onClick = { onFilterModeChange(FilterMode.READY); showFilterMenu = false },
                                leadingIcon = { if (filterMode == FilterMode.READY) Icon(Icons.Default.Check, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("已缓存") },
                                onClick = { onFilterModeChange(FilterMode.CACHED); showFilterMenu = false },
                                leadingIcon = { if (filterMode == FilterMode.CACHED) Icon(Icons.Default.Check, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("未就绪") },
                                onClick = { onFilterModeChange(FilterMode.NOT_READY); showFilterMenu = false },
                                leadingIcon = { if (filterMode == FilterMode.NOT_READY) Icon(Icons.Default.Check, null) }
                            )
                        }
                    }
                    
                    // 排序按钮
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "排序", tint = Slate600)
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("时间倒序") },
                                onClick = { onSortModeChange(SortMode.TIME_DESC); showSortMenu = false },
                                leadingIcon = { if (sortMode == SortMode.TIME_DESC) Icon(Icons.Default.Check, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("时间正序") },
                                onClick = { onSortModeChange(SortMode.TIME_ASC); showSortMenu = false },
                                leadingIcon = { if (sortMode == SortMode.TIME_ASC) Icon(Icons.Default.Check, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("名称升序") },
                                onClick = { onSortModeChange(SortMode.NAME_ASC); showSortMenu = false },
                                leadingIcon = { if (sortMode == SortMode.NAME_ASC) Icon(Icons.Default.Check, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("名称降序") },
                                onClick = { onSortModeChange(SortMode.NAME_DESC); showSortMenu = false },
                                leadingIcon = { if (sortMode == SortMode.NAME_DESC) Icon(Icons.Default.Check, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("就绪优先") },
                                onClick = { onSortModeChange(SortMode.READY_FIRST); showSortMenu = false },
                                leadingIcon = { if (sortMode == SortMode.READY_FIRST) Icon(Icons.Default.Check, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("已缓存优先") },
                                onClick = { onSortModeChange(SortMode.CACHED_FIRST); showSortMenu = false },
                                leadingIcon = { if (sortMode == SortMode.CACHED_FIRST) Icon(Icons.Default.Check, null) }
                            )
                        }
                    }
                    
                    // 查看方式按钮
                    Row {
                        IconButton(onClick = { onViewModeChange(ViewMode.LIST) }) {
                            Icon(
                                Icons.Default.ViewList, 
                                contentDescription = "列表",
                                tint = if (viewMode == ViewMode.LIST) Teal500 else Slate400
                            )
                        }
                        IconButton(onClick = { onViewModeChange(ViewMode.GRID) }) {
                            Icon(
                                Icons.Default.GridView, 
                                contentDescription = "网格",
                                tint = if (viewMode == ViewMode.GRID) Teal500 else Slate400
                            )
                        }
                        IconButton(onClick = { onViewModeChange(ViewMode.COMPACT) }) {
                            Icon(
                                Icons.Default.ViewAgenda, 
                                contentDescription = "紧凑",
                                tint = if (viewMode == ViewMode.COMPACT) Teal500 else Slate400
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    TextButton(
                        onClick = onEditPath,
                        enabled = !pathEditing
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("地址")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("刷新")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onReplay, colors = ButtonDefaults.buttonColors(containerColor = Teal500)) {
                        Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重播")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (pathEditing) {
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = onPathChange,
                    label = { Text("远端目录") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = onApplyPath) {
                            Icon(Icons.Default.Check, contentDescription = "确认")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEditPath)
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "远端目录: $remotePath",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate700
                    )
                    Text(
                        text = "点击可修改目录路径",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayingStatusBar(
    selectedSegment: String?,
    fileCount: Int,
    filteredCount: Int
) {
    val bg = Green100
    val fg = Color(0xFF166534)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (selectedSegment != null) "正在播放 $selectedSegment" else "未选择文件",
            color = fg,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (filteredCount < fileCount) {
            Text(
                text = "显示 $filteredCount / $fileCount 个",
                color = fg,
                fontWeight = FontWeight.Medium
            )
        } else {
            Text(
                text = "$fileCount 个文件",
                color = fg,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CacheMessage(
    selectedSegment: String?,
    hasCache: Boolean
) {
    val text = when {
        selectedSegment == null -> "请选择要播放的文件。"
        hasCache -> "$selectedSegment 已下载到缓存，可以直接播放。"
        else -> "$selectedSegment 尚未缓存，首次播放会自动从 C3 同步。"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Slate600
    )
}

@Composable
private fun VideoListPanel(
    segments: List<SegmentSummary>,
    selected: String?,
    viewMode: ViewMode,
    onSelect: (String) -> Unit,
    onPreprocess: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = modifier.fillMaxHeight()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Teal500,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "视频列表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate900
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${segments.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500
                )
            }

            Divider(color = Slate200, modifier = Modifier.padding(horizontal = 16.dp))

            if (segments.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            tint = Slate400,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("暂无匹配的视频", color = Slate500)
                    }
                }
            } else {
                when (viewMode) {
                    ViewMode.LIST -> VideoListView(segments, selected, onSelect, onPreprocess)
                    ViewMode.GRID -> VideoGridView(segments, selected, onSelect, onPreprocess)
                    ViewMode.COMPACT -> VideoCompactView(segments, selected, onSelect, onPreprocess)
                }
            }
        }
    }
}

@Composable
private fun VideoListView(
    segments: List<SegmentSummary>,
    selected: String?,
    onSelect: (String) -> Unit,
    onPreprocess: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.forEach { seg ->
            VideoListItem(
                segment = seg,
                selected = selected == seg.segmentId,
                onClick = { onSelect(seg.segmentId) },
                onPreprocess = { onPreprocess(seg.segmentId) }
            )
        }
    }
}

@Composable
private fun VideoGridView(
    segments: List<SegmentSummary>,
    selected: String?,
    onSelect: (String) -> Unit,
    onPreprocess: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(scrollState)
            .padding(12.dp)
    ) {
        segments.chunked(2).forEach { rowSegments ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowSegments.forEach { seg ->
                    VideoGridItem(
                        segment = seg,
                        selected = selected == seg.segmentId,
                        onClick = { onSelect(seg.segmentId) },
                        onPreprocess = { onPreprocess(seg.segmentId) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 填充空白
                if (rowSegments.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun VideoCompactView(
    segments: List<SegmentSummary>,
    selected: String?,
    onSelect: (String) -> Unit,
    onPreprocess: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        segments.forEach { seg ->
            VideoCompactItem(
                segment = seg,
                selected = selected == seg.segmentId,
                onClick = { onSelect(seg.segmentId) },
                onPreprocess = { onPreprocess(seg.segmentId) }
            )
        }
    }
}

@Composable
private fun VideoGridItem(
    segment: SegmentSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onPreprocess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ready = segment.hasOverlay && segment.hasVideo
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Teal50 else Slate50
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (ready) Icons.Default.VideoFile else Icons.Outlined.VideoFile,
                contentDescription = null,
                tint = if (ready) Teal500 else Slate400,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = segment.segmentId.takeLast(12),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Slate900,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                StatusChip("视频", segment.hasVideo || segment.cachedVideo)
                Spacer(modifier = Modifier.width(4.dp))
                StatusChip("数据", segment.hasOverlay || segment.cachedOverlay)
            }
            if (!segment.hasOverlay && !segment.cachedOverlay) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = onPreprocess,
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("预处理", style = MaterialTheme.typography.labelSmall, color = Teal500)
                }
            }
        }
    }
}

@Composable
private fun VideoCompactItem(
    segment: SegmentSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onPreprocess: () -> Unit
) {
    val ready = segment.hasOverlay && segment.hasVideo
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) Teal50 else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (ready) Teal500 else Slate400,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = segment.segmentId,
            style = MaterialTheme.typography.bodySmall,
            color = Slate900,
            modifier = Modifier.weight(1f)
        )
        if (segment.cachedVideo || segment.cachedOverlay) {
            Icon(
                Icons.Default.Download,
                contentDescription = "已缓存",
                tint = Teal500,
                modifier = Modifier.size(16.dp)
            )
        }
        if (!segment.hasOverlay && !segment.cachedOverlay) {
            TextButton(onClick = onPreprocess) {
                Text("预处理", style = MaterialTheme.typography.labelSmall, color = Teal500)
            }
        }
    }
}

@Composable
private fun VideoListItem(
    segment: SegmentSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onPreprocess: () -> Unit
) {
    val ready = segment.hasOverlay && segment.hasVideo
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Teal50 else Slate50
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (ready) Icons.Default.VideoFile else Icons.Outlined.VideoFile,
                    contentDescription = null,
                    tint = if (ready) Teal500 else Slate400,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = segment.segmentId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row {
                        StatusChip("视频", segment.hasVideo || segment.cachedVideo)
                        Spacer(modifier = Modifier.width(4.dp))
                        StatusChip("叠加数据", segment.hasOverlay || segment.cachedOverlay)
                    }
                }
            }
            if (!segment.hasOverlay && !segment.cachedOverlay) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = onPreprocess,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("预处理", color = Teal500)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    val bg = if (ok) Teal100 else Slate200
    val fg = if (ok) Teal700 else Slate500
    Text(
        text = "$label ${if (ok) "✓" else "×"}",
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}
