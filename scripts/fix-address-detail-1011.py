#!/usr/bin/env python3
"""修正 userId 1011 新增地址里重复的小区名。

任务地址是 locationName + " " + detail 拼出来的（OrderTaskSnapshotFactory.fullAddress），
造数据时把小区名同时写进了两个字段，骑手端每张卡片都会显示成
「阅海万家A区 阅海万家A区 3号楼 2单元 601室」。这里把 detail 里的小区名前缀去掉，
只改地址簿和本次新增的订单快照，历史订单的快照保持原样不动。

用法：
    python3 fix-address-detail-1011.py            # 只预览
    python3 fix-address-detail-1011.py --apply    # 写回 MySQL
"""

import hashlib
import json
import subprocess
import sys

USER_ID = 1011
NEW_ORDER_MIN = 1165  # 本次造的订单从这个 id 开始
DB = ['mysql', '-h', '127.0.0.1', '-P', '3306', '-u', 'yulin_fresh', 'yulin_fresh']

# 地址 id -> (小区名, 门牌部分)
FIXES = {
    95:  ('兴顺苑A区', '5号楼 3单元 201室'),
    103: ('阅海万家A区', '3号楼 2单元 601室'),
    104: ('阅海万家A区', '3号楼 2单元 1102室'),
    105: ('金凤万达广场', 'B座 1808室'),
    106: ('森林公园小区', '12号楼 1单元 302室'),
}


def load():
    out = subprocess.run(
        DB + ['-N', '--raw', '-e', "SELECT payload FROM application_state WHERE state_key='storefront';"],
        capture_output=True, check=True,
    ).stdout
    return json.loads(out.rstrip(b'\n').decode('utf-8'))


data = load()
changed = 0

print('=== 地址簿 ===')
for entry in data['addresses']:
    if entry['userId'] != USER_ID:
        continue
    addr = entry['address']
    fix = FIXES.get(addr['id'])
    if not fix:
        continue
    location, detail = fix
    if addr.get('locationName') == location and addr.get('detail') == detail:
        print(f"  id={addr['id']} 已正确，跳过")
        continue
    print(f"  id={addr['id']}: {addr.get('locationName')!r} + {addr.get('detail')!r}")
    print(f"        -> {location!r} + {detail!r}")
    addr['locationName'] = location
    addr['detail'] = detail
    changed += 1

print('\n=== 订单地址快照（仅本次新增的单）===')
touched_orders = 0
for order in data['orders']:
    if order['userId'] != USER_ID or order['id'] < NEW_ORDER_MIN:
        continue
    addr = order.get('address') or {}
    fix = FIXES.get(addr.get('id'))
    if not fix:
        continue
    location, detail = fix
    if addr.get('locationName') == location and addr.get('detail') == detail:
        continue
    addr['locationName'] = location
    addr['detail'] = detail
    touched_orders += 1
print(f'  修正 {touched_orders} 笔订单的地址快照')

if changed == 0 and touched_orders == 0:
    print('\n无需修改')
    sys.exit(0)

# 校验：detail 不应再以小区名开头
for entry in data['addresses']:
    if entry['userId'] != USER_ID:
        continue
    addr = entry['address']
    if addr['id'] in FIXES:
        loc = addr['locationName']
        assert not addr['detail'].startswith(loc), f"id={addr['id']} detail 仍含小区名"
        assert addr['latitude'] is not None, f"id={addr['id']} 缺坐标"
print('\n  ✓ 校验通过：detail 不再重复小区名，坐标齐全')

payload = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
sha = hashlib.sha256(payload.encode('utf-8')).hexdigest()
print(f'  ✓ payload {len(payload.encode("utf-8"))} 字节，sha256 {sha[:16]}...')

# 拼出修好之后的完整地址，供后面同步 delivery_task 使用
mapping = {aid: f'{loc} {det}' for aid, (loc, det) in FIXES.items()}
with open('/tmp/address_full.json', 'w', encoding='utf-8') as f:
    json.dump(mapping, f, ensure_ascii=False, indent=2)
print('\n修正后的完整地址：')
for aid, full in mapping.items():
    print(f'  地址 {aid}: {full}')

if '--apply' not in sys.argv:
    print('\n未加 --apply，仅预览')
    sys.exit(0)

sql_path = '/tmp/storefront_fix.sql'
with open(sql_path, 'w', encoding='ascii') as f:
    f.write("UPDATE application_state SET payload = CONVERT(UNHEX('")
    f.write(payload.encode('utf-8').hex())
    f.write("') USING utf8mb4), payload_sha256 = '")
    f.write(sha)
    f.write("', version = version + 1 WHERE state_key = 'storefront';\n")
subprocess.run(DB, stdin=open(sql_path, 'rb'), check=True)

stored = subprocess.run(
    DB + ['-N', '--raw', '-e', "SELECT payload FROM application_state WHERE state_key='storefront';"],
    capture_output=True, check=True,
).stdout.rstrip(b'\n')
assert hashlib.sha256(stored).hexdigest() == sha, '写入后校验和不符'
print('\n已写回 MySQL，校验和一致')
