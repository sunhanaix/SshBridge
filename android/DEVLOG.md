# SSH Client (Android) 开发经验总结

## 项目概述

原生 Android SSH 客户端，Kotlin + Jetpack Compose + ConnectBot sshlib + Termux TerminalView，
实现 JuiceSSH 等效功能，读取 SecureCRT INI 配置文件。

**技术栈：** Kotlin 2.1.10, Compose BOM 2025.06, Room 2.7.1, DataStore 1.1.3, 
ConnectBot sshlib 2.2.46, Termux terminal-view 0.118.0, minSdk 26, targetSdk 35

---

## 1. 依赖管理踩坑

### 1.1 sshlib 版本选择

**坑：** 设计文档中写的是 `com.github.connectbot:sshlib:2.2.1`，但 JitPack 上这个版本根本不存在。

**解决：** 通过 JitPack 网站逐版本检查，最终找到可用版本 `2.2.46`。
`2.2.1` 到 `2.2.45` 之间存在大量构建失败的版本。

**经验：** 在 plan 中写死具体版本号之前，先在 JitPack/Maven Central 上确认该版本确实存在且构建成功。
JitPack 的版本号不代表线性发布——很多版本号存在但构建失败（红色标记）。

### 1.2 Termux AAR 无法通过 Gradle 远程解析

**坑：** JitPack 上 `com.termux.termux-app:terminal-view:0.118.0` 可以查到，
但 Gradle 远程拉取时返回 HTTP 401（未授权）。

**解决：** 直接从 JitPack 网页下载 AAR 文件（`terminal-view-0.118.0.aar` 和
`terminal-emulator-0.118.0.aar`），放入 `app/libs/`，使用 `fileTree` 依赖：

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
```

**经验：** JitPack 对某些 artifact 的 Gradle 远程解析支持不完善（特别是多模块项目）。
当 `implementation("com.xxx:yyy:version")` 无法解析时，直接下载 AAR/JAR 作为本地依赖
是最可靠的兜底方案。注意 AAR 文件需要随项目一起提交到 git（或使用 Git LFS）。

### 1.3 Gradle 缓存与依赖诊断

**坑：** 依赖已通过编译（`compileDebugKotlin` 成功），但在 `~/.gradle/caches/modules-2/files-2.1/`
下找不到对应的 JAR 文件，导致无法用 `javap` 反编译查看 API。

**原因：** Android Gradle Plugin 对依赖做了 transform（脱糖、Jetifier 等），
转换后的产物存放在 `~/.gradle/caches/transforms-*/` 下，原始 JAR 可能已被移动或重命名。

**经验：** 排查依赖问题时，使用 `./gradlew :app:dependencyInsight --configuration <config> --dependency <name>`
比直接搜索文件系统更可靠。需要查看依赖 API 时，优先搜索该库的在线 JavaDoc 或源码仓库，
不要花太多时间在本地找 JAR 文件。

---

## 2. Termux 架构理解踩坑

### 2.1 TermuxTerminalSession ≠ SSH 可用的 Session

**最关键的坑：** Termux 的 `TerminalSession`（`com.termux.terminal.TerminalSession`）
是一个绑定到**本地 PTY 子进程**的类。它通过 JNI 调用 `fork()` + `exec()` 启动本地 shell
（如 `/system/bin/sh`），并通过 PTY 与子进程通信。

**这对于 SSH 客户端是完全不合适的**——SSH 客户端不需要本地 PTY，不需要本地子进程，
需要的是将远程 SSH 数据流接入终端模拟器。

**解决：** 不使用 `TerminalSession`，直接使用底层的 `TerminalEmulator`：

```kotlin
// 错误做法：用 TermuxTerminalSession 包装 SSH
val session = TermuxTerminalSession("/system/bin/sh", ...) // 这会 fork 本地 shell!

// 正确做法：直接用 TerminalEmulator + 自定义 TerminalOutput 桥接
val emulator = TerminalEmulator(terminalOutput, rows, cols, scrollback, client)
```

`TerminalEmulator` 是纯粹的终端模拟引擎——处理转义序列、维护屏幕缓冲区、
管理光标状态——完全不涉及进程管理。数据流变为：

```
SSH远程输出 → emulator.append(data, size) → 屏幕缓冲区 → TerminalView 渲染
键盘输入   → ptyOutputStream.write(data) → SSH通道stdin → 远程主机
```

### 2.2 TerminalOutput 桥接

`TerminalEmulator` 构造函数需要一个 `TerminalOutput` 参数。当终端模拟器内部产生
控制响应（如 DSR 设备状态报告、光标位置应答）时，会回调 `TerminalOutput.write()`。
这些数据需要发往 SSH 通道，而非本地 PTY：

```kotlin
private val termOutput = object : TerminalOutput() {
    override fun write(data: ByteArray, offset: Int, count: Int) {
        ptyOutputStream.write(data, offset, count)
        ptyOutputStream.flush()
    }
    // 其他回调(titleChanged, onBell, onColorsChanged...)留空即可
}
```

### 2.3 TerminalView 的 mTermSession 非空依赖

**坑：** `TerminalView.attachSession()` 内部会访问 `mTermSession` 的多个字段，
并且代码中有大量非空断言。直接传 `null` 或伪 Session 会导致 NPE。

**解决（hack）：** 创建一个"哑" `TermuxTerminalSession`（让它启动 `/system/bin/sh`），
调用 `attachSession(dummySession)` 满足内部非空检查，然后立即用反射/字段访问
替换 `mEmulator` 为真正的 SSH-backed emulator：

```kotlin
// 1. 创建哑 session（会启动本地 shell，但不会接收实际输入）
val dummySession = TermuxTerminalSession("/system/bin/sh", "ssh", emptyArray(), null, 24, client)

// 2. 挂载到 TerminalView（初始化渲染器、设置尺寸等）
view.attachSession(dummySession)

// 3. 替换模拟器为 SSH 版本的 emulator
view.mEmulator = realSession.emulator
```

**注意事项：**
- 哑 session 会启动一个浪费的本地 shell 进程，但这是目前唯一的兼容方案
- `setTextSize()` 和 `updateSize()` 内部会重新创建 `mEmulator`，需要在这些操作后
  重新替换 `mEmulator`
- `TerminalView` 的 `mRenderer` 必须在 `attachSession()` 之前设置，否则
  `updateSize()` 会因无字体度量而崩溃

---

## 3. 键盘输入路由

### 3.1 拦截键盘事件

`TerminalView` 默认的键盘处理会将按键写入 `mTermSession`（即哑 session 的本地 PTY）。
必须通过 `TerminalViewClient` 拦截所有按键：

```kotlin
override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TermuxTerminalSession?): Boolean {
    // 1. 尝试 KeyHandler 转换特殊键（方向键、功能键等）为转义序列
    val seq = KeyHandler.getCode(keyCode, e.metaState, ...)
    if (seq != null) {
        terminalSession?.write(seq.toByteArray())
        return true // 返回 true 阻止 TerminalView 的默认处理
    }
    // 2. 提取 Unicode 码点，编码为 UTF-8 写入 SSH
    val unicode = e.getUnicodeChar(e.metaState)
    if (unicode != 0) {
        terminalSession?.write(String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8))
        return true
    }
    return true // 总是返回 true，防止 fallback 到默认处理
}
```

**软键盘输入**通过 `onCodePoint()` 回调，处理方式类似。

### 3.2 长按粘贴

Android 终端的长按粘贴需要从系统剪贴板读取：

```kotlin
override fun onLongPress(e: MotionEvent): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)
    if (!text.isNullOrEmpty()) terminalSession?.write(text.toString())
    return true
}
```

---

## 4. 线程安全

### 4.1 ConnPool 的并发保护

**坑：** 初版 `ConnPool` 使用普通的 `mutableMapOf<Long, TerminalSession>()`，
没有任何同步措施。多协程并发添加/移除 session 时存在竞态条件。

**解决：** 使用 `kotlinx.coroutines.sync.Mutex`：

```kotlin
class ConnPool {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<Long, TerminalSession>()

