# SSH Android Client Design Spec

## Overview

A native Android SSH client (JuiceSSH-equivalent) that reads SecureCRT session INI files as its configuration source. Built with Kotlin + Jetpack Compose + ConnectBot sshlib + Termux TerminalView.

## Architecture

```
UI Layer (Compose)
  HomeScreen | TerminalScreen | SettingsScreen | ImportScreen

ViewModel Layer
  HomeVM | TerminalVM | SettingsVM | ImportVM

Domain Layer (pure Kotlin, no Android deps)
  ConfigParser | SessionRepo | ConnPool | PortForwardMgr | SnippetMgr | KeyMgr

Infrastructure
  sshlib (SSH) | TerminalView (terminal) | Room (DB) | DataStore (prefs)
```

## SCRT INI Parsing (ConfigParser)

Input: SecureCRT `.ini` session files with `Type:"Key"=Value` format.
Types: `S` (string), `D` (DWORD hex), `B` (binary block), `Z` (empty).

### Field Mapping

| INI Key | Model Field | Notes |
|---------|-------------|-------|
| `S:"Hostname"` | hostname | |
| `D:"[SSH2] Port"` | port | hex, default 22 |
| `S:"Username"` | username | |
| `S:"Protocol Name"` | protocol | SSH2 / Telnet / etc |
| `S:"Password V2"` | encryptedPassword | SCRT-encrypted, decode later |
| `S:"Identity Filename V2"` | identityFile | |
| `S:"Firewall Name"` | jumpHost | "None" → null |
| `S:"Cipher List"` | ciphers | comma-separated |
| `S:"Key Exchange Algorithms"` | kexAlgorithms | |
| `S:"Host Key Algorithms"` | hostKeyAlgorithms | |
| `S:"SSH2 Authentications V2"` | authMethods | |
| `S:"Emulation"` | terminalType | VT100 / xterm / etc |
| `D:"Rows"` | rows | |
| `D:"Cols"` | cols | |
| `S:"Color Scheme"` | colorScheme | |
| `D:"Enable Agent Forwarding"` | agentForwarding | |
| `D:"Request pty"` | requestPty | |
| `S:"Sftp Tab Remote Directory"` | defaultRemoteDir | |
| File name (no .ini) | name | |
| Parent directory path | groupName | |

## Session Model (Room DB)

| Column | Type | Notes |
|--------|------|-------|
| id | Long (PK) | auto-increment |
| name | String | from filename |
| groupName | String | from parent dir |
| hostname | String | |
| port | Int | default 22 |
| username | String | |
| authType | Enum | PASSWORD / KEY / BOTH |
| encryptedPassword | String? | from SCRT, pending decrypt |
| plainPassword | String? | user-entered fallback |
| identityFile | String? | |
| jumpHost | String? | |
| ciphers | String | comma-separated |
| kexAlgorithms | String | |
| hostKeyAlgorithms | String | |
| terminalType | String | default "xterm-256color" |
| rows / cols | Int | |
| defaultRemoteDir | String? | |
| sortOrder | Int | |
| isFavorite | Boolean | |
| sourceFile | String | origin .ini path |

## Connection Management (ConnPool)

Maintains a map of active `TerminalSession` instances keyed by session ID.
Each `TerminalSession` owns:
- `SSHClient` (sshlib)
- `TerminalSession` (Termux buffer + VT100 emulator)
- Active port forward rules

## UI Navigation (Single Activity + Compose Navigation)

```
HomeScreen
  Drawer: group tree + search
  ConnectionList: current group entries
  FAB: quick-add

TerminalScreen (one per tab)
  TerminalView (Termux, fullscreen)
  FloatingActionBar: Ctrl/Alt/Esc/Tab/PgUp/PgDn/arrows/F1-F10/clipboard
  SnippetDrawer: right-swipe, quick commands
  BottomSheet: port forward status

ImportScreen
  DirectoryPicker → ScanResultList → ImportConfirm

SessionEditScreen
SnippetManagerScreen
SettingsScreen
```

## Terminal Rendering Pipeline

```
 SSH Server
   ↓ byte stream
 sshlib (decrypt/decompress)
   ↓ raw PTY output
 Termux TerminalSession (VT100 state machine + ring buffer)
   ↓ update notifications
 TerminalView (Canvas rendering)
   ↓
 Compose AndroidView wrapper
```

Input (reverse):
keyboard / physical KB / FloatingActionBar → TerminalView.onKey() → Termux write() → sshlib sendData() → SSH Server

## Port Forwarding (PortForwardMgr)

Three types, unified as `ForwardRule`:

| Type | Direction | Use Case |
|------|-----------|----------|
| LOCAL (-L) | local port → SSH → remote | Database clients |
| REMOTE (-R) | remote port → SSH → local | Intranet penetration |
| DYNAMIC (-D) | local SOCKS5 proxy | Proxy access |

Management UI: BottomSheet per tab, toggle per rule, add/edit via dialog.

## Quick Commands (SnippetMgr)

```kotlin
data class Snippet(
    id: Long,
    label: String,       // "View error log"
    command: String,     // "tail -f /var/log/nginx/error.log\n"
    group: String?,
    isMacro: Boolean,
    sortOrder: Int
)
```

Macro substitution before send: `${HOST}`, `${USER}`, `${PORT}`.
Trigger: right-swipe SnippetDrawer on TerminalScreen.

## Key Management (KeyMgr)

- In-app key pair generation (RSA-2048/4096, Ed25519)
- Import existing private keys from filesystem
- Export public key for server auth
- Passphrase-protected keys: decrypt dialog on connect, cached in memory

## Settings (DataStore)

| Category | Setting | Default |
|----------|---------|---------|
| Appearance | Theme | Dark |
| Appearance | Font size | 14sp |
| Terminal | Scrollback lines | 32000 |
| Terminal | Terminal type | xterm-256color |
| Terminal | Cursor style | Block |
| SSH | Global Cipher List | aes256-ctr,... |
| SSH | Global KEX Algorithms | ecdh-sha2-nistp521,... |
| SSH | Idle timeout (s) | 300 |
| Behavior | Auto reconnect | Off |
| Behavior | Close tab on disconnect | Off |
| Behavior | Bell | Visual |

## Phased Implementation Plan

| Phase | Content | Est. Files |
|-------|---------|------------|
| P1 Core | Project skeleton + INI parser + Room DB + connection list + sshlib → terminal pipeline | ~20 |
| P2 Terminal UX | Multi-tab, FloatingActionBar, physical KB, themes, font, scrollback | ~8 |
| P3 Advanced | Port forwarding (3 types), snippets, key management (gen/import/export) | ~10 |
| P4 Polish | Import wizard, jump host, backup/restore, auto-reconnect, perf tuning | ~8 |

## Dependencies

```
compose-bom = "2025.06.00"
room = "2.7.1"
datastore-preferences = "1.1.3"
org.connectbot:sshlib
com.termux:terminal-view
kotlinx-coroutines = "1.9.0"
```
