"""
Export all SecureCRT session configurations with decrypted passwords.
Output: JSON file for migration to custom SSH client.
"""
import sys, os, re, struct, json
from securecrt_cipher import bcrypt_pbkdf2
from Crypto.Cipher import AES
from Crypto.Hash import SHA256

CONFIG_DIR = os.environ.get(
    'SCRT_CONFIG_DIR',
    os.path.join(os.environ.get('LOCALAPPDATA', ''), 'VanDyke Software', 'Config')
)
SESSIONS_DIR = os.path.join(CONFIG_DIR, 'Sessions')
PASSPHRASE = b''  # default/empty config passphrase

# Cache for key derivation (salt -> key+IV)
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
        raise ValueError(f"SHA256 checksum mismatch")
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

def main():
    config_dir = CONFIG_DIR
    sessions_dir = SESSIONS_DIR

    # Parse Global.ini for metadata
    global_ini = os.path.join(config_dir, 'Global.ini')
    global_data = {}
    if os.path.exists(global_ini):
        with open(global_ini, 'r', encoding='utf-8-sig', errors='replace') as fh:
            global_data = parse_ini(fh.read())

    # Check if config passphrase was ever changed
    changed = global_data.get('Change Config Passphrase', '00000000')
    print(f'Config Passphrase changed: {changed != "00000000"}')

    # Verify config passphrase
    cp_enc = global_data.get('Config Passphrase', '')
    if cp_enc.startswith('03:'):
        try:
            cp = decrypt_v3(cp_enc[3:])
            print(f'Config Passphrase value: {repr(cp)} (len={len(cp)})')
        except Exception as e:
            print(f'WARNING: Could not decrypt Config Passphrase: {e}')

    # Export all sessions
    sessions = []
    errors = []
    total_files = 0
    pw_count = 0
    mon_pw_count = 0

    for root, dirs, files in os.walk(sessions_dir):
        for fname in sorted(files):
            if not fname.endswith('.ini'):
                continue
            # Skip internal files
            if fname in ('__FolderData__.ini', 'Default.ini', 'Default_LocalShell.ini', 'Default_RDP.ini'):
                continue

            total_files += 1
            fpath = os.path.join(root, fname)
            relpath = os.path.relpath(fpath, sessions_dir)
            folder = os.path.dirname(relpath).replace('\\', '/')

            try:
                with open(fpath, 'r', encoding='utf-8-sig', errors='replace') as fh:
                    data = parse_ini(fh.read())
            except Exception as e:
                errors.append({'session': relpath, 'error': f'Parse error: {e}'})
                continue

            session_name = relpath.replace('\\', '/').removesuffix('.ini')

            entry = {
                'session': session_name,
                'folder': folder,
                'hostname': data.get('Hostname', ''),
                'port': int(data.get('[SSH2] Port', '16'), 16),
                'username': data.get('Username', ''),
                'protocol': data.get('Protocol Name', 'SSH2'),
                'firewall': data.get('Firewall Name', 'None'),
                'has_saved_password': data.get('Session Password Saved', '00000000') == '00000001',
                'description': data.get('Description', ''),
            }

            # Decrypt Password V2
            pwd_v2 = data.get('Password V2', '')
            if pwd_v2.startswith('03:'):
                try:
                    entry['password'] = decrypt_v3(pwd_v2[3:])
                    pw_count += 1
                except Exception as e:
                    entry['password'] = None
                    entry['password_error'] = str(e)
            elif pwd_v2.startswith('02:'):
                try:
                    from Crypto.Cipher import AES as AES2
                    raw = bytes.fromhex(pwd_v2[3:])
                    iv = raw[:16]
                    ct = raw[16:]
                    key = SHA256.new(b'').digest()
                    cipher = AES2.new(key, AES2.MODE_CBC, iv=iv)
                    padded = cipher.decrypt(ct)
                    pt_len = struct.unpack('<I', padded[0:4])[0]
                    plaintext = padded[4:4+pt_len]
                    entry['password'] = plaintext.decode('utf-8')
                    pw_count += 1
                except Exception as e:
                    entry['password'] = None
                    entry['password_error'] = str(e)

            # Decrypt Monitor Password V2 (often same as login password)
            mon_pwd_v2 = data.get('Monitor Password V2', '')
            if mon_pwd_v2.startswith('03:'):
                try:
                    entry['monitor_password'] = decrypt_v3(mon_pwd_v2[3:])
                    mon_pw_count += 1
                except:
                    pass

            sessions.append(entry)

    # Statistics
    with_pwd = [s for s in sessions if s.get('password')]
    with_saved = [s for s in sessions if s.get('has_saved_password')]
    without_pwd = [s for s in with_saved if not s.get('password')]

    print(f'\nTotal session files: {total_files}')
    print(f'Sessions with saved passwords: {len(with_saved)}')
    print(f'Successfully decrypted passwords: {len(with_pwd)}')
    print(f'Failed decryptions: {len(without_pwd)}')
    print(f'Monitor passwords decrypted: {mon_pw_count}')

    if without_pwd:
        print(f'\nFailed sessions:')
        for s in without_pwd[:10]:
            print(f"  {s['session']}: {s.get('password_error', 'unknown')}")

    # Export to JSON
    output_path = os.path.join(os.path.dirname(__file__) or '.', 'securecrt_sessions_export.json')
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(sessions, f, indent=2, ensure_ascii=False)
    print(f'\nExported {len(sessions)} sessions to: {output_path}')

    # Also print a quick summary table
    print(f'\n--- Session Summary ---')
    for s in with_pwd[:30]:
        pw = s.get('password', '')
        print(f"  {s['session']:50s} | {s['hostname']:20s}:{s['port']:<5d} | {s['username']:20s} | {pw}")

    if len(with_pwd) > 30:
        print(f'  ... and {len(with_pwd) - 30} more')

if __name__ == '__main__':
    main()
