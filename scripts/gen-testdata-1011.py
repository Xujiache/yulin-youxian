#!/usr/bin/env python3
"""为 userId 1011（徐嘉诚 / 18195819181）生成全链路测试数据。

读取 MySQL application_state.storefront 的当前 payload，追加地址、订单、退款和
购物车条目，校验通过后写回并递增 version。配送任务不在这里造，本脚本只负责把
订单铺到「备货中」，后续由管理端 pick-ready / assign 接口生成真实任务。

用法：
    python3 gen-testdata-1011.py            # 只生成并校验，输出到 /tmp
    python3 gen-testdata-1011.py --apply    # 校验通过后写回 MySQL
"""

import copy
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timedelta

USER_ID = 1011
PHONE = '18195819181'
NAME = '徐嘉诚'
DB = ['mysql', '-h', '127.0.0.1', '-P', '3306', '-u', 'yulin_fresh', 'yulin_fresh']

# 门店坐标（delivery_config store.lat / store.lng），服务半径 3km
STORE_LAT, STORE_LNG = 38.383423, 106.096144
M_PER_DEG_LAT = 111320.0
M_PER_DEG_LNG = 87290.0  # 纬度 38.38° 处


def offset(north_m, east_m):
    return (
        round(STORE_LAT + north_m / M_PER_DEG_LAT, 6),
        round(STORE_LNG + east_m / M_PER_DEG_LNG, 6),
    )


def load_payload():
    out = subprocess.run(
        DB + ['-N', '--raw', '-e', "SELECT payload FROM application_state WHERE state_key='storefront';"],
        capture_output=True, check=True,
    ).stdout
    return json.loads(out.rstrip(b'\n').decode('utf-8'))


data = load_payload()
print(f"读取成功：{len(data['orders'])} 单，{len(data['refunds'])} 退款，{len(data['addresses'])} 地址")

# ---------------------------------------------------------------- ID 分配
next_order_id = max(o['id'] for o in data['orders']) + 1
next_item_id = max(it['id'] for o in data['orders'] for it in o['items']) + 1
next_addr_id = max(a['address']['id'] for a in data['addresses']) + 1
next_refund_id = max(r['refund']['id'] for r in data['refunds']) + 1
next_refund_no = max(int(r['refund']['refundNo'][2:]) for r in data['refunds']) + 1
next_cart_id = max(c['id'] for c in data['cartItems']) + 1

# ---------------------------------------------------------------- 地址
# 两条落在同一栋楼，用来验证同楼合并派送与楼栋交接统计。
lat1, lng1 = offset(900, 300)
lat2, lng2 = offset(-400, 1800)
lat3, lng3 = offset(-1100, -1000)

NEW_ADDRESSES = [
    {'detail': '阅海万家A区 3号楼 2单元 601室', 'locationName': '阅海万家A区',
     'latitude': lat1, 'longitude': lng1},
    {'detail': '阅海万家A区 3号楼 2单元 1102室', 'locationName': '阅海万家A区',
     'latitude': lat1, 'longitude': lng1},
    {'detail': '金凤万达 B座 1808室', 'locationName': '金凤万达广场',
     'latitude': lat2, 'longitude': lng2},
    {'detail': '森林公园小区 12号楼 1单元 302室', 'locationName': '森林公园小区',
     'latitude': lat3, 'longitude': lng3},
]

addresses = []
for spec in NEW_ADDRESSES:
    addr = {'id': next_addr_id, 'name': NAME, 'phone': PHONE, 'isDefault': False, **spec}
    data['addresses'].append({'userId': USER_ID, 'address': addr})
    addresses.append(addr)
    print(f"  + 地址 id={next_addr_id}: {spec['detail']}")
    next_addr_id += 1

