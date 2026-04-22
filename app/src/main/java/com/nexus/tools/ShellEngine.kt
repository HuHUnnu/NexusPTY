package com.nexus.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class ScriptInfo(val name: String, val path: String, val sizeBytes: Long) {
    val sizeText: String
        get() = when {
            sizeBytes > 1_048_576 -> "%.1f MB".format(sizeBytes / 1_048_576.0)
            sizeBytes > 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
}

sealed class LogLine {
    abstract val text: String
    abstract val timestamp: Long

    data class Stdout(override val text: String, override val timestamp: Long = System.currentTimeMillis()) : LogLine()
    data class Stderr(override val text: String, override val timestamp: Long = System.currentTimeMillis()) : LogLine()
    data class Info(override val text: String, override val timestamp: Long = System.currentTimeMillis()) : LogLine()
    data class Stdin(override val text: String, override val timestamp: Long = System.currentTimeMillis()) : LogLine()
}

enum class EngineState { IDLE, RUNNING, STOPPING }

class ShellEngine {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var masterFd: Int = -1
    private var childPid: Int = -1

    private val _output = MutableSharedFlow<LogLine>(replay = 200, extraBufferCapacity = 500)
    val output: SharedFlow<LogLine> = _output

    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _pid = MutableStateFlow<Int?>(null)
    val pid: StateFlow<Int?> = _pid.asStateFlow()

    private val _scripts = MutableStateFlow<List<ScriptInfo>>(emptyList())
    val scripts: StateFlow<List<ScriptInfo>> = _scripts.asStateFlow()

    private val _selected = MutableStateFlow<ScriptInfo?>(null)
    val selected: StateFlow<ScriptInfo?> = _selected.asStateFlow()

    companion object {
        const val SCRIPT_DIR = "/data/adb/esp_scripts"
        private val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[a-zA-Z]|\u001B\\][^\u0007]*\u0007|\u001B\\(B")
    }

    fun selectScript(script: ScriptInfo) {
        _selected.value = script
    }

    fun loadScripts() {
        scope.launch {
            try {
                val cmd = "find $SCRIPT_DIR -maxdepth 1 -name '*.sh' -type f -exec stat -c '%s %n' {} \\;"
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                val list = mutableListOf<ScriptInfo>()
                var line = reader.readLine()
                while (line != null) {
                    val spaceIdx = line.indexOf(' ')
                    if (spaceIdx > 0) {
                        val size = line.substring(0, spaceIdx).toLongOrNull() ?: 0
                        val path = line.substring(spaceIdx + 1)
                        list.add(ScriptInfo(File(path).name, path, size))
                    }
                    line = reader.readLine()
                }
                reader.close()
                p.waitFor()
                list.sortByDescending { it.sizeBytes }
                _scripts.value = list
                if (_selected.value == null && list.isNotEmpty()) {
                    _selected.value = list.first()
                }
            } catch (e: Exception) {
                _scripts.value = emptyList()
            }
        }
    }

    fun sendInput(text: String) {
        if (_state.value != EngineState.RUNNING || masterFd < 0) return
        scope.launch {
            try {
                val data = "$text\n".toByteArray()
                NativePty.nativeWrite(masterFd, data, data.size)
            } catch (e: Exception) {
                _output.emit(LogLine.Stderr("stdin error: ${e.message}"))
            }
        }
    }

    fun execute(script: ScriptInfo) {
        if (_state.value != EngineState.IDLE) return
        _state.value = EngineState.RUNNING

        scope.launch {
            _output.emit(LogLine.Info("▶ Starting: ${script.name} [PTY]"))
            try {
                val result = NativePty.nativeCreateSubprocess(
                    "su", null, null, 60, 120
                )
                masterFd = result[0]
                childPid = result[1]

                if (masterFd < 0) {
                    _output.emit(LogLine.Stderr("✗ Failed to create PTY (fd=$masterFd)"))
                    _state.value = EngineState.IDLE
                    return@launch
                }

                _pid.value = childPid
                _output.emit(LogLine.Info("  PID: $childPid  FD: $masterFd"))

                delay(300)

                val cmd = "sh ${script.path}\n"
                NativePty.nativeWrite(masterFd, cmd.toByteArray(), cmd.length)

                val readJob = launch(Dispatchers.IO) {
                    val buf = ByteArray(4096)
                    val lineBuf = StringBuilder()
                    try {
                        while (true) {
                            val n = NativePty.nativeRead(masterFd, buf, buf.size)
                            if (n <= 0) break
                            val text = String(buf, 0, n)
                            for (ch in text) {
                                when (ch) {
                                    '\r' -> {}
                                    '\n' -> {
                                        _output.emit(LogLine.Stdout(stripAnsi(lineBuf.toString())))
                                        lineBuf.clear()
                                    }
                                    else -> lineBuf.append(ch)
                                }
                            }
                            if (lineBuf.isNotEmpty()) {
                                _output.emit(LogLine.Stdout(stripAnsi(lineBuf.toString())))
                                lineBuf.clear()
                            }
                        }
                    } catch (_: Exception) {}
                }

                val exitCode = withContext(Dispatchers.IO) {
                    NativePty.nativeWaitFor(childPid)
                }
                readJob.join()
                _output.emit(LogLine.Info("■ Exited with code $exitCode"))
            } catch (e: Exception) {
                _output.emit(LogLine.Stderr("✗ Error: ${e.message}"))
            } finally {
                if (masterFd >= 0) {
                    try { NativePty.nativeClose(masterFd) } catch (_: Exception) {}
                }
                masterFd = -1
                childPid = -1
                _state.value = EngineState.IDLE
                _pid.value = null
            }
        }
    }

    fun stop() {
        if (_state.value != EngineState.RUNNING) return
        _state.value = EngineState.STOPPING
        scope.launch {
            try {
                if (childPid > 0) {
                    NativePty.nativeKill(childPid, 9)
                }
                if (masterFd >= 0) {
                    NativePty.nativeClose(masterFd)
                }
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f esp_scripts")).waitFor()
            } catch (_: Exception) {}
            _output.emit(LogLine.Info("⏹ Terminated by user"))
            masterFd = -1
            childPid = -1
            _pid.value = null
            _state.value = EngineState.IDLE
        }
    }

    fun importScript(name: String, tempPath: String) {
        scope.launch {
            try {
                val dst = "$SCRIPT_DIR/$name"
                Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "mkdir -p $SCRIPT_DIR && cp '$tempPath' '$dst' && chmod 777 '$dst'")
                ).waitFor()
                _output.emit(LogLine.Info("✓ Imported: $name"))
                loadScripts()
            } catch (e: Exception) {
                _output.emit(LogLine.Stderr("Import failed: ${e.message}"))
            }
        }
    }

    fun deleteScript(script: ScriptInfo) {
        scope.launch {
            try {
                Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "rm -f '${script.path}'")
                ).waitFor()
                _output.emit(LogLine.Info("✓ Deleted: ${script.name}"))
                if (_selected.value == script) _selected.value = null
                loadScripts()
            } catch (e: Exception) {
                _output.emit(LogLine.Stderr("Delete failed: ${e.message}"))
            }
        }
    }

    fun clearLog() {
        _output.resetReplayCache()
    }

    fun refreshScripts() {
        loadScripts()
    }

    private fun stripAnsi(text: String): String {
        return ANSI_REGEX.replace(text, "")
    }
}