    suspend fun add(session: TerminalSession) {
        mutex.withLock {
            sessions[session.id] = session
            _activeIds.value = sessions.keys.toSet()
        }
    }
    // 所有 public 方法都是 suspend 并在内部获取 Mutex
}
```

**原则：**
- 所有 ConnPool 方法标记为 `suspend`，内部用 `mutex.withLock{}` 保护
- `activeIds` 使用 `MutableStateFlow`，在 `withLock` 块内更新确保一致性
- 调用方必须在协程作用域内（`viewModelScope.launch`）

### 4.2 网络线程 vs 主线程

**坑：** Android 主线程（UI 线程）不允许网络 I/O，否则抛出 `NetworkOnMainThreadException`。

**解决：** 所有 SSH 操作（`connect()`, `authenticateWithPassword()`, `openSession()`）
必须包裹在 `withContext(Dispatchers.IO)` 中。后台读取循环用 `launch(Dispatchers.IO)` 启动。

---

## 5. sshlib API 发现过程

### 5.1 实际 API 与设计文档的差异

设计文档假设的 sshlib API：
```kotlin
val client = SSHClient(hostname, port)           // ① 类名错误
client.authenticate(PasswordAuthenticator(pwd))   // ② 认证方式错误
val pty = client.openPTY(term, cols, rows)        // ③ PTY打开方式错误
```

实际的 trilead sshlib API（`com.trilead.ssh2`）：
```kotlin
val conn = Connection(hostname, port)             // ① 类名: Connection
conn.connect(hostKeyVerifier)                      //    需要显式 connect()
conn.authenticateWithPassword(user, password)      // ② 直接传用户名+密码
val session = conn.openSession()                   // ③ 先打开 Session
session.requestPTY(term, cols, rows, 0, 0, null)   //    再请求 PTY
session.startShell()                               //    再启动 Shell
val stdout: InputStream = session.stdout           //    getStdout() → stdout
val stdin: OutputStream = session.stdin            //    getStdin() → stdin
```

**经验：** 设计阶段使用的 API 名称来自对 ConnectBot 源码的不完全理解。
实际 API 基于 Trilead SSH2 库（`com.trilead.ssh2`），命名风格是传统 Java 风格
（`getXxx()` 而非 Kotlin 属性风格）。在做 plan 时，应先确认依赖库的实际 API 再写代码模板。

### 5.2 API 发现方法

当被墙或网络限制无法访问 GitHub/JitPack 时：
1. 使用 WebSearch 搜索 `trilead ssh2 Connection.java API` 找到 JavaDoc 镜像
2. 通过 JavaTips、SVN 仓库镜像获取源码
3. 也可以先写代码，让编译器报错来反推正确的 API（效率较低但可靠）

### 5.3 主机密钥验证（TOFU）

Trilead SSH2 的 `Connection.connect()` 需要一个 `ServerHostKeyVerifier`：

```kotlin
val AcceptAllHostKeys = ServerHostKeyVerifier { _, _, _, _ -> true }
conn.connect(AcceptAllHostKeys)
```

P1 阶段永远接受（TOFU），P2 需要持久化 known_hosts 并在变更时提示用户。

---

## 6. Room 数据库踩坑

### 6.1 fallbackToDestructiveMigration

开发阶段数据库 schema 频繁变更，必须设置 `fallbackToDestructiveMigration()`，
否则升级版本号后首次启动会崩溃。生产环境需要提供 `Migration` 对象。

### 6.2 线程安全单例

```kotlin
companion object {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getInstance(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(...).build().also { INSTANCE = it }
        }
    }
}
```

双重检查锁定（DCL）+ `@Volatile` 是 Android Room 的标准单例模式。

### 6.3 Flow 响应式查询

Room DAO 返回 `Flow<List<SessionEntity>>` 可以自动在数据库变更时发射新数据。
配合 Compose 的 `collectAsState()` 实现 UI 自动更新。

---

## 7. Compose / UI 踩坑

### 7.1 AndroidView 与 TerminalView 集成

Termux `TerminalView` 是传统 Android View，需要通过 `AndroidView` 嵌入 Compose：

```kotlin
AndroidView(
    factory = { ctx -> createTerminalView(ctx, fontSizePx, terminalSession) },
    update = { view ->
        view.mEmulator = terminalSession?.emulator
        // update 会在每次 recomposition 时调用
        // mEmulator 被 setTextSize/updateSize 重置后需要恢复
    },
)
```

**关键问题：** `factory` 只在 View 首次创建时调用一次。但 `update` 在每次
recomposition 时调用。如果 `terminalSession` 在 `factory` 调用时为 `null`
（异步连接还未完成），则在 `update` 中需要补设 `mEmulator`。

### 7.2 布局变化监听

TerminalView 的尺寸变化需要重新计算行列数，并通知 TerminalEmulator 和 SSH 服务器：

```kotlin
view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
    val cols = (width / fontWidth).toInt().coerceAtLeast(4)
    val rows = ((height - lineSpacing) / lineSpacing).toInt().coerceAtLeast(4)
    if (emu.mColumns != cols || emu.mRows != rows) {
        terminalSession.resize(rows, cols) // 注意参数顺序: (rows, cols)
        view.mEmulator = emu // 恢复被 updateSize 覆盖的 emulator
    }
}
```

**注意：** `TerminalEmulator.resize(cols, rows)` 的参数顺序是 (cols, rows)，
不是 (rows, cols)。

---

## 8. SCRT 配置解析

### 8.1 INI 格式特点

SecureCRT INI 使用类型前缀标记：
- `S:"Key"=value` — 字符串
- `D:"Key"=hex` — DWORD（32位整数，十六进制）
- `B:"Key"=hexbytes` — 二进制数据
- `Z:"Key"=` — 空值/零值

解析正则：
```kotlin
private val entryRegex = Regex("""^([SDB]):"([^"]+)"=(.*)""")
// Z 型被排除，因为 Z 型默认使用 Session 数据类的默认值
```

### 8.2 密码解密

SCRT V3 加密方案：AES-256-CBC + bcrypt_pbkdf2("", salt, 48, 16)。
所有 V3 密码共享同一 salt `1ba3b3825b360ab57f9ae42a89897416`。

P1 阶段选择将解密后的配置导出为 JSON 并打包进 APK assets，
避免在 Kotlin 中实现 bcrypt_pbkdf2（复杂的 JNI/纯 Java 实现）。

---

## 9. 项目结构经验

### 9.1 手动依赖注入

P1 阶段使用手动 DI（`AppContainer`），避免引入 Koin/Hilt 等 DI 框架的复杂度：

```kotlin
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.getInstance(context)
    val sessionDao = database.sessionDao()
    val sessionRepository = SessionRepository(sessionDao)
    val preferences = AppPreferences(context)
    val connPool = ConnPool()
}
```

在 `Application.onCreate()` 中初始化，通过 `ViewModel.Factory` 传递给各 ViewModel。

### 9.2 包结构

```
com.sunbeat.sshclient
├── data/local/        # Room DB: Entity, DAO, Database
├── data/preferences/  # DataStore preferences
├── di/                # Manual DI container
├── domain/model/      # Domain models (Session, SshKey, etc.)
├── domain/parser/     # SCRT INI parser
├── domain/repository/ # SessionRepository
├── domain/ssh/        # ConnPool, TerminalSession
├── ui/home/           # HomeScreen + HomeViewModel
├── ui/terminal/       # TerminalScreen + TerminalViewModel
├── ui/components/     # Shared UI components (ConnectionCard)
├── ui/navigation/     # NavGraph
├── ui/theme/          # Material3 theme
└── SshApp.kt          # Application subclass
```

---

## 10. Git 与工程规范

### 10.1 提交粒度

每个 task 一个 commit（或 fixup commit），commit message 遵循 conventional commits：
- `feat: add X` — 新功能
- `fix: fix Y` — bug 修复
- `docs: add Z` — 文档
- `perf:` / `refactor:` / `test:` / `chore:`

### 10.2 Windows 环境注意事项

- **编码：** PowerShell 输出重定向到文件时默认 UTF-16 LE，gradle 构建日志
  使用 `2>&1` 重定向可能因编码问题导致乱码。使用 `Select-String` 过滤更可靠。
- **路径分隔符：** Gradle 在 Windows 下使用 `\`，但 gradle-wrapper 内部
  已做转换，不影响使用。
- **BOM 问题：** PowerShell 的 `Out-File -Encoding utf8` 会添加 UTF-8 BOM，
  导致 Gradle 初始化脚本解析失败。使用 Bash Here-Doc 写入无 BOM 文件。
- **CRLF vs LF：** Git 在 Windows 下默认转换 CRLF。Kotlin 源码使用 LF 即可，
  但 `.bat` 和 `.ps1` 脚本必须使用 GBK 编码。

---

## 11. 核心教训总结

| # | 教训 | 影响 |
|---|------|------|
| 1 | **先在依赖仓库确认版本存在**，再写入 plan | sshlib 版本号错误，plan 写了不存在的 2.2.1 |
| 2 | **理解第三方库的架构边界**，不要假设 API | TermuxTerminalSession 绑定本地 PTY，完全不适合 SSH |
| 3 | **AAR 远程解析失败时用本地文件兜底** | Termux terminal-view 在 JitPack 返回 401 |
| 4 | **用 `dependencyInsight` 而非文件系统搜索依赖** | Gradle transforms 改变了 JAR 存储路径 |
| 5 | **所有共享可变状态都要加锁** | ConnPool 初版无锁，review 时被发现 |
| 6 | **网络 I/O 必须在 `Dispatchers.IO`** | Android 主线程禁止网络操作 |
| 7 | **SAM 接口用 Kotlin lambda 简化** | `ServerHostKeyVerifier { _, _, _, _ -> true }` |
| 8 | **View 系统中注意异步初始化时序** | TerminalView 的 mEmulator 可能被后续操作覆盖 |
| 9 | **先编译验证 API 再写完整实现** | trilead API 与设计预期差异大，逐步修正 |
| 10 | **fallbackToDestructiveMigration 仅在开发阶段使用** | 生产环境需要 Migration 对象 |
| 11 | **JSON 资产预导入 + 去重** | 700+ SCRT 配置通过 assets JSON 打包，首次启动自动导入 |
| 12 | **分组折叠 UI 模式** | `DisplayItem` 扁平行首+卡片，`mutableStateMapOf` 管理展开状态 |
| 13 | **Reactive group filter 用 flatMapLatest** | `selectGroup("")` 触发 `getSessionsByGroup("")` 只返回空 group 会话 |
| 14 | **AlertDialog 手动添加会话** | FAB 直接触发表单对话框，含 hostname/port/user/pwd 字段 |

---

## 12. JSON 资产导入与首次启动

### 12.1 预解密 SCRT 配置打包

SCRT 的 V3 加密（AES-256-CBC + bcrypt_pbkdf2）在 P1 Kotlin 中实现复杂。
选择在外部（Python）完成解密，导出为 JSON 数组放入 `app/src/main/assets/sessions_export.json`：

```json
[
  {
    "session": "生产服务器",
    "hostname": "10.0.0.1",
    "port": 29095,
    "username": "root",
    "folder": "Production",
    "protocol": "SSH2",
    "has_saved_password": true,
    "password": "decrypted_password",
    "firewall": "None"
  }
]
```

### 12.2 首次启动导入

在 `HomeViewModel.init` 中检查数据库是否为空，空则自动导入：

```kotlin
viewModelScope.launch {
    if (repository.count() == 0) {
        repository.importFromJsonAssets(appContext)
    }
}
```

这要求 `HomeViewModel` 持有 `Context` 引用，通过 `Factory` 传入 `appContext`。
`AppContainer` 也需要暴露 `appContext`。

### 12.3 去重策略

同一 `hostname:port:username` 组合视为重复。使用 `linkedMapOf` 保持首次出现的顺序，
但遇到重复时优先保留**有密码**的条目：

```kotlin
val seen = linkedMapOf<String, Session>()
for (i in 0 until jsonArray.length()) {
    val key = "$hostname:$port:$username"
    val existing = seen[key]
    if (existing == null) {
        seen[key] = session
    } else if (existing.plainPassword.isNullOrEmpty() && !session.plainPassword.isNullOrEmpty()) {
        seen[key] = session // 用有密码的替换
    }
}
```

去重前 738 条 → 去重后 737 条（仅 1 组重复，且两条均无密码）。

---

## 13. 分组折叠 UI 模式

### 13.1 问题

700+ 会话按 folder 分组后约 20 个组，直接渲染卡顿且不可浏览。

### 13.2 解决：DisplayItem 扁平化

使用 `DisplayItem` 封闭类将分组标题和会话卡片扁平化为单列表：

```kotlin
private data class DisplayItem(
    val isHeader: Boolean,
    val group: String = "",
    val session: Session? = null,
)

