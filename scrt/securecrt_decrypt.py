"""
Decrypt SecureCRT (VanDyke) encrypted passwords from .ini config files.

Supports both v2 (02:) and v3 (03:) encryption formats used by SecureCRT 7.x - 9.x.

Algorithm:
  - AES-256-CBC
  - IV = first 16 bytes of the encrypted blob
  - Key = SHA-256(Config Passphrase)
  - The "Config Passphrase" defaults to empty string when user hasn't set one,
    and is itself stored encrypted in Global.ini
"""
import os
import sys
import hashlib
import argparse
from pathlib import Path

try:
    from Crypto.Cipher import AES
except ImportError:
    print("Requires pycryptodome: pip install pycryptodome")
    sys.exit(1)


def unpad_pkcs7(data: bytes) -> bytes:
    pad_len = data[-1]
    if pad_len > 16 or pad_len == 0:
        return data  # best-effort, might not be padded
    if data[-pad_len:] == bytes([pad_len]) * pad_len:
        return data[:-pad_len]
    return data


def decrypt_v2(encrypted_hex: str, passphrase: str = "") -> str:
    """Decrypt 02: prefixed values."""
    data = bytes.fromhex(encrypted_hex)
    iv = data[:16]
    ciphertext = data[16:]
    key = hashlib.sha256(passphrase.encode("utf-8")).digest()
    cipher = AES.new(key, AES.MODE_CBC, iv=iv)
    plaintext = cipher.decrypt(ciphertext)
    plaintext = unpad_pkcs7(plaintext)
    return plaintext.decode("utf-8", errors="replace")


def decrypt_v3(encrypted_hex: str, passphrase: str = "") -> str:
    """Decrypt 03: prefixed values. Same algorithm as v2 in practice."""
    return decrypt_v2(encrypted_hex, passphrase)


def decrypt_value(encrypted_str: str, passphrase: str = "") -> str:
    """Auto-detect version and decrypt."""
    if encrypted_str.startswith("02:"):
        return decrypt_v2(encrypted_str[3:], passphrase)
    elif encrypted_str.startswith("03:"):
        return decrypt_v3(encrypted_str[3:], passphrase)
    else:
        raise ValueError(f"Unknown encryption prefix in: {encrypted_str[:20]}...")


def parse_ini_file(filepath: str) -> dict:
    """Parse a SecureCRT .ini file into a dict.
    Handles S: (string), D: (dword), B: (binary), Z: (blob) types.
    """
    result = {}
    current_blob_key = None
    current_blob_lines = []

    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
        lines = f.readlines()

    for line in lines:
        line = line.rstrip("\n\r")

        # Continuation of binary blob (lines starting with whitespace after a B:/Z: key)
        if current_blob_key and (line.startswith(" ") or line.startswith("\t")):
            current_blob_lines.append(line.strip())
            continue
        elif current_blob_key:
            # Finish previous blob
            result[current_blob_key] = "".join(current_blob_lines)
            current_blob_key = None
            current_blob_lines = []

        if not line or line.startswith(" "):
            continue

        # Parse key=value
        if "=" not in line:
            continue

        # Extract type and content
        # Format: <Type>:"<Key>"=<Value>
        # or: <Type>:"<Key>"= (empty)
        # First char is type, then colon
        try:
            eq_pos = line.index("=")
            type_key = line[:eq_pos]
            value = line[eq_pos + 1:]

            # Parse type
            type_char = type_key[0]

            # Extract key name between quotes
            key_start = type_key.index('"') + 1
            key_end = type_key.index('"', key_start)
            key_name = type_key[key_start:key_end]

            if type_char == "S":
                result[key_name] = value
            elif type_char == "D":
                result[key_name] = value  # hex dword as string
            elif type_char == "B" or type_char == "Z":
                if value.strip():
                    current_blob_lines = [value.strip()]
                else:
                    current_blob_lines = []
                current_blob_key = key_name
        except (ValueError, IndexError):
            continue

    # Handle last blob
    if current_blob_key:
        result[current_blob_key] = "".join(current_blob_lines)

    return result