# 已有的那条地址没有经纬度，配送任务会拿不到坐标，补上门店附近的点位
for entry in data['addresses']:
    if entry['userId'] == USER_ID and entry['address']['id'] == 95:
        if entry['address'].get('latitude') is None:
            la, ln = offset(600, -700)
            entry['address']['latitude'] = la
            entry['address']['longitude'] = ln
            entry['address']['locationName'] = '兴顺苑A区'
            print(f"  ~ 地址 id=95 补充坐标 ({la}, {ln})")
        addresses.insert(0, entry['address'])

# ---------------------------------------------------------------- 商品池
pool = []
for p in data['products']:
    if p.get('status') != 1 or not p.get('skus'):
        continue
    sku = next((s for s in p['skus'] if s.get('status') == 1), None)
    if not sku:
        continue
    pool.append({
        'productId': p['id'], 'productName': p['name'], 'imageUrl': p.get('imageUrl', ''),
        'saleUnit': sku.get('saleUnit') or p.get('saleUnit', '份'),
        'unitPrice': sku['unitPrice'], 'skuId': sku['id'],
        'skuCode': sku.get('skuCode', ''), 'specificationText': sku.get('specificationText', ''),
    })
    if len(pool) >= 20:
        break
print(f"商品池：{len(pool)} 个可用 SKU")

SLOTS = ['明日 10:00-11:00', '明日 11:00-12:00', '明日 13:00-14:00', '明日 14:00-15:00',
         '明日 15:00-16:00', '明日 16:00-17:00', '明日 17:00-18:00', '明日 18:00-19:00']

# 订单计划：(状态, 数量, 已付比例, 已退比例, 备注, 退款状态)
PLAN = [
    ('备货中',   9, 1.0, 0.0, '配送链路测试单', None),
    ('待支付',   2, 0.0, 0.0, '等待付款',       None),
    ('已完成',   2, 1.0, 0.0, '历史完成单',     None),
    ('已完成',   1, 1.0, 0.0, '申请退款被驳回', '已拒绝'),
    ('已取消',   1, 0.0, 0.0, '下错了取消',     None),
    ('已关闭',   1, 0.0, 0.0, '超时未支付关闭', None),
    ('已退款',   1, 1.0, 1.0, '全额退款',       '退款成功'),
    ('退款中',   1, 1.0, 0.0, '退款待审核',     '待审核'),
    ('部分退款', 1, 1.0, 0.5, '部分商品退款',   '退款成功'),
]

BEIJING_NOW = datetime.utcnow() + timedelta(hours=8)
TODAY = BEIJING_NOW.date()
TOMORROW = TODAY + timedelta(days=1)

new_orders, new_refunds = [], []
seq = 0

