"""
Systematic test of various decryption approaches for SecureCRT V3 (03:) passwords.
"""
import sys, os, re, struct
from securecrt_cipher import bcrypt_pbkdf2, bcrypt_hash
from Crypto.Cipher import AES, Blowfish
from Crypto.Hash import SHA256, SHA512, HMAC, MD5
from Crypto.Protocol import KDF
import hashlib

# ============================================================
# Data
# ============================================================
cp_ct = '1ba3b3825b360ab57f9ae42a898974162005d88d256bdad0693667bef97c95a8bd6209e6ec81d4c11eb5b155892b7da6f5e4f44fc61c3aac8a59e810caf88725'

# Session password sample
session_ct = '1ba3b3825b360ab57f9ae42a898974163e45069fb2d0612e70ce6859aceb0f81a0661dd97bc3c5d1693d43c80f1eb7bdc449c064c0e21d7d92c0f6abfcc6092c238b6058ef221efe4442b8d599dd7372'

def try_decrypt(label, ct_hex, key, iv, do_print=True):
    """Try AES-CBC decrypt and check structure."""
    try:
        ct = bytes.fromhex(ct_hex)
        cipher = AES.new(key, AES.MODE_CBC, iv=iv)
        padded = cipher.decrypt(ct)
        pt_len = struct.unpack('<I', padded[0:4])[0]
        if 0 <= pt_len <= 512:
            try:
                plaintext = padded[4:4+pt_len].decode('utf-8', errors='replace')
            except:
                plaintext = repr(padded[4:4+pt_len])
            cs = padded[4+pt_len:4+pt_len+32]
            expected_cs = SHA256.new(padded[4:4+pt_len]).digest()
            cs_match = sum(a==b for a,b in zip(cs, expected_cs))

            # Check padding
            expected_pad = 16 - (4 + pt_len + 32) % 16
            if expected_pad < 8:
                expected_pad += 16
            actual_pad = len(padded) - 4 - pt_len - 32
            pad_ok = (actual_pad == expected_pad)

            if (cs_match == 32 and pad_ok) or pt_len > 0:
                print(f'  [{label}] pt_len={pt_len}, cs={cs_match}/32, pad={"OK" if pad_ok else "BAD"}: {plaintext[:60]}')
                return True, plaintext
            elif do_print:
                if cs_match >= 24:
                    print(f'  [{label}] pt_len={pt_len}, cs={cs_match}/32 (close)')
        elif do_print and pt_len < 10000:
            print(f'  [{label}] pt_len={pt_len} (small, invalid)')
    except Exception as e:
        if do_print:
            print(f'  [{label}] ERROR: {e}')
    return False, None

# ============================================================
# Approach 1: Various bcrypt_pbkdf2 variants
# ============================================================
print("=== Approach 1: bcrypt_pbkdf2 with various passphrases ===")
cp_full = bytes.fromhex(cp_ct)
salt = cp_full[:16]
ct_rest = cp_full[16:]

# Test different passphrases
passphrases = [
    ('empty', b''),
    ('VanDyke', b'VanDyke'),
    ('SecureCRT', b'SecureCRT'),
    ('vandyke', b'vandyke'),
    ('securecrt', b'securecrt'),
    ('Vandyke', b'Vandyke'),
    ('VanDyke Software', b'VanDyke Software'),
    ('Configuration Passphrase', b'Configuration Passphrase'),
    (' ', b' '),
    ('\x00', b'\x00'),
]

# Important: try the user SID
sid = 'S-1-5-21-3375472909-3211867624-3369589858-1000'
passphrases.append(('user SID', sid.encode('utf-8')))
passphrases.append(('SHA256(user SID)', SHA256.new(sid.encode('utf-8')).digest()))
passphrases.append(('machine SID', 'S-1-5-21-3375472909-3211867624-3369589858'.encode('utf-8')))

import socket, getpass
hostname = socket.gethostname()
username = getpass.getuser()
passphrases.append(('hostname', hostname.encode('utf-8')))
passphrases.append(('username', username.encode('utf-8')))
passphrases.append((r'host\user', f'{hostname}\\{username}'.encode('utf-8')))

for label, pp in passphrases:
    try:
        kdf = bcrypt_pbkdf2(pp, salt, 48, 16)
        try_decrypt(f'bcrypt, pp={label}', ct_rest.hex(), kdf[:32], kdf[32:])
    except Exception as e:
        pass

# ============================================================
# Approach 2: Treat "salt" as IV, key = SHA256(passphrase)
# ============================================================
print("\n=== Approach 2: salt=IV, key=SHA256(pp) ===")
for label, pp in passphrases:
    key = SHA256.new(pp).digest()
    iv = salt
    try_decrypt(f'SHA256 key, salt IV, pp={label}', ct_rest.hex(), key, iv)

# ============================================================
# Approach 3: Zero IV, key = SHA256(passphrase), decrypt entire blob
# ============================================================
print("\n=== Approach 3: zero IV, SHA256 key, entire blob ===")
for label, pp in passphrases[:8]:
    key = SHA256.new(pp).digest()
    iv = b'\x00' * 16
    try_decrypt(f'SHA256 key, zero IV, pp={label}', cp_ct, key, iv)

