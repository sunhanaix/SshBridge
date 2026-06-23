"""
Detailed V3 decryption test - investigate checksum mismatch.
"""
import sys, os, re, struct
from securecrt_cipher import bcrypt_pbkdf2, bcrypt_hash
from Crypto.Cipher import AES
from Crypto.Hash import SHA256, SHA512
from Crypto.Protocol import KDF

# ============================================================
# Part 1: Deep dive on Config Passphrase checksum
# ============================================================
cp_ct = '1ba3b3825b360ab57f9ae42a898974162005d88d256bdad0693667bef97c95a8bd6209e6ec81d4c11eb5b155892b7da6f5e4f44fc61c3aac8a59e810caf88725'
ct_bytes = bytes.fromhex(cp_ct)
salt = ct_bytes[:16]
ct = ct_bytes[16:]

kdf = bcrypt_pbkdf2(b'', salt, 48, 16)
key = kdf[:32]
iv = kdf[32:]
print(f'Config Passphrase:')
print(f'  Salt: {salt.hex()}')
print(f'  Key:  {key.hex()}')
print(f'  IV:   {iv.hex()}')

cipher = AES.new(key, AES.MODE_CBC, iv=iv)
padded = cipher.decrypt(ct)
pt_len = struct.unpack('<I', padded[0:4])[0]
print(f'  pt_len: {pt_len}')
print(f'  Padded ({len(padded)}B): {padded.hex()}')

# Try decrypting with slight key variations to understand sensitivity
print(f'\n--- Key sensitivity test ---')
# Flip each bit of the key and see how pt_len changes
for byte_idx in range(32):
    for bit in range(8):
        modified_key = bytearray(key)
        modified_key[byte_idx] ^= (1 << bit)
        try:
            cipher2 = AES.new(bytes(modified_key), AES.MODE_CBC, iv=iv)
            padded2 = cipher2.decrypt(ct)
            pt_len2 = struct.unpack('<I', padded2[0:4])[0]
            if 0 <= pt_len2 <= 100:
                print(f'  Key byte {byte_idx} bit {bit} flipped: pt_len={pt_len2}')
        except:
            pass

# ============================================================
# Part 2: Test session passwords
# ============================================================
print(f'\n--- Session password tests ---')
sessions_dir = r'd:\SecureCRT9.6\Config\Sessions'
samples = []
for fname in os.listdir(sessions_dir):
    if fname.endswith('.ini'):
        fpath = os.path.join(sessions_dir, fname)
        with open(fpath, 'r', encoding='utf-8', errors='replace') as fh:
            content = fh.read()
        for line in content.split('\n'):
            m = re.search(r'"Password V2"=(03:[a-f0-9]+)', line)
            if m:
                samples.append((fname, m.group(1)))
                break
    if len(samples) >= 5:
        break

for fname, encrypted in samples:
    ct_full = bytes.fromhex(encrypted[3:])
    s_salt = ct_full[:16]
    s_ct = ct_full[16:]

    # bcrypt_pbkdf2 with empty passphrase
    kdf = bcrypt_pbkdf2(b'', s_salt, 48, 16)
    cipher = AES.new(kdf[:32], AES.MODE_CBC, iv=kdf[32:])
    padded = cipher.decrypt(s_ct)
    pt_len = struct.unpack('<I', padded[0:4])[0]
    print(f'  {fname}: pt_len={pt_len}, salt={s_salt.hex()[:32]}...')

# ============================================================
# Part 3: Investigate bcrypt_hash internals
# ============================================================
print(f'\n--- bcrypt_hash internals ---')
pp = b''
pp_hashed = SHA512.new(pp).digest()
salt_hashed = SHA512.new(salt).digest()
print(f'  SHA512(pp): {pp_hashed.hex()[:64]}...')
print(f'  SHA512(salt): {salt_hashed.hex()[:64]}...')

# Get raw bcrypt hash before 4-byte reversal
raw_digest = KDF._bcrypt_hash(pp_hashed, 6, salt_hashed, b'OxychromaticBlowfishSwatDynamite', False)
print(f'  Raw bcrypt digest ({len(raw_digest)}B): {raw_digest.hex()}')
print(f'  Raw as 4-byte words:')
for i in range(0, len(raw_digest), 4):
    word = raw_digest[i:i+4]
    print(f'    [{i//4}]: {word.hex()} (reversed: {word[::-1].hex()})')

reversed_digest = b''.join(raw_digest[i:i + 4][::-1] for i in range(0, len(raw_digest), 4))
print(f'  Reversed digest: {reversed_digest.hex()}')

# Check: is the reversed digest the same as the standard bcrypt hash format?
# Standard bcrypt uses _bcrypt_hash with invert=True and magic="OrpheanBeholderScryDoubt"
std_raw = KDF._bcrypt_hash(pp_hashed, 6, salt_hashed, b'OrpheanBeholderScryDoubt', True)
print(f'  Standard bcrypt raw: {std_raw.hex()}')

# Try without 4-byte reversal but with different power-on of PBKDF2
print(f'\n--- Testing variant bcrypt_pbkdf2 ---')

# Variant 1: no reversal, different PBKDF2 stride
for no_rev in [True, False]:
    for invert in [False, True]:
        def make_bh(no_rev, invert):
            def bh(password, salt):
                password = SHA512.new(password).digest()
                salt = SHA512.new(salt).digest()
                digest = KDF._bcrypt_hash(password, 6, salt, b'OxychromaticBlowfishSwatDynamite', invert)
                if not no_rev:
                    digest = b''.join(digest[i:i + 4][::-1] for i in range(0, len(digest), 4))
                return digest
            return bh

        def make_pbkdf2(bh_func):
            def pbkdf2(password, salt, key_length, rounds):
                BCRYPT_BLOCKS = 8
                BCRYPT_HASHSIZE = BCRYPT_BLOCKS * 4
                out_len = (key_length + BCRYPT_HASHSIZE - 1) // BCRYPT_HASHSIZE * BCRYPT_HASHSIZE
                out = KDF.PBKDF2(password, salt, out_len, rounds, prf=bh_func)
                stride_n = (key_length + BCRYPT_HASHSIZE - 1) // BCRYPT_HASHSIZE
                return bytes(out[sum(a * b for a, b in zip(divmod(i, stride_n), (1, BCRYPT_HASHSIZE)))] for i in range(0, key_length))
            return pbkdf2

        bh = make_bh(no_rev, invert)
        pbkdf2 = make_pbkdf2(bh)
        kdf = pbkdf2(b'', salt, 48, 16)
        cipher = AES.new(kdf[:32], AES.MODE_CBC, iv=kdf[32:])
        padded = cipher.decrypt(ct)
        pt_len = struct.unpack('<I', padded[0:4])[0]
        label = f'no_rev={no_rev}, invert={invert}'
        if 0 <= pt_len <= 100:
            cs_match = sum(a==b for a,b in zip(padded[4:36], SHA256.new(b'').digest()))
            print(f'  {label}: pt_len={pt_len}, cs_match={cs_match}/32, key={kdf[:32].hex()[:32]}...')
        else:
            print(f'  {label}: pt_len={pt_len} (invalid)')
