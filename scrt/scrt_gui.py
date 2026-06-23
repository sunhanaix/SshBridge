#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SCRT Config Export Tool (GUI)
==============================
Graphical tool to export SecureCRT sessions for import into SSH APK app.

Select sessions via checkboxes, then export as:
  - QR code (displayed in a popup window)
  - Base64 string (displayed in a popup, copyable)
  - Copy base64 directly to clipboard

Auto-detects SecureCRT config at:
  %%LOCALAPPDATA%%\\VanDyke Software\\Config
  D:\\SecureCRT9.6\\Config (fallback)

Usage: python scrt_gui.py [optional_config_path]

Requirements:
  pip install pycryptodome customtkinter Pillow qrcode[pil]
"""

import sys, os, struct, json, base64, threading, time, socket, winreg

# ── Crypto imports ──────────────────────────────────────────────────
from securecrt_cipher import bcrypt_pbkdf2
from Crypto.Cipher import AES
from Crypto.Hash import SHA256

# ── GUI imports ─────────────────────────────────────────────────────
import customtkinter as ctk
from tkinter import ttk

# Lazy imports (deferred to first use for faster startup):
#   from http.server import HTTPServer, BaseHTTPRequestHandler  → SyncServer
#   import qrcode                                               → QRDialog
#   from PIL import Image                                       → QRDialog
# (PIL.Image is imported by customtkinter already, kept as alias reference)

# ══════════════════════════════════════════════════════════════════════
# Config path detection
# ══════════════════════════════════════════════════════════════════════

def _read_registry_config_path():
    """Read SecureCRT's own Config Path from Windows registry.

    Returns (config_base, sessions_dir) or (None, None).
    """
    # 1) HKCU — SecureCRT's own "Config Path" setting (most authoritative)
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                            r'Software\VanDyke\SecureCRT') as key:
            config_path, _ = winreg.QueryValueEx(key, 'Config Path')
            if config_path:
                sessions = os.path.join(config_path, 'Sessions')
                if os.path.isdir(sessions):
                    return config_path, sessions
    except (OSError, FileNotFoundError):
        pass

    # 2) HKLM — install directory + "Config" subdir
    for subkey in (r'Software\VanDyke\SecureCRT\Install',
                   r'Software\WOW6432Node\VanDyke\SecureCRT\Install'):
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, subkey) as key:
                main_dir, _ = winreg.QueryValueEx(key, 'Main Directory')
                if main_dir:
                    config_path = os.path.join(main_dir, 'Config')
                    sessions = os.path.join(config_path, 'Sessions')
                    if os.path.isdir(sessions):
                        return config_path, sessions
        except (OSError, FileNotFoundError):
            pass

    return None, None


def detect_config_path():
    """Return (config_base, sessions_dir) or (None, None) if not found.

    Detection order:
    1. Windows registry (SecureCRT's own Config Path setting)
    2. %%LOCALAPPDATA%%\\VanDyke Software\\Config (Windows default)
    3. %%APPDATA%% fallback + system-wide ProgramData
    """
    # 1. Registry — SecureCRT's canonical setting
    base, sessions = _read_registry_config_path()
    if sessions:
        return base, sessions

    # 2. Default Windows per-user path
    try:
        local_appdata = os.environ['LOCALAPPDATA']
    except KeyError:
        local_appdata = os.path.expanduser('~')
    default_base = os.path.join(local_appdata, 'VanDyke Software', 'Config')
    if os.path.isdir(os.path.join(default_base, 'Sessions')):
        return default_base, os.path.join(default_base, 'Sessions')

    # 3. Broader fallbacks
    alt_bases = [
        os.path.join(os.environ.get('APPDATA', ''), 'VanDyke Software', 'Config'),
        r'C:\ProgramData\VanDyke Software\Config',
    ]
    for base in alt_bases:
        if not base or not os.path.isdir(base):
            continue
        sessions = os.path.join(base, 'Sessions')
        if os.path.isdir(sessions):
            return base, sessions

    return None, None

# ══════════════════════════════════════════════════════════════════════
# Crypto helpers (reused from scrt_exp.py)
# ══════════════════════════════════════════════════════════════════════

KDF_CACHE = {}
PASSPHRASE = b''  # empty = default config passphrase

def get_cached_kdf(salt):
    if salt not in KDF_CACHE:
        KDF_CACHE[salt] = bcrypt_pbkdf2(PASSPHRASE, bytes.fromhex(salt), 48, 16)
    return KDF_CACHE[salt]

def decrypt_v3(hex_val):
    """Decrypt a V3 (03: prefix) encrypted value. Returns plaintext str or None."""
    raw = bytes.fromhex(hex_val)
    salt = raw[:16]
    ct = raw[16:]
    kdf = get_cached_kdf(salt.hex())
    cipher = AES.new(kdf[:32], AES.MODE_CBC, iv=kdf[32:])
    padded = cipher.decrypt(ct)
    pt_len = struct.unpack('<I', padded[0:4])[0]
    if pt_len < 0 or pt_len > 1024:
        return None
    plaintext = padded[4:4 + pt_len]
    checksum = padded[4 + pt_len:4 + pt_len + 32]
    expected = SHA256.new(plaintext).digest()
    if checksum != expected:
        return None
    return plaintext.decode('utf-8')

def decrypt_v2(hex_val):
    """Decrypt a V2 (02: prefix) encrypted value. Returns plaintext str or None."""
    raw = bytes.fromhex(hex_val)
    key = SHA256.new(PASSPHRASE).digest()
    cipher = AES.new(key, AES.MODE_CBC, iv=raw[:16])
    padded = cipher.decrypt(raw[16:])
    pt_len = struct.unpack('<I', padded[0:4])[0]
    if pt_len < 0 or pt_len > 1024:
        return None
    return padded[4:4 + pt_len].decode('utf-8', errors='replace')

# ══════════════════════════════════════════════════════════════════════
# Session cache — avoids re-parsing on subsequent runs
# ══════════════════════════════════════════════════════════════════════

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_FILE = os.path.join(SCRIPT_DIR, '.scrt_sessions_cache.json')

SKIP_FILES = frozenset({
    '__FolderData__.ini', 'Default.ini',
    'Default_LocalShell.ini', 'Default_RDP.ini',
})

def _scan_mtimes(sessions_dir):
    """Collect {dirpath: mtime} for all directories under sessions_dir.

    When a file changes, its parent directory's mtime is updated by the OS
    (NTFS, ext4, APFS).  Checking ~83 directory mtimes is faster than checking
    ~600 file mtimes while still catching any file addition/removal/modification.
    """
    mtimes = {}
    for root, dirs, files in os.walk(sessions_dir):
        mtimes[root] = os.path.getmtime(root)
    return mtimes

def _try_load_cache(sessions_dir):
    """Return (sessions, errors) from cache if valid, else (None, None)."""
    try:
        with open(CACHE_FILE, 'r', encoding='utf-8') as f:
            cache = json.load(f)
    except Exception:
        return None, None

    cached_mtimes = cache.get('_mtimes', {})
    current_mtimes = _scan_mtimes(sessions_dir)
    if cached_mtimes != current_mtimes:
        return None, None

    sessions = cache.get('_sessions', [])
    errors = cache.get('_errors', [])

    # Re-decrypt passwords (fast — AES only, KDF is cached)
    for s in sessions:
        enc = s.pop('_enc', None)
        if enc:
            if enc.startswith('03:'):
                s['password'] = decrypt_v3(enc[3:])
            elif enc.startswith('02:'):
                s['password'] = decrypt_v2(enc[3:])
            else:
                s['password'] = None
        s['has_password'] = s.get('has_password', False)
        # Ensure all keys exist (backward compat with older cache format)
        s.setdefault('description', '')
        s.setdefault('firewall', '')
        s.setdefault('identity_file', '')
        s.setdefault('use_global_key', False)
        s.setdefault('has_key', False)
        s.setdefault('auth_methods', [])
        s.setdefault('pub_key_type', '')

    return sessions, errors

def _save_cache(sessions, errors, sessions_dir):
    """Save sessions (minus passwords) + file mtimes to cache."""
    cache_sessions = []
    for s in sessions:
        entry = dict(s)
        entry['_enc'] = entry.pop('_encrypted', '')
        entry.pop('password', None)
        cache_sessions.append(entry)

    cache = {
        '_sessions': cache_sessions,
        '_errors': errors,
        '_mtimes': _scan_mtimes(sessions_dir),
    }
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f:
            json.dump(cache, f, ensure_ascii=False)
    except Exception:
        pass

# ══════════════════════════════════════════════════════════════════════
# INI parser + session loader (reused from scrt_exp.py)
# ══════════════════════════════════════════════════════════════════════

def parse_ini(content):
    """Parse SecureCRT INI content. Handles S:, D:, B:, Z: type prefixes."""
    result = {}
    current_blob_key = None
    current_blob_lines = []

    for line in content.split('\n'):
        line = line.rstrip('\r')

        if current_blob_key and (line.startswith(' ') or line.startswith('\t')):
            current_blob_lines.append(line.strip())
            continue
        elif current_blob_key:
            result[current_blob_key] = ''.join(current_blob_lines)
            current_blob_key = None
            current_blob_lines = []

        if not line or line.startswith(' '):
            continue
        if '=' not in line:
            continue

        try:
            eq_pos = line.index('=')
            type_key = line[:eq_pos]
            value = line[eq_pos + 1:]

            type_char = type_key[0]
            key_start = type_key.index('"') + 1
            key_end = type_key.index('"', key_start)
            key_name = type_key[key_start:key_end]

            if type_char == 'S':
                result[key_name] = value
            elif type_char == 'D':
                result[key_name] = value
            elif type_char in ('B', 'Z'):
                if value.strip():
                    current_blob_lines = [value.strip()]
                else:
                    current_blob_lines = []
                current_blob_key = key_name
        except (ValueError, IndexError):
            continue

    if current_blob_key:
        result[current_blob_key] = ''.join(current_blob_lines)

    return result

def load_all_sessions(sessions_dir):
    """Walk the SCRT Sessions directory and return (sessions, errors)."""
    sessions = []
    errors = []

    if not os.path.isdir(sessions_dir):
        return sessions, [f"Sessions directory not found: {sessions_dir}"]

    for root, dirs, files in os.walk(sessions_dir):
        for fname in sorted(files):
            if not fname.endswith('.ini'):
                continue
            if fname in SKIP_FILES:
                continue

            fpath = os.path.join(root, fname)
            relpath = os.path.relpath(fpath, sessions_dir)
            folder = os.path.dirname(relpath).replace('\\', '/')
            session_name = relpath.replace('\\', '/').removesuffix('.ini')

            try:
                with open(fpath, 'r', encoding='utf-8-sig', errors='replace') as fh:
                    data = parse_ini(fh.read())
            except Exception as e:
                errors.append(f"{relpath}: parse error: {e}")
                continue

            port_raw = data.get('[SSH2] Port', '') or data.get('Port', '16')
            try:
                port = int(port_raw, 16)
            except ValueError:
                port = 22

            # Auth: password + key
            pwd_saved = data.get('Session Password Saved', '00000000') == '00000001'
            identity_file = data.get('Identity Filename V2', '')
            use_global_key = data.get('Use Global Public Key', '00000000') == '00000001'
            auth_methods = [m.strip() for m in data.get('SSH2 Authentications V2', '').split(',')
                          if m.strip()]
            pub_key_type = data.get('Public Key Type', '')

            has_key = bool(identity_file) or use_global_key
            has_pwd = pwd_saved

            entry = {
                'name': session_name,
                'folder': folder,
                'hostname': data.get('Hostname', ''),
                'port': port,
                'username': data.get('Username', ''),
                'protocol': data.get('Protocol Name', 'SSH2'),
                'has_password': has_pwd,
                'password': None,
                'description': data.get('Description', ''),
                'firewall': data.get('Firewall Name', ''),
                'identity_file': identity_file,
                'use_global_key': use_global_key,
                'has_key': has_key,
                'auth_methods': auth_methods,
                'pub_key_type': pub_key_type,
            }

            pwd_v2 = data.get('Password V2', '')
            entry['_encrypted'] = pwd_v2
            if pwd_v2.startswith('03:'):
                entry['password'] = decrypt_v3(pwd_v2[3:])
            elif pwd_v2.startswith('02:'):
                entry['password'] = decrypt_v2(pwd_v2[3:])

            sessions.append(entry)

    return sessions, errors

# ══════════════════════════════════════════════════════════════════════
# Session classification
# ══════════════════════════════════════════════════════════════════════

NON_NETWORK_PROTOS = frozenset({'Serial', 'serial', 'TAPI', 'tapi', 'RDP', 'rdp'})

def is_exportable(session):
    """Only network sessions with a hostname are exportable for phone use."""
    if session.get('protocol', '') in NON_NETWORK_PROTOS:
        return False
    if not session.get('hostname', '').strip():
        return False
    return True

# ══════════════════════════════════════════════════════════════════════
# Export formatters (reused from scrt_exp.py)
# ══════════════════════════════════════════════════════════════════════

def _resolve_vds_path(path, install_dir, config_dir):
    """Resolve SecureCRT ${VDS_*} variables in a path string."""
    if not path:
        return ''
    for var, val in [('${VDS_INSTALL_PATH}', install_dir),
                     ('${VDS_CONFIG_PATH}', config_dir)]:
        path = path.replace(var, val)
    return os.path.expandvars(path)  # also handle %ENVVAR% on Windows


def _read_key_content(filepath):
    """Read a private key file, return its text content or None."""
    if not filepath or not os.path.isfile(filepath):
        return None
    try:
        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
        if 'PRIVATE KEY' in content and len(content) < 32768:
            return content
    except Exception:
        pass
    return None


def _load_global_identity(config_base):
    """Read SSH2.ini from config_base, resolve and load the global identity key.

    Returns the key content string, or None if not found/readable.
    """
    ssh2_ini = os.path.join(config_base, 'SSH2.ini')
    if not os.path.isfile(ssh2_ini):
        return None

    try:
        data = parse_ini(open(ssh2_ini, 'r', encoding='utf-8-sig',
                              errors='replace').read())
    except Exception:
        return None

    identity_path = data.get('Identity Filename V2', '')
    if not identity_path:
        return None

    # Determine install dir from registry or derive from config path
    install_dir = ''
    try:
        import winreg
        for subkey in (r'Software\VanDyke\SecureCRT\Install',
                       r'Software\WOW6432Node\VanDyke\SecureCRT\Install'):
            try:
                with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, subkey) as key:
                    install_dir, _ = winreg.QueryValueEx(key, 'Main Directory')
                    break
            except (OSError, FileNotFoundError):
                pass
    except Exception:
        pass

    if not install_dir:
        # Derive from config path: .../Config → parent is install dir
        parent = os.path.dirname(config_base)
        if os.path.basename(config_base).lower() == 'config':
            install_dir = parent

    resolved = _resolve_vds_path(identity_path, install_dir, config_base)
    return _read_key_content(resolved)

def _determine_auth_type(s):
    """Return 'PASSWORD', 'KEY', or 'BOTH' based on session auth fields."""
    has_pwd = bool(s.get('password'))
    has_key = s.get('has_key', False)

    if has_pwd and has_key:
        return 'BOTH'
    if has_key:
        return 'KEY'
    return 'PASSWORD'


def sessions_to_export_json(selected, global_key_content=None):
    """Convert selected sessions to the compact JSON format for app import.

    Keys (kept short for compact QR codes):
      n=name, h=hostname, p=port, u=username, a=authType, pw=password,
      g=group/folder, k=identityFile, kc=keyContent, j=jumpHost/firewall

    global_key_content: if provided and a session uses the global key,
      this PEM text is embedded as the 'kc' field.
    """
    arr = []
    for s in selected:
        obj = {
            'n': s['name'],
            'h': s['hostname'],
            'p': s['port'],
            'u': s['username'],
        }
        auth_type = _determine_auth_type(s)
        obj['a'] = auth_type
        if s.get('password'):
            obj['pw'] = s['password']

        # Key identity
        identity = s.get('identity_file', '')
        if identity and auth_type in ('KEY', 'BOTH'):
            obj['k'] = identity
            # Try to read session-specific key
            key_body = _read_key_content(identity)
            if key_body:
                obj['kc'] = key_body
        elif s.get('use_global_key') and auth_type in ('KEY', 'BOTH'):
            if global_key_content:
                obj['kc'] = global_key_content
                obj['k'] = '(global)'
            else:
                obj['k'] = '(global: key not found)'

        if s.get('has_password'):
            obj['sh'] = True
        if s.get('folder'):
            obj['g'] = s['folder']
        fw = s.get('firewall', '')
        if fw and fw != 'None':
            obj['j'] = fw
        arr.append(obj)
    return json.dumps({'v': 1, 's': arr}, ensure_ascii=False, separators=(',', ':'))

def encode_b64(selected, global_key_content=None):
    """Encode selected sessions as base64 string."""
    j = sessions_to_export_json(selected, global_key_content=global_key_content)
    return base64.b64encode(j.encode('utf-8')).decode('ascii')

def encode_with_prefix(selected, global_key_content=None):
    """Encode with SSHCONF: prefix for app recognition."""
    return 'SSHCONF:' + encode_b64(selected, global_key_content=global_key_content)

# ══════════════════════════════════════════════════════════════════════
# WLAN Sync Server — HTTP server for phone sync
# ══════════════════════════════════════════════════════════════════════

SYNC_PORT = 9876

def get_local_ip():
    """Get the LAN IP address of this machine."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def pick_port():
    """Find a free port, starting from SYNC_PORT."""
    for port in range(SYNC_PORT, SYNC_PORT + 100):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.bind(('0.0.0.0', port))
                return port
        except OSError:
            continue
    return SYNC_PORT  # fallback

from http.server import HTTPServer, BaseHTTPRequestHandler  # lazy import

class SyncHTTPHandler(BaseHTTPRequestHandler):
    """Minimal HTTP handler serving the export data."""
    data = b''
    content_type = 'text/plain; charset=utf-8'

    def do_GET(self):
        if self.path in ('/', '/sync'):
            self.send_response(200)
            self.send_header('Content-Type', self.content_type)
            self.send_header('Content-Length', str(len(self.data)))
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(self.data)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass  # suppress server logs

class SyncServer:
    """Manages the HTTP sync server lifecycle."""
    def __init__(self, data_bytes):
        self.data = data_bytes
        self.httpd = None
        self.port = None

    def start(self):
        self.port = pick_port()
        SyncHTTPHandler.data = self.data
        self.httpd = HTTPServer(('0.0.0.0', self.port), SyncHTTPHandler)
        self.httpd.timeout = 2
        t = threading.Thread(target=self._serve, daemon=True)
        t.start()

    def _serve(self):
        try:
            self.httpd.serve_forever()
        except Exception:
            pass

    def stop(self):
        if self.httpd:
            try:
                self.httpd.shutdown()
                self.httpd.server_close()
            except Exception:
                pass
            self.httpd = None

# ══════════════════════════════════════════════════════════════════════
# Sync Server Dialog
# ══════════════════════════════════════════════════════════════════════

class SyncDialog(ctk.CTkToplevel):
    """Popup showing sync server QR code + URL + status."""

    def __init__(self, parent, export_data_str, session_count=0):
        import qrcode  # lazy import
        super().__init__(parent)
        self.title("WLAN Sync Server")
        self.geometry("480x620")
        self.resizable(False, False)
        self.transient(parent)
        self.protocol("WM_DELETE_WINDOW", self._stop_and_close)

        self.parent = parent
        self.server = None
        self._running = True

        # Start server
        try:
            self.server = SyncServer(export_data_str.encode('utf-8'))
            self.server.start()
            url = f"http://{get_local_ip()}:{self.server.port}/sync"
            status = f"Server running on port {self.server.port}"
            status_color = "#66cc66"
        except Exception as e:
            url = ""
            status = f"Failed to start server: {e}"
            status_color = "#ff4444"
            self._running = False

        # QR code of the URL (prefixed so the app detects it as a sync link)
        if self._running:
            qr = qrcode.QRCode(
                version=None,
                error_correction=qrcode.constants.ERROR_CORRECT_M,
                box_size=10,
                border=4,
            )
            # SSHCONFSYNC: prefix tells the phone app to fetch from this URL
            qr.add_data("SSHCONFSYNC:" + url)
            qr.make(fit=True)
            qr_img = qr.make_image(fill_color='black', back_color='white')
            pil_img = qr_img.get_image() if hasattr(qr_img, 'get_image') else qr_img._img
            ctk_img = ctk.CTkImage(light_image=pil_img, dark_image=pil_img, size=(260, 260))
            img_label = ctk.CTkLabel(self, image=ctk_img, text="")
            img_label.pack(pady=(20, 5))

        # Instructions
        instr = ("1. Make sure your phone is on the same WiFi as this PC\n"
                 "2. Scan the QR code with SSH APK (top-bar QR button)\n"
                 "3. The app will auto-detect the sync server and import\n"
                 "4. Or copy the URL below and open in browser")
        ctk.CTkLabel(self, text=instr, font=ctk.CTkFont(size=11),
                     justify="left", wraplength=430).pack(pady=(10, 5))

        # URL display
        if self._running:
            url_frame = ctk.CTkFrame(self, fg_color="transparent")
            url_frame.pack(fill="x", padx=15, pady=5)
            ctk.CTkLabel(url_frame, text="URL:", font=ctk.CTkFont(size=12, weight="bold")).pack(
                side="left", padx=(0, 5))
            url_entry = ctk.CTkEntry(url_frame, font=ctk.CTkFont(size=11, family="Consolas"),
                                     height=28)
            url_entry.insert(0, url)
            url_entry.configure(state="readonly")
            url_entry.pack(side="left", fill="x", expand=True)

        # Copy URL button
        if self._running:
            def copy_url():
                self.clipboard_clear()
                self.clipboard_append(url)
                # Also generate a shareable QR to clipboard if it fits
            ctk.CTkButton(self, text="Copy URL to Clipboard", width=180, height=30,
                          font=ctk.CTkFont(size=11),
                          command=copy_url).pack(pady=(5, 5))

        # Status line
        ctk.CTkLabel(self, text=status, font=ctk.CTkFont(size=13, weight="bold"),
                     text_color=status_color).pack(pady=(5, 10))

        # Data info
        data_len = len(export_data_str) if export_data_str else 0
        ctk.CTkLabel(self, text=f"Export data: {data_len:,} chars | "
                     f"{session_count} sessions",
                     font=ctk.CTkFont(size=10)).pack(pady=(0, 10))

        # Buttons
        btn_frame = ctk.CTkFrame(self, fg_color="transparent")
        btn_frame.pack(pady=10)
        if self._running:
            ctk.CTkButton(btn_frame, text="Stop Server & Close", width=160,
                          command=self._stop_and_close,
                          fg_color="#993333", hover_color="#772222").pack(side="left", padx=5)
        else:
            ctk.CTkButton(btn_frame, text="Close", width=100,
                          command=self.destroy).pack(side="left", padx=5)

        self.grab_set()

    def _stop_and_close(self):
        self._running = False
        if self.server:
            self.server.stop()
        self.destroy()

# ══════════════════════════════════════════════════════════════════════
# QR Dialog
# ══════════════════════════════════════════════════════════════════════

class QRDialog(ctk.CTkToplevel):
    """Popup showing QR code image + base64 text.

    If the data exceeds QR code capacity (version 40), displays an error
    view with alternatives instead of crashing.
    """

    # QR code version 40 capacity with M error correction:
    #   byte mode:  ~2,953 bytes
    #   alnum mode: ~4,296 chars
    # Base64 chars include +/= so qrcode uses byte mode → ~2,900 safe limit
    QR_MAX_BYTES = 2900

    def __init__(self, parent, base64_str):
        super().__init__(parent)
        self.title("QR Code Export")
        self.resizable(False, False)
        self.transient(parent)
        self.grab_set()

        data_len = len(base64_str)

        if data_len > self.QR_MAX_BYTES:
            self._build_oversize_ui(base64_str, data_len)
        else:
            self._build_qr_ui(base64_str)

        self.wait_window()

    def _build_qr_ui(self, base64_str):
        import qrcode  # lazy import
        self.geometry("480x580")

        try:
            qr = qrcode.QRCode(
                version=None,
                error_correction=qrcode.constants.ERROR_CORRECT_M,
                box_size=10,
                border=4,
            )
            qr.add_data(base64_str)
            qr.make(fit=True)
            qr_img = qr.make_image(fill_color='black', back_color='white')
            pil_img = qr_img.get_image() if hasattr(qr_img, 'get_image') else qr_img._img
        except ValueError:
            # Data still too large despite pre-check (edge case)
            self._build_oversize_ui(base64_str, len(base64_str))
            return

        ctk_img = ctk.CTkImage(light_image=pil_img, dark_image=pil_img, size=(280, 280))
        img_label = ctk.CTkLabel(self, image=ctk_img, text="")
        img_label.pack(pady=(20, 10))

        info = ctk.CTkLabel(self, text="Scan with SSH APK app (top bar QR button)",
                            font=ctk.CTkFont(size=12))
        info.pack(pady=(0, 5))

        text_label = ctk.CTkLabel(self, text="Base64 string (select to copy):",
                                  font=ctk.CTkFont(size=11))
        text_label.pack(pady=(10, 2))
        textbox = ctk.CTkTextbox(self, height=60, width=420,
                                 font=ctk.CTkFont(family="Consolas", size=10),
                                 wrap="none")
        textbox.insert("0.0", base64_str)
        textbox.configure(state="disabled")
        textbox.pack(pady=5, padx=10, fill="x")

        char_count = ctk.CTkLabel(self, text=f"{len(base64_str)} characters",
                                  font=ctk.CTkFont(size=10))
        char_count.pack(pady=(0, 5))

        btn_frame = ctk.CTkFrame(self, fg_color="transparent")
        btn_frame.pack(pady=10)

        def copy_base64():
            self.clipboard_clear()
            self.clipboard_append(base64_str)

        ctk.CTkButton(btn_frame, text="Copy to Clipboard", width=140,
                      command=copy_base64).pack(side="left", padx=5)
        ctk.CTkButton(btn_frame, text="Close", width=80,
                      command=self.destroy).pack(side="left", padx=5)

    def _build_oversize_ui(self, base64_str, data_len):
        self.geometry("480x380")

        # Warning icon/header
        warn = ctk.CTkLabel(self, text="⚠ Data Too Large for QR Code",
                            font=ctk.CTkFont(size=16, weight="bold"),
                            text_color="#ff9944")
        warn.pack(pady=(25, 15))

        # Explanation
        limit_kb = self.QR_MAX_BYTES / 1024
        actual_kb = data_len / 1024
        msg = (f"QR codes have a maximum data capacity.\n\n"
               f"Selected data: {data_len:,} chars ({actual_kb:.1f} KB)\n"
               f"QR code limit: ~{self.QR_MAX_BYTES:,} chars ({limit_kb:.1f} KB)\n\n"
               f"Consider selecting fewer sessions, or use\n"
               f"one of the alternatives below:")
        ctk.CTkLabel(self, text=msg, font=ctk.CTkFont(size=12),
                     justify="left", wraplength=420).pack(pady=(5, 15))

        btn_frame = ctk.CTkFrame(self, fg_color="transparent")
        btn_frame.pack(pady=5)

        def show_b64():
            self.destroy()
            Base64Dialog(self.master, base64_str)

        def copy_data():
            self.clipboard_clear()
            self.clipboard_append(base64_str)

        ctk.CTkButton(btn_frame, text="Show as Base64", width=140,
                      command=show_b64).pack(side="left", padx=5)
        ctk.CTkButton(btn_frame, text="Copy to Clipboard", width=140,
                      command=copy_data).pack(side="left", padx=5)

        close_btn = ctk.CTkFrame(self, fg_color="transparent")
        close_btn.pack(pady=(15, 10))
        ctk.CTkButton(close_btn, text="Close", width=80,
                      command=self.destroy).pack()

# ══════════════════════════════════════════════════════════════════════
# Base64 Dialog
# ══════════════════════════════════════════════════════════════════════

class Base64Dialog(ctk.CTkToplevel):
    """Popup showing base64 string with copy button."""

    def __init__(self, parent, base64_str):
        super().__init__(parent)
        self.title("Base64 Export")
        self.geometry("550x360")
        self.resizable(True, True)
        self.minsize(400, 250)
        self.transient(parent)
        self.grab_set()

        header = ctk.CTkLabel(self, text=f"Base64 export ({len(base64_str)} characters):",
                              font=ctk.CTkFont(size=13, weight="bold"))
        header.pack(pady=(15, 5))

        instr = ctk.CTkLabel(self, text="Copy this string and paste into SSH APK app (top bar paste icon)",
                             font=ctk.CTkFont(size=11), wraplength=500)
        instr.pack(pady=(0, 10))

        textbox = ctk.CTkTextbox(self, font=ctk.CTkFont(family="Consolas", size=10),
                                 wrap="none", border_width=1)
        textbox.insert("0.0", base64_str)
        textbox.configure(state="disabled")
        textbox.pack(fill="both", expand=True, padx=15, pady=5)

        btn_frame = ctk.CTkFrame(self, fg_color="transparent")
        btn_frame.pack(pady=10)

        def copy_text():
            self.clipboard_clear()
            self.clipboard_append(base64_str)

        ctk.CTkButton(btn_frame, text="Copy to Clipboard", width=140,
                      command=copy_text).pack(side="left", padx=5)
        ctk.CTkButton(btn_frame, text="Close", width=80,
                      command=self.destroy).pack(side="left", padx=5)

        self.wait_window()

# ══════════════════════════════════════════════════════════════════════
# Main Application
# ══════════════════════════════════════════════════════════════════════

class SCRTApp:
    def __init__(self, sessions_dir_override=None):
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("dark-blue")

        self.app = ctk.CTk()
        self.app.title("SCRT Config Export")
        self.app.geometry("1100x750")
        self.app.minsize(800, 500)

        # State
        self.sessions = []
        self.errors = []
        self.selected_indices = set()
        self.filtered_indices = []
        self._search_after_id = None
        self._detail_index = None
        self._pw_visible = False
        self._sessions_dir = None
        self._config_base = None
        self._global_key_content = None

        # Override
        self._sessions_dir_override = sessions_dir_override

        self._center_window()
        self._build_ui()
        self.app.after(100, self.load_sessions)
        self.app.protocol("WM_DELETE_WINDOW", self._on_close)
        self.app.mainloop()

    # ── Window helpers ──────────────────────────────────────────

    def _center_window(self):
        self.app.update_idletasks()
        w, h = 1100, 750
        sw = self.app.winfo_screenwidth()
        sh = self.app.winfo_screenheight()
        x = (sw - w) // 2
        y = (sh - h) // 2
        self.app.geometry(f"{w}x{h}+{x}+{y}")

    def _on_close(self):
        self.app.destroy()

    # ── UI construction ─────────────────────────────────────────

    def _build_ui(self):
        # ── Search bar ──
        search_frame = ctk.CTkFrame(self.app)
        search_frame.pack(fill="x", padx=10, pady=(10, 0))

        ctk.CTkLabel(search_frame, text="Search:", font=ctk.CTkFont(size=13)).pack(
            side="left", padx=(10, 5))

        self.search_entry = ctk.CTkEntry(search_frame,
                                         placeholder_text="Type to filter sessions...",
                                         height=32, font=ctk.CTkFont(size=13))
        self.search_entry.pack(side="left", fill="x", expand=True, padx=5)
        self.search_entry.bind("<KeyRelease>", self._on_search_key)

        ctk.CTkButton(search_frame, text="Select All", width=90,
                      command=self._on_select_all).pack(side="left", padx=3)
        ctk.CTkButton(search_frame, text="Clear", width=70,
                      command=self._on_clear_all).pack(side="left", padx=3)
        ctk.CTkButton(search_frame, text="− Folders", width=70,
                      command=self._on_collapse_all).pack(side="left", padx=3)
        ctk.CTkButton(search_frame, text="+ Folders", width=70,
                      command=self._on_expand_all).pack(side="left", padx=3)
        ctk.CTkButton(search_frame, text="Export...", width=90,
                      command=self._on_export).pack(side="left", padx=(3, 10))

        # ── Main area ──
        main_frame = ctk.CTkFrame(self.app)
        main_frame.pack(fill="both", expand=True, padx=10, pady=10)

        # Left: session list
        left_panel = ctk.CTkFrame(main_frame)
        left_panel.pack(side="left", fill="both", expand=True)

        self._build_treeview(left_panel)

        # Right: scrollable detail + export panel
        right_panel = ctk.CTkScrollableFrame(main_frame, width=315,
                                             scrollbar_button_color="#555555",
                                             scrollbar_button_hover_color="#777777")
        right_panel.pack(side="right", fill="y", padx=(10, 0))

        # ── Detail section ──
        detail_frame = ctk.CTkFrame(right_panel)
        detail_frame.pack(fill="x", padx=8, pady=(8, 5))

        ctk.CTkLabel(detail_frame, text="Session Details",
                     font=ctk.CTkFont(size=14, weight="bold")).pack(pady=(6, 6))

        self.detail_labels = {}
        fields = [
            ('name', 'Name:'),
            ('folder', 'Folder:'),
            ('hostname', 'Host:'),
            ('port', 'Port:'),
            ('username', 'User:'),
            ('protocol', 'Proto:'),
            ('firewall', 'F/W:'),
            ('password', 'Pwd:'),
        ]
        for key, label in fields:
            lbl = ctk.CTkLabel(detail_frame, text=f"{label}",
                               font=ctk.CTkFont(size=11),
                               anchor="w", justify="left")
            lbl.pack(fill="x", padx=12, pady=1)
            self.detail_labels[key] = lbl

        self.pw_btn = ctk.CTkButton(detail_frame, text="Show Password",
                                    width=120, height=22, font=ctk.CTkFont(size=11),
                                    command=self._toggle_pw_visible)
        self.pw_btn.pack(pady=(4, 6))

        # ── Export section ──
        export_frame = ctk.CTkFrame(right_panel)
        export_frame.pack(fill="x", padx=8, pady=(0, 8))

        ctk.CTkLabel(export_frame, text="Export",
                     font=ctk.CTkFont(size=14, weight="bold")).pack(pady=(6, 6))

        ctk.CTkButton(export_frame, text="QR Code", height=32,
                      command=self._on_export_qr).pack(fill="x", padx=12, pady=3)
        ctk.CTkButton(export_frame, text="Show Base64", height=32,
                      command=self._on_export_b64_show).pack(fill="x", padx=12, pady=3)
        ctk.CTkButton(export_frame, text="Copy to Clipboard", height=32,
                      command=self._on_export_clipboard).pack(fill="x", padx=12, pady=3)

        ctk.CTkLabel(export_frame, text="").pack()
        ctk.CTkLabel(export_frame, text="Phone Sync",
                     font=ctk.CTkFont(size=14, weight="bold")).pack(pady=(2, 4))

        ctk.CTkButton(export_frame, text="Start Sync Server", height=34,
                      command=self._on_sync_server,
                      fg_color="#2b6b3a", hover_color="#1f4f2a").pack(
                          fill="x", padx=12, pady=(2, 6))

        # ── Status bar ──
        status_frame = ctk.CTkFrame(self.app, height=28)
        status_frame.pack(fill="x", padx=10, pady=(0, 8))
        status_frame.pack_propagate(False)

        self.status_label = ctk.CTkLabel(status_frame, text="Initializing...",
                                         font=ctk.CTkFont(size=11),
                                         anchor="w")
        self.status_label.pack(side="left", padx=10, pady=2)

        self.progress = ctk.CTkProgressBar(status_frame, width=150, mode="indeterminate")
        self.progress.pack(side="right", padx=10, pady=4)
        self.progress.start()

    def _build_treeview(self, parent):
        """Create and style the ttk.Treeview (native, fast)."""
        style = ttk.Style()
        style.theme_use('clam')  # most customizable on Windows

        bg = '#2b2b2b'
        fg = '#dcdcdc'
        sel_bg = '#1a5fb4'
        sel_fg = '#ffffff'
        gray = '#666666'
        folder_fg = '#6ba4d9'

        style.configure('Treeview',
                        background=bg, foreground=fg,
                        fieldbackground=bg, borderwidth=0,
                        rowheight=26, font=('Consolas', 11))
        style.map('Treeview',
                  background=[('selected', sel_bg)],
                  foreground=[('selected', sel_fg)])

        # Remove the tree column indicator (lines/dots) for cleaner look
        style.layout('Treeview', [
            ('Treeview.treearea', {'sticky': 'nswe'})
        ])

        self.tree = ttk.Treeview(parent, show='tree', selectmode='none',
                                 columns=(), padding=(6, 2))
        self.tree.pack(fill='both', expand=True, padx=2, pady=2)

        # Tags for styling
        self.tree.tag_configure('grayed', foreground=gray)
        self.tree.tag_configure('folder', foreground=folder_fg,
                                font=('Consolas', 11, 'bold'))

        # Bindings
        self.tree.bind('<ButtonRelease-1>', self._on_tree_click)

        # Scrollbar
        vsb = ttk.Scrollbar(parent, orient='vertical', command=self.tree.yview)
        vsb.pack(side='right', fill='y')
        self.tree.configure(yscrollcommand=vsb.set)

    # ── Session loading ─────────────────────────────────────────

    def load_sessions(self):
        """Detect config path and load sessions with cache + threading."""
        if self._sessions_dir_override:
            sessions_dir = self._sessions_dir_override
            if not os.path.isdir(sessions_dir):
                self._abort_load(f"Sessions directory not found:\n{sessions_dir}")
                return
        else:
            base, sessions_dir = detect_config_path()
            if not sessions_dir:
                sessions_dir = self._browse_config_path()
                if not sessions_dir:
                    self._abort_load(
                        "SecureCRT configuration not found.\n\n"
                        "Checked:\n"
                        "  • Windows registry (VanDyke Software key)\n"
                        "  • %LOCALAPPDATA%\\VanDyke Software\\Config\n"
                        "  • %APPDATA%\\VanDyke Software\\Config\n"
                        "  • C:\\ProgramData\\VanDyke Software\\Config\n"
                        "\nTip: you can also specify a path on the command line:\n"
                        "  python scrt_gui.py D:\\path\\to\\Config")
                    return

        # Save config base for key resolution
        if base and not self._config_base:
            self._config_base = base
        elif not self._config_base:
            self._config_base = os.path.dirname(sessions_dir)

        # Always load in background thread for responsive UI
        self._sessions_dir = sessions_dir
        self.status_label.configure(
            text=f"Loading sessions from {sessions_dir} ...")
        self.progress.start()
        self._pending_load_result = None

        def _load_worker():
            t0 = time.time()
            # Try cache first
            sessions, errors = _try_load_cache(sessions_dir)
            if sessions is not None:
                cached = True
            else:
                sessions, errors = load_all_sessions(sessions_dir)
                cached = False
            elapsed = time.time() - t0
            # Stash result for main-thread polling (avoids Tcl thread-safety issues)
            self._pending_load_result = (sessions, errors, sessions_dir,
                                         elapsed, cached)

        threading.Thread(target=_load_worker, daemon=True).start()
        self._poll_load_result()

    def _poll_load_result(self):
        """Poll for background load completion every 50ms (main-thread safe)."""
        if self._pending_load_result is not None:
            sessions, errors, sessions_dir, elapsed, cached = self._pending_load_result
            self._pending_load_result = None
            self._on_sessions_loaded(sessions, errors, sessions_dir, elapsed,
                                     cached=cached)
        else:
            self.app.after(50, self._poll_load_result)

    def _abort_load(self, msg):
        self.progress.stop()
        self.progress.pack_forget()
        self.status_label.configure(text="Failed to load sessions")
        self._show_error(msg)

    def _on_sessions_loaded(self, sessions, errors, sessions_dir, elapsed, cached=False):
        self.progress.stop()
        self.progress.pack_forget()

        if not sessions:
            self.status_label.configure(text="No sessions found")
            self.tree.insert('', 'end', text=f"No sessions found in: {sessions_dir}")
            return

        self.sessions = sessions
        self.errors = errors
        self.filtered_indices = list(range(len(sessions)))

        with_pw = sum(1 for s in sessions if s.get('password'))
        tag = " (cached)" if cached else f" ({elapsed:.1f}s)"
        self.status_label.configure(
            text=f"{len(sessions)} sessions, {with_pw} passwords, {len(errors)} errors{tag}")

        self._populate_session_list()
        self._update_status()

        # Load global identity key (background, non-blocking)
        config_base = self._config_base
        if config_base:
            threading.Thread(target=self._load_global_key_bg,
                             args=(config_base,), daemon=True).start()

        # Save cache for next run (background)
        if not cached:
            threading.Thread(target=_save_cache,
                             args=(sessions, errors, sessions_dir),
                             daemon=True).start()

    def _load_global_key_bg(self, config_base):
        """Load global identity key in background, then stash result."""
        key = _load_global_identity(config_base)
        self._global_key_content = key

    def _browse_config_path(self):
        """Ask user to browse for the SecureCRT Config folder via native dialog."""
        from tkinter import filedialog

        # Temporarily lower topmost so the native dialog appears on top
        try:
            self.app.attributes('-topmost', False)
        except Exception:
            pass

        path = filedialog.askdirectory(
            parent=self.app,
            title="Select SecureCRT Config Folder",
            mustexist=True,
        )

        try:
            self.app.attributes('-topmost', True)
        except Exception:
            pass

        if not path:
            return None

        # User may have selected the Config folder, or the Sessions sub-folder
        sessions = os.path.join(path, 'Sessions')
        if os.path.isdir(sessions):
            return sessions
        if os.path.isdir(path):
            return path
        return None

    # ── Session list ────────────────────────────────────────────

    # ── Treeview rendering (native, fast) ────────────────────────

    def _folder_stats_for(self, path):
        """Return (total, pwd, key, sel) for sessions under a folder path."""
        prefix = path + '/'
        total, pwd, key, sel = 0, 0, 0, 0
        for idx in self.filtered_indices:
            s = self.sessions[idx]
            f = s.get('folder', '') or ''
            if f == path or f.startswith(prefix):
                total += 1
                if s.get('password'):
                    pwd += 1
                if s.get('has_key'):
                    key += 1
                if idx in self.selected_indices:
                    sel += 1
        return total, pwd, key, sel

    def _populate_session_list(self):
        """Populate Treeview from filtered_indices (instant — native widget)."""
        # Remember expanded folders
        expanded = set()
        for item in self.tree.get_children(''):
            self._save_expanded(item, expanded)
        # If search is active, expand matching folders
        q = self.search_entry.get().strip().lower()
        if q:
            expanded.update(self._matching_folders(q))

        self.tree.delete(*self.tree.get_children(''))

        if not self.filtered_indices:
            self._update_status()
            return

        # Group by folder
        tree = {}
        for idx in self.filtered_indices:
            s = self.sessions[idx]
            f = s.get('folder', '') or ''
            if f not in tree:
                tree[f] = []
            tree[f].append(idx)

        # Sort folders, sessions
        for f in sorted(tree.keys()):
            if f == '':
                # Root sessions — insert directly
                for idx in sorted(tree[f],
                                  key=lambda i: self.sessions[i]['name'].lower()):
                    self._insert_session(idx, parent='')
                continue

            # Ensure parent path chain exists
            parts = f.split('/')
            for i in range(len(parts)):
                sub = '/'.join(parts[:i + 1])
                if not self.tree.exists(sub):
                    parent = '/'.join(parts[:i]) if i > 0 else ''
                    self._insert_folder(sub, parent)

            # Insert sessions under the folder
            for idx in sorted(tree[f],
                              key=lambda i: self.sessions[i]['name'].lower()):
                self._insert_session(idx, parent=f)

        # Restore expanded state; if search active expand matching
        for item in expanded:
            if self.tree.exists(item):
                self.tree.item(item, open=True)

        self._update_status()

    def _insert_folder(self, path, parent):
        name = path.split('/')[-1]
        total, pwd, key, sel = self._folder_stats_for(path)
        cb = '☑ ' if sel == total and total > 0 else '☐ '
        text = f"{cb}{name}  [{total} sessions"
        if pwd:
            text += f", {pwd} pwd"
        if key:
            text += f", {key} key"
        if sel:
            text += f", {sel} sel"
        text += "]"
        self.tree.insert(parent, 'end', iid=path, text=text, tags=('folder',), open=False)

    def _insert_session(self, idx, parent):
        s = self.sessions[idx]
        valid = is_exportable(s)
        iid = f's_{idx}'

        cb = '☑ ' if idx in self.selected_indices else '☐ '
        if not valid:
            cb = '  '

        # Compact auth indicator: P=password, K=key
        auth = ''
        if valid:
            has_pwd = bool(s.get('password'))
            has_key = s.get('has_key', False)
            if has_pwd and has_key:
                auth = '  BOTH'
            elif has_pwd:
                auth = '  Pwd '
            elif has_key:
                auth = '  Key '
        kind_flag = '' if valid else f' [{s.get("protocol", "?")}]'
        name = s['name']
        if len(name) > 38:
            name = name[:35] + '...'
        user = s.get('username', '')
        if len(user) > 12:
            user = user[:10] + '..'
        host = s['hostname']
        if len(host) > 24:
            host = host[:22] + '..'
        text = f"{cb}{name:<38s}  {user:>12s}@{host}:{s['port']}{auth}{kind_flag}"

        tags = ()
        if not valid:
            tags = ('grayed',)
        self.tree.insert(parent, 'end', iid=iid, text=text, tags=tags)

    def _save_expanded(self, item, expanded):
        if self.tree.item(item, 'open'):
            expanded.add(item)
        for child in self.tree.get_children(item):
            self._save_expanded(child, expanded)

    def _matching_folders(self, query):
        paths = set()
        for idx in self.filtered_indices:
            s = self.sessions[idx]
            if (query in s['name'].lower() or query in s['hostname'].lower()
                    or query in s.get('username', '').lower()
                    or query in s.get('folder', '').lower()):
                f = s.get('folder', '') or ''
                if f:
                    parts = f.split('/')
                    for i in range(len(parts)):
                        paths.add('/'.join(parts[:i + 1]))
        return paths

    def _on_tree_click(self, event):
        """Handle single click: toggle session + show detail, or toggle folder.

        Uses identify_element to distinguish expand/collapse arrow clicks
        ('Treeitem.indicator') from row-text clicks ('text').
        """
        elem = self.tree.identify_element(event.x, event.y)
        if elem == 'Treeitem.indicator':
            return  # let native expand/collapse handle it

        iid = self.tree.focus()
        if not iid:
            return
        if iid.startswith('s_'):
            idx = int(iid[2:])
            self._toggle_session(idx)
            self._show_detail(idx)
            self._update_status()
        else:
            # Folder row text clicked — bulk toggle all sessions under it
            self._toggle_folder_selection(iid)

    def _toggle_session(self, idx):
        s = self.sessions[idx]
        if not is_exportable(s):
            return
        if idx in self.selected_indices:
            self.selected_indices.discard(idx)
        else:
            self.selected_indices.add(idx)
        # Update row text
        iid = f's_{idx}'
        if self.tree.exists(iid):
            cb = '☑ ' if idx in self.selected_indices else '☐ '
            text = self.tree.item(iid, 'text')
            self.tree.item(iid, text=cb + text[2:])
        # Update parent folder label
        folder = s.get('folder', '')
        if folder:
            self._refresh_folder_labels(folder)

    def _show_detail(self, idx):
        """Update the right detail panel."""
        self._detail_index = idx
        self._pw_visible = False
        s = self.sessions[idx]

        def _fmt(val, default='-'):
            return val if val else default

        self.detail_labels['name'].configure(
            text=f"Name:  {_fmt(s.get('name'))}")
        self.detail_labels['folder'].configure(
            text=f"Folder: {_fmt(s.get('folder'))}")
        self.detail_labels['hostname'].configure(
            text=f"Host:  {_fmt(s.get('hostname'))}")
        self.detail_labels['port'].configure(
            text=f"Port:  {_fmt(str(s.get('port', '')))}")
        self.detail_labels['username'].configure(
            text=f"User:  {_fmt(s.get('username'))}")
        self.detail_labels['protocol'].configure(
            text=f"Proto: {_fmt(s.get('protocol'))}")
        self.detail_labels['firewall'].configure(
            text=f"F/W:   {_fmt(s.get('firewall'))}")

        # Auth type (shown inline with protocol)
        auth_type = _determine_auth_type(s)
        if auth_type == 'BOTH':
            auth_text = 'Password + Key'
        elif auth_type == 'KEY':
            auth_text = 'Public Key'
        else:
            auth_text = 'Password'

        # Key info (on firewall line when relevant)
        identity = s.get('identity_file', '')
        fw = s.get('firewall', '')
        if identity:
            fw_text = f"Key:   {identity}"
        elif s.get('use_global_key') and auth_type in ('KEY', 'BOTH'):
            fw_text = "Key:   (global)"
        elif auth_type in ('KEY', 'BOTH'):
            fw_text = "Key:   (none)"
        elif fw and fw != 'None':
            fw_text = f"F/W:   {fw}"
        else:
            fw_text = ""

        proto_text = f"Proto: {_fmt(s.get('protocol'))}"
        if auth_type != 'PASSWORD' or s.get('has_key'):
            proto_text += f"  |  Auth: {auth_text}"
        self.detail_labels['protocol'].configure(text=proto_text)

        if fw_text:
            self.detail_labels['firewall'].configure(text=fw_text)
        else:
            self.detail_labels['firewall'].configure(text="")

        # Password
        pwd = s.get('password')
        if pwd:
            self.detail_labels['password'].configure(text="Pwd:   ********")
            self.pw_btn.configure(state="normal", text="Show Password")
        else:
            self.detail_labels['password'].configure(
                text="Pwd:   (not saved)")
            self.pw_btn.configure(state="disabled", text="No Password")

    def _toggle_folder_selection(self, path):
        """Select/deselect all exportable sessions under a folder."""
        prefix = path + '/'
        matching = []
        selected_in = 0
        for idx in self.filtered_indices:
            s = self.sessions[idx]
            f = s.get('folder', '') or ''
            if (f == path or f.startswith(prefix)) and is_exportable(s):
                matching.append(idx)
                if idx in self.selected_indices:
                    selected_in += 1
        if not matching:
            return

        select = selected_in < len(matching) / 2
        for idx in matching:
            if select:
                self.selected_indices.add(idx)
            else:
                self.selected_indices.discard(idx)

        # Update only affected rows (no full rebuild)
        cb_new = '☑ ' if select else '☐ '
        cb_old = '☐ ' if select else '☑ '
        for idx in matching:
            iid = f's_{idx}'
            if self.tree.exists(iid):
                text = self.tree.item(iid, 'text')
                self.tree.item(iid, text=cb_new + text[2:])

        # Update folder and its ancestors
        self._refresh_folder_labels(path)
        self._update_status()

    def _refresh_folder_labels(self, path):
        """Refresh text for a folder and all its ancestors (avoids full rebuild)."""
        # Update the folder itself and walk up to root
        current = path
        while current:
            if self.tree.exists(current):
                total, pwd, key, sel = self._folder_stats_for(current)
                name = current.split('/')[-1]
                cb = '☑ ' if sel == total and total > 0 else '☐ '
                text = f"{cb}{name}  [{total} sessions"
                if pwd:
                    text += f", {pwd} pwd"
                if key:
                    text += f", {key} key"
                if sel:
                    text += f", {sel} sel"
                text += "]"
                self.tree.item(current, text=text)
            # Walk up to parent
            parts = current.rsplit('/', 1)
            current = parts[0] if len(parts) > 1 else ''

    def _update_status(self):
        total = len(self.sessions)
        exportable = sum(1 for s in self.sessions if is_exportable(s))
        skipped = total - exportable
        sel = len(self.selected_indices)
        filt = len(self.filtered_indices)
        errs = len(self.errors)
        with_pw = sum(1 for s in self.sessions if s.get('password') and is_exportable(s))
        with_key = sum(1 for s in self.sessions if s.get('has_key') and is_exportable(s))

        parts = [f"{total} sessions ({exportable} exportable)"]
        if with_pw:
            parts.append(f"{with_pw} pwd")
        if with_key:
            parts.append(f"{with_key} key")
        if skipped:
            parts.append(f"{skipped} grayed")
        if filt not in (0, total):
            parts.append(f"{filt} visible")
        parts.append(f"{sel} selected")
        if errs:
            parts.append(f"{errs} errors")

        text = "  |  ".join(parts)
        self.status_label.configure(text=text)
        if errs:
            self.status_label.configure(text_color="#ff9944")
        else:
            self.status_label.configure(text_color=("gray70", "gray50"))

    # ── Event handlers ──────────────────────────────────────────

    def _on_search_key(self, event):
        if self._search_after_id:
            self.app.after_cancel(self._search_after_id)
        self._search_after_id = self.app.after(200, self._do_filter)

    def _do_filter(self):
        q = self.search_entry.get().strip().lower()
        if not q:
            self.filtered_indices = list(range(len(self.sessions)))
        else:
            self.filtered_indices = [
                i for i, s in enumerate(self.sessions)
                if q in s['name'].lower()
                or q in s['hostname'].lower()
                or q in s.get('username', '').lower()
                or q in s.get('folder', '').lower()
            ]

        self._populate_session_list()
        self._update_status()

    def _toggle_pw_visible(self):
        if self._detail_index is None:
            return
        s = self.sessions[self._detail_index]
        pwd = s.get('password')
        if not pwd:
            return
        self._pw_visible = not self._pw_visible
        if self._pw_visible:
            self.detail_labels['password'].configure(text=f"Pwd:   {pwd}")
            self.pw_btn.configure(text="Hide Password")
        else:
            self.detail_labels['password'].configure(text="Pwd:   ********")
            self.pw_btn.configure(text="Show Password")

    def _on_select_all(self):
        self.selected_indices = {
            i for i in self.filtered_indices
            if is_exportable(self.sessions[i])
        }
        self._populate_session_list()
        self._update_status()

    def _on_clear_all(self):
        self.selected_indices.clear()
        self._populate_session_list()
        self._update_status()

    def _on_collapse_all(self):
        for item in self.tree.get_children(''):
            self._set_all_open(item, False)

    def _on_expand_all(self):
        for item in self.tree.get_children(''):
            self._set_all_open(item, True)

    def _set_all_open(self, item, open_state):
        self.tree.item(item, open=open_state)
        for child in self.tree.get_children(item):
            self._set_all_open(child, open_state)

    def _get_selected_sessions(self):
        return [self.sessions[i] for i in sorted(self.selected_indices)
                if is_exportable(self.sessions[i])]

    def _on_sync_server(self):
        """Start WLAN sync server with ALL exportable sessions."""
        all_exportable = [s for s in self.sessions if is_exportable(s)]
        if not all_exportable:
            self._show_error("No exportable sessions found.")
            return

        # Use SSHCONF:base64 format for compatibility with phone app import
        data = encode_with_prefix(all_exportable, global_key_content=self._global_key_content)
        SyncDialog(self.app, data, len(all_exportable))

    def _on_export(self):
        """Export button — show menu-like choice, then delegate."""
        if not self.selected_indices:
            self._show_error("No sessions selected.\n\n"
                             "Check at least one session in the list first.")
            return
        # Show QR by default (most common use case)
        self._on_export_qr()

    def _on_export_qr(self):
        selected = self._get_selected_sessions()
        if not selected:
            self._show_error("No sessions selected.")
            return
        b64 = encode_with_prefix(selected, global_key_content=self._global_key_content)
        QRDialog(self.app, b64)

    def _on_export_b64_show(self):
        selected = self._get_selected_sessions()
        if not selected:
            self._show_error("No sessions selected.")
            return
        b64 = encode_with_prefix(selected, global_key_content=self._global_key_content)
        Base64Dialog(self.app, b64)

    def _on_export_clipboard(self):
        selected = self._get_selected_sessions()
        if not selected:
            self._show_error("No sessions selected.")
            return
        b64 = encode_with_prefix(selected, global_key_content=self._global_key_content)
        try:
            self.app.clipboard_clear()
            self.app.clipboard_append(b64)
            self._flash_status(f"Copied {len(selected)} session(s) to clipboard!")
        except Exception:
            self._show_error("Clipboard access failed.\n\n"
                             "Use 'Show Base64' to display the string instead.")

    def _flash_status(self, text, duration=3000):
        prev = self.status_label.cget("text")
        self.status_label.configure(text=text, text_color="#66cc66")
        self.app.after(duration, lambda: self._restore_status(prev))

    def _restore_status(self, prev_text):
        self.status_label.configure(text=prev_text)
        self._update_status()

    def _show_error(self, message):
        dlg = ctk.CTkToplevel(self.app)
        dlg.title("Error")
        dlg.geometry("420x180")
        dlg.resizable(False, False)
        dlg.transient(self.app)
        dlg.grab_set()
        ctk.CTkLabel(dlg, text=message, wraplength=380,
                     font=ctk.CTkFont(size=13), justify="left").pack(pady=25)
        ctk.CTkButton(dlg, text="OK", width=80,
                      command=dlg.destroy).pack(pady=(0, 15))
        dlg.wait_window()

# ══════════════════════════════════════════════════════════════════════
# Entry point
# ══════════════════════════════════════════════════════════════════════

def main():
    override = None
    if len(sys.argv) > 1:
        arg = sys.argv[1]
        if os.path.isdir(arg):
            # Could be Config dir or Sessions dir
            sessions = os.path.join(arg, 'Sessions')
            if os.path.isdir(sessions):
                override = sessions
            else:
                override = arg
        else:
            print(f"Warning: path not found: {arg}")
    SCRTApp(sessions_dir_override=override)

if __name__ == '__main__':
    main()