def find_sessions_with_passwords(config_dir: str) -> list[dict]:
    """Find all session files that have saved passwords."""
    sessions_dir = os.path.join(config_dir, "Sessions")
    results = []

    for root, dirs, files in os.walk(sessions_dir):
        for fname in files:
            if not fname.endswith(".ini"):
                continue
            fpath = os.path.join(root, fname)
            try:
                data = parse_ini_file(fpath)
            except Exception:
                continue

            pwd_saved = data.get("Session Password Saved", "00000000")
            if pwd_saved != "00000001":
                continue

            pwd_v2 = data.get("Password V2", "")
            if not pwd_v2:
                continue

            # Relative session name
            rel = os.path.relpath(fpath, sessions_dir)
            session_name = rel.replace("\\", "/").removesuffix(".ini")

            results.append({
                "session": session_name,
                "hostname": data.get("Hostname", ""),
                "port": int(data.get("Port", "22"), 16),
                "username": data.get("Username", ""),
                "protocol": data.get("Protocol Name", "SSH2"),
                "password_v2": pwd_v2,
                "firewall": data.get("Firewall Name", "None"),
                "has_password": True,
            })

    return results


def export_all_sessions(config_dir: str, passphrase: str, decrypt: bool = True) -> list[dict]:
    """Export all sessions with metadata. Decrypts passwords if decrypt=True."""
    sessions_dir = os.path.join(config_dir, "Sessions")
    results = []
    errors = []

    for root, dirs, files in os.walk(sessions_dir):
        for fname in files:
            if not fname.endswith(".ini"):
                continue
            fpath = os.path.join(root, fname)
            try:
                data = parse_ini_file(fpath)
            except Exception as e:
                continue

            rel = os.path.relpath(fpath, sessions_dir)
            session_name = rel.replace("\\", "/").removesuffix(".ini")

            entry = {
                "session": session_name,
                "hostname": data.get("Hostname", ""),
                "port": int(data.get("Port", "16"), 16),
                "username": data.get("Username", ""),
                "protocol": data.get("Protocol Name", "SSH2"),
                "firewall": data.get("Firewall Name", "None"),
                "folder": os.path.dirname(session_name) or "",
                "has_saved_password": data.get("Session Password Saved", "00000000") == "00000001",
            }

            # Try to decrypt password
            pwd_v2 = data.get("Password V2", "")
            if decrypt and pwd_v2 and pwd_v2.startswith(("02:", "03:")):
                try:
                    entry["password"] = decrypt_value(pwd_v2, passphrase)
                except Exception as e:
                    entry["password"] = None
                    entry["password_error"] = str(e)
                    errors.append(f"{session_name}: {e}")
            elif pwd_v2:
                entry["password_encrypted"] = pwd_v2

            results.append(entry)

    if errors:
        print(f"\n[WARNING] {len(errors)} decryption errors (passphrase may be wrong):")
        for err in errors[:5]:
            print(f"  - {err}")
        if len(errors) > 5:
            print(f"  ... and {len(errors) - 5} more")

    return results


