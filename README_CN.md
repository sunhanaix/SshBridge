# SshBridge

将 PC 端 SecureCRT 会话无缝迁移到手机 SSH 访问的工具集。

## 概述

SshBridge 由两个组件组成：

1. **SCRT 配置导出工具** (`scrt/`) — Python 桌面应用程序，解密并导出 [SecureCRT](https://www.vandyke.com/products/securecrt/) 会话配置（含已保存的密码），生成手机 App 可导入的格式。支持 GUI、CLI、二维码和 WLAN 同步等多种导出模式。

2. **Android SSH 客户端** (`android/`) — 原生 Android SSH 客户端，基于 Kotlin 和 Jetpack Compose 构建。可连接 SSH/Telnet 服务器，并导入桌面工具导出的会话配置。

## 功能特性

### 桌面导出工具 (Python)

- **GUI 应用程序** — 目录树浏览、多选勾选、实时搜索
- **CLI 工具** — 交互式终端会话导出
- **二维码导出** — 用 Android App 相机直接扫描导入
- **Base64 导出** — 复制到剪贴板或以文本分享
- **WLAN HTTP 同步** — 启动本地服务器，手机扫码即可批量导入全部会话
- 支持 SecureCRT 7.3+ 版本（V2/V3 加密算法）
- 自动从 Windows 注册表检测 SecureCRT 配置路径
- 会话缓存机制，快速重载（约 0.2 秒，对比无缓存约 6 秒）

### Android SSH 客户端 (Kotlin)

- **SSH** 和 **Telnet** 协议支持
- **密码**、**keyboard-interactive** 和 **SSH 密钥**（PEM 格式）认证
- 通过 **二维码扫描**、**Base64 粘贴** 或 **WLAN 同步** 导入会话
- 按文件夹分组显示会话（与 SecureCRT 组织结构一致）
- 基于 [Termux terminal-view](https://github.com/termux/termux-app) 的完整终端模拟
- 可配置保活的连接池
- 快捷命令/宏片段系统
- 粘滞修饰键（Ctrl、Alt、Tab 等）
- Material 3 设计，支持深色/浅色主题
- 应用内更新机制

## 项目结构

```
SshBridge/
├── scrt/                          # SecureCRT 配置导出工具 (Python)
│   ├── scrt_gui.py                # GUI 应用程序（主入口）
│   ├── scrt_exp.py                # CLI 交互式导出工具
│   ├── export_sessions.py         # 批量导出到 JSON
│   ├── securecrt_cipher.py        # 核心加密/解密库
│   ├── securecrt_decrypt.py       # 早期 V2 解密探索
│   ├── requirements.txt           # Python 依赖
│   ├── analyze_all_encrypted.py   # 加密分析工具
│   ├── check_salts.py             # 盐值唯一性检查
│   ├── verify_decrypt.py          # 解密验证
│   ├── test_all_approaches.py     # 综合解密测试套件
│   ├── test_v3_detail.py          # V3 算法研究
│   ├── show_results.py            # 导出结果摘要查看
│   └── doc/                       # 加密算法文档
│       ├── how-does-SecureCRT-encrypt-password.md
│       ├── hypersine-readme.md
│       └── *.png                  # 加密管线图
│
├── android/                       # Android SSH 客户端 (Kotlin)
│   ├── build.gradle.kts           # 根构建脚本
│   ├── settings.gradle.kts        # Gradle 设置
│   ├── gradle.properties          # Gradle 属性
│   ├── gradlew / gradlew.bat      # Gradle Wrapper 脚本
│   ├── gradle/wrapper/            # Gradle Wrapper 配置
│   ├── DEVLOG.md                  # 开发经验总结（中文）
│   ├── docs/                      # 设计文档
│   └── app/
│       ├── build.gradle.kts       # App 构建脚本
│       ├── proguard-rules.pro     # ProGuard 混淆规则
│       ├── libs/                  # 本地 AAR/JAR 依赖
│       └── src/
│           ├── main/              # 主源码 + 资源
│           ├── test/              # 单元测试
│           └── androidTest/       # 仪器化测试
│
├── README.md                      # 英文说明文档
└── README_CN.md                   # 本文件（简体中文）
```

## 快速开始

### 桌面导出工具

**环境要求：** Python 3.12+，Windows 操作系统

```bash
# 安装依赖
pip install pycryptodome customtkinter Pillow qrcode[pil]

# 运行 GUI
python scrt/scrt_gui.py

# 或运行 CLI
python scrt/scrt_exp.py
```

首次运行时，工具会自动检测 SecureCRT 配置路径。也可以手动指定：

```bash
python scrt/scrt_gui.py "D:\SecureCRT9.6\Config"
```

**导出流程：**
1. 启动 GUI——会话按文件夹树展示
2. 选择会话（勾选或多选）
3. 选择导出格式：二维码、Base64 或 WLAN 同步
4. 在 Android App 中扫码、粘贴 Base64 或通过 WLAN 连接导入

### Android SSH 客户端

**环境要求：**
- Android SDK 35（通过 Android Studio 或 `sdkmanager` 安装）
- JDK 17+
- Android 8.0+（API 26+）的设备或模拟器

```bash
cd android

# 设置 Android SDK 路径 (Windows PowerShell)
$env:ANDROID_HOME = "E:\Android\Sdk"

# 构建 Debug APK
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

APK 输出路径：`android/app/build/outputs/apk/debug/app-debug.apk`。

**或使用预编译版本：** 从 [GitHub Releases](https://github.com/sunhanaix/SshBridge/releases) 下载 `scrt_gui.exe` 和 `app-release.apk`。

## 工作原理

### 加密算法逆向

SecureCRT 使用三代加密算法将密码存储在 INI 文件中：

| 版本 | 算法 | 密钥派生方式 | SecureCRT 版本 |
|------|------|-------------|---------------|
| V1 | 双重 Blowfish-CBC | 硬编码密钥 | < 7.3.3 |
| V2 | AES-256-CBC | `SHA256(配置密码)` | >= 7.3.3 |
| V3 | AES-256-CBC + bcrypt PBKDF2 | `bcrypt_pbkdf2(配置密码, salt, 48, 16)` | >= 9.x |

核心加密库 (`securecrt_cipher.py`) 基于 [HyperSine 的研究成果](https://github.com/HyperSine/how-does-SecureCRT-encrypt-password)，并扩展了 V3 支持。详细算法文档见 `scrt/doc/` 目录。

### 数据流

```
┌──────────────────────┐   二维码 / Base64 / HTTP   ┌─────────────────────┐
│  SecureCRT 配置目录    │ ─────────────────────────> │  Android SSH 客户端  │
│  (%LOCALAPPDATA%\     │                            │  (com.sunbeat.      │
│   VanDyke Software\)  │                            │   sshclient)        │
└──────────────────────┘                            └─────────────────────┘
         │                                                      │
         ▼                                                      ▼
   scrt_gui.py 解密                                   Room 数据库存储
   V2/V3 密码 → JSON                                 会话 → SSH 连接
```

## 技术栈

### 桌面工具
- **Python 3.12+**
- **PyCryptodome** — AES/Blowfish/SHA/bcrypt
- **customtkinter** — 现代暗色主题 GUI
- **qrcode + Pillow** — 二维码生成

### Android App
- **Kotlin 2.1.10** + **Jetpack Compose** (BOM 2025.06)
- **ConnectBot sshlib 2.2.46** — SSH 协议实现
- **Termux terminal-view 0.118.0** — 终端模拟器控件
- **Room 2.7.1** — 本地 SQLite 数据库
- **DataStore 1.1.3** — 偏好设置存储
- **minSdk 26** (Android 8.0) / **targetSdk 35** (Android 15)

## 致谢

- [HyperSine/how-does-SecureCRT-encrypt-password](https://github.com/HyperSine/how-does-SecureCRT-encrypt-password) — 原创 SecureCRT 加密研究与解密实现
- [ConnectBot/sshlib](https://github.com/connectbot/sshlib) — Java SSH 库
- [Termux/termux-app](https://github.com/termux/termux-app) — Android 终端模拟器

## 许可证

MIT License — 详见 [LICENSE](LICENSE)。

## 免责声明

本工具仅供**合法迁移**您自己的 SecureCRT 会话配置使用。请仅解密您拥有且有权访问的密码。作者对任何滥用行为不承担任何责任。
