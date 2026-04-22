# Nexus — Android PTY Shell Executor

A modern Android shell script executor with **native PTY** (pseudo-terminal) support, built with Kotlin, Jetpack Compose, and JNI. Designed for rooted devices running KernelSU / SukiSU.

## Features

- **Native PTY via JNI** — Real pseudo-terminal (`/dev/ptmx`) for fully interactive shell sessions. Supports `read`, `select`, password prompts, and any script expecting a true terminal environment.
- **Root Execution** — Launches scripts through `su` with a proper terminal session (`setsid` + controlling TTY).
- **Script Manager** — Import `.sh` files from storage, delete scripts with long-press, auto-scan `/data/adb/esp_scripts/`.
- **Live Console** — Streaming output with ANSI escape code stripping, auto-scroll, and expandable view.
- **Quick Input Bar** — One-tap buttons for digits 0–9, y/n, Enter. Plus a text input field for custom commands.
- **Material Design 3** — Dark theme with custom color palette, smooth animations, adaptive layouts.

## Architecture

```
app/
├── src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt        # NDK build config
│   │   └── pty_jni.c             # Native PTY: fork, setsid, /dev/ptmx
│   ├── java/com/nexus/tools/
│   │   ├── MainActivity.kt       # Compose UI
│   │   ├── ShellEngine.kt        # Process lifecycle, PTY read/write
│   │   ├── NativePty.kt          # JNI bridge declarations
│   │   └── ui/Theme.kt           # Material 3 theme
│   └── res/                      # Icons, strings, manifests
├── build.gradle.kts              # App-level config (NDK, CMake, Compose)
└── ...
```

### PTY Flow

```
NativePty.nativeCreateSubprocess("su", ...)
  → open("/dev/ptmx")
  → grantpt() / unlockpt() / ptsname()
  → fork()
  → child: setsid() → open(pts) → TIOCSCTTY → dup2 → execvp("su")
  → parent: returns [masterFd, childPid]

ShellEngine.sendInput(text)
  → NativePty.nativeWrite(masterFd, "$text\n")

ShellEngine read loop
  → NativePty.nativeRead(masterFd, buf, 4096)
  → strip ANSI → emit to UI
```

## Requirements

- **Android 8.0+** (API 26)
- **Root access** via KernelSU, Magisk, or similar
- **arm64-v8a** device

## Build

```bash
# Prerequisites: Android SDK, NDK 27.x, JDK 17

# Clone
git clone https://github.com/HuHUnnu/NexusPTY.git
cd NexusPTY

# Build debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Install the APK on a rooted device
2. Grant root permission when prompted
3. Place `.sh` scripts in `/data/adb/esp_scripts/` or tap **+** to import
4. Select a script → tap **Execute**
5. Use quick buttons or the text input to interact with the running script

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| Native | C (JNI) via Android NDK 27 |
| Terminal | POSIX PTY (`/dev/ptmx`, `forkpty` equivalent) |
| Build | Gradle 8.11 + AGP 8.9 + CMake 3.22 |
| Min SDK | 26 (Android 8.0) |
| ABI | arm64-v8a |

## License

MIT