def main():
    parser = argparse.ArgumentParser(
        description="Decrypt/export SecureCRT session configurations"
    )
    parser.add_argument(
        "config_dir",
        nargs="?",
        default=r"d:\SecureCRT9.6\Config",
        help="Path to SecureCRT Config directory",
    )
    parser.add_argument(
        "-p", "--passphrase",
        default="",
        help="Config passphrase (default: empty string = machine-default)",
    )
    parser.add_argument(
        "--no-decrypt",
        action="store_true",
        help="Skip password decryption, just export metadata",
    )
    parser.add_argument(
        "--test",
        action="store_true",
        help="Test decryption on a single known value first",
    )
    parser.add_argument(
        "-o", "--output",
        default="",
        help="Output JSON file path",
    )
    parser.add_argument(
        "--show-passwords",
        action="store_true",
        help="Show decrypted passwords in console output",
    )
    args = parser.parse_args()

    config_dir = args.config_dir
    global_ini = os.path.join(config_dir, "Global.ini")

    # Read Global.ini for config passphrase
    global_data = {}
    if os.path.exists(global_ini):
        global_data = parse_ini_file(global_ini)
        print(f"[*] Loaded Global.ini ({len(global_data)} keys)")

    config_passphrase = global_data.get("Config Passphrase", "")
    print(f"[*] Config Passphrase (encrypted): {config_passphrase[:60]}...")

    # Test decryption with a try of the config passphrase itself
    if args.test and config_passphrase.startswith("03:"):
        print("\n[*] Testing decryption of Config Passphrase itself...")
        for test_pass in ["", "VanDyke", "SecureCRT", "password"]:
            try:
                result = decrypt_value(config_passphrase, test_pass)
                print(f"    passphrase='{test_pass}' -> '{result}'")
            except Exception as e:
                print(f"    passphrase='{test_pass}' -> ERROR: {e}")

    # Also try decrypting a sample password
    sample_pwd = "03:1ba3b3825b360ab57f9ae42a898974163e45069fb2d0612e70ce6859aceb0f81a0661dd97bc3c5d1693d43c80f1eb7bdc449c064c0e21d7d92c0f6abfcc6092c238b6058ef221efe4442b8d599dd7372"
    if args.test:
        print("\n[*] Testing decryption of sample Password V2...")
        for test_pass in ["", "VanDyke", "SecureCRT", "password"]:
            try:
                result = decrypt_value(sample_pwd, test_pass)
                print(f"    passphrase='{test_pass}' -> '{result}'")
            except Exception as e:
                print(f"    passphrase='{test_pass}' -> ERROR: {e}")
        return

    # Export all sessions
    decrypt = not args.no_decrypt
    print(f"\n[*] Exporting all sessions from {config_dir} (decrypt={decrypt})...")
    sessions = export_all_sessions(config_dir, args.passphrase, decrypt=decrypt)

    with_pwd = [s for s in sessions if s.get("password")]
    with_saved = [s for s in sessions if s.get("has_saved_password")]
    total = len(sessions)

    print(f"\n{'='*60}")
    print(f"  Total sessions: {total}")
    print(f"  Sessions with saved passwords: {len(with_saved)}")
    print(f"  Successfully decrypted: {len(with_pwd)}")
    print(f"{'='*60}")

    # Print decrypted sessions
    if args.show_passwords:
        print(f"\n--- Sessions with decrypted passwords ---")
        for s in with_pwd:
            print(f"  {s['session']}")
            print(f"    host={s['hostname']}:{s['port']} user={s['username']}")
            print(f"    password={s['password']}")
            print()

    # Print sessions with failed decryption
    failed = [s for s in with_saved if not s.get("password") and s.get("password_error")]
    if failed:
        print(f"\n--- Failed decryptions ({len(failed)}) ---")
        for s in failed:
            print(f"  {s['session']}: {s.get('password_error', 'unknown')}")

    # Save JSON if requested
    if args.output:
        import json
        # Clean up for JSON export
        export_data = []
        for s in sessions:
            entry = {
                "session": s["session"],
                "hostname": s["hostname"],
                "port": s["port"],
                "username": s["username"],
                "protocol": s["protocol"],
                "folder": s["folder"],
                "firewall": s.get("firewall", "None"),
                "has_saved_password": s["has_saved_password"],
            }
            if s.get("password"):
                entry["password"] = s["password"]
            elif s.get("password_encrypted"):
                entry["password_encrypted"] = s["password_encrypted"]
            export_data.append(entry)

        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(export_data, f, indent=2, ensure_ascii=False)
        print(f"\n[*] Exported to {args.output}")

    # Also dump Global.ini passphrase for analysis
    print(f"\n[*] Writing encrypted data analysis to analysis.txt...")
    analysis_path = os.path.join(os.path.dirname(__file__) or ".", "analysis.txt")
    with open(analysis_path, "w", encoding="utf-8") as f:
        f.write("SecureCRT Encryption Analysis\n")
        f.write("=" * 60 + "\n\n")
        f.write(f"Config Passphrase (encrypted):\n  {config_passphrase}\n\n")
        f.write("Sample encrypted Password V2 values:\n")
        count = 0
        for s in with_saved:
            pwd = s.get("password_encrypted", s.get("password_v2", ""))
            if pwd:
                f.write(f"  [{s['session']}]\n")
                f.write(f"    IV:  {pwd[3:3+32]}\n")
                f.write(f"    CT:  {pwd[3+32:]}\n")
                count += 1
                if count >= 10:
                    break


if __name__ == "__main__":
    main()
