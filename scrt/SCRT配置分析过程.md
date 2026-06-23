# SecureCRT 9.6 配置分析过程

## 1. 任务目标

将 SecureCRT 9.6 的 SSH 登录配置迁移到自研 SSH 客户端软件。配置文件位于 `d:\SecureCRT9.6\Config`，其中的密码是加密存储的，需要研究加密方案并导出为可读格式（JSON）。

---

## 2. 配置文件格式分析

### 2.1 INI 变体格式

SecureCRT 使用自定义的类 INI 格式，值带有类型前缀：

| 前缀 | 含义 | 示例 |
|------|------|------|
| `S:` | 字符串 | `S:"Username"=administrator` |
| `D:` | 十六进制 DWORD | `D:"Port"=00000017` (十进制 23，SSH 端口 22 为 00000016) |
| `B:` | 二进制（单行 hex） | `B:"Window Placement"=0000002c ...` |
| `Z:` | 二进制 blob（多行 hex） | 类似 B: 但可跨行 |

### 2.2 关键文件

| 文件 | 说明 |
|------|------|
| `Global.ini` | 全局配置，含加密的 Config Passphrase（452 keys） |
| `Sessions/*.ini` | 会话配置（828 个文件），含加密的登录密码 |
| `Sessions/<子目录>/*.ini` | 按客户/组织分组的会话（83 个文件夹） |
| `Firewalls/*.ini` | 防火墙/代理配置，含加密的代理密码 |
| `Default.ini` | 默认会话模板，含 Login Script |

---

## 3. 加密算法体系

SecureCRT 有三代加密算法：

### 3.1 V1 — "Password" 算法（7.3.3 之前）

- **算法**: 双重 Blowfish-CBC
- **无前缀**，直接 hex 编码
- **密钥**: 两把硬编码的 16 字节密钥
  - Key1 = `24 A6 3D DE 5B D3 B3 82 9C 7E 06 F4 08 16 AA 07`
  - Key2 = `5F B0 45 A2 94 17 D9 16 C6 C6 A2 FF 06 41 82 B7`
- **IV**: 全零 (8 字节)
- **结构**: `cipher1( random4 + cipher2( padded_plaintext ) + random4 )`
- **填充**: 随机字节填充到 Blowfish 块大小 (8 字节) 的倍数
- **空终止符**: UTF-16LE 编码 + `\x00\x00` 终止符

### 3.2 V2 — "Password V2" 算法（7.3.3 引入，前缀 `02:`）

- **算法**: AES-256-CBC
- **前缀**: `02:`
- **密钥**: `SHA256(Config Passphrase)` 直接作为 AES 密钥
- **IV**: 全零 (16 字节)
- **结构**: `[4B LE 长度][明文][32B SHA256(明文)][随机填充]`
- **填充规则**: 最少半个块 (8 字节)
  ```python
  padding_len = 16 - len(lvc) % 16
  if padding_len < 8:
      padding_len += 16
  ```

### 3.3 V3 — "Password V2" 算法（9.x 引入，前缀 `03:`）

- **算法**: AES-256-CBC + bcrypt PBKDF2 密钥派生
- **前缀**: `03:`
- **密钥派生**: `bcrypt_pbkdf2(ConfigPassphrase, salt, 48, 16)`
  - 输出 48 字节: 前 32 字节为 AES 密钥，后 16 字节为 IV
  - 16 轮 PBKDF2 迭代
- **Salt**: 随机 16 字节，作为 `03:` 前缀后的前 16 字节存储
- **结构**: 与 V2 相同（长度 + 明文 + SHA256 + 填充）

### 3.4 bcrypt_hash 实现细节

这是整个分析中最关键也最容易出错的部分：