# ============================================================
# Approach 4: Blowfish V1 (legacy) on entire blob
# ============================================================
print("\n=== Approach 4: Blowfish V1 ===")
key1 = bytes.fromhex('24A63DDE5BD3B3829C7E06F40816AA07')
key2 = bytes.fromhex('5FB045A29417D916C6C6A2FF064182B7')
bf_iv = b'\x00' * 8

for label, ct_hex in [('Config Passphrase', cp_ct), ('Session Pwd', session_ct)]:
    ct = bytes.fromhex(ct_hex)
    try:
        c1 = Blowfish.new(key1, Blowfish.MODE_CBC, iv=bf_iv)
        c2 = Blowfish.new(key2, Blowfish.MODE_CBC, iv=bf_iv)
        result = c2.decrypt(c1.decrypt(ct)[4:-4])
        print(f'  [{label}] BF: {result[:60].hex()}')
    except Exception as e:
        print(f'  [{label}] BF ERROR: {e}')

# ============================================================
# Approach 5: Different AES modes
# ============================================================
print("\n=== Approach 5: Different AES modes ===")
key = bcrypt_pbkdf2(b'', salt, 48, 16)[:32]

# Try CFB, OFB, CTR modes
for mode_name, mode in [('CFB', AES.MODE_CFB), ('OFB', AES.MODE_OFB), ('CTR', AES.MODE_CTR)]:
    for iv_source in [('salt_as_iv', salt), ('zero_iv', b'\x00'*16)]:
        try:
            cipher = AES.new(key, mode, iv=iv_source[1], segment_size=128) if mode != AES.MODE_CTR else AES.new(key, mode, nonce=b'', initial_value=iv_source[1])
            padded = cipher.decrypt(ct_rest)
            pt_len = struct.unpack('<I', padded[0:4])[0]
            if 0 <= pt_len <= 256:
                print(f'  {mode_name}, {iv_source[0]}: pt_len={pt_len}')
        except:
            pass

# ============================================================
# Approach 6: Try Windows DPAPI (if available)
# ============================================================
print("\n=== Approach 6: DPAPI ===")
# The first 16 bytes might not be salt but a different structure
# Check if the entire blob might be a hex-encoded DPAPI blob
# DPAPI blobs start with version/guid structures

# ============================================================
# Approach 7: bcrypt with explicit Blowfish implementation
# ============================================================
print("\n=== Approach 7: Test bcrypt internals ===")
# The issue might be in KDF.PBKDF2 stride extraction
# Let's trace through the exact calculation

pp = b''
salt_for_cp = bytes.fromhex('1ba3b3825b360ab57f9ae42a89897416')

BCRYPT_BLOCKS = 8
BCRYPT_HASHSIZE = BCRYPT_BLOCKS * 4  # 32

key_length = 48  # 32 key + 16 IV
out_len = (key_length + BCRYPT_HASHSIZE - 1) // BCRYPT_HASHSIZE * BCRYPT_HASHSIZE  # 64
print(f'  out_len={out_len}')

# Get the raw PBKDF2 output
out = KDF.PBKDF2(pp, salt_for_cp, out_len, 16, prf=bcrypt_hash)
print(f'  PBKDF2 output ({len(out)}B): {out.hex()}')

stride_n = (key_length + BCRYPT_HASHSIZE - 1) // BCRYPT_HASHSIZE  # 2
print(f'  stride_n={stride_n}')

# Manual extraction
for i in range(key_length):  # i = 0..47
    a, b = divmod(i, stride_n)  # stride_n=2
    # divmod(i, 2) -> for i=0: (0,0), i=1: (0,1), i=2: (1,0), i=3: (1,1), ...
    idx = a * 1 + b * BCRYPT_HASHSIZE
    # i=0: a=0,b=0 -> idx=0
    # i=1: a=0,b=1 -> idx=32
    # i=2: a=1,b=0 -> idx=1
    # i=3: a=1,b=1 -> idx=33
    # ...
    print(f'  i={i}: a={a}, b={b}, idx={idx}, byte={out[idx]:02x}')

# This gives a "striped" extraction: bytes 0, 32, 1, 33, 2, 34, ...
# Compare with bcrypt_pbkdf2
result = bcrypt_pbkdf2(pp, salt_for_cp, 48, 16)
print(f'\n  Stripe extraction: {out.hex()[:80]}...')
print(f'  Smart extraction:  {result.hex()}')

# Let's verify:
manual = bytes(out[sum(aa * bb for aa, bb in zip(divmod(ii, stride_n), (1, BCRYPT_HASHSIZE)))] for ii in range(0, key_length))
print(f'  Manual:            {manual.hex()}')
print(f'  Match: {manual == result}')

# ============================================================
# Approach 8: Check if the PBKDF2 iterations need to be different
# Try various round counts
# ============================================================
print("\n=== Approach 8: Different PBKDF2 rounds ===")
for rounds in [1, 2, 4, 8, 16, 32, 64, 100, 1000]:
    out = KDF.PBKDF2(pp, salt_for_cp, out_len, rounds, prf=bcrypt_hash)
    kdf = bytes(out[sum(a * b for a, b in zip(divmod(i, stride_n), (1, BCRYPT_HASHSIZE)))] for i in range(0, key_length))
    key, iv = kdf[:32], kdf[32:]
    ok, pt = try_decrypt(f'rounds={rounds}', ct_rest.hex(), key, iv, do_print=False)
    if ok:
        print(f'  rounds={rounds}: SUCCESS - "{pt}"')
