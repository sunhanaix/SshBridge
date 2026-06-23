# SshBridge

A toolkit that bridges your PC SecureCRT sessions to mobile SSH access.

## Overview

SshBridge consists of two components:

1. **SCRT Config Export Tool** (`scrt/`) — A Python desktop application that decrypts and exports [SecureCRT](https://www.vandyke.com/products/securecrt/) session configurations (including saved passwords) into a format importable by the mobile app. Supports GUI, CLI, QR code, and WLAN sync export modes.

2. **Android SSH Client** (`android/`) — A native Android SSH client built with Kotlin and Jetpack Compose. Connects to SSH/Telnet servers and imports session configurations exported by the desktop tool.

## Features

### Desktop Export Tool (Python)

- **GUI application** — folder tree browsing, multi-select, checkboxes, live search
- **CLI tool** — interactive terminal-based session export
- **QR code export** — scan directly with the Android app camera
- **Base64 export** — copy to clipboard or share as text
- **WLAN HTTP sync** — start a local server, scan a QR code with the phone to import all sessions at once
- Supports SecureCRT 7.3+ (V2/V3 encryption algorithms)
- Auto-detects SecureCRT config path from Windows registry
- Session caching for fast reload (~0.2s vs ~6s)

### Android SSH Client (Kotlin)

- **SSH** and **Telnet** protocol support
- **Password**, **keyboard-interactive**, and **SSH key** (PEM) authentication
- Import sessions via **QR code scan**, **Base64 paste**, or **WLAN sync**
- Session grouping by folder (mirrors SecureCRT organization)
- Full terminal emulation via [Termux terminal-view](https://github.com/termux/termux-app)
- Connection pooling with configurable keep-alive
- Snippet/macro system for quick commands
- Sticky modifier keys (Ctrl, Alt, Tab, etc.)
- Material 3 design with dark/light theme
- In-app update mechanism

## Project Structure

```
SshBridge/
├── scrt/                          # SecureCRT Config Export Tool (Python)
│   ├── scrt_gui.py                # GUI application (main entry point)
│   ├── scrt_exp.py                # CLI interactive export tool
│   ├── export_sessions.py         # Batch export to JSON
│   ├── securecrt_cipher.py        # Core encryption/decryption library
│   ├── securecrt_decrypt.py       # Early V2 decryption exploration
│   ├── requirements.txt           # Python dependencies
│   ├── analyze_all_encrypted.py   # Encryption analysis utility
│   ├── check_salts.py             # Salt uniqueness checker
│   ├── verify_decrypt.py          # Decryption verification
│   ├── test_all_approaches.py     # Comprehensive decryption test suite
│   ├── test_v3_detail.py          # V3 algorithm investigation
│   ├── show_results.py            # Export summary viewer
│   └── doc/                       # Encryption algorithm documentation
│       ├── how-does-SecureCRT-encrypt-password.md
│       ├── hypersine-readme.md
│       └── *.png                  # Pipeline diagrams
│
├── android/                       # Android SSH Client (Kotlin)
│   ├── build.gradle.kts           # Root build script
│   ├── settings.gradle.kts        # Gradle settings
│   ├── gradle.properties          # Gradle properties
│   ├── gradlew / gradlew.bat      # Gradle wrapper scripts
│   ├── gradle/wrapper/            # Gradle wrapper config
│   ├── DEVLOG.md                  # Development journal (Chinese)
│   ├── docs/                      # Design documents
│   └── app/
│       ├── build.gradle.kts       # App build script
│       ├── proguard-rules.pro     # ProGuard rules
│       ├── libs/                  # Local AAR/JAR dependencies
│       └── src/
│           ├── main/              # Main source + resources
│           ├── test/              # Unit tests
│           └── androidTest/       # Instrumented tests
│
├── README.md                      # This file (English)
└── README_CN.md                   # Simplified Chinese version
```

## Quick Start

### Desktop Export Tool

**Prerequisites:** Python 3.12+, Windows

```bash
# Install dependencies
pip install pycryptodome customtkinter Pillow qrcode[pil]

# Run GUI
python scrt/scrt_gui.py

# Or run CLI
python scrt/scrt_exp.py
```

On first run, the tool auto-detects your SecureCRT configuration. You can also specify the path:

```bash
python scrt/scrt_gui.py "D:\SecureCRT9.6\Config"
```

**Export workflow:**
1. Launch the GUI — sessions appear in a folder tree
2. Select sessions (checkboxes or multi-select)
3. Choose export format: QR code, Base64, or WLAN sync
4. On the Android app: scan the QR code, paste the Base64 string, or connect via WLAN

### Android SSH Client

**Prerequisites:**
- Android SDK 35 (install via Android Studio or `sdkmanager`)
- JDK 17+
- Android device/emulator running Android 8.0+ (API 26+)

```bash
cd android

# Set Android SDK path (Windows PowerShell)
$env:ANDROID_HOME = "E:\Android\Sdk"

# Build debug APK
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

The APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.

**Or use the pre-built release:** Download `scrt_gui.exe` and `app-release.apk` from [GitHub Releases](https://github.com/sunhanaix/SshBridge/releases).

## How It Works

### Encryption Reverse Engineering

SecureCRT stores passwords in INI files using three generations of encryption:

| Version | Algorithm | Key Derivation | SecureCRT |
|---------|-----------|---------------|-----------|
| V1 | Double Blowfish-CBC | Hardcoded keys | < 7.3.3 |
| V2 | AES-256-CBC | `SHA256(passphrase)` | >= 7.3.3 |
| V3 | AES-256-CBC + bcrypt PBKDF2 | `bcrypt_pbkdf2(passphrase, salt, 48, 16)` | >= 9.x |

The core crypto library (`securecrt_cipher.py`) is based on [HyperSine's research](https://github.com/HyperSine/how-does-SecureCRT-encrypt-password) with extensions for V3 support. See `scrt/doc/` for detailed algorithm documentation.

### Data Flow

```
┌─────────────────────┐     QR / Base64 / HTTP     ┌─────────────────────┐
│  SecureCRT Config   │ ──────────────────────────> │  Android SSH Client │
│  (%LOCALAPPDATA%\    │                             │  (com.sunbeat.      │
│   VanDyke Software\) │                             │   sshclient)        │
└─────────────────────┘                             └─────────────────────┘
        │                                                      │
        ▼                                                      ▼
  scrt_gui.py decrypts                                   Room DB stores
  V2/V3 passwords → JSON                                sessions → SSH
                                                         connection
```

## Tech Stack

### Desktop Tool
- **Python 3.12+**
- **PyCryptodome** — AES/Blowfish/SHA/bcrypt
- **customtkinter** — Modern dark-themed GUI
- **qrcode + Pillow** — QR code generation

### Android App
- **Kotlin 2.1.10** + **Jetpack Compose** (BOM 2025.06)
- **ConnectBot sshlib 2.2.46** — SSH protocol implementation
- **Termux terminal-view 0.118.0** — Terminal emulator widget
- **Room 2.7.1** — Local SQLite database
- **DataStore 1.1.3** — Preferences storage
- **minSdk 26** (Android 8.0) / **targetSdk 35** (Android 15)

## Credits

- [HyperSine/how-does-SecureCRT-encrypt-password](https://github.com/HyperSine/how-does-SecureCRT-encrypt-password) — Original SecureCRT encryption research and cipher implementation
- [ConnectBot/sshlib](https://github.com/connectbot/sshlib) — Java SSH library
- [Termux/termux-app](https://github.com/termux/termux-app) — Android terminal emulator

## License

MIT License — see [LICENSE](LICENSE) for details.

## Disclaimer

This tool is intended for **legitimate migration** of your own SecureCRT session configurations. Only use it to decrypt passwords that you own and have the right to access. The authors are not responsible for any misuse.