```python
def bcrypt_hash(password: bytes, salt: bytes) -> bytes:
    # 1. 对 password 和 salt 先做 SHA-512
    password = SHA512.new(password).digest()
    salt = SHA512.new(salt).digest()
    # 2. 使用 PyCryptodome 内部 _bcrypt_hash API
    #    cost=6 (2^6=64 轮 EksBlowfish 密钥调度)
    #    invert=False (先 ExpandKey(salt) 再 ExpandKey(key))
    #    magic=b'OxychromaticBlowfishSwatDynamite' (自定义魔数，非标准 bcrypt)
    digest = KDF._bcrypt_hash(password, 6, salt,
                              b'OxychromaticBlowfishSwatDynamite', False)
    # 3. 每 4 字节反转（字节序变换）
    digest = b''.join(digest[i:i+4][::-1] for i in range(0, len(digest), 4))
    return digest
```

**关键参数**:

| 参数 | 值 | 说明 |
|------|-----|------|
| cost | 6 | EksBlowfish 密钥调度轮数的指数（2^6 = 64） |
| invert | False | 先扩展 salt 再扩展 key（原始 bcrypt 规范） |
| magic | `OxychromaticBlowfishSwatDynamite` | 32 字节自定义魔数，非标准 bcrypt 的 `OrpheanBeholderScryDoubt` |

**与标准 bcrypt 的区别**:
1. 魔数不同：`OxychromaticBlowfishSwatDynamite` vs `OrpheanBeholderScryDoubt`
2. invert 参数不同：`False` vs `True`
3. 输入先做 SHA-512 哈希
4. 输出每 4 字节做反转

### 3.5 bcrypt_pbkdf2 字节提取

```python
BCRYPT_BLOCKS = 8
BCRYPT_HASHSIZE = 32  # 8 * 4

# 输出的提取是"条纹式"的：
# 对于 key_length=48, stride_n=2
# 取字节: 0, 32, 1, 33, 2, 34, 3, 35, ...
#         |-- 块0 --|-- 块1 --|-- 块0 --|-- 块1 --|...
```

---

## 4. Config Passphrase 机制

### 4.1 默认值

当用户未设置 Config Passphrase 时：
- `D:"Change Config Passphrase"=00000000` — 从未修改过
- 默认值为空字符串 `""`
- Config Passphrase 自身也被加密存储在 `Global.ini` 中

### 4.2 自加密

Config Passphrase 用自身作为密码加密：
- 加密: `bcrypt_pbkdf2("", salt, 48, 16)` → AES 加密空字符串
- 解密: 用 `bcrypt_pbkdf2("", salt, 48, 16)` → AES 解密得到 `pt_len=0`
- 明文长度 = 0（确实是空字符串）

### 4.3 验证流程

```
                用户输入 Config Passphrase
                          │
                          ▼
              bcrypt_pbkdf2(PP, salt)
                          │
                    ┌─────┴─────┐
                    ▼           ▼
                  Key         IV
                    │           │
                    └─────┬─────┘
                          ▼
                   AES-CBC 解密
                          │
                          ▼
              ┌───────────────────────┐
              │ [4B len][data][32B    │
              │  SHA256(data)][padding]│
              └───────────────────────┘
                          │
                          ▼
              比较 SHA256(data) 与存储的校验和
              一致 → PP 正确，data 即 Config Passphrase
              不一致 → PP 错误
```

---

## 5. 踩过的坑与关键发现

### 5.1 所有 V3 密文使用相同的 Salt

**现象**: 全部 1640 个 `03:` 前缀的加密值，salt 都是 `1ba3b3825b360ab57f9ae42a89897416`。

**原因**: 当 Config Passphrase 为空时，bcrypt_pbkdf2 的输入固定 (password="", random_salt)，但 VanDyke 的实现似乎使用了某种确定性的 salt 生成方式，或所有密码在 Config Passphrase 设置/修改时被统一重加密。

**影响**: 密钥派生完全确定性，同一个空 passphrase 可解密所有密码。

### 5.2 初始解密失败 — 误判为算法错误

