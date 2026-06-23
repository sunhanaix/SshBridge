import json
from collections import Counter

with open(r'd:\perl_wrk\SCRT\securecrt_sessions_export.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

total = len(data)
with_pwd = [s for s in data if s.get('password')]
with_mon = [s for s in data if s.get('monitor_password', '') and len(s.get('monitor_password', '')) > 0]
folders = set(s.get('folder', '') for s in data if s.get('folder'))
protocols = set(s.get('protocol', '') for s in data)

print('=== SecureCRT Session Export Summary ===')
print(f'Total sessions: {total}')
print(f'Sessions with saved passwords: {len(with_pwd)}')
print(f'Sessions with monitor passwords: {len(with_mon)}')
print(f'Folders: {len(folders)}')
print(f'Protocols: {protocols}')
print()

folder_counts = Counter(s.get('folder', '') for s in data)
print('Top folders:')
for folder, count in folder_counts.most_common(15):
    print(f'  [{folder}] {count} sessions')

print()
print('Sample entries with passwords:')
for s in with_pwd[:10]:
    print(f'  [{s["folder"]}] {s["session"]}')
    print(f'    host={s["hostname"]}:{s["port"]}  user={s["username"]}  pw={s["password"]}')