val displayItems = buildList {
    for ((group, sessions) in grouped) {
        add(DisplayItem(isHeader = true, group = group))
        val expanded = expandedGroups[group] ?: allExpanded
        if (expanded) {
            sessions.forEach { add(DisplayItem(isHeader = false, session = it)) }
        }
    }
}
```

LazyColumn 的 `items` 根据 `isHeader` 渲染标题行或 `ConnectionCard`。Key 策略：
- Header: `"hdr_${group}"`
- Session: `"sess_${session.id}"`

### 13.3 两层展开控制

- **全局状态** `allExpanded: Boolean` — 一键展开/折叠所有组
- **单组状态** `expandedGroups: SnapshotStateMap<String, Boolean>` — 点击组标题切换
- 默认值：`expandedGroups[group] ?: allExpanded`
- 切换全局时 `expandedGroups.clear()` 以恢复统一状态

```kotlin
TextButton(onClick = {
    allExpanded = !allExpanded
    expandedGroups.clear()
}) {
    Text(if (allExpanded) "Collapse All" else "Expand All")
}
```

### 13.4 导航抽屉 vs 分组标题

导航抽屉（`ModalNavigationDrawer`）中的 `NavigationDrawerItem` 用于**筛选**某一组的会话。
分组标题用于**视觉折叠/展开**，两者独立工作——抽屉选中 "Production"，
列表只显示 Production 组；再点折叠仅影响该组的可见性。

---

## 14. All Sessions 筛选修复

### 14.1 Bug

`selectGroup("")` 触发 `getSessionsByGroup("")`，SQL 为 `WHERE group_name = ''`，
只返回 `groupName` 为空串的会话（默认 "Ungrouped" 组），导致 "All Sessions" 不显示其他组。

### 14.2 修复：flatMapLatest

```kotlin
private val groupFilter = MutableStateFlow("")

init {
    viewModelScope.launch {
        groupFilter.flatMapLatest { group ->
            if (group.isEmpty()) repository.getAllSessions()
            else repository.getSessionsByGroup(group)
        }.collect { sessions ->
            _uiState.value = _uiState.value.copy(sessions = sessions)
        }
    }
}

fun selectGroup(group: String) {
    _uiState.value = _uiState.value.copy(selectedGroup = group)
    groupFilter.value = group
}
```

`flatMapLatest` 在 group 变化时自动切换数据源，取消旧的 Flow 订阅。
需要 `@OptIn(ExperimentalCoroutinesApi::class)` 标记在类上（不能标记在 `init` 块）。

---

## 15. 手动添加会话对话框

### 15.1 问题

初版 FAB 只调用 `onImportClick`（重新导入 JSON），用户期望能手动输入单个会话。

### 15.2 修复：AlertDialog 表单

```kotlin
var showAddDialog by remember { mutableStateOf(false) }
// Hostname, Port, Username, Password 四个 OutlinedTextField
// Port 限制数字输入：newPort.filter { c -> c.isDigit() }
// 确认后构建 Session 调用 viewModel.addSession(session)
```

`authType` 自动判断：有密码 → `PASSWORD`，无密码 → `KEY`。
`sourceFile` 设为 `"manual"` 区别于导入的配置。

---

## 16. 更新后的核心教训总结

| # | 教训 | 影响 |
|---|------|------|
| 11 | **JSON 资产 + 首次启动检查是可靠的种子数据方案** | 比运行时解析 INI 更可控，解密在外部完成 |
| 12 | **去重键的选择影响数据质量** | hostname:port:username 唯一标识一个连接，去重同时保留有密码条目 |
| 13 | **flatMapLatest 是响应式分组的正确模式** | 避免手动管理两个 Flow 订阅，自动取消旧订阅 |
| 14 | **UI 状态分层：全局 + 单组 + 默认值** | `allExpanded` 兜底，`expandedGroups` 覆盖，toggle 时 clear |
| 15 | **Local enum disallowed in Kotlin** | 封闭类/数据类作为 item type 标记替代 `enum class ItemType` |
| 16 | **`@OptIn` 不能标记 `init` 块** | 需标记在整个类上 `@OptIn(ExperimentalCoroutinesApi::class)` |
| 17 | **长按编辑比独立编辑按钮更符合移动端直觉** | `combinedClickable` + 预填表单 + 删除确认二级对话框 |
| 18 | **SCRT INI key 格式重要** | `data.get('[SSH2] Port')` 而非 `data.get('Port')`，忽略导致全部端口回退到 22 |
| 19 | **编辑表单复用添加表单结构但语义不同** | 编辑需要 Delete 按钮（二级确认）、Save 而非 Add、预填当前值 |

---

## 17. 长按编辑会话对话框

### 17.1 需求

用户长按某个会话卡片后弹出编辑界面，可修改 hostname、port、username、auth type、
password、key path、name，支持删除会话。

### 17.2 实现

**ConnectionCard 增加 onLongClick 参数：**

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionCard(
    session: Session,
    isConnected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,  // 新增
    modifier: Modifier = Modifier,
)
```

