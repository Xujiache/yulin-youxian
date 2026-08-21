#!/usr/bin/env python3
"""把测试门店与收货地址从乌鲁木齐还原回银川。

relocate-testdata-to-urumqi.py 的逆操作。门店真实经营地在银川金凤区，
在银川正式上线配送前必须跑这个脚本，否则派单距离、ETA、到达围栏全是错的。

会还原两处：
  1. delivery_config 的 store.lat / store.lng
  2. userId 1011 的 5 个测试地址（地址簿 + 本次测试单的地址快照）

历史订单的地址快照不受影响。

用法：
    python3 revert-testdata-relocation.py            # 预览
    python3 revert-testdata-relocation.py --apply    # 还原（需先停服务）
"""

import hashlib
import json
import subprocess
import sys

USER_ID = 1011
NEW_ORDER_MIN = 1165
DB = ['mysql', '-h', '127.0.0.1', '-P', '3306', '-u', 'yulin_fresh', 'yulin_fresh']

# 银川金凤区，改动前 delivery_config 里的原值
STORE_LAT, STORE_LNG = 38.383423, 106.096144

# 地址 id -> (小区名, 门牌, 纬度, 经度)
# 坐标直接抄搬迁前库里的实测值，不用偏移量反算，避免浮点误差导致还原后差几米。
PLAN = {
    95:  ('兴顺苑A区',    '5号楼 3单元 201室',   38.388813, 106.088125),
    103: ('阅海万家A区',  '3号楼 2单元 601室',   38.391508, 106.099581),
    104: ('阅海万家A区',  '3号楼 2单元 1102室',  38.391508, 106.099581),
    105: ('金凤万达广场', 'B座 1808室',          38.379830, 106.116765),
    106: ('森林公园小区', '12号楼 1单元 302室',  38.373542, 106.084688),
}


def normalize_key(text):
    return ''.join(ch for ch in text.lower()
                   if ch.isdigit() or ('a' <= ch <= 'z') or '\u4e00' <= ch <= '\u9fff')


def load():
    out = subprocess.run(
        DB + ['-N', '--raw', '-e', "SELECT payload FROM application_state WHERE state_key='storefront';"],
        capture_output=True, check=True,
    ).stdout
    return json.loads(out.rstrip(b'\n').decode('utf-8'))


coords = PLAN

print(f'还原门店坐标到银川：{STORE_LAT}, {STORE_LNG}')
print('\n=== 地址还原计划 ===')
for aid, (loc, det, lat, lng) in coords.items():
    print(f'  {aid}: {loc} {det}  ({lat}, {lng})')

data = load()
for entry in data['addresses']:
    if entry['userId'] != USER_ID or entry['address']['id'] not in coords:
        continue
    addr = entry['address']
    loc, det, lat, lng = coords[addr['id']]
    addr['locationName'], addr['detail'] = loc, det
    addr['latitude'], addr['longitude'] = lat, lng

touched = 0
order_to_address = {}
for order in data['orders']:
    if order['userId'] != USER_ID or order['id'] < NEW_ORDER_MIN:
        continue
    addr = order.get('address') or {}
    if addr.get('id') not in coords:
        continue
    order_to_address[order['id']] = addr['id']
    loc, det, lat, lng = coords[addr['id']]
    addr['locationName'], addr['detail'] = loc, det
    addr['latitude'], addr['longitude'] = lat, lng
    touched += 1
print(f'\n还原 {touched} 笔测试单的地址快照')

payload = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
sha = hashlib.sha256(payload.encode('utf-8')).hexdigest()

def case(expr):
    """按 order_id 生成 CASE 分支，值由各订单对应的地址决定。"""
    branches = ' '.join(
        f"WHEN {oid} THEN {expr(*coords[aid])}" for oid, aid in sorted(order_to_address.items())
    )
    return f'CASE order_id {branches} END'


def q(text):
    return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"


task_sql = (
    'UPDATE delivery_task SET '
    f'address_lat = {case(lambda loc, det, lat, lng: lat)}, '
    f'address_lng = {case(lambda loc, det, lat, lng: lng)}, '
    f'area_label = {case(lambda loc, det, lat, lng: q(loc))}, '
    f'address_detail = {case(lambda loc, det, lat, lng: q(loc + " " + det))}, '
    f'group_key = CONCAT({case(lambda loc, det, lat, lng: q(normalize_key(loc)))}, '
    "SUBSTRING(group_key, LOCATE('|', group_key))) "
    f'WHERE order_id IN ({",".join(str(o) for o in sorted(order_to_address))});'
)

print(f'payload {len(payload.encode("utf-8"))} 字节，sha256 {sha[:16]}...')
print(f'配送任务同步还原：覆盖 {len(order_to_address)} 个测试订单号，实际更新其中已生成任务的那些')

if '--apply' not in sys.argv:
    print('\n未加 --apply，仅预览')
    sys.exit(0)

sql_path = '/tmp/storefront_revert.sql'
with open(sql_path, 'w', encoding='ascii') as f:
    f.write("UPDATE application_state SET payload = CONVERT(UNHEX('")
    f.write(payload.encode('utf-8').hex())
    f.write("') USING utf8mb4), payload_sha256 = '")
    f.write(sha)
    f.write("', version = version + 1 WHERE state_key = 'storefront';\n")
subprocess.run(DB, stdin=open(sql_path, 'rb'), check=True)

rest = (
    f"UPDATE delivery_config SET config_value = '{STORE_LAT}' WHERE config_key = 'store.lat';\n"
    f"UPDATE delivery_config SET config_value = '{STORE_LNG}' WHERE config_key = 'store.lng';\n"
    + task_sql + '\n'
)
with open('/tmp/revert_rest.sql', 'w', encoding='utf-8') as f:
    f.write(rest)
subprocess.run(DB, stdin=open('/tmp/revert_rest.sql', 'rb'), check=True)

stored = subprocess.run(
    DB + ['-N', '--raw', '-e', "SELECT payload FROM application_state WHERE state_key='storefront';"],
    capture_output=True, check=True,
).stdout.rstrip(b'\n')
assert hashlib.sha256(stored).hexdigest() == sha, '写入后校验和不符'
print('\n已还原门店坐标与测试地址')