for status, count, paid_ratio, refund_ratio, remark, refund_status in PLAN:
    for i in range(count):
        oid = next_order_id
        next_order_id += 1

        # 备货中的单排在最近，其余往前推几天
        if status == '备货中':
            created = BEIJING_NOW - timedelta(minutes=25 * (count - i))
            delivery_date = TOMORROW
        elif status == '待支付':
            created = BEIJING_NOW - timedelta(minutes=8 * (i + 1))
            delivery_date = TOMORROW
        else:
            created = BEIJING_NOW - timedelta(days=2 + seq % 5, hours=3 * (i + 1))
            delivery_date = created.date() + timedelta(days=1)
        seq += 1

        order_no = f"XD{created.strftime('%Y%m%d%H%M%S')}{oid}"

        items, product_amount = [], 0
        for j in range(2 + (oid % 2)):
            p = pool[(oid * 3 + j) % len(pool)]
            qty = 1 + (j % 2)
            amount = p['unitPrice'] * qty
            product_amount += amount
            items.append({
                'id': next_item_id, 'productId': p['productId'], 'productName': p['productName'],
                'imageUrl': p['imageUrl'], 'saleUnit': p['saleUnit'], 'unitPrice': p['unitPrice'],
                'quantity': qty, 'amount': amount, 'skuId': p['skuId'],
                'skuCode': p['skuCode'], 'specificationText': p['specificationText'],
            })
            next_item_id += 1

        delivery_fee = 0      # settings.deliveryFee = 0（开业免配送费）
        package_fee = 100     # settings.packageFee = 100
        payable = product_amount + delivery_fee + package_fee
        paid = int(payable * paid_ratio)
        refunded = int(payable * refund_ratio)

        addr = copy.deepcopy(addresses[oid % len(addresses)])

        order = {
            'id': oid, 'userId': USER_ID, 'orderNo': order_no, 'status': status,
            'address': addr, 'deliverySlot': SLOTS[oid % len(SLOTS)], 'items': items,
            'productAmount': product_amount, 'deliveryFee': delivery_fee,
            'packageFee': package_fee, 'payableAmount': payable,
            'paidAmount': paid, 'refundedAmount': refunded,
            'remark': remark,
            'createdAt': created.strftime('%Y-%m-%dT%H:%M:%S.') + f'{created.microsecond:06d}000',
            'deliveryDate': delivery_date.strftime('%Y-%m-%d'),
        }
        new_orders.append(order)

        if paid > 0:
            data.setdefault('paymentTransactionIds', {})[order_no] = f"42000{oid:06d}{created.strftime('%Y%m%d')}"
            data.setdefault('paymentMethods', {})[str(oid)] = 'WECHAT'

        if refund_status:
            amount = refunded if refunded > 0 else int(paid * 0.2)
            new_refunds.append({'userId': USER_ID, 'refund': {
                'id': next_refund_id, 'orderId': oid, 'refundNo': f"RF{next_refund_no}",
                'refundAmount': amount, 'reason': f'{remark}（测试数据）', 'status': refund_status,
                'evidenceImages': [], 'userId': USER_ID, 'orderNo': order_no, 'source': 'USER',
                'createdAt': (created + timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%S'),
            }})
            next_refund_id += 1
            next_refund_no += 1

data['orders'].extend(new_orders)
data['refunds'].extend(new_refunds)

# ---------------------------------------------------------------- 购物车
for idx, (pi, qty, selected) in enumerate([(1, 2, True), (4, 1, True), (7, 3, False), (10, 1, True)]):
    p = pool[pi]
    data['cartItems'].append({
        'id': next_cart_id, 'userId': USER_ID, 'productId': p['productId'],
        'skuId': p['skuId'], 'quantity': qty, 'selected': selected,
    })
    print(f"  + 购物车 id={next_cart_id}: {p['productName']} x{qty}")
    next_cart_id += 1

# ---------------------------------------------------------------- 校验
print('\n=== 校验 ===')
ids = [o['id'] for o in data['orders']]
assert len(ids) == len(set(ids)), '订单 id 重复'
nos = [o['orderNo'] for o in data['orders']]
assert len(nos) == len(set(nos)), '订单号重复'
# 历史数据里明细 id 只在订单内唯一，全局有重复（现存 300 条里 40 个 id 撞车）。
# 这里不去动历史，只保证订单内唯一，且新分配的 id 不和任何历史 id 相撞。
for o in data['orders']:
    within = [it['id'] for it in o['items']]
    assert len(within) == len(set(within)), f"订单 {o['id']} 内明细 id 重复"
new_order_ids = {o['id'] for o in new_orders}
old_item_ids = {it['id'] for o in data['orders'] if o['id'] not in new_order_ids for it in o['items']}
new_item_ids = {it['id'] for o in new_orders for it in o['items']}
assert not (old_item_ids & new_item_ids), '新明细 id 与历史撞车'
item_ids = new_item_ids
refund_ids = [r['refund']['id'] for r in data['refunds']]
assert len(refund_ids) == len(set(refund_ids)), '退款 id 重复'
addr_ids = [a['address']['id'] for a in data['addresses'] if a['userId'] == USER_ID]
assert len(addr_ids) == len(set(addr_ids)), '地址 id 重复'
print(f'  ✓ 全局 id 唯一（订单 {len(ids)}，明细 {len(item_ids)}，退款 {len(refund_ids)}）')

for o in new_orders:
    assert o['payableAmount'] == o['productAmount'] + o['deliveryFee'] + o['packageFee'], f"{o['id']} 金额不平"
    if o['status'] in ('待支付', '已取消', '已关闭'):
        assert o['paidAmount'] == 0 and o['refundedAmount'] == 0, f"{o['id']} 未支付单不该有金额"
    elif o['status'] in ('备货中', '已完成'):
        assert o['paidAmount'] == o['payableAmount'], f"{o['id']} 应全额支付"
    elif o['status'] == '已退款':
        assert o['refundedAmount'] == o['payableAmount'], f"{o['id']} 应全额退款"
    elif o['status'] == '部分退款':
        assert 0 < o['refundedAmount'] < o['payableAmount'], f"{o['id']} 应部分退款"
    assert o['address'].get('latitude') is not None, f"{o['id']} 地址缺坐标"
print(f'  ✓ {len(new_orders)} 单金额与状态自洽，地址均有坐标')

ready = [o for o in new_orders if o['status'] == '备货中']
assert len(ready) == 9, f'备货中应为 9 单，实际 {len(ready)}'
print(f'  ✓ 备货中 {len(ready)} 单可进入配送链路')

payload = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
assert '???' not in payload, '出现乱码'
sha = hashlib.sha256(payload.encode('utf-8')).hexdigest()
print(f'  ✓ payload {len(payload.encode("utf-8"))} 字节，sha256 {sha[:16]}...')

with open('/tmp/storefront_new.json', 'w', encoding='utf-8') as f:
    f.write(payload)
with open('/tmp/storefront_new.sha256', 'w') as f:
    f.write(sha)
with open('/tmp/testdata_orders.json', 'w', encoding='utf-8') as f:
    json.dump({'orders': new_orders, 'refunds': new_refunds,
               'readyOrderIds': [o['id'] for o in ready]}, f, ensure_ascii=False, indent=2)

print(f"\n新增 {len(new_orders)} 单 / {len(new_refunds)} 退款 / {len(NEW_ADDRESSES)} 地址 / 4 购物车")
print('备货中订单 id:', [o['id'] for o in ready])

if '--apply' not in sys.argv:
    print('\n未加 --apply，仅生成未写库')
    sys.exit(0)

# ---------------------------------------------------------------- 写回
# payload 里含大量中文和 JSON 转义符，直接拼 SQL 字符串很容易被引号和反斜杠咬到，
# 改走 UNHEX：把 UTF-8 字节转成十六进制常量，MySQL 侧再还原成 utf8mb4 文本。
sql_path = '/tmp/storefront_update.sql'
with open(sql_path, 'w', encoding='ascii') as f:
    f.write("SET SESSION group_concat_max_len = 4294967295;\n")
    f.write("UPDATE application_state SET payload = CONVERT(UNHEX('")
    f.write(payload.encode('utf-8').hex())
    f.write("') USING utf8mb4), payload_sha256 = '")
    f.write(sha)
    f.write("', version = version + 1 WHERE state_key = 'storefront';\n")

subprocess.run(DB, stdin=open(sql_path, 'rb'), check=True)

# 读回来核对，确认写进去的和算出来的完全一致
check = subprocess.run(
    DB + ['-N', '--raw', '-e',
          "SELECT payload_sha256, version, CHAR_LENGTH(payload) FROM application_state WHERE state_key='storefront';"],
    capture_output=True, check=True,
).stdout.decode().strip().split('\t')
stored = subprocess.run(
    DB + ['-N', '--raw', '-e', "SELECT payload FROM application_state WHERE state_key='storefront';"],
    capture_output=True, check=True,
).stdout.rstrip(b'\n')
actual = hashlib.sha256(stored).hexdigest()

print(f"已写回 MySQL：version={check[1]}，payload {check[2]} 字符")
assert check[0] == sha, f'库中校验和 {check[0]} 与预期 {sha} 不符'
assert actual == sha, f'库中实际内容哈希 {actual} 与校验和 {sha} 不符'
print('  ✓ 校验和与实际内容一致，写入完整')