使用 `combinedClickable` 替代 `clickable`：

```kotlin
Card(
    modifier = modifier.fillMaxWidth().combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick,
    ),
    ...
)
```

**编辑对话框 AlertDialog：**

```kotlin
var showEditDialog by remember { mutableStateOf(false) }
var editSession by remember { mutableStateOf<Session?>(null) }
// editName, editHostname, editPort, editUsername, editAuthType, editPassword, editKeyPath

// LazyColumn 中连接长按事件：
onLongClick = {
    editSession = session
    editName = session.name
    editHostname = session.hostname
    editPort = session.port.toString()
    editUsername = session.username
    editAuthType = session.authType
    editPassword = session.plainPassword ?: ""
    editKeyPath = session.identityFile ?: ""
    showEditDialog = true
}
```

**删除按钮使用二级确认：**

```kotlin
var showDeleteConfirm by remember { mutableStateOf(false) }

// 编辑对话框 dismissButton 中：
Row {
    TextButton(onClick = { showDeleteConfirm = true }) {
        Text("Delete", color = MaterialTheme.colorScheme.error)
    }
    TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
}

// 删除确认对话框
if (showDeleteConfirm && editSession != null) {
    AlertDialog(
        title = { Text("Delete Session") },
        text = { Text("Delete \"${editSession!!.name}\"? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                editSession?.let { viewModel.deleteSession(it) }
                showDeleteConfirm = false
                showEditDialog = false
            }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
    )
}
```

### 17.3 关键点

- `editSession` 保存原始 Session 引用，用于 `updateSession()` 和 `deleteSession()` 定位
- 编辑框用 `by remember { mutableStateOf(...) }` 独立管理，不直接修改 Session 对象
- Save 时通过 `session.copy(...)` 创建新实例传给 `viewModel.updateSession()`
- 删除需要两次点击（Delete → 确认），防止误操作

---

## 18. SCRT 端口导入修复

### 18.1 Bug

所有 SCRT 导入的会话端口都是 22，而实际配置如 `zsan-bandwagon.ini` 端口为 29095、
`MSI-laptop-m.atitech.com.cn_孙元一_42222.ini` 端口为 42222。

### 18.2 根因

Python 导出脚本 `export_sessions.py` 中使用了错误的 INI key：

```python
# 错误：key 是 'Port'，但 SCRT INI 文件中是 '[SSH2] Port'
'port': int(data.get('Port', '16'), 16),
```

SCRT INI 格式为 `D:"[SSH2] Port"=000071a7`，key 包含方括号和 SSH2 前缀。
解析器 `parse_ini()` 返回的字典 key 是 `[SSH2] Port`（含方括号）。

### 18.3 修复

```python
# 正确：使用含方括号的完整 key
'port': int(data.get('[SSH2] Port', '16'), 16),
```

16 进制值示例：`000071a7` → 29095, `0000a4ee` → 42222, `00000016` → 22

重新导出后验证：zsan-bandwagon → 29095, MSI-laptop-* → 42222/49322 等所有非标准端口恢复正确。

### 18.4 经验

SCRT INI 的 section 不是独立层级，而是嵌入在 key 名中的 `[SectionName] KeyName` 格式。
不要假设 key 只是字段名——完整 key 包含 section 前缀和方括号。

---

## 19. 添加对话框的 Key 认证选择器

### 19.1 需求

添加会话时需要支持三种认证方式：Password、Key、Key+Pwd。

### 19.2 实现

在 AlertDialog 中添加 AuthType 选择器：

```kotlin
var newAuthType by remember { mutableStateOf(AuthType.PASSWORD) }

Row(Modifier.fillMaxWidth()) {
    AuthType.entries.forEach { at ->
        TextButton(onClick = { newAuthType = at }, modifier = Modifier.weight(1f)) {
            Text(
                text = when (at) {
                    AuthType.PASSWORD -> "Password"
                    AuthType.KEY -> "Key"
                    AuthType.BOTH -> "Key+Pwd"
                },
                color = if (newAuthType == at)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (newAuthType == at) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
```

根据选择的条件显示对应字段：
- PASSWORD 或 BOTH → 显示 Password 文本框
- KEY 或 BOTH → 显示 Key file path 文本框

### 19.3 编辑对话框复用

编辑对话框使用相同结构，但使用独立的 `editAuthType`、`editPassword`、`editKeyPath` 状态变量，
确保编辑操作不影响添加表单。

## 20. NetworkOnMainThreadException — SSH 写入线程问题

### 20.1 现象

连接 SSH 后，键盘可以弹出，按键事件可以触发 `onKeyDown`/`onCodePoint`，但写入 `ptyOutputStream`
永远失败，错误为 `NetworkOnMainThreadException: null`。终端界面无回显，无 shell 输出。

### 20.2 根因

ConnectBot sshlib 的 `Session.getStdin()` 返回 `ChannelOutputStream`。
`ChannelOutputStream.write()` 的调用链为：

```
ChannelOutputStream.write()
  → ChannelManager.sendData()
    → TransportManager.sendMessage()
      → TransportConnection.sendMessage()
        → CipherOutputStream.write()
          → BufferedOutputStream.flush()
            → SocketOutputStream.write()
```

最终落到 `SocketOutputStream.write()` —— 这是一个网络 I/O 操作。

而 Android 的键盘/IME 事件（`onKeyDown`、`onCodePoint`）是在**主线程**上调用的。
`TerminalView.onKeyDown()` → `TerminalViewClientImpl.onKeyDown()` → `TerminalSession.write()` → 
`ptyOutputStream.write()` 全程在主线程执行，触发 Android StrictMode 的 `NetworkOnMainThreadException`。

从字节码确认调用链：
```
296: invokevirtual  // Method com/trilead/ssh2/Session.getStdin:()Ljava/io/OutputStream;
305: invokestatic   // Intrinsics.checkNotNullExpressionValue — getStdin() 非 null
```

`getStdin()` 返回非 null，但输出的异常 `e.message == null` 导致误以为是 NPE。
实际上 NPE 和 `NetworkOnMainThreadException` 的 `message` 都是 null，必须打印异常类名才能区分。

### 20.3 修复

在 `TerminalSession` 中维护一个专用**单线程 Executor**，所有 SSH 写入异步执行：

```kotlin
private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "SSH-stdin").apply { isDaemon = true }
}

fun write(data: ByteArray) {
    writeExecutor.execute {
        try {
            ptyOutputStream.write(data)
            ptyOutputStream.flush()
        } catch (e: Exception) {
            Log.e("SSHTerm", "write failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
```

**关键设计决策：**
- **单线程 Executor** 保证写入顺序（终端 I/O 对顺序敏感，字符必须按键入顺序到达服务器）
- **Daemon 线程** 不阻止应用退出
- **`close()` 中调用 `shutdown()`** 停止接受新任务但等待已提交任务完成

同样修复了 `TerminalOutput.write()`（终端模拟器向 SSH 发送控制响应时的写入路径）。

### 20.4 调试经验

- `e.message == null` 不一定是 NPE，`NetworkOnMainThreadException` 同样没有 message。
  诊断时必须打印异常类名（`e.javaClass.simpleName`）或堆栈（`Log.e(TAG, msg, e)`）。
- `adb logcat -s TAG` 可以实时过滤特定标签的日志，但输出量太大会触发 Android logcat 的
  rate limiter 丢弃事件。生产环境应适当减少日志量。
- 使用 `javap -c -p` 反编译 Kotlin 字节码可以确认 Kotlin 属性访问实际调用的 Java 方法，
  在排查 Java 互操作问题时非常有用。

## 21. 终端实时回显 — onEmulatorUpdated 回调 + IME 适配

### 21.1 现象

