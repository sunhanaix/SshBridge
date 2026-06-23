#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SCRT Config Export Tool
=======================
Interactive CLI tool to export SecureCRT sessions for import into SSH APK app.

Select sessions interactively, then export as:
  - QR code (terminal ASCII or PNG file) for phone camera scan
  - Base64 string for clipboard sharing

Usage: python scrt_exp.py

Requirements:
  pip install pycryptodome qrcode[pil]
  (qrcode is optional — base64 export always works)

Windows notes:
  - Run from CMD or PowerShell with GBK encoding support
  - QR PNG saves to current directory unless --qr-path specified
"""

import sys, os, re, struct, json, base64, textwrap

# ── SCRT crypto imports ──────────────────────────────────────────
from securecrt_cipher import bcrypt_pbkdf2
from Crypto.Cipher import AES
from Crypto.Hash import SHA256

# ── Optional QR library ──────────────────────────────────────────
try:
    import qrcode
    import qrcode.image.svg
    HAS_QRCODE = True
except ImportError:
    HAS_QRCODE = False

# ── Paths ────────────────────────────────────────────────────────
try:
    LOCALAPPDATA = os.environ['LOCALAPPDATA']
except KeyError:
    LOCALAPPDATA = os.path.expanduser('~')
SCRT_BASE = os.path.join(LOCALAPPDATA, 'VanDyke Software', 'Config')
# Fallback: try common paths if default doesn't exist
if not os.path.isdir(SCRT_BASE):
    ALT_PATHS = [
        r'D:\SecureCRT9.6\Config',
        r'C:\ProgramData\VanDyke Software\Config',
        os.path.expanduser(r'~\AppData\Roaming\VanDyke Software\Config'),
    ]
    SCRT_BASE = None
    for p in ALT_PATHS:
        if os.path.isdir(p):
            SCRT_BASE = p
            break

SESSIONS_DIR = os.path.join(SCRT_BASE, 'Sessions') if SCRT_BASE else None
PASSPHRASE = b''

# ── Crypto helpers ───────────────────────────────────────────────

KDF_CACHE = {}

def get_cached_kdf(salt):
    if salt not in KDF_CACHE:
        KDF_CACHE[salt] = bcrypt_pbkdf2(PASSPHRASE, salt, 48, 16)
    return KDF_CACHE[salt]

def decrypt_v3(hex_val):
    raw = bytes.fromhex(hex_val)
    salt = raw[:16]
    ct = raw[16:]
    kdf = get_cached_kdf(salt)
    cipher = AES.new(kdf[:32], AES.MODE_CBC, iv=kdf[32:])
    padded = cipher.decrypt(ct)
    pt_len = struct.unpack('<I', padded[0:4])[0]
    if pt_len < 0 or pt_len > 1024:
        raise ValueError(f"Invalid pt_len: {pt_len}")
    plaintext = padded[4:4+pt_len]
    checksum = padded[4+pt_len:4+pt_len+32]
    expected = SHA256.new(plaintext).digest()
    if checksum != expected:
        raise ValueError("SHA256 checksum mismatch")
    return plaintext.decode('utf-8')

def parse_ini(content):
    """Parse SecureCRT INI content. Handles S:, D:, B:, Z: types."""
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

# ── Session loading ──────────────────────────────────────────────

def load_all_sessions(sessions_dir):
    """Walk the SCRT Sessions directory and return a list of session dicts."""
    sessions = []
    errors = []

    if not os.path.isdir(sessions_dir):
        return sessions, [f"Sessions directory not found: {sessions_dir}"]

    for root, dirs, files in os.walk(sessions_dir):
        for fname in sorted(files):
            if not fname.endswith('.ini'):
                continue
            if fname in ('__FolderData__.ini', 'Default.ini',
                         'Default_LocalShell.ini', 'Default_RDP.ini'):
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

            entry = {
                'name': session_name,
                'folder': folder,
                'hostname': data.get('Hostname', ''),
                'port': int(data.get('[SSH2] Port', '16'), 16),
                'username': data.get('Username', ''),
                'protocol': data.get('Protocol Name', 'SSH2'),
                'has_password': data.get('Session Password Saved', '00000000') == '00000001',
                'password': None,
            }

            pwd_v2 = data.get('Password V2', '')
            if pwd_v2.startswith('03:'):
                try:
                    entry['password'] = decrypt_v3(pwd_v2[3:])
                except Exception:
                    pass
            elif pwd_v2.startswith('02:'):
                try:
                    raw = bytes.fromhex(pwd_v2[3:])
                    key = SHA256.new(b'').digest()
                    cipher = AES.new(key, AES.MODE_CBC, iv=raw[:16])
                    padded = cipher.decrypt(raw[16:])
                    pt_len = struct.unpack('<I', padded[0:4])[0]
                    entry['password'] = padded[4:4+pt_len].decode('utf-8')
                except Exception:
                    pass

            sessions.append(entry)

    return sessions, errors

# ── Export format (compatible with Android app ConfigImporter) ───

def sessions_to_export_json(selected):
    """Convert selected sessions to the compact JSON format."""
    arr = []
    for s in selected:
        obj = {
            'n': s['name'],
            'h': s['hostname'],
            'p': s['port'],
            'u': s['username'],
        }
        # authType
        if s.get('password'):
            obj['a'] = 'PASSWORD'
            obj['pw'] = s['password']
        else:
            obj['a'] = 'KEY'
        if s.get('folder'):
            obj['g'] = s['folder']
        arr.append(obj)
    return json.dumps({'v': 1, 's': arr}, ensure_ascii=False, separators=(',', ':'))

def encode_b64(selected):
    """Encode selected sessions as base64 for app import."""
    j = sessions_to_export_json(selected)
    return base64.b64encode(j.encode('utf-8')).decode('ascii')

def encode_with_prefix(selected):
    return 'SSHCONF:' + encode_b64(selected)

# ── QR code output ───────────────────────────────────────────────

def qr_terminal(data):
    """Display QR code as ASCII art in the terminal."""
    if not HAS_QRCODE:
        print("\n[!] qrcode library not installed. Run: pip install qrcode")
        return False

    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_L,
        box_size=1,
        border=2,
    )
    qr.add_data(data)
    qr.make(fit=True)

    # Use the terminal printer
    try:
        qr.print_ascii(out=sys.stdout)
    except Exception:
        # Fallback: print manually
        qr.print_tty(out=sys.stdout)
    return True

def qr_png(data, path):
    """Save QR code as PNG image file."""
    if not HAS_QRCODE:
        print("\n[!] qrcode library not installed. Run: pip install qrcode[pil]")
        return False

    try:
        from PIL import Image  # noqa: F811
    except ImportError:
        print("\n[!] Pillow not installed. Run: pip install Pillow")
        return False

    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=10,
        border=4,
    )
    qr.add_data(data)
    qr.make(fit=True)
    img = qr.make_image(fill_color='black', back_color='white')
    img.save(path)
    print(f"\nQR code saved to: {path}")
    return True

# ── Clipboard (Windows) ──────────────────────────────────────────

def copy_to_clipboard(text):
    """Copy text to Windows clipboard via clip.exe."""
    try:
        import subprocess
        proc = subprocess.Popen(
            ['clip.exe'],
            stdin=subprocess.PIPE,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        proc.communicate(input=text.encode('gbk', errors='replace'))
        return proc.returncode == 0
    except Exception:
        return False

# ── Interactive UI ───────────────────────────────────────────────

def clear_screen():
    os.system('cls' if os.name == 'nt' else 'clear')

def select_sessions(sessions):
    """Interactive session selector. Returns list of selected session dicts."""
    selected = set()
    search = ''
    page_size = 20
    offset = 0

    # Build index
    indexed = list(enumerate(sessions))

    while True:
        clear_screen()
        print("=" * 72)
        print("  SCRT Config Export Tool")
        print(f"  {len(sessions)} sessions loaded  |  {len(selected)} selected")
        print("=" * 72)

        # Filter
        filtered = indexed
        if search:
            q = search.lower()
            filtered = [(i, s) for i, s in indexed
                        if q in s['name'].lower()
                        or q in s['hostname'].lower()
                        or q in s.get('username', '').lower()
                        or q in s.get('folder', '').lower()]

        print(f"  Search: {search or '<type to filter>'}")
        print(f"  {'─' * 68}")

        # Page
        total = len(filtered)
        if offset >= total:
            offset = max(0, total - page_size)
        page = filtered[offset:offset + page_size]

        for idx, (i, s) in enumerate(page):
            mark = '[x]' if i in selected else '[ ]'
            pw_flag = ' **' if s.get('password') else ''
            line = f"  {mark} [{i:3d}] {s['name'][:50]:50s} {s.get('username',''):16s}@{s['hostname']}:{s['port']}{pw_flag}"
            # Truncate to terminal width
            print(line[:120])

        if total > page_size:
            print(f"  {'─' * 68}")
            print(f"  Showing {offset+1}-{min(offset+page_size, total)} of {total}  "
                  f"(PgUp/PgDn to scroll)")

        print()
        print("  Commands:")
        print("    <number>  Toggle session     [Space] Toggle selection")
        print("    [A] Select all filtered     [N] Deselect all filtered")
        print("    [S] Type search filter      [Esc] Clear search")
        print("    [PgUp/PgDn] Scroll          [Q] EXPORT → choose mode")
        print("    [X] Exit without export")

        # Input
        try:
            cmd = input("\n  > ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nExiting.")
            sys.exit(130)

        if not cmd:
            continue

        # ── Numeric: toggle single session ──
        try:
            num = int(cmd)
            if 0 <= num < len(sessions):
                if num in selected:
                    selected.discard(num)
                else:
                    selected.add(num)
            continue
        except ValueError:
            pass

        cl = cmd.lower()

        # ── Search ──
        if cl in ('s', '/', 'f'):
            try:
                search = input("  Search: ").strip()
            except (EOFError, KeyboardInterrupt):
                pass
            offset = 0
            continue
        elif cl == 'esc' or cl == '\x1b' or cl == '':
            # Actually let's handle ESC differently
            pass

        # ── Clear search ──
        if cl in ('c', 'clear'):
            search = ''
            offset = 0
            continue

        # ── Select all filtered ──
        if cl == 'a':
            for i, _ in filtered:
                selected.add(i)
            continue

        # ── Deselect all ──
        if cl == 'n':
            if search:
                for i, _ in filtered:
                    selected.discard(i)
            else:
                selected.clear()
            continue

        # ── Scroll ──
        if cmd in ('pgup', 'pageup', 'up', 'u'):
            offset = max(0, offset - page_size)
            continue
        if cmd in ('pgdn', 'pagedn', 'pgdown', 'down', 'd'):
            offset = min(total - 1, offset + page_size)
            continue

        # ── Space: toggle current page ──
        if cmd == ' ':
            # Toggle all visible
            visible = [i for i, _ in page]
            all_sel = all(i in selected for i in visible)
            for i in visible:
                if all_sel:
                    selected.discard(i)
                else:
                    selected.add(i)
            continue

        # ── Export ──
        if cl in ('q', 'e', 'export'):
            if not selected:
                print("\n  [!] No sessions selected. Select at least one session first.")
                input("  Press Enter to continue...")
                continue
            result = do_export([sessions[i] for i in sorted(selected)])
            return result

        # ── Exit ──
        if cl in ('x', 'exit', 'quit'):
            print("\nExiting without export.")
            return None

    return None

def do_export(selected):
    """Export sub-menu: choose QR or base64 output."""
    while True:
        clear_screen()
        print("=" * 72)
        print("  Export {0} session{1}".format(len(selected), 's' if len(selected) != 1 else ''))
        print("=" * 72)
        for s in selected[:10]:
            pw = ' ***' if s.get('password') else ''
            print(f"  - {s['name'][:50]:50s} {s.get('username',''):16s}@{s['hostname']}:{s['port']}{pw}")
        if len(selected) > 10:
            print(f"  ... and {len(selected) - 10} more")
        print()
        print("  Export modes:")
        print("    [1] QR code — show in terminal (ASCII art)")
        print("    [2] QR code — save as PNG file")
        print("    [3] Base64 — print string (for manual copy)")

        if os.name == 'nt':
            print("    [4] Base64 — copy to clipboard (via clip.exe)")

        print("    [B] Back to session selection")
        print()

        try:
            cmd = input("  Choice [1-4]: ").strip()
        except (EOFError, KeyboardInterrupt):
            return None

        cl = cmd.lower()
        if cl in ('b', 'back'):
            return None  # Go back to selection

        b64 = encode_b64(selected)
        sshconf = 'SSHCONF:' + b64

        # Show preview of the JSON
        json_str = sessions_to_export_json(selected)
        # Truncate long JSON for display
        if len(json_str) > 200:
            preview = json_str[:200] + '...'
        else:
            preview = json_str

        if cl in ('1', 'qr', 'q'):
            clear_screen()
            print("=" * 72)
            print(f"  QR Code — {len(selected)} session(s)")
            print(f"  Size: {len(b64)} chars base64")
            print("=" * 72)
            print(f"\n  Data preview: {preview}\n")
            qr_terminal(sshconf)
            print()
            input("  Press Enter to continue...")
            return selected

        elif cl in ('2', 'png', 'p'):
            path = input("  Save PNG to [scrt_export_qr.png]: ").strip()
            if not path:
                path = 'scrt_export_qr.png'
            if qr_png(sshconf, path):
                print(f"\n  Scan this QR with the SSH APK app (top bar → QR button).")
            input("\n  Press Enter to continue...")
            return selected

        elif cl in ('3', 'base64', 'b64'):
            clear_screen()
            print("=" * 72)
            print(f"  Base64 Export — {len(selected)} session(s)")
            print("=" * 72)
            print()
            print("  --- Copy everything below this line ---")
            print(sshconf)
            print("  --- Copy everything above this line ---")
            print()
            print("  Paste this into the SSH APK app:")
            print("    Home → top bar [paste icon] → Paste → Import")
            input("\n  Press Enter to continue...")
            return selected

        elif cl in ('4', 'clip', 'c'):
            if copy_to_clipboard(sshconf):
                clear_screen()
                print("=" * 72)
                print("  Copied to clipboard!")
                print("=" * 72)
                print()
                print(f"  {len(selected)} session(s) → clipboard as base64")
                print(f"  Size: {len(sshconf)} characters")
                print()
                print("  On your phone, open SSH APK:")
                print("    Home → top bar [paste icon] → Paste from clipboard → Import")
                print()
                print("  If your phone shares clipboard with this PC (e.g. via")
                print("  Microsoft SwiftKey / KDE Connect / Pushbullet), paste directly.")
                print()
                print("  Otherwise, paste into a messaging app or QR generator to")
                print("  transfer to your phone.")
            else:
                print("  Failed to copy to clipboard. Try option [3] instead.")
            input("\n  Press Enter to continue...")
            return selected

        else:
            print("  Invalid choice.")
            continue

# ── Main ─────────────────────────────────────────────────────────

def main():
    global SESSIONS_DIR

    # Check config path
    if not SCRT_BASE or not SESSIONS_DIR or not os.path.isdir(SESSIONS_DIR):
        print("=" * 72)
        print("  SCRT Config Export Tool — ERROR")
        print("=" * 72)
        print()
        print("  SecureCRT config directory not found.")
        print(f"  Tried: {SCRT_BASE or '(not found)'}")
        print()
        print("  To specify a path manually:")
        print("    python scrt_exp.py D:\\path\\to\\SecureCRT\\Config")
        print()
        if not HAS_QRCODE:
            print("  Also: pip install qrcode[pil]  (for QR code support)")
        sys.exit(1)

    # Optional command-line override
    if len(sys.argv) > 1:
        cfg = sys.argv[1]
        sessions_dir = os.path.join(cfg, 'Sessions')
        if os.path.isdir(sessions_dir):
            SESSIONS_DIR = sessions_dir
        else:
            sessions_dir = cfg  # maybe they passed Sessions dir directly
            if os.path.isdir(sessions_dir):
                SESSIONS_DIR = sessions_dir
            else:
                print(f"ERROR: Not a valid config directory: {cfg}")
                sys.exit(1)

    print("Loading SCRT sessions...")
    sessions, errors = load_all_sessions(SESSIONS_DIR)

    if errors:
        print(f"  {len(errors)} parse errors (skipped)")

    if not sessions:
        print("No sessions found in", SESSIONS_DIR)
        sys.exit(1)

    with_pw = sum(1 for s in sessions if s.get('password'))
    print(f"  Loaded {len(sessions)} sessions ({with_pw} with decrypted passwords)")

    result = select_sessions(sessions)

    if result:
        print(f"\nDone. Exported {len(result)} session(s).")
        print("Import into SSH APK app: Home → top bar buttons (QR or Paste)")
    else:
        print("\nExport cancelled.")

if __name__ == '__main__':
    main()
