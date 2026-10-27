#!/usr/bin/env python3
"""把测试用的门店与收货地址从银川搬到乌鲁木齐（骑手真机所在地）。

背景：delivery_config 的 store.lat/lng 配在银川金凤区，而真机定位在乌鲁木齐，
相距 1655 公里，超过 store.gps_sanity_radius_meters（200 公里），骑手端所有
定位上报都被判 GPS_DRIFT 丢弃，地图、到达围栏、距离显示、轨迹回放全部失效。

这里只动测试数据和门店坐标，不碰历史订单的地址快照。门店真实经营地在银川，
正式上线前必须用 revert-testdata-relocation.py 还原。

用法：
    python3 relocate-testdata-to-urumqi.py            # 预览
    python3 relocate-testdata-to-urumqi.py --apply    # 写回（需先停服务）
"""

import hashlib
import json
import math
import subprocess
import sys

USER_ID = 1011
NEW_ORDER_MIN = 1165
DB = ['mysql', '-h', '127.0.0.1', '-P', '3306', '-u', 'yulin_fresh', 'yulin_fresh']

# 骑手真机上报过的位置，把门店放在这里，取货动作在原地就能完成
STORE_LAT, STORE_LNG = 43.807787, 87.646187
M_PER_DEG_LAT = 111320.0
M_PER_DEG_LNG = 111320.0 * math.cos(math.radians(STORE_LAT))  # ≈ 80359


def offset(north_m, east_m):
    return (
        round(STORE_LAT + north_m / M_PER_DEG_LAT, 6),
        round(STORE_LNG + east_m / M_PER_DEG_LNG, 6),
    )


# 地址 id -> (小区名, 门牌, 北向偏移米, 东向偏移米)
# 围栏判定半径 80 米、停留 30 秒，所以距离都控制在步行可达范围内。
# 103 和 104 共用一个坐标，保持「同一栋楼两单」的合并派送场景。
PLAN = {
    95:  ('幸福路社区',     '5号楼 3单元 201室',    177,  177),
    103: ('天山家园A区',    '3号楼 2单元 601室',    600,    0),
    104: ('天山家园A区',    '3号楼 2单元 1102室',   600,    0),
    105: ('中心广场写字楼', 'B座 1808室',          -200, 1100),
    106: ('绿谷小区',       '12号楼 1单元 302室',  -566, -566),
}


def normalize_key(text):
    """复刻 DeliveryAddressIntelligence.normalizeKey：小写后只留数字、字母、汉字。"""
    out = []
    for ch in text.lower():
        if ch.isdigit() or ('a' <= ch <= 'z') or '\u4e00' <= ch <= '\u9fff':
            out.append(ch)
    return ''.join(out)


def load():
    out = subprocess.run(
        DB + ['-N', '--raw', '-e', "SELECT payload FROM application_state WHERE state_key='storefront';"],
        capture_output=True, check=True,
    ).stdout
    return json.loads(out.rstrip(b'\n').decode('utf-8'))


coords = {}
print(f'新门店坐标：{STORE_LAT}, {STORE_LNG}（乌鲁木齐）')
print('\n=== 地址搬迁计划 ===')
for aid, (location, detail, north, east) in PLAN.items():
    lat, lng = offset(north, east)
    coords[aid] = (location, detail, lat, lng)
    dist = math.hypot(north, east)
    print(f'  {aid}: {location} {detail}')
    print(f'       ({lat}, {lng})  距门店 {dist:.0f} 米')

data = load()

print('\n=== 地址簿 ===')
for entry in data['addresses']:
    if entry['userId'] != USER_ID:
        continue
    addr = entry['address']
    if addr['id'] not in coords:
        continue
    location, detail, lat, lng = coords[addr['id']]
    print(f"  {addr['id']}: {addr.get('locationName')} -> {location}   坐标 {addr.get('latitude')},{addr.get('longitude')} -> {lat},{lng}")
    addr['locationName'] = location
    addr['detail'] = detail
    addr['latitude'] = lat
    addr['longitude'] = lng

touched = 0
for order in data['orders']:
    if order['userId'] != USER_ID or order['id'] < NEW_ORDER_MIN:
        continue
    addr = order.get('address') or {}
    if addr.get('id') not in coords:
        continue
    location, detail, lat, lng = coords[addr['id']]
    addr['locationName'] = location
    addr['detail'] = detail
    addr['latitude'] = lat
    addr['longitude'] = lng
    touched += 1
print(f'\n=== 订单地址快照 ===\n  更新 {touched} 笔（仅本次造的测试单，历史单不动）')

# 校验：全部落在服务半径内
print('\n=== 校验 ===')
for aid, (location, detail, lat, lng) in coords.items():
    d = math.hypot((lat - STORE_LAT) * M_PER_DEG_LAT, (lng - STORE_LNG) * M_PER_DEG_LNG)
    assert d <= 3000, f'地址 {aid} 距门店 {d:.0f} 米，超出 3000 米服务半径'
    assert not detail.startswith(location), f'地址 {aid} detail 重复小区名'
print('  ✓ 全部地址在 3 公里服务半径内，且 detail 不重复小区名')

for aid, (location, detail, lat, lng) in coords.items():
    d = math.hypot((lat - STORE_LAT) * M_PER_DEG_LAT, (lng - STORE_LNG) * M_PER_DEG_LNG)
    assert d < 200000, '仍超出 GPS 合理性半径'
print('  ✓ 均在 GPS 合理性半径（200 公里）内，定位上报不会再被丢弃')

payload = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
sha = hashlib.sha256(payload.encode('utf-8')).hexdigest()
print(f'  ✓ payload {len(payload.encode("utf-8"))} 字节，sha256 {sha[:16]}...')

# 给 delivery_task 用的更新语句：坐标、显示地址、区域名、分组键
task_sql = []
for aid, (location, detail, lat, lng) in coords.items():
    task_sql.append({
        'addressId': aid,
        'lat': lat, 'lng': lng,
        'addressDetail': f'{location} {detail}',
        'areaLabel': location,
        'areaSortKey': normalize_key(location),
    })
with open('/tmp/task_address_update.json', 'w', encoding='utf-8') as f:
    json.dump(task_sql, f, ensure_ascii=False, indent=2)
print('\n  任务表更新参数已写入 /tmp/task_address_update.json')
for t in task_sql:
    print(f"    地址{t['addressId']}: area_label={t['areaLabel']}  分组前缀={t['areaSortKey']}")

if '--apply' not in sys.argv:
    print('\n未加 --apply，仅预览')
    sys.exit(0)

sql_path = '/tmp/storefront_relocate.sql'
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