SSH 连接后，在键盘上输入字符时终端无任何显示，直到按下回车键后字符才一起落到终端上。
同时，弹出的软键盘会遮挡终端命令行区域，用户看不到正在输入的内容。

### 21.2 根因分析

**渲染不及时：**
`TerminalSession.feedRemoteData()` 只调用了 `emulator.append()`，没有主动通知 View 重绘。
虽然 `wireEmulatorCallback()` 设置了 `TerminalSessionClient.onTextChanged` → `postInvalidate()`，
但 `emulator.append()` 内部调用 `mClient.onTextChanged()` 发生在 IO 线程，
而 Compose 的 `AndroidView` 对 `postInvalidate()` 传播不够可靠。

**键盘遮挡：**
Compose 的 `android:windowSoftInputMode="adjustResize"` 只在 Activity 层生效，
但在 Compose 布局中需要配合 `Modifier.imePadding()` 主动为键盘腾出空间。

### 21.3 修复

**1) TerminalSession 增加 onEmulatorUpdated 回调：**

```kotlin
var onEmulatorUpdated: (() -> Unit)? = null

fun feedRemoteData(data: ByteArray) {
    emulator.append(data, data.size)
    onEmulatorUpdated?.invoke()
}
```

替代依赖 `TerminalSessionClient.onTextChanged`（IO 线程回调，与 Compose 渲染管线不可靠衔接）。

**2) TerminalScreen 的 AndroidView update 块中 wiring：**

```kotlin
update = { view ->
    if (terminalSession != null) {
        view.mEmulator = terminalSession.emulator
        wireEmulatorCallback(view, terminalSession)
        terminalSession.onEmulatorUpdated = {
            Handler(Looper.getMainLooper()).post { view.postInvalidate() }
        }
    }
}
```

`Handler.post` 保证 `postInvalidate()` 在主线程执行，绕过 Compose AndroidView 的渲染传播问题。

**3) 键盘 IME 适配：**

```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .imePadding(),  // ← 为软键盘预留空间
)
```

`imePadding()` 让 Compose 布局在键盘弹起时自动收缩，终端最后一行始终在键盘上方可见。

### 21.4 经验

- Compose `AndroidView` 封装的原生 View 的 `postInvalidate()` 不总能可靠触发 Compose 端的重绘。
  直接回调 + `Handler.post` 是更可靠的桥接方式。
- `TerminalSessionClient.onTextChanged` 的回调线程取决于 `emulator.append()` 的调用线程，
  不能假设是主线程。跨线程通知 UI 必须显式 post 到主线程。
- `adjustResize` + `imePadding()` 是 Compose 中完整键盘适配方案的两部分，缺一不可。

## 22. 键盘显隐导致 Dummy Session 屏幕缓冲泄露

### 22.1 现象

SSH 登录成功后终端显示正常的 shell 提示符。收起键盘后再点击终端弹出键盘，
终端内容被替换为 `chdir("ssh"): No such file or directory`，然后显示 `:/ $`。

### 22.2 根因

`TerminalView` 有一个内置行为：当 View 尺寸变化时，`onSizeChanged()` 自动调用
`updateSize()`，而 `updateSize()` 内部会将 `mEmulator` 重置为 attached session
的 emulator：

```
keyboard hide/show
  → TerminalView 尺寸变化
    → onSizeChanged() → updateSize()
      → mEmulator = mTermSession.getEmulator()  // dummy session 的 emulator!
```

dummy session 是一个 `TermuxTerminalSession`，构造参数 cwd="ssh"（无效路径），
其 shell 启动时会输出 `chdir("ssh"): No such file or directory`。

原来的 `onLayoutChangeListener` 只在尺寸变化时恢复 `mEmulator`：

```kotlin
if (emu.mColumns != cols || emu.mRows != rows) {
    ts.resize(rows, cols)
    view.mEmulator = emu  // 只在 if 内恢复!
}
```

但 `updateSize()` 已经将 dummy emulator 的尺寸调整为相同的 cols/rows，
因此条件 `emu.mColumns != cols` 为 false（SSH emulator 的尺寸没变），
跳过恢复逻辑，导致 dummy emulator 的屏幕缓冲被渲染到界面。

### 22.3 修复

**1) 无条件恢复 mEmulator：**

```kotlin
val emu = ts.emulator
if (emu.mColumns != cols || emu.mRows != rows) {
    ts.resize(rows, cols)
}
// 无条件恢复 — TerminalView.onSizeChanged() 每次 layout
// 都会将 mEmulator 重置为 dummy session
view.mEmulator = emu
```

**2) 修正 dummy session 的 cwd：**

```kotlin
val dummySession = TermuxTerminalSession(
    "/system/bin/sh",
    "/",       // ← 原来是 "ssh"，shell 会尝试 chdir 到无效路径
    emptyArray(),
    null,
    24,
    NoopTerminalSessionClient,
)
```

### 22.4 经验

- `TerminalView.onSizeChanged()` → `updateSize()` 会在每次尺寸变化时覆盖 `mEmulator`。
  任何基于 `TerminalView` 的非标准集成（替换 emulator 而非使用标准 session）都必须在
  每次 layout 后**无条件**恢复自定义 emulator，不能依赖尺寸变化判断。
- Compose `imePadding()` 使 keyboard 显隐触发 layout 变化，意味着 `onSizeChanged()`
  的调用频率比无键盘场景更高，放大了这个 bug 的触发概率。
- dummy session 的构造参数虽然"看不见"，但它的 shell 进程确实在运行并输出内容。
  确保参数干净（cwd="/"）可以减少意外输出的干扰。

## 23. 粘滞修饰键（Ctrl/Alt Toggle）实现

### 23.1 需求

原控制键栏只提供了 10 个硬编码的 Ctrl+字母组合（C-c/d/z/l/a/e/w/u/k/r），
用户无法发送 Ctrl+h、Ctrl+n、Ctrl+p 等常用组合，也无法发送 Ctrl+数字或 Alt+字母。

### 23.2 方案设计

JuiceSSH 风格的粘滞键（sticky modifier key）：
- 点击 Ctrl/Alt 按钮 → 高亮激活 → 下一个输入的字符自动带上修饰符
- 按完一个字符后自动释放（latch-once），也可再点一次手动取消
- Ctrl 和 Alt 互斥（同时只能激活一个）
- 中文/非 ASCII 字符不受修饰键影响，原样透传

### 23.3 架构

**共享状态对象** `ModifierState`：连接 Compose 控制栏和 `TerminalViewClientImpl`：

```kotlin
class ModifierState {
    @Volatile var ctrlActive: Boolean = false
    @Volatile var altActive: Boolean = false
    fun release() { ctrlActive = false; altActive = false }
}
```

- `TerminalScreen` composable 中 `remember { ModifierState() }` 持有单一实例
- `ControlKeyBar` 读取 `modifierState.ctrlActive`/`altActive` 决定按钮高亮
- `TerminalViewClientImpl` 通过 `readControlKey()`/`readAltKey()` 返回粘滞键状态
- Composable 使用 `modifierVersion` 计数器强制在状态切换时重组

### 23.4 字符映射逻辑（onCodePoint）

```kotlin
val effectiveCtrl = controlDown || modifierState.ctrlActive
val effectiveAlt = modifierState.altActive

// Ctrl+字母 a-z → ASCII 0x01–0x1A
if (effectiveCtrl && codePoint in 'a'..'z') {
    write((codePoint - 'a' + 1).toByte()); release(); return
}

// Ctrl+任意 ASCII 可打印字符 → codePoint & 0x1F
// 涵盖：@→0x00, [→0x1B(ESC), \→0x1C, ]→0x1D, ^→0x1E, _→0x1F, ?→0x7F(DEL)
if (effectiveCtrl && codePoint in 0x20..0x7F) {
    write((codePoint and 0x1F).toByte()); release(); return
}

// Alt+ASCII 可打印字符 → ESC 前缀 + 原始字节
if (effectiveAlt && codePoint in 0x20..0x7E) {
    write(0x1B.toByte() + charBytes); release(); return
}

// 非 ASCII（中文等）或无修饰键 → 原样 UTF-8 透传
if (hasModifier) release()
write(utf8Bytes)
```

### 23.5 UI 实现

**Toggle 按钮（`ModifierToggleButton`）**：
- 激活时：使用 `primary` 色背景 + `●` 后缀 + `onPrimary` 文字色
- 未激活时：使用 `secondaryContainer` 背景（与普通键一致）
- 点击 Ctrl 时关闭 Alt，反之亦然（互斥）

