package com.chaoxing.eduapp

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaoxing.eduapp.ui.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexusTheme {
                NexusApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusApp() {
    val engine = remember { ShellEngine() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val scripts by engine.scripts.collectAsState()
    val selected by engine.selected.collectAsState()
    val engineState by engine.state.collectAsState()
    val pid by engine.pid.collectAsState()

    var logLines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    val logListState = rememberLazyListState()
    var consoleExpanded by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val cr = context.contentResolver
            val name = uri.lastPathSegment?.substringAfterLast('/')?.let {
                if (it.endsWith(".sh")) it else "$it.sh"
            } ?: "imported_${System.currentTimeMillis()}.sh"

            val tmpFile = java.io.File(context.cacheDir, name)
            cr.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            engine.importScript(name, tmpFile.absolutePath)
        } catch (e: Exception) {
            scope.launch {
                engine.output.let { /* swallow */ }
            }
        }
    }

    LaunchedEffect(Unit) {
        engine.loadScripts()
    }

    LaunchedEffect(Unit) {
        engine.output.collect { line ->
            logLines = (logLines + line).let {
                if (it.size > 600) it.takeLast(500) else it
            }
        }
    }

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            logListState.animateScrollToItem(logLines.lastIndex)
        }
    }

    val isRunning = engineState == EngineState.RUNNING
    val isStopping = engineState == EngineState.STOPPING

    Scaffold(
        containerColor = SurfaceDark,
        contentWindowInsets = WindowInsets(0)
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
                onRefresh = { engine.refreshScripts() },
                onImport = { filePicker.launch("*/*") }
            )

            if (!consoleExpanded) {
                ScriptPanel(
                    scripts = scripts,
                    selected = selected,
                    onSelect = { engine.selectScript(it) },
                    onDelete = { engine.deleteScript(it) },
                    modifier = Modifier.weight(1f)
                )
            }

            ConsolePanel(
                logLines = logLines,
                listState = logListState,
                expanded = consoleExpanded,
                isRunning = isRunning,
                onToggleExpand = { consoleExpanded = !consoleExpanded },
                onClear = { logLines = emptyList(); engine.clearLog() },
                onSendInput = { engine.sendInput(it) },
                modifier = if (consoleExpanded) Modifier.weight(1f) else Modifier.height(300.dp)
            )

            ControlBar(
                canExecute = selected != null && !isRunning && !isStopping,
                canStop = isRunning,
                isStopping = isStopping,
                selectedName = selected?.name,
                onExecute = { selected?.let { engine.execute(it) } },
                onStop = { engine.stop() }
            )
        }
    }
}

@Composable
private fun AppHeader(
    scriptCount: Int,
    engineState: EngineState,
    pid: Int?,
    onRefresh: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Cyan.copy(alpha = .06f), Color.Transparent)
                )
            )
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF4A90D9), Color(0xFF67B8DE)),
                            start = Offset.Zero,
                            end = Offset(36f, 36f)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text("学习通", style = MaterialTheme.typography.headlineMedium)
                Text("智慧学习平台", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }

            IconButton(onClick = onImport) {
                Icon(Icons.Filled.Add, contentDescription = "Import", tint = Cyan, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChipStatus("$scriptCount scripts", Icons.Outlined.Folder, Purple)
            ChipStatus(
                label = when (engineState) {
                    EngineState.IDLE -> "Idle"
                    EngineState.RUNNING -> "Running"
                    EngineState.STOPPING -> "Stopping"
                },
                icon = when (engineState) {
                    EngineState.IDLE -> Icons.Outlined.RadioButtonUnchecked
                    EngineState.RUNNING -> Icons.Filled.PlayCircle
                    EngineState.STOPPING -> Icons.Filled.HourglassBottom
                },
                color = when (engineState) {
                    EngineState.IDLE -> TextSecondary
                    EngineState.RUNNING -> Green
                    EngineState.STOPPING -> Amber
                },
                pulse = engineState == EngineState.RUNNING
            )
            if (pid != null) {
                ChipStatus("PID $pid", Icons.Outlined.Memory, Amber)
            }
        }
    }

    HorizontalDivider(color = Border.copy(alpha = .5f), thickness = 0.5.dp)
}