**过程**:
1. 最初用简单的 V2 算法（SHA256(passphrase) 作 key，嵌入 IV）尝试解密 V3 密码 → 完全失败
2. 从 HyperSine GitHub 仓库克隆了权威的 Python 实现
3. 用 bcrypt_pbkdf2 解密 Config Passphrase → `pt_len=0`，结构正确，但 **SHA-256 校验和差 4 字节 (28/32 匹配)**
4. 以为是 PyCryptodome 版本不兼容导致的 bcrypt 实现差异

**最终确认**: 解密实际上是 **正确的**（1640/1640 个 V3 值校验和 32/32 匹配）。早期测试中 Config Passphrase 的 "28/32" 问题可能是测试脚本的小错误，不影响实际解密。

### 5.3 PyCryptodome 版本敏感性

- 当前环境: **PyCryptodome 3.21.0**
- 使用的私有 API: `KDF._bcrypt_hash()` 和 `Crypto.Cipher._EKSBlowfish`
- `_EKSBlowfish` 是 C 扩展（`_raw_eksblowfish`），`invert` 参数的语义:
  - `False`: 先 ExpandKey(salt) 再 ExpandKey(key)
  - `True`: 先 ExpandKey(key) 再 ExpandKey(salt)
- 如果 PyCryptodome 版本不同，`_bcrypt_hash` 的行为可能变化，导致派生密钥不同

### 5.4 PowerShell 编码陷阱

当在 PowerShell 中内联执行 Python 代码时：
- 单引号 `'` 在 PowerShell here-string `@'...'@` 中会导致解析错误
- 中文字符在 GBK 控制台可能显示乱码
- **解决方案**: 将 Python 代码写入 .py 文件再执行，避免内联转义问题

### 5.5 GBK 编码问题

Windows 简体中文环境默认 GBK 编码：
- `print()` 输出包含非 ASCII 字符时会抛出 `UnicodeEncodeError`
- JSON 文件导出必须使用 UTF-8 编码 (`encoding='utf-8'`)
- INI 文件读取使用 `encoding='utf-8-sig'`（处理 BOM）
- 输出到文件而非控制台，避免 GBK 编码错误

### 5.6 会话文件递归扫描

Sessions 目录有 83 个子文件夹（按客户/组织分类），必须用 `os.walk()` 递归扫描，而非仅列举根目录。

---

## 6. 工具脚本清单

| 脚本 | 用途 |
|------|------|
| `hyper_decrypt/python3/securecrt_cipher.py` | **权威实现** — HyperSine 的 SecureCRT 加密/解密库 |
| `securecrt_decrypt.py` | 早期探索版本（简化的 V2 解密，不支持 bcrypt） |
| `export_sessions.py` | **最终导出脚本** — 解密所有会话密码并导出 JSON |
| `analyze_all_encrypted.py` | 分析所有加密值的 salt、长度分布、校验和匹配率 |
| `check_salts.py` | 检查 salt 唯一性 |
| `verify_decrypt.py` | 验证解密正确性 |
| `test_v3_detail.py` | V3 算法细节测试 |
| `test_all_approaches.py` | 系统性测试各种解密方案 |
| `show_results.py` | 显示导出结果摘要 |
| `scrt_exp.py` | **CLI 导出工具** — 命令行版本的会话导出，支持 QR 和 Base64 格式 |
| `scrt_gui.py` | **GUI 导出工具** — 图形界面版本，基于 customtkinter + ttk.Treeview，支持目录树浏览、多选导出、WLAN 同步、二维码/Base64 导出 |

---

## 7. GUI 工具 (scrt_gui.py)

基于 `scrt_exp.py` 的加密和导出逻辑，实现的完整图形界面工具。

### 7.1 主要特性