**ControlKeyBar Row 1 布局**：
```
[Ctrl●] [Alt] [Esc] [Tab] [/] [-] [|] [↑][↓][←][→] [⌫]
```

Row 3 保留原有的 10 个 Ctrl+字母快捷按钮（C-c, C-d, C-z...），
这些按钮直接发送控制字符字节，不经过修饰键状态。

### 23.6 经验

- `@Volatile` 注解确保主线程写入后、IO 线程的 `onCodePoint` 立即可见。
  虽然 `readControlKey()` 在 IO 线程调用，`@Volatile` 保证 happens-before。
- Kotlin 中 `_` `__` `___` 等纯下划线名称是保留字，不能用作变量名。
  需要强制读取某个值以驱动 Compose 重组时，使用普通命名即可。
- 粘滞键在按键事件（`onKeyDown`）和软键盘字符（`onCodePoint`）两个路径
  都需要处理，否则硬键盘或软键盘之一的修饰键会失效。
- `KeyHandler.getCode()` 通过 `readControlKey()`/`readAltKey()` 判断修饰键，
  因此粘滞键对硬件键盘的功能键路径自动生效，无需额外处理。
- 中文输入通过 IME 的 `onCodePoint` 到达，codePoint > 0x7F。检查
  `codePoint in 0x20..0x7E` 确保了修饰键不会干扰 CJK 输入。

---

## 24. 应用内更新修复 — apkUrl 动态获取

### 24.1 问题

App 检测到新版本后，下载安装的仍然是旧版本（0.5.0 → 提示有 0.9.0 → 安装后还是 0.5.0）。

### 24.2 根因

`UpdateManager.downloadAndInstall()` 硬编码了 APK URL 路径：

```kotlin
// 旧代码 — 有问题的写法
private const val APK_PATH = "/ssh_apk/app-debug.apk"
```

部署脚本只推送了带版本号的文件名（`sshclient-0.9.0.apk`），服务器上的
`app-debug.apk` 一直是旧文件，从未更新。

### 24.3 修复

**思路：** 服务端 `version.json` 中增加 `apkUrl` 字段，客户端从 JSON 中
动态获取下载地址，不再硬编码。

**服务端 version.json 格式：**
```json
{
  "versionCode": 12,
  "versionName": "0.10.0",
  "apkUrl": "https://www.sunbeatus.com/ssh_apk/sshclient-0.10.0.apk"
}
```

**客户端 UpdateManager 改动：**
```kotlin
// UpdateInfo 中新增 apkUrl 字段
data class UpdateInfo(
    val currentCode: Int, val currentName: String,
    val latestCode: Int = 0, val latestName: String = "",
    val apkUrl: String = "",  // 新增
    val updateAvailable: Boolean = false, val error: String? = null,
)

// checkUpdate() 中从 JSON 提取 apkUrl
val remoteApkUrl = remote.optString("apkUrl", "")
// ...
return UpdateInfo(apkUrl = remoteApkUrl, ...)

// downloadAndInstall() 改为接受 apkUrl 参数
fun downloadAndInstall(context: Context, apkUrl: String) {
    val url = URL(apkUrl)  // 不再使用硬编码路径
    // ...
}
```

**部署脚本改动：** 每次构建同时推送 `app-debug.apk`（兼容旧客户端）
和 `sshclient-X.Y.Z.apk`（版本化文件名）。

### 24.4 经验

- 下载地址是可变配置，不应硬编码。从版本元数据 JSON 中动态获取，
  允许服务端随时更改 CDN 路径、文件名等，客户端无需更新。
- `apkUrl` 必须使用公网域名（`www.sunbeatus.com`），不能用内网地址。
  手机在移动网络下无法访问内网。
- 部署时保留 `app-debug.apk` 作为兜底：旧版客户端（0.5.0）代码中
  仍硬编码此路径，更新后新版本才会使用动态 URL。

---

## 25. QR 码扫描 + 剪贴板导入

### 25.1 需求

手机端需要从电脑端导入配置，提供两种途径：
- 扫描电脑屏幕上的 QR 码（PC 端 `scrt_exp.py` 生成）
- 从剪贴板粘贴 base64 字符串（跨设备剪贴板共享或手动传输）

### 25.2 QR 扫描实现

使用 ZXing 嵌入式扫描库：

```kotlin
// build.gradle.kts
implementation("com.journeyapps:zxing-android-embedded:4.3.0")

// AndroidManifest.xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

**Compose 集成（HomeScreen.kt）：**
```kotlin
val scanLauncher = rememberLauncherForActivityResult(
    contract = ScanContract()
) { result ->
    // ScanContract 返回的内容逐条解析并导入
    result.contents.forEach { text ->
        viewModel.importFromBase64(text)
    }
}
```

**注意事项：**
- ZXing `ScanContract` 可连续扫描多条 QR 码（批量导入场景），
  结果通过 `ScanResult.contents: List<String>` 返回
- CAMERA 权限声明为 `required="false"`，不阻止无摄像头设备安装
- Material Icons 核心库不含 `QrCodeScanner`/`CameraAlt` 图标，
  使用 `Text("QR")` 纯文字按钮替代

### 25.3 剪贴板导入

**ConfigImporter 导入格式（`data/import/ConfigImporter.kt`）：**
```kotlin
object ConfigImporter {
    data class ImportResult(
        val sessions: List<Session> = emptyList(),
        val error: String? = null,
    )
    
    fun parse(input: String): ImportResult {
        // 去掉可选的 SSHCONF: 前缀
        val raw = input.trimStart("SSHCONF:")
        val json = String(Base64.decode(raw, Base64.DEFAULT))
        // 解析紧凑 JSON → Session 列表
    }
    
    fun encode(sessions: List<Session>): String  // JSON → base64
    fun encodeWithPrefix(sessions: List<Session>): String = "SSHCONF:$encode"
}
```

**紧凑 JSON 格式（短键名以减小 QR 码密度）：**
```json
{
  "v": 1,
  "s": [
    {
      "n": "SessionName",
      "h": "192.168.1.1",
      "p": 22,
      "u": "root",
      "a": "PASSWORD",
      "pw": "password",
      "g": "Group/Subgroup"
    }
  ]
}
```

字段名缩写：`n`=name, `h`=hostname, `p`=port, `u`=username, `a`=authType,
`pw`=password, `g`=groupName, `k`=identityFile, `j`=jumpHost

**HomeScreen UI：**
- 顶部栏最右侧：`Text("QR")` 按钮（启动扫描）
- 顶部栏右侧第二：`Text("⎀")` 按钮（弹出粘贴对话框）
- 对话框内包含 `Text("📋 ")" 大按钮 + OutlinedTextField 用于手动粘贴
- 导入结果通过 Snackbar 显示（`SnackbarHost` + `LaunchedEffect` 监听 statusMessage）

### 25.4 经验

- Material Icons 核心集图标非常有限。遇到缺失图标时，
  用文字（`Text("QR")`）或 Unicode 符号（`Text("⎀")`）替代，
  比引入额外图标库更轻量。
- QR 码数据密度有限，JSON 键名尽量短（单字母或双字母），
  减少 base64 编码后字符串长度，降低 QR 码复杂度。
- `SSHCONF:` 前缀作为魔数标识，方便 `ScanContract` 自动识别
  并路由到导入逻辑。即使从剪贴板粘贴，去掉前缀也很简单。
- `rememberLauncherForActivityResult` 返回的结果在 Compose
  lifecycle 中可能被多次调用，确保 `viewModel.importFromBase64()`
  是幂等的（Room `@Insert` 的 `OnConflictStrategy.REPLACE` 已保证）。

---

## 26. SCRT 配置导出工具（Windows）

### 26.1 工具概述

`scrt_exp.py` — Windows 下交互式 CLI 工具，读取本地 SecureCRT 配置，
支持搜索、多选会话，导出为 QR 码或 base64 字符串供手机端 APP 导入。

**路径：** `D:\perl_wrk\SCRT\scrt_exp.py`

**依赖：**
```
pip install pycryptodome qrcode[pil]
```

**启动：**
```
python scrt_exp.py
python scrt_exp.py "D:\SecureCRT\Config"  # 手动指定配置目录
```

### 26.2 核心模块