@Composable
private fun ChipStatus(
    label: String,
    icon: ImageVector,
    color: Color,
    pulse: Boolean = false
) {
    val alpha by if (pulse) {
        val inf = rememberInfiniteTransition(label = "pulse")
        inf.animateFloat(
            initialValue = 1f, targetValue = .5f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "pulseAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = .08f),
        border = BorderStroke(0.5.dp, color.copy(alpha = .2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp).alpha(alpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ScriptPanel(
    scripts: List<ScriptInfo>,
    selected: ScriptInfo?,
    onSelect: (ScriptInfo) -> Unit,
    onDelete: (ScriptInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SCRIPTS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text("${scripts.size} loaded", style = MaterialTheme.typography.labelSmall, color = TextDim)
        }

        if (scripts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.FolderOff, null, tint = TextDim, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No scripts found", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap + to import or place .sh in ${ShellEngine.SCRIPT_DIR}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(scripts, key = { it.path }) { script ->
                    ScriptItem(
                        script = script,
                        isSelected = script == selected,
                        onClick = { onSelect(script) },
                        onDelete = { onDelete(script) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ScriptItem(
    script: ScriptInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor by animateColorAsState(
        if (isSelected) Cyan.copy(alpha = .5f) else Border.copy(alpha = .4f), label = "border"
    )
    val bgColor by animateColorAsState(
        if (isSelected) Cyan.copy(alpha = .06f) else SurfaceCard, label = "bg"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Cyan.copy(alpha = .12f) else SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Terminal, null,
                    tint = if (isSelected) Cyan else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    script.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    script.sizeText,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (isSelected) 0.dp else 1.5.dp,
                        color = if (isSelected) Color.Transparent else Border,
                        shape = CircleShape
                    )
                    .background(if (isSelected) Cyan else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }

            Surface(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = Red.copy(alpha = .08f)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Outlined.Close, null,
                        tint = Red.copy(alpha = .7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsolePanel(
    logLines: List<LogLine>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    expanded: Boolean,
    isRunning: Boolean,
    onToggleExpand: () -> Unit,
    onClear: () -> Unit,
    onSendInput: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF08080E),
        border = BorderStroke(1.dp, Border.copy(alpha = .4f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .background(SurfaceElevated.copy(alpha = .7f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Red))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Amber))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Green))
                }

                Text(
                    "console",
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    "${logLines.size}",
                    fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 8.dp)
                )

                IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.DeleteSweep, null, tint = TextDim, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                        null, tint = TextDim, modifier = Modifier.size(15.dp)
                    )
                }
            }

            HorizontalDivider(color = Border.copy(alpha = .3f), thickness = 0.5.dp)

            if (logLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            ">_", fontSize = 24.sp, fontFamily = FontFamily.Monospace,
                            color = TextDim.copy(alpha = .3f), fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "waiting for execution",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextDim.copy(alpha = .5f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    items(logLines.size) { idx ->
                        val line = logLines[idx]
                        val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        val ts = sdf.format(Date(line.timestamp))
                        val color = when (line) {
                            is LogLine.Stdout -> CyanBright
                            is LogLine.Stderr -> Red
                            is LogLine.Info -> Blue
                            is LogLine.Stdin -> Amber
                        }
                        val prefix = when (line) {
                            is LogLine.Stdout -> " "
                            is LogLine.Stderr -> "!"
                            is LogLine.Info -> "i"
                            is LogLine.Stdin -> ">"
                        }
                        Row(modifier = Modifier.padding(vertical = 0.5.dp)) {
                            Text(
                                ts, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                color = TextDim.copy(alpha = .5f),
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Text(
                                prefix, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                color = color.copy(alpha = .5f),
                                modifier = Modifier.width(10.dp)
                            )
                            Text(
                                line.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = color, lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isRunning) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceElevated.copy(alpha = .5f))
                ) {
                    HorizontalDivider(color = Border.copy(alpha = .2f), thickness = 0.5.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val quickKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "y", "n", "Enter")
                        quickKeys.forEach { key ->
                            Surface(
                                onClick = {
                                    onSendInput(if (key == "Enter") "" else key)
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (key == "Enter") Cyan.copy(alpha = .15f) else SurfaceCard,
                                border = BorderStroke(
                                    0.5.dp,
                                    if (key == "Enter") Cyan.copy(alpha = .3f) else Border.copy(alpha = .4f)
                                ),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        key,
                                        color = if (key == "Enter") Cyan else TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "❯",
                            color = Cyan,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            placeholder = {
                                Text(
                                    "type input...",
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextDim
                                )
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (inputText.isNotBlank()) {
                                        onSendInput(inputText.trim())
                                        inputText = ""
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan.copy(alpha = .5f),
                                unfocusedBorderColor = Border.copy(alpha = .3f),
                                cursorColor = Cyan,
                                focusedContainerColor = Color(0xFF0A0A14),
                                unfocusedContainerColor = Color(0xFF0A0A14)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(Modifier.width(8.dp))

                        Surface(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    onSendInput(inputText.trim())
                                    inputText = ""
                                }
                            },
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = Cyan.copy(alpha = .15f),
                            border = BorderStroke(0.5.dp, Cyan.copy(alpha = .3f))
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Filled.Send, null, tint = Cyan, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlBar(
    canExecute: Boolean,
    canStop: Boolean,
    isStopping: Boolean,
    selectedName: String?,
    onExecute: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceCard,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(Border, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1f)
                }
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding()
        ) {
            if (selectedName != null) {
                Text(
                    "Selected: $selectedName",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onExecute,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = canExecute,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cyan,
                        contentColor = Color(0xFF002818),
                        disabledContainerColor = Cyan.copy(alpha = .2f),
                        disabledContentColor = TextDim
                    )
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Execute", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = canStop,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (canStop) Coral.copy(alpha = .6f) else Border.copy(alpha = .3f)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Coral,
                        disabledContentColor = TextDim
                    )
                ) {
                    if (isStopping) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Amber
                        )
                    } else {
                        Icon(Icons.Filled.Stop, null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isStopping) "Stopping..." else "Terminate",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                }
            }
        }
    }
}
