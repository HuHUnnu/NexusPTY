package com.chaoxing.eduapp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaoxing.eduapp.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen() } }
    }
}

private enum class SortMode(val label: String) {
    SIZE_DESC("大小 ↓"),
    SIZE_ASC("大小 ↑"),
    NAME_ASC("名称 A→Z"),
    NAME_DESC("名称 Z→A")
}

// ═══════════════════════════════════════════════════════════════════
//  Main Screen
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val vm: AppViewModel = viewModel()
    val engine = vm.engine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    val scripts by engine.scripts.collectAsState()
    val selected by engine.selected.collectAsState()
    val engineState by engine.state.collectAsState()
    val pid by engine.pid.collectAsState()

    var logLines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    val logListState = rememberLazyListState()
    var consoleExpanded by remember { mutableStateOf(false) }
    var scriptToDelete by remember { mutableStateOf<ScriptInfo?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.SIZE_DESC) }
    var isRefreshing by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("edu_cfg", Context.MODE_PRIVATE) }
    var fontSize by remember { mutableIntStateOf(prefs.getInt("font_size", 11)) }
    var autoScroll by remember { mutableStateOf(prefs.getBoolean("auto_scroll", true)) }
    var hapticEnabled by remember { mutableStateOf(prefs.getBoolean("haptic", true)) }
    var suPath by remember { mutableStateOf(prefs.getString("su_path", "su") ?: "su") }

    LaunchedEffect(suPath) { engine.suPath = suPath }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.let {
                if (it.endsWith(".sh")) it else "$it.sh"
            } ?: "imported_${System.currentTimeMillis()}.sh"
            val tmpFile = java.io.File(context.cacheDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            engine.importScript(name, tmpFile.absolutePath)
        } catch (_: Exception) {}
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val text = logLines.joinToString("\n") { line ->
                val prefix = when (line) {
                    is LogLine.Stdout -> "OUT"
                    is LogLine.Stderr -> "ERR"
                    is LogLine.Info -> "INF"
                    is LogLine.Stdin -> "IN "
                }
                "[${sdf.format(Date(line.timestamp))}] $prefix  ${line.text}"
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            scope.launch { snackbarHostState.showSnackbar("日志已导出") }
        } catch (_: Exception) {
            scope.launch { snackbarHostState.showSnackbar("导出失败") }
        }
    }

    LaunchedEffect(Unit) { engine.loadScripts() }
    LaunchedEffect(Unit) {
        engine.output.collect { line ->
            logLines = (logLines + line).let { if (it.size > 600) it.takeLast(500) else it }
        }
    }
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty() && autoScroll) logListState.animateScrollToItem(logLines.lastIndex)
    }

    val filteredScripts = remember(scripts, searchQuery, sortMode) {
        val base = if (searchQuery.isBlank()) scripts
        else scripts.filter { it.name.contains(searchQuery, ignoreCase = true) }
        when (sortMode) {
            SortMode.NAME_ASC -> base.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> base.sortedByDescending { it.name.lowercase() }
            SortMode.SIZE_ASC -> base.sortedBy { it.sizeBytes }
            SortMode.SIZE_DESC -> base.sortedByDescending { it.sizeBytes }
        }
    }

    val isRunning = engineState == EngineState.RUNNING
    val isStopping = engineState == EngineState.STOPPING

    // ── Delete confirmation ──
    scriptToDelete?.let { script ->
        AlertDialog(
            onDismissRequest = { scriptToDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${script.name}」吗？\n此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    engine.deleteScript(script)
                    scriptToDelete = null
                    scope.launch { snackbarHostState.showSnackbar("已删除 ${script.name}") }
                }) { Text("删除", color = Red) }
            },
            dismissButton = { TextButton(onClick = { scriptToDelete = null }) { Text("取消") } },
            containerColor = SurfaceElevated,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    // ── Settings bottom sheet ──
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = SurfaceElevated,
            contentColor = TextPrimary,
            dragHandle = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.width(36.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Border)
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        ) {
            SettingsContent(
                fontSize = fontSize,
                autoScroll = autoScroll,
                hapticEnabled = hapticEnabled,
                suPath = suPath,
                onFontSizeChange = { fontSize = it; prefs.edit().putInt("font_size", it).apply() },
                onAutoScrollChange = { autoScroll = it; prefs.edit().putBoolean("auto_scroll", it).apply() },
                onHapticChange = { hapticEnabled = it; prefs.edit().putBoolean("haptic", it).apply() },
                onSuPathChange = { suPath = it; prefs.edit().putString("su_path", it).apply() }
            )
        }
    }

    Scaffold(
        containerColor = SurfaceDark,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SurfaceElevated,
                    contentColor = TextPrimary,
                    actionColor = Cyan,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .systemBarsPadding()
        ) {
            AppHeader(
                scriptCount = scripts.size,
                engineState = engineState,
                pid = pid,
                onRefresh = {
                    if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    engine.refreshScripts()
                },
                onImport = { filePicker.launch("*/*") },
                onSettings = { showSettings = true }
            )

            if (!consoleExpanded) {
                ScriptPanel(
                    scripts = filteredScripts,
                    totalCount = scripts.size,
                    selected = selected,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    onSelect = { engine.selectScript(it) },
                    onDelete = { scriptToDelete = it },
                    sortMode = sortMode,
                    onSortChange = { sortMode = it },
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        engine.refreshScripts()
                        scope.launch { delay(1200); isRefreshing = false }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            ConsolePanel(
                logLines = logLines,
                listState = logListState,
                expanded = consoleExpanded,
                isRunning = isRunning,
                fontSize = fontSize,
                onToggleExpand = { consoleExpanded = !consoleExpanded },
                onClear = { logLines = emptyList(); engine.clearLog() },
                onSendInput = { engine.sendInput(it) },
                onSendSignal = { engine.sendSignal(it) },
                onCopyLine = { text ->
                    clipboardManager.setText(AnnotatedString(text))
                    scope.launch { snackbarHostState.showSnackbar("已复制到剪贴板") }
                },
                onExport = {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    exportLauncher.launch("log_$ts.txt")
                },
                hapticEnabled = hapticEnabled,
                modifier = if (consoleExpanded) Modifier.weight(1f) else Modifier.height(300.dp)
            )

            ControlBar(
                canExecute = selected != null && !isRunning && !isStopping,
                canStop = isRunning,
                isStopping = isStopping,
                selectedName = selected?.name,
                onExecute = {
                    if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    selected?.let { engine.execute(it) }
                },
                onStop = {
                    if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    engine.stop()
                }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  App Header
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun AppHeader(
    scriptCount: Int, engineState: EngineState, pid: Int?,
    onRefresh: () -> Unit, onImport: () -> Unit, onSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Cyan.copy(alpha = .06f), Color.Transparent)))
            .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF4A90D9), Color(0xFF67B8DE)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("学习通", style = MaterialTheme.typography.headlineMedium)
                Text("智慧学习平台", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            IconButton(onClick = onImport) { Icon(Icons.Filled.Add, null, tint = Cyan, modifier = Modifier.size(22.dp)) }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, null, tint = TextSecondary, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, null, tint = TextSecondary, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipStatus("$scriptCount 个脚本", Icons.Outlined.Folder, Purple)
            ChipStatus(
                when (engineState) { EngineState.IDLE -> "空闲"; EngineState.RUNNING -> "运行中"; EngineState.STOPPING -> "终止中" },
                when (engineState) { EngineState.IDLE -> Icons.Outlined.RadioButtonUnchecked; EngineState.RUNNING -> Icons.Filled.PlayCircle; EngineState.STOPPING -> Icons.Filled.HourglassBottom },
                when (engineState) { EngineState.IDLE -> TextSecondary; EngineState.RUNNING -> Green; EngineState.STOPPING -> Amber },
                pulse = engineState == EngineState.RUNNING
            )
            if (pid != null) ChipStatus("PID $pid", Icons.Outlined.Memory, Amber)
        }
    }
    HorizontalDivider(color = Border.copy(alpha = .5f), thickness = 0.5.dp)
}

