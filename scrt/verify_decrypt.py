"""Verify V3 decryption works and show actual passwords."""
import sys, os, re, struct
from securecrt_cipher import bcrypt_pbkdf2
from Crypto.Cipher import AES
from Crypto.Hash import SHA256

def decrypt_v3(hex_val, passphrase=b''):
    raw = bytes.fromhex(hex_val)
    salt = raw[:16]
    ct = raw[16:]
    kdf = bcrypt_pbkdf2(passphrase, salt, 48, 16)
    cipher = AES.new(kdf[:32], AES.MODE_CBC, iv=kdf[32:])
    padded = cipher.decrypt(ct)
    pt_len = struct.unpack('<I', padded[0:4])[0]
    plaintext = padded[4:4+pt_len]
    checksum = padded[4+pt_len:4+pt_len+32]
    expected = SHA256.new(plaintext).digest()
    cs_match = sum(a==b for a,b in zip(checksum, expected))
    return pt_len, plaintext, cs_match

# Test Config Passphrase
cp_hex = '1ba3b3825b360ab57f9ae42a898974162005d88d256bdad0693667bef97c95a8bd6209e6ec81d4c11eb5b155892b7da6f5e4f44fc61c3aac8a59e810caf88725'
pt_len, plaintext, cs_match = decrypt_v3(cp_hex)
print(f'Config Passphrase: pt_len={pt_len}, cs_match={cs_match}/32, plaintext={repr(plaintext)}')

# Test session passwords
sessions_dir = r'd:\SecureCRT9.6\Config\Sessions'
tested = 0
for root, dirs, files in os.walk(sessions_dir):
    for fname in files:
        if not fname.endswith('.ini'):
            continue
        fpath = os.path.join(root, fname)
        relpath = os.path.relpath(fpath, sessions_dir)
        with open(fpath, 'r', encoding='utf-8-sig', errors='replace') as fh:
            content = fh.read()

        for line in content.split('\n'):
            m = re.search(r'"Password V2"=(03:[a-f0-9]+)', line)
            if m:
                val = m.group(1)[3:]
                pt_len, plaintext, cs_match = decrypt_v3(val)
                pw = plaintext.decode('utf-8', errors='replace')
                print(f'{relpath}: pt_len={pt_len}, cs={cs_match}/32, pw={repr(pw)}')
                tested += 1
                break
        if tested >= 10:
            break
    if tested >= 10:
        break