**配置路径发现：**
```python
# 1. 默认路径：%LOCALAPPDATA%\VanDyke Software\Config\Sessions
# 2. 回退路径：D:\SecureCRT9.6\Config、C:\ProgramData\... 等
SCRT_BASE = os.path.join(os.environ['LOCALAPPDATA'],
                         'VanDyke Software', 'Config')
SESSIONS_DIR = os.path.join(SCRT_BASE, 'Sessions') if SCRT_BASE else None
```

**INI 解析：** 复用现有 `parse_ini()` 函数，提取 `[SSH2] Hostname`、
`[SSH2] Port`（hex 16-bit）、`[SSH2] Username`、`[SSH2] Password` 等。

**密码解密：** 复用 `securecrt_cipher.py`（V1/V2/V3 三种加密方案）：
- V1: AES-128-CBC + SHA1 KDF（旧版）
- V2: AES-192-CBC + SHA1 KDF
- V3: AES-256-CBC + bcrypt_pbkdf2（当前默认）

**交互式 CLI：**
- 实时搜索过滤（输入文本即时匹配会话名/主机/用户名/组名）
- 编号多选（输入数字切换选中状态，空格键切换当前高亮）
- 全选/取消全选（A/N 键）
- PgUp/PgDn 翻页浏览（每页 20 条）
- 选中计数实时显示

**4 种导出模式：**
1. **QR ASCII** — 终端内显示 QR 码，手机对着屏幕扫描
2. **QR PNG** — 保存为 PNG 图片文件
3. **Base64 打印** — 打印到终端，手动传输
4. **Base64 剪贴板** — 通过 `clip.exe`（Windows 自带）写入剪贴板，
   配合跨设备剪贴板共享（SwiftKey / KDE Connect）直接粘贴到手机

### 26.3 交互流程

```
=================================================================
  SCRT Config Export Tool
  576 sessions loaded  |  3 selected
=================================================================
  Search: 10.10.
  ─────────────────────────────────────────────────────────────────
  [✓] [  0] 10.10.10.250               root          @10.10.10.250:22
  [✓] [  1] 10.10.10.53                administrator @10.10.10.53:22 **
  [✓] [  3] 10.10.30.145               root          @10.10.30.145:22 **
  ─────────────────────────────────────────────────────────────────
  Showing 3 of 576 filtered

  Commands:
    <number>  Toggle selection   [Space] Toggle selection
    [A] Select all filtered      [N] Deselect all filtered
    [S] Type search filter       [Esc] Clear search
    [PgUp/PgDn] Scroll           [Q] EXPORT → choose mode
    [X] Exit without export
  >
```

选择 Q 后进入导出模式选择，选定模式后立即输出。

### 26.4 关键细节

**Python `global` 声明位置：**
在 `main()` 函数中通过命令行参数覆盖 `SESSIONS_DIR` 时，
必须在函数顶部声明 `global SESSIONS_DIR`，不能在使用之后再声明。
Python 语法规定：同一作用域内对全局变量第一次引用之前必须有 `global` 声明。

```python
# 正确
def main():
    global SESSIONS_DIR  # ← 必须在任何引用之前
    if not SESSIONS_DIR or not os.path.isdir(SESSIONS_DIR):
        ...

# 错误 — SyntaxError: name 'SESSIONS_DIR' is used prior to global declaration
def main():
    if not SESSIONS_DIR or not os.path.isdir(SESSIONS_DIR):  # ← 先用了
        ...
    global SESSIONS_DIR  # ← 后声明 ← 语法错误
```

**QR 码容量限制：**
单条会话的 base64 约 100-200 字符，QR 码可容纳。多条会话（如全选 576 条）
base64 可达 50KB+，必须分多张 QR 码输出。当前工具按每 batch 导出模式处理。

**Windows 剪贴板写入：**
```python
subprocess.run(['clip.exe'], input=payload, text=True, check=True)
```
`clip.exe` 是 Windows 自带的剪贴板工具，UTF-8 文本可直接管道输入。

### 26.5 与手机端配合使用

```
PC端 (scrt_exp.py)              手机端 (SSH APK)
  │                                │
  ├─ QR ASCII/PNG ──────────────→ 相机扫描 (ZXing)
  │                                │
  ├─ Base64 剪贴板 ────┬──────→ 跨设备剪贴板 (SwiftKey等)
  │                    └──────→ 手动传输 → 粘贴到对话框
  │                                │
  └─ 都使用 SSHCONF:base64 ──→ ConfigImporter.parse()
                                    │
                                 Room INSERT (REPLACE策略)
```

## 27. 多选批量操作模式

### 27.1 需求

长按目录或主机配置项时进入选择模式，支持多选框、全选/取消全选、批量删除和批量移动，且对目录操作要递归处理其子目录和配置。

### 27.2 HomeUiState 扩展

```kotlin
data class HomeUiState(
    // ...existing fields...
    val selectionMode: Boolean = false,
    val selectedSessionIds: Set<Long> = emptySet(),
    val selectedGroupPaths: Set<String> = emptySet(),
)
```

三个新字段全部放在一个 data class 中，每次 copy 即可触发 Compose 重组。

### 27.3 ViewModel 选择操作

- `enterSelectionMode()` / `exitSelectionMode()` — 进入/退出，清空选中集
- `toggleSessionSelection(id)` / `toggleGroupSelection(path)` — 切换单个条目
- `selectAllFiltered()` — 选中当前筛选可见的所有 session 和 group
- `deselectAll()` — 清空选中

### 27.4 批量删除（递归）

```kotlin
fun batchDelete() {
    // 1. 收集直接选中的 session ID
    // 2. 对每个选中的 group path，调用 getSessionsInGroupTree() 递归获取子目录 session
    // 3. 合并所有 ID，调用 deleteByIds() 一次性删除
    // 4. removeKnownGroupTree() 清理空的已知分组
}
```

核心 DAO 查询：
```kotlin
@Query("SELECT * FROM sessions WHERE group_name = :group OR group_name LIKE :group || '/%'")
suspend fun getSessionsInGroupTree(group: String): List<SessionEntity>
```

### 27.5 批量移动（递归）

```kotlin
fun batchMove(targetGroup: String) {
    // 1. 直接选中的 session → copy(groupName = targetGroup)
    // 2. 选中的 group tree → 逐一修改 groupName 前缀
    //    - 根级: groupPath → targetGroup
    //    - 子级: groupPath/sub → targetGroup/sub（只替换前缀）
    // 3. updateAll() 批量写入
}
```

### 27.6 UI 要点

- 选择模式下 TopBar 替换为：已选计数 + 全选/取消全选 + 删除/移动按钮
- 每个 item 左侧出现 Checkbox，点击切换选中而非导航
- FAB 在选择模式下隐藏
- 长按进入选择模式并同时选中该条目
- 批量移动弹出 Group 列表选择对话框

### 27.7 经验

- `Checkbox` 和 `combinedClickable` 可以共存：checkbox 的 `onCheckedChange` 负责 toggle，Card 的 `onClick` 在 selection mode 下也改为 toggle
- `selectAllFiltered()` 选中 groups 时要根据当前所在目录筛选：根目录选全部 groups，子目录只选当前路径下的子树
- `removeKnownGroupTree()` 需清理 groupPath 及其所有以 `groupPath/` 开头的子分组

## 28. WLAN 同步服务器导入 + version.json 部署踩坑

### 28.1 需求

Windows SCRT GUI 工具启动 HTTP 同步服务器，生成 QR 码供手机扫描，手机通过 WiFi 从 PC 拉取配置导入，无需公网中转。

### 28.2 双端协议约定

- 直接配置 QR：`SSHCONF:base64data...`（原有格式，不变）
- 同步服务器 QR：`SSHCONFSYNC:http://ip:port/sync`（新增前缀）
- 同步服务器 HTTP 响应：`SSHCONF:base64data...`（与直接配置格式相同，复用 parse）

### 28.3 Android 端实现

```kotlin
object ConfigImporter {
    const val SYNC_PREFIX = "SSHCONFSYNC:"

    fun isSyncUrl(input: String): Boolean =
        input.trimStart().startsWith(SYNC_PREFIX)

    suspend fun fetchFromSyncUrl(input: String): ImportResult {
        val rawUrl = input.trimStart().removePrefix(SYNC_PREFIX).trim()
        // HTTP GET → 读取 body → parse(body)
        // 超时: connect 8s, read 10s
    }
}
```

`importFromBase64()` 首先检查 `isSyncUrl()`，是则走 `fetchFromSyncUrl()` 分支。