- **自动检测 SCRT 配置路径**：依次尝试 `%LOCALAPPDATA%\VanDyke Software\Config` → 备用路径 → 手动输入
- **目录树浏览**：按 SCRT 原生的文件夹结构分层展示，可展开/折叠，默认全部折叠
- **会话缓存**：首次解析后的 JSON 缓存 + 文件 mtime 变更检测，缓存命中时载入时间从 ~6s 降至 ~0.2s
- **多选导出**：支持跨目录多选，单击会话行切换选中，单击目录行批量切换整个目录
- **筛选与搜索**：实时搜索过滤（200ms 防抖），搜索时自动展开匹配目录
- **导出格式**：
  - QR Code 二维码（含容量边界检测，超限时显示 Base64 替代方案）
  - Base64 字符串展示（可选择复制）
  - 一键复制到剪贴板
- **WLAN 同步服务器**：启动本地 HTTP 服务器，手机扫码即可同步配置
- **无效配置灰色显示**：Serial/TAPI/无主机名的配置自动灰化，不可选中

### 7.2 技术实现

| 组件 | 技术选型 | 说明 |
|------|---------|------|
| GUI 框架 | customtkinter 5.2.2 | 暗色主题，现代化控件 |
| 会话列表 | ttk.Treeview | 原生控件，单 Widget 替代数百个 CTk 控件，消除 ~29ms/行的创建开销 |
| 加密解密 | PyCryptodome + HyperSine bcrypt_pbkdf2 | 复用 `securecrt_cipher.py`，支持 V2/V3 密码解密 |
| 二维码 | qrcode + Pillow | QR 生成 + CTkImage 显示，版本 40 上限检测 |
| WLAN 同步 | http.server.HTTPServer | 端口 9876，HTTP GET 返回配置数据 |

### 7.3 运行方式

```batch
REM 直接运行（需 Python 3，依赖已安装）
scrt_gui.bat

REM 或手动指定 Python
py -3 d:\perl_wrk\SCRT\scrt_gui.py

REM 可选：指定 SCRT 配置路径
py -3 d:\perl_wrk\SCRT\scrt_gui.py D:\SecureCRT9.6\Config
```

依赖安装：
```bash
pip install pycryptodome customtkinter Pillow qrcode[pil]
```

---

## 8. JSON 导出结果（export_sessions.py）

- **输出文件**: `d:\perl_wrk\SCRT\securecrt_sessions_export.json`
- **总会话数**: 737
- **含已保存密码的会话**: 161（全部成功解密）
- **协议类型**: SSH2, SSH1, Telnet, Serial, TAPI
- **文件夹数**: 83（按客户/组织分类）
- **JSON 编码**: UTF-8

### JSON 结构

```json
{
  "session": "10.10.10.53",
  "folder": "g公司",
  "hostname": "10.10.10.53",
  "port": 23,
  "username": "administrator",
  "protocol": "SSH2",
  "firewall": "None",
  "has_saved_password": true,
  "description": "00000000",
  "password": "P@ssw0rdati1oo7",
  "monitor_password": ""
}
```

---

## 9. 经验总结

1. **先找权威实现**: HyperSine 的 GitHub 仓库是这次分析成功的关键。从零开始逆向加密算法要困难得多。

2. **不要过早下结论**: 校验和 28/32 匹配时以为是算法不兼容，但实际上解密完全正确。微小的测试差异不应成为阻塞点 — 用更多样本验证。

3. **私有 API 风险**: `KDF._bcrypt_hash` 和 `_EKSBlowfish` 是 PyCryptodome 的私有接口，不同版本行为可能不同。生产环境应考虑自行实现 bcrypt 密钥派生以消除依赖。

4. **Salt 去重是关键线索**: 发现全部 1640 个密文共享同一 salt 时，就应该意识到密钥派生是完全确定性的，不需要猜测 Config Passphrase。

5. **编码问题在 Windows 下不可忽视**: GBK/UTF-8 的冲突在跨语言场景（中文路径、中文文件夹名）中频繁出现，务必约定好编码策略。

6. **脚本化导出优先于逆向**: 如果能用 SecureCRT 自身的 COM/脚本 API 导出密码，就不需要折腾加密算法。但离线批量处理的需求使得逆向算法更有价值。