@Composable
private fun ChipStatus(label: String, icon: ImageVector, color: Color, pulse: Boolean = false) {
    val alpha by if (pulse) {
        rememberInfiniteTransition(label = "p").animateFloat(1f, .5f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    } else remember { mutableFloatStateOf(1f) }
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = .08f), border = BorderStroke(0.5.dp, color.copy(alpha = .2f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp).alpha(alpha), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Script Panel (with search, sort, pull-to-refresh)
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptPanel(
    scripts: List<ScriptInfo>, totalCount: Int, selected: ScriptInfo?,
    searchQuery: String, onSearchChange: (String) -> Unit,
    onSelect: (ScriptInfo) -> Unit, onDelete: (ScriptInfo) -> Unit,
    sortMode: SortMode, onSortChange: (SortMode) -> Unit,
    isRefreshing: Boolean, onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // ── Header with sort ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("脚本列表", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已加载 $totalCount", style = MaterialTheme.typography.labelSmall, color = TextDim)
                Spacer(Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Sort, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        containerColor = SurfaceElevated
                    ) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (mode == sortMode) Icon(Icons.Filled.Check, null, tint = Cyan, modifier = Modifier.size(16.dp))
                                        else Spacer(Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(mode.label, color = if (mode == sortMode) Cyan else TextPrimary, fontSize = 13.sp)
                                    }
                                },
                                onClick = { onSortChange(mode); showSortMenu = false }
                            )
                        }
                    }
                }
            }
        }

        // ── Search bar ──
        OutlinedTextField(
            value = searchQuery, onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp).heightIn(min = 40.dp),
            placeholder = { Text("搜索脚本...", fontSize = 13.sp, color = TextDim) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextDim, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Clear, null, tint = TextDim, modifier = Modifier.size(16.dp))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan.copy(alpha = .4f), unfocusedBorderColor = Border.copy(alpha = .3f),
                cursorColor = Cyan, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
            ),
            shape = RoundedCornerShape(10.dp)
        )

        // ── Pull-to-refresh list ──
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
        ) {
            if (scripts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Outlined.FolderOff, null, tint = TextDim, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("暂无脚本", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点击 + 导入脚本\n或将 .sh 文件放入 ${ShellEngine.SCRIPT_DIR}",
                        style = MaterialTheme.typography.labelSmall, color = TextDim,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(scripts, key = { it.path }) { script ->
                        ScriptItem(script, script == selected, { onSelect(script) }, { onDelete(script) })
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ScriptItem(script: ScriptInfo, isSelected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val borderColor by animateColorAsState(if (isSelected) Cyan.copy(alpha = .5f) else Border.copy(alpha = .4f), label = "b")
    val bgColor by animateColorAsState(if (isSelected) Cyan.copy(alpha = .06f) else SurfaceCard, label = "bg")

    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = bgColor, border = BorderStroke(1.dp, borderColor)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Cyan.copy(alpha = .12f) else SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Terminal, null, tint = if (isSelected) Cyan else TextSecondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(script.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(script.sizeText, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .border(if (isSelected) 0.dp else 1.5.dp, if (isSelected) Color.Transparent else Border, CircleShape)
                    .background(if (isSelected) Cyan else Color.Transparent),
                contentAlignment = Alignment.Center
            ) { if (isSelected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
            Surface(onClick = onDelete, modifier = Modifier.size(36.dp), shape = CircleShape, color = Red.copy(alpha = .08f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, null, tint = Red.copy(alpha = .7f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Console Panel
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsolePanel(
    logLines: List<LogLine>, listState: androidx.compose.foundation.lazy.LazyListState,
    expanded: Boolean, isRunning: Boolean, fontSize: Int,
    onToggleExpand: () -> Unit, onClear: () -> Unit,
    onSendInput: (String) -> Unit, onSendSignal: (Int) -> Unit,
    onCopyLine: (String) -> Unit, onExport: () -> Unit,
    hapticEnabled: Boolean, modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val showScrollBtn by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            logLines.size > 0 && logLines.lastIndex - last > 3
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp), color = Color(0xFF08080E),
        border = BorderStroke(1.dp, Border.copy(alpha = .4f))
    ) {
        Column {
            // ── Title bar ──
            Row(
                Modifier.fillMaxWidth().clickable { onToggleExpand() }
                    .background(SurfaceElevated.copy(alpha = .7f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Red))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Amber))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Green))
                }
                Text("控制台", Modifier.weight(1f).padding(horizontal = 12.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = TextDim, fontFamily = FontFamily.Monospace)
                Text("${logLines.size}", fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 4.dp))
                IconButton(onClick = onExport, Modifier.size(24.dp)) { Icon(Icons.Outlined.SaveAlt, null, tint = TextDim, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = onClear, Modifier.size(24.dp)) { Icon(Icons.Outlined.DeleteSweep, null, tint = TextDim, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = onToggleExpand, Modifier.size(24.dp)) {
                    Icon(if (expanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore, null, tint = TextDim, modifier = Modifier.size(15.dp))
                }
            }
            HorizontalDivider(color = Border.copy(alpha = .3f), thickness = 0.5.dp)

            // ── Log body ──
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (logLines.isEmpty()) {
                    val cursorAlpha by rememberInfiniteTransition(label = "cur").animateFloat(
                        1f, 0f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "ca"
                    )
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(">_", fontSize = 24.sp, fontFamily = FontFamily.Monospace,
                            color = Cyan.copy(alpha = cursorAlpha * .4f), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("等待执行", style = MaterialTheme.typography.labelSmall, color = TextDim.copy(alpha = .5f))
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
                        items(logLines.size) { idx ->
                            val line = logLines[idx]
                            val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                            val ts = sdf.format(Date(line.timestamp))
                            val color = when (line) { is LogLine.Stdout -> CyanBright; is LogLine.Stderr -> Red; is LogLine.Info -> Blue; is LogLine.Stdin -> Amber }
                            val prefix = when (line) { is LogLine.Stdout -> " "; is LogLine.Stderr -> "!"; is LogLine.Info -> "i"; is LogLine.Stdin -> ">" }
                            Row(Modifier.padding(vertical = 0.5.dp).combinedClickable(onClick = {}, onLongClick = { onCopyLine(line.text) })) {
                                Text(ts, fontSize = (fontSize - 2).sp, fontFamily = FontFamily.Monospace, color = TextDim.copy(alpha = .5f), modifier = Modifier.padding(end = 6.dp))
                                Text(prefix, fontSize = (fontSize - 1).sp, fontFamily = FontFamily.Monospace, color = color.copy(alpha = .5f), modifier = Modifier.width(10.dp))
                                Text(line.text, fontSize = fontSize.sp, fontFamily = FontFamily.Monospace, color = color, lineHeight = (fontSize + 4).sp)
                            }
                        }
                    }
                }

                // ── Scroll-to-bottom FAB ──
                if (showScrollBtn) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.lastIndex) } },
                        containerColor = Cyan.copy(alpha = .85f), contentColor = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(32.dp)
                    ) { Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(18.dp)) }
                }
            }

            // ── Interactive input ──
            AnimatedVisibility(visible = isRunning) {
                Column(Modifier.fillMaxWidth().background(SurfaceElevated.copy(alpha = .5f))) {
                    HorizontalDivider(color = Border.copy(alpha = .2f), thickness = 0.5.dp)

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        data class QK(val label: String, val display: String = label, val accent: Color? = null)
                        val keys = listOf(
                            QK("1"), QK("2"), QK("3"), QK("4"), QK("5"),
                            QK("6"), QK("7"), QK("8"), QK("9"), QK("0"),
                            QK("y"), QK("n"),
                            QK("Tab", "⇥"), QK("Ctrl+C", "^C", Red),
                            QK("Enter", "⏎", Cyan)
                        )
                        keys.forEach { qk ->
                            val accent = qk.accent
                            Surface(
                                onClick = {
                                    if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    when (qk.label) {
                                        "Enter" -> onSendInput("")
                                        "Tab" -> onSendInput("\t")
                                        "Ctrl+C" -> onSendSignal(2)
                                        else -> onSendInput(qk.label)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = (accent ?: SurfaceCard).let { if (accent != null) it.copy(alpha = .12f) else it },
                                border = BorderStroke(0.5.dp, (accent ?: Border).copy(alpha = if (accent != null) .3f else .4f)),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text(qk.display, color = accent ?: TextPrimary, fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("❯", color = Cyan, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        OutlinedTextField(
                            value = inputText, onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            placeholder = { Text("输入命令...", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = TextDim) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = TextPrimary),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (inputText.isNotBlank()) { onSendInput(inputText.trim()); inputText = "" } }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan.copy(alpha = .5f), unfocusedBorderColor = Border.copy(alpha = .3f),
                                cursorColor = Cyan, focusedContainerColor = Color(0xFF0A0A14), unfocusedContainerColor = Color(0xFF0A0A14)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            onClick = {
                                if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (inputText.isNotBlank()) { onSendInput(inputText.trim()); inputText = "" }
                            },
                            modifier = Modifier.size(42.dp), shape = RoundedCornerShape(10.dp),
                            color = Cyan.copy(alpha = .15f), border = BorderStroke(0.5.dp, Cyan.copy(alpha = .3f))
                        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = Cyan, modifier = Modifier.size(20.dp)) } }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Control Bar (gradient buttons)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ControlBar(
    canExecute: Boolean, canStop: Boolean, isStopping: Boolean,
    selectedName: String?, onExecute: () -> Unit, onStop: () -> Unit
) {
    Surface(Modifier.fillMaxWidth(), color = SurfaceCard, shadowElevation = 8.dp) {
        Column(
            Modifier.fillMaxWidth()
                .drawBehind { drawLine(Border, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1f) }
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding()
        ) {
            if (selectedName != null) {
                Text("已选择: $selectedName", style = MaterialTheme.typography.labelSmall, color = TextDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // ── Execute (gradient) ──
                Surface(
                    onClick = onExecute, enabled = canExecute,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp), color = Color.Transparent
                ) {
                    Box(
                        Modifier.fillMaxSize().background(
                            brush = if (canExecute) Brush.horizontalGradient(listOf(Cyan, CyanBright))
                            else SolidColor(Cyan.copy(alpha = .15f)),
                            shape = RoundedCornerShape(12.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val tint = if (canExecute) Color(0xFF002818) else TextDim
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp), tint = tint)
                            Spacer(Modifier.width(6.dp))
                            Text("执行", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = tint)
                        }
                    }
                }
                // ── Stop (gradient) ──
                Surface(
                    onClick = onStop, enabled = canStop,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp), color = Color.Transparent,
                    border = BorderStroke(1.dp, if (canStop) Coral.copy(alpha = .5f) else Border.copy(alpha = .2f))
                ) {
                    Box(
                        Modifier.fillMaxSize().background(
                            brush = if (canStop) Brush.horizontalGradient(listOf(Coral.copy(alpha = .12f), Color(0xFFFF8A80).copy(alpha = .08f)))
                            else SolidColor(Color.Transparent),
                            shape = RoundedCornerShape(12.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val tint = if (canStop) Coral else TextDim
                            if (isStopping) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Amber)
                            else Icon(Icons.Filled.Stop, null, Modifier.size(20.dp), tint = tint)
                            Spacer(Modifier.width(6.dp))
                            Text(if (isStopping) "终止中..." else "终止", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = tint)
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Settings Bottom Sheet
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SettingsContent(
    fontSize: Int, autoScroll: Boolean, hapticEnabled: Boolean, suPath: String,
    onFontSizeChange: (Int) -> Unit, onAutoScrollChange: (Boolean) -> Unit,
    onHapticChange: (Boolean) -> Unit, onSuPathChange: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        Column {
            Text("SU 路径", color = TextPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text("SKRoot 等非标准 root 需要指定自定义 su 二进制路径", color = TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = suPath, onValueChange = onSuPathChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                placeholder = { Text("su", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = TextDim) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = TextPrimary),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Terminal, null, tint = Cyan, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (suPath != "su") {
                        IconButton(onClick = { onSuPathChange("su") }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.RestartAlt, null, tint = TextDim, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cyan.copy(alpha = .4f), unfocusedBorderColor = Border.copy(alpha = .3f),
                    cursorColor = Cyan, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                ),
                shape = RoundedCornerShape(10.dp)
            )
        }

        HorizontalDivider(color = Border.copy(alpha = .3f))

        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("控制台字号", color = TextPrimary, fontSize = 14.sp)
                Text("${fontSize}sp", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = fontSize.toFloat(), onValueChange = { onFontSizeChange(it.toInt()) },
                valueRange = 9f..16f, steps = 6,
                colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Border)
            )
        }

        HorizontalDivider(color = Border.copy(alpha = .3f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("自动滚动", color = TextPrimary, fontSize = 14.sp); Text("新输出时自动滚到底部", color = TextDim, fontSize = 11.sp) }
            Switch(checked = autoScroll, onCheckedChange = onAutoScrollChange, colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
        }

        HorizontalDivider(color = Border.copy(alpha = .3f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("触觉反馈", color = TextPrimary, fontSize = 14.sp); Text("按键时振动", color = TextDim, fontSize = 11.sp) }
            Switch(checked = hapticEnabled, onCheckedChange = onHapticChange, colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
        }

        HorizontalDivider(color = Border.copy(alpha = .3f))

        Column {
            Text("脚本存放目录", color = TextPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = SurfaceCard, border = BorderStroke(0.5.dp, Border.copy(alpha = .3f))) {
                Text(ShellEngine.SCRIPT_DIR, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
            }
        }

        Text("v3.2.0 · com.chaoxing.eduapp", fontSize = 10.sp, color = TextDim,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