### 28.4 Windows 端改动

- `sessions_to_export_json()` 新增字段：`sh`(hasSavedPassword), `j`(jumpHost/firewall)
- SyncDialog QR 码内容从 `http://...` 改为 `SSHCONFSYNC:http://...`
- 同步服务器响应体为 `SSHCONF:base64...`（无需改动）

### 28.5 version.json 部署踩坑（重要）

**错误做法：** 通过 SSH `echo` 命令直接写 JSON 到服务器文件：
```bash
ssh server "echo '{\"versionCode\":21,...}' > /path/version.json"
```
这会导致 shell 转义问题：引号被吃掉、`$` 符号被当作变量展开、JSON 键值失去引号变成非法 JSON。

**正确做法：** 先本地写好 version.json 文件，再 SCP 上传：
```bash
scp version.json sunbeatus:/var/www/html/ssh_apk/version.json
```

**教训：**
- 永远不要通过 shell echo 写入 JSON 文件，SCP 才是可靠方式
- `apkUrl` 必须使用 HTTPS 公网域名（`https://www.sunbeatus.com/...`），不能用内部 DDNS 地址

## 29. SSH 密钥认证 + 认证类型 UI 区分

### 29.1 需求

从 Windows SCRT GUI 导出的配置可能包含密钥（PEM 格式），Android 端需：
- 解析 `kc`（key content）字段，存储到数据库
- 使用 trilead-ssh2 的 `authenticateWithPublicKey()` 进行密钥认证
- 在会话列表中显示认证类型图标（🔑 密钥 / 🔑🔒 密钥+密码）
- 添加/编辑对话框支持粘贴 PEM 密钥内容

### 29.2 数据模型变更

```kotlin
// Session.kt
data class Session(
    // ...existing fields...
    val keyContent: String? = null,  // PEM 文本，对应 JSON kc 字段
)

// SessionEntity.kt
@Entity(tableName = "sessions")
data class SessionEntity(
    // ...
    @ColumnInfo(name = "key_content") val keyContent: String? = null,
)
```

### 29.3 Room 版本迁移踩坑

**坑：** 添加 `key_content` 列后，使用 `fallbackToDestructiveMigration()` 仍然崩溃：
```
Room cannot verify the data integrity. Expected identity hash: 9a97..., found: 24a1...
```

**原因：** Room 在 `@Database` 注解中记录了 entity 的 identity hash。即使设置了
`fallbackToDestructiveMigration()`，如果 entity 变了但 `version` 没变，Room 仍然
拒绝打开数据库。

**解决：** 必须同时递增 `@Database(version = N)` 的版本号：
```kotlin
// 之前: version = 1
@Database(entities = [SessionEntity::class], version = 2, exportSchema = false)
```

**教训：** Room 的 `fallbackToDestructiveMigration()` 只处理跨版本迁移（如 v1→v2），
不处理同版本的 schema 变更。每次修改 entity 类必须同步递增 version。

### 29.4 密钥认证实现

trilead-ssh2 的 `Connection` 继承自 trilead-ssh2，提供：
```kotlin
conn.authenticateWithPublicKey(user, File(pemPath), passphrase)
```

需要 PEM 内容写入临时文件（缓存目录），认证后立即删除：
```kotlin
private fun authenticateWithKey(conn: Connection, session: Session): Boolean {
    val keyFile = File.createTempFile("sshkey_", ".pem", appContext.cacheDir)
    try {
        keyFile.writeText(session.keyContent!!)
        return conn.authenticateWithPublicKey(
            session.username, keyFile, session.plainPassword ?: ""
        )
    } finally {
        keyFile.delete()  // 安全清理：无论成功失败都删除
    }
}
```

### 29.5 authType 三种模式

| authType | 尝试顺序 |
|----------|---------|
| PASSWORD | password → keyboard-interactive（兜底）|
| KEY | publickey → keyboard-interactive（兜底）|
| BOTH | publickey → password → keyboard-interactive（逐级兜底）|

### 29.6 UI 变更

- **ConnectionCard**：会话名称右侧显示 🔑（KEY）/ 🔑🔒（BOTH），PASSWORD 不显示图标
- **Add/Edit 对话框**：KEY/BOTH 类型增加多行文本框，粘贴 PEM 私钥内容
- **长按菜单**：长按单个会话弹出上下文菜单（Edit / Delete / Select…），不再直接进入多选模式

### 29.7 经验

- `authenticateWithPublicKey()` 返回 `false` **不等于**抛出异常——返回 false 只是密钥
  不匹配，connection 仍然有效，可以继续尝试其他认证方法
- 临时 PEM 文件必须放在 `appContext.cacheDir`（应用私有缓存目录），权限安全
- 临时文件在 `finally` 块中删除，确保异常路径也不会残留

## 30. 详细连接进度 + keyboard-interactive 认证

### 30.1 连接状态细化

**之前：** `ConnectionState.Connecting` 是一个无参数单例，UI 只显示 "Connecting…"
——用户看到长时间转圈，不知道卡在哪一步。

**修改：** `Connecting` 改为 data class 携带 step 消息：
```kotlin
sealed class ConnectionState {
    data class Connecting(val step: String) : ConnectionState()
    // 各阶段消息:
    // "Connecting to host:port…"       → TCP 连接
    // "SSH handshake OK, authenticating…" → 密钥交换完成
    // "Authenticating as user (method)…"  → 认证中
    // "Opening session, allocating PTY…"  → PTY 分配
    // "Starting shell…"                   → 启动 shell
}
```

UI 中 `state.step` 显示在 CircularProgressIndicator 下方，用户可以看到具体进度。

### 30.2 keyboard-interactive 认证支持

**问题：** 某些服务器（VMware ESXi、部分 Linux 发行版）只接受 `publickey` 和
`keyboard-interactive` 认证方法，**不支持** `password` 方法。即使 SCRT 用密码登录，
实际走的也是 keyboard-interactive 协议（服务器发送 "Password:" 提示，客户端回复密码）。

trilead-ssh2 的 `authenticateWithPassword()` 只发送 SSH `password` 认证请求，
服务器不支持时直接返回 false。

**解决：** 添加 keyboard-interactive 作为密码认证的兜底方案：
```kotlin
private fun tryPassword(conn: Connection, username: String, password: String): Boolean {
    if (conn.authenticateWithPassword(username, password)) return true
    // password 方法不可用 → 尝试 keyboard-interactive
    if (hasKbInteractive(conn, username)) {
        return authenticateWithKeyboardInteractive(conn, username, password)
    }
    return false
}
```

keyboard-interactive 回调实现——简单返回密码到所有提示：
```kotlin
private class PasswordKbCallback(private val password: String) : InteractiveCallback {
    override fun replyToChallenge(
        name: String, instruction: String, numPrompts: Int,
        prompt: Array<String>, echo: BooleanArray,
    ): Array<String> = Array(numPrompts) { password }
}
```

**注意：** `InteractiveCallback.replyToChallenge()` 的参数签名使用 Java 平台类型
（`String!`, `BooleanArray!`），Kotlin 中必须精确匹配，不能用 `String?` / `Array<out Boolean>?`。

### 30.3 BOTH 认证的关键 bug

**原始代码：**
```kotlin
AuthType.BOTH -> {
    if (!kc.isNullOrBlank()) {
        try {
            authenticateWithKey(conn, session)  // 返回 false，不抛异常
        } catch (_: Exception) {
            tryPassword(conn, session.username, password)  // 永远不会执行！
        }
    }
}
```

**bug：** `authenticateWithKey()` 密钥不匹配时返回 `false`（不抛异常），try 块正常结束，
catch 块不触发，`tryPassword()` 永远不会被调用。when 分支返回 false，认证失败。

**修复：**
```kotlin
AuthType.BOTH -> {
    if (!kc.isNullOrBlank()) {
        val keyOk = try { authenticateWithKey(conn, session) } catch (_: Exception) { false }
        if (!keyOk) tryPassword(conn, session.username, password) else true
    } else {
        tryPassword(conn, session.username, password)
    }
}
```

**教训：** 不要假设失败一定会抛异常。很多 Java 库的认证方法通过返回 `boolean`
表示成功/失败，`false` 不等于异常。设计 fallback 链时必须同时处理两种情况：
- 抛异常 → catch 块兜底
- 返回 false → 条件判断兜底
- PowerShell here-string `@"..."@` 会展开变量，写 JSON 时用 `@'...'@` 或直接写文件
