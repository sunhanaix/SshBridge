"""Check salt uniqueness across all encrypted passwords."""
import os, re

_config_base = os.environ.get(
    'SCRT_CONFIG_DIR',
    os.path.join(os.environ.get('LOCALAPPDATA', ''), 'VanDyke Software', 'Config')
)
sessions_dir = os.path.join(_config_base, 'Sessions')
salts = {}
count = 0
for fname in os.listdir(sessions_dir):
    if not fname.endswith('.ini'):
        continue
    fpath = os.path.join(sessions_dir, fname)
    with open(fpath, 'r', encoding='utf-8', errors='replace') as fh:
        content = fh.read()
    for line in content.split('\n'):
        m = re.search(r'"Password V2"=(03:[a-f0-9]+)', line)
        if m:
            val = m.group(1)
            salt = val[3:3+32]
            if salt not in salts:
                salts[salt] = []
            salts[salt].append(fname)
            count += 1

print(f'Total encrypted passwords: {count}')
print(f'Unique salts: {len(salts)}')
for salt, files in sorted(salts.items(), key=lambda x: -len(x[1]))[:10]:
    print(f'  Salt {salt[:32]}...: {len(files)} files')
    if len(files) <= 3:
        for f in files:
            print(f'    - {f}')

# Monitor Password V2
print(f'\n--- Monitor Password V2 salts ---')
mon_salts = {}
for fname in os.listdir(sessions_dir):
    if not fname.endswith('.ini'):
        continue
    fpath = os.path.join(sessions_dir, fname)
    with open(fpath, 'r', encoding='utf-8', errors='replace') as fh:
        content = fh.read()
    for line in content.split('\n'):
        m = re.search(r'"Monitor Password V2"=(03:[a-f0-9]+)', line)
        if m:
            val = m.group(1)
            salt = val[3:3+32]
            if salt not in mon_salts:
                mon_salts[salt] = []
            mon_salts[salt].append(fname)

print(f'Total monitor passwords: {sum(len(v) for v in mon_salts.values())}')
print(f'Unique salts: {len(mon_salts)}')
for salt, files in sorted(mon_salts.items(), key=lambda x: -len(x[1]))[:5]:
    print(f'  Salt {salt[:32]}...: {len(files)} files')

# Default.ini / Global.ini
print(f'\n--- Default.ini / Global.ini ---')
config_dir = _config_base
for fname in ['Default.ini', 'Global.ini']:
    fpath = os.path.join(config_dir, fname)
    if os.path.exists(fpath):
        with open(fpath, 'r', encoding='utf-8', errors='replace') as fh:
            content = fh.read()
        for line in content.split('\n'):
            for prefix in ['02:', '03:']:
                if prefix in line:
                    m = re.search(r'=(' + prefix + r'[a-f0-9]+)', line)
                    if m:
                        val = m.group(1)
                        salt = val[3:3+32] if prefix == '03:' else 'N/A (v2)'
                        print(f'  {fname}: {line.strip()[:100]}')
                        print(f'    prefix={prefix}, salt={salt[:40]}')
