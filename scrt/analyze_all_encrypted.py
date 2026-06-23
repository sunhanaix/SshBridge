"""
Analyze all encrypted values across the entire SecureCRT config.
"""
import os, re, struct, sys, json
from securecrt_cipher import bcrypt_pbkdf2
from Crypto.Hash import SHA256

config_dir = os.environ.get(
    'SCRT_CONFIG_DIR',
    os.path.join(os.environ.get('LOCALAPPDATA', ''), 'VanDyke Software', 'Config')
)
encrypted_values = []
keys_seen = set()

def parse_ini(content):
    """Simple parser for SecureCRT ini format."""
    result = {}
    for line in content.split('\n'):
        line = line.strip()
        if '=' not in line:
            continue
        # Find the key between quotes
        try:
            eq_pos = line.index('=')
            type_key = line[:eq_pos]
            value = line[eq_pos + 1:]

            # Type is first char
            type_char = type_key[0]
            # Key is between quotes
            q1 = type_key.index('"') + 1
            q2 = type_key.index('"', q1)
            key_name = type_key[q1:q2]

            if type_char == 'S':
                result[key_name] = value
            elif type_char == 'D':
                result[key_name] = value
        except (ValueError, IndexError):
            continue
    return result

# Collect all encrypted values
for root, dirs, files in os.walk(config_dir):
    for fname in files:
        if not fname.endswith('.ini'):
            continue
        fpath = os.path.join(root, fname)
        relpath = os.path.relpath(fpath, config_dir)
        try:
            with open(fpath, 'r', encoding='utf-8-sig', errors='replace') as fh:
                content = fh.read()
        except:
            continue

        data = parse_ini(content)
        for key, value in data.items():
            if not isinstance(value, str):
                continue
            for prefix in ['02:', '03:']:
                if value.startswith(prefix):
                    hex_val = value[3:]
                    try:
                        raw = bytes.fromhex(hex_val)
                    except:
                        continue
                    ct_len = len(raw)
                    salt = raw[:16].hex() if ct_len >= 16 else 'N/A'

                    # Try decrypt with empty passphrase via bcrypt
                    pt_len = None
                    cs_match = None
                    if prefix == '03:' and ct_len >= 16:
                        try:
                            kdf = bcrypt_pbkdf2(b'', raw[:16], 48, 16)
                            from Crypto.Cipher import AES
                            cipher = AES.new(kdf[:32], AES.MODE_CBC, iv=kdf[32:])
                            padded = cipher.decrypt(raw[16:])
                            pt_len = struct.unpack('<I', padded[0:4])[0]
                            if 0 <= pt_len <= 256:
                                cs = padded[4+pt_len:4+pt_len+32]
                                expected = SHA256.new(padded[4:4+pt_len]).digest()
                                cs_match = sum(a==b for a,b in zip(cs, expected))
                        except:
                            pass

                    encrypted_values.append({
                        'file': relpath,
                        'key': key,
                        'prefix': prefix,
                        'ct_len': ct_len,
                        'salt': salt,
                        'pt_len': pt_len,
                        'cs_match': cs_match,
                    })

# Summary statistics
total = len(encrypted_values)
v2 = [e for e in encrypted_values if e['prefix'] == '02:']
v3 = [e for e in encrypted_values if e['prefix'] == '03:']

print(f'Total encrypted values: {total}')
print(f'  V2 (02:): {len(v2)}')
print(f'  V3 (03:): {len(v3)}')

if v3:
    salts = set(e['salt'] for e in v3)
    print(f'\nUnique V3 salts: {len(salts)}')
    for s in sorted(salts):
        count = sum(1 for e in v3 if e['salt'] == s)
        print(f'  Salt {s[:32]}...: {count} values')

    # Check unique pt_len from bcrypt/empty
    pt_lens = [(e['pt_len'], e['cs_match']) for e in v3 if e['pt_len'] is not None]
    valid_pt = [p for p in pt_lens if 0 <= p[0] <= 256]
    print(f'\nV3 values with 0<=pt_len<=256 (bcrypt/empty): {len(valid_pt)}')
    for pl, cs in valid_pt[:20]:
        print(f'  pt_len={pl}, cs_match={cs}/32')

# Count ciphertext lengths
if v3:
    ct_lengths = {}
    for e in v3:
        l = e['ct_len']
        ct_lengths[l] = ct_lengths.get(l, 0) + 1
    print(f'\nV3 ciphertext length distribution:')
    for l, c in sorted(ct_lengths.items())[:20]:
        print(f'  {l}B: {c} values')

# Show some key examples
print(f'\n--- Example V3 values ---')
for e in v3[:10]:
    print(f'  [{e["key"]}] in {e["file"]}: ct_len={e["ct_len"]}, pt_len={e["pt_len"]}, cs_match={e["cs_match"]}')

# Show ALL V2 values
if v2:
    print(f'\n--- V2 values ---')
    for e in v2:
        print(f'  [{e["key"]}] in {e["file"]}: ct_len={e["ct_len"]}')
