#!/usr/bin/env python3
"""
Generate full-coverage test data for userId 10001 (phone 18195819181, 徐嘉诚).

Covers all 7 order statuses, all 3 refund statuses, multiple payment methods
(friend-pay + WeChat), multiple addresses, and multi-SKU cart items.

Writes to BOTH MySQL (application_state.storefront with correct sha256) and
the JSON file (storefront-state.json), then the server is restarted to reload.
"""

import json
import hashlib
import subprocess
import sys
import copy
from datetime import datetime, timedelta

DATA_FILE = '/root/yulin-youxian/server/data/storefront-state.json'
USER_ID = 10001
PHONE = '18195819181'
NAME = '徐嘉诚'

# ============================================================
# 1. Load existing data
# ============================================================
with open(DATA_FILE, 'r', encoding='utf-8') as f:
    data = json.load(f)

print(f"Loaded {len(data['orders'])} existing orders, {len(data['refunds'])} refunds")

# ============================================================
# 2. ID allocation (from current max + 1)
# ============================================================
max_order_id = max(o.get('id', 0) for o in data['orders'])
max_item_id = 0
for o in data['orders']:
    for it in o.get('items', []):
        if it.get('id', 0) > max_item_id:
            max_item_id = it['id']
max_refund_id = max(r.get('refund', {}).get('id', 0) for r in data['refunds'])
max_refund_no = max(int(r.get('refund', {}).get('refundNo', 'RF0')[2:]) for r in data['refunds'])
max_cart_id = max(c.get('id', 0) for c in data['cartItems'])

next_order_id = max_order_id + 1      # 1140
next_item_id = max_item_id + 1        # 10012
next_refund_id = max_refund_id + 1    # 2020
next_refund_no = max_refund_no + 1    # 2020
next_cart_id = max_cart_id + 1        # 127

print(f"ID allocation: orders from {next_order_id}, items from {next_item_id}, "
      f"refunds from {next_refund_id}/RF{next_refund_no}, cart from {next_cart_id}")

# ============================================================
# 3. Helper: pick products for order items
# ============================================================
# Use real products from the data (with SKUs for variety)
PRODUCT_POOL = []
for p in data['products'][:12]:
    product = {
        'id': p['id'],
        'name': p['name'],
        'imageUrl': p.get('imageUrl', ''),
        'saleUnit': p.get('saleUnit', '份'),
        'unitPrice': p.get('unitPrice', 299),
    }
    # Use first SKU if available
    if p.get('skus'):
        sku = p['skus'][0]
        product['skuId'] = sku['id']
        product['skuCode'] = sku.get('skuCode', '')
        product['specificationText'] = sku.get('specificationText', '')
        product['unitPrice'] = sku.get('unitPrice', product['unitPrice'])
    else:
        product['skuId'] = None
        product['skuCode'] = None
        product['specificationText'] = None
    PRODUCT_POOL.append(product)

DELIVERY_SLOTS = ['今日 09:00-11:00', '今日 14:00-16:00', '明日 09:00-11:00',
                  '明日 14:00-16:00', '明日 15:00-17:00']

# Base address (user 10001's existing address)
BASE_ADDRESS = {
    'name': NAME,
    'phone': PHONE,
    'detail': '1号楼 1202 室',
    'locationName': '乌鲁木齐社区',
    'latitude': 43.8256,
    'longitude': 87.6168,
}

# Additional addresses to add
NEW_ADDRESSES = [
    {
        'name': NAME,
        'phone': PHONE,
        'detail': '3号楼 503 室',
        'locationName': '幸福花园小区',
        'latitude': 43.8351,
        'longitude': 87.6212,
        'isDefault': False,
    },
    {
        'name': NAME,
        'phone': PHONE,
        'detail': 'A座 1801 室',
        'locationName': '万达广场写字楼',
        'latitude': 43.8401,
        'longitude': 87.5989,
        'isDefault': False,
    },
    {
        'name': '李丽（同事代收）',
        'phone': '13800001111',
        'detail': '2号楼 301 室',
        'locationName': '幸福花园小区',
        'latitude': 43.8351,
        'longitude': 87.6212,
        'isDefault': False,
    },
]

# Add new addresses (id starts from 2 for user 10001)
addr_id = 2
for new_addr in NEW_ADDRESSES:
    addr_entry = {
        'userId': USER_ID,
        'address': {
            'id': addr_id,
            **new_addr,
        }
    }
    data['addresses'].append(addr_entry)
    print(f"  + Address id={addr_id}: {new_addr['detail']} ({new_addr['locationName']})")
    addr_id += 1

# ============================================================
# 4. Generate orders covering all 7 statuses
# ============================================================
# Status financial semantics:
# 待支付: paidAmount=0, refundedAmount=0
# 已取消: paidAmount=0, refundedAmount=0
# 已关闭: paidAmount=0, refundedAmount=0
# 已完成: paidAmount=payableAmount, refundedAmount=0
# 已退款: paidAmount=payableAmount, refundedAmount=payableAmount (full)
# 退款中: paidAmount=payableAmount, refundedAmount=0 (refund pending)
# 部分退款: paidAmount=payableAmount, refundedAmount<payableAmount (partial)

ORDER_TEMPLATES = [
    # (status, count, paid_multiplier, refunded_multiplier, remark, payment_method)
    ('待支付', 2, 0, 0, '尽快送达', None),
    ('已取消', 2, 0, 0, '改天再买', None),
    ('已关闭', 2, 0, 0, '超时未支付自动关闭', None),
    ('已完成', 2, 1, 0, '已完成订单', 'WECHAT'),  # 1 with wechat pay
    ('已完成', 1, 1, 0, '好友代付已完成', 'FRIEND'),  # 1 with friend pay
    ('已退款', 2, 1, 1, '全额退款', 'WECHAT'),
    ('退款中', 2, 1, 0, '退款审核中', 'WECHAT'),
    ('部分退款', 2, 1, 0.5, '部分退款', 'WECHAT'),  # 50% refunded
]

new_orders = []
new_refunds = []
new_payment_methods = {}
new_payment_shares = {}
new_payment_txids = {}
new_payment_order_nos = {}
new_payment_started_ats = {}

# Use a base time spread over the last 7 days
base_time = datetime(2026, 8, 5, 10, 0, 0)

for status, count, paid_mult, refunded_mult, remark, pay_method in ORDER_TEMPLATES:
    for i in range(count):
        order_id = next_order_id
        next_order_id += 1

        # Generate orderNo: XD + YYYYMMDD + HHMMSS + orderId
        order_time = base_time + timedelta(hours=len(new_orders) * 6)
        order_no = f"XD{order_time.strftime('%Y%m%d%H%M%S')}{order_id}"

        # Pick 2-3 products for this order
        num_items = 2 + (i % 2)  # 2 or 3 items
        chosen = [PRODUCT_POOL[(order_id + j) % len(PRODUCT_POOL)] for j in range(num_items)]

        items = []
        product_amount = 0
        for j, p in enumerate(chosen):
            qty = 1 + (j % 3)  # 1-3 quantity
            unit_price = p['unitPrice']
            amount = unit_price * qty
            product_amount += amount
            items.append({
                'id': next_item_id,
                'productId': p['id'],
                'productName': p['name'],
                'imageUrl': p['imageUrl'],
                'saleUnit': p['saleUnit'],
                'unitPrice': unit_price,
                'quantity': qty,
                'amount': amount,
                'skuId': p['skuId'],
                'skuCode': p['skuCode'],
                'specificationText': p['specificationText'],
            })
            next_item_id += 1

        # Fees
        delivery_fee = 500  # 5.00 yuan in cents
        package_fee = 100   # 1.00 yuan in cents
        payable_amount = product_amount + delivery_fee + package_fee
        paid_amount = int(payable_amount * paid_mult)
        refunded_amount = int(payable_amount * refunded_mult)

        # Pick address (cycle through user 10001's addresses: id 1-4)
        addr_idx = (order_id % 4) + 1  # 1-4
        # Find the address for user 10001 with this id
        order_address = None
        for a in data['addresses']:
            if a.get('userId') == USER_ID and a.get('address', {}).get('id') == addr_idx:
                order_address = copy.deepcopy(a['address'])
                break
        if not order_address:
            # Fallback to address id 1
            for a in data['addresses']:
                if a.get('userId') == USER_ID and a.get('address', {}).get('id') == 1:
                    order_address = copy.deepcopy(a['address'])
                    break

        # Delivery slot
        slot = DELIVERY_SLOTS[(order_id) % len(DELIVERY_SLOTS)]

        # Timestamps
        created_at = order_time.strftime('%Y-%m-%dT%H:%M:%S') + f'.{1000000 + order_id}'
        delivery_date = (order_time + timedelta(days=1)).strftime('%Y-%m-%d')

        order = {
            'id': order_id,
            'userId': USER_ID,
            'orderNo': order_no,
            'status': status,
            'address': order_address,
            'deliverySlot': slot,
            'items': items,
            'productAmount': product_amount,
            'deliveryFee': delivery_fee,
            'packageFee': package_fee,
            'payableAmount': payable_amount,
            'paidAmount': paid_amount,
            'refundedAmount': refunded_amount,
            'remark': f'{remark}（调试数据）',
            'createdAt': created_at,
            'deliveryDate': delivery_date if status in ('已完成', '待支付', '退款中', '部分退款') else None,
        }
        new_orders.append(order)

        # Payment records for paid orders
        if paid_amount > 0 and pay_method == 'WECHAT':
            # WeChat payment transaction ID
            txid = f"4200000{300 + order_id}2026081010{order_id}"
            new_payment_txids[order_no] = txid

        if pay_method == 'FRIEND':
            # Friend pay: add paymentMethods and paymentShares
            new_payment_methods[str(order_id)] = 'FRIEND'
            import uuid
            token = uuid.uuid4().hex
            new_payment_shares[token] = {
                'token': token,
                'orderId': order_id,
                'creatorUserId': USER_ID,
                'createdAt': created_at,
            }
            # Also add transaction id for completed friend pay
            if status == '已完成':
                txid = f"4200000{400 + order_id}2026081010{order_id}"
                new_payment_txids[order_no] = txid

        # For 待支付 orders, add paymentOrderNos and paymentStartedAts (payment in progress)
        if status == '待支付':
            import string, random
            suffix = ''.join(random.choices(string.ascii_uppercase + string.digits, k=8))
            new_payment_order_nos[str(order_id)] = f"{order_no}{suffix}"
            new_payment_started_ats[str(order_id)] = created_at

        # Generate refunds for refund-related statuses
        if status == '已退款':
            # Full refund (退款成功)
            refund_id = next_refund_id
            refund_no = f"RF{next_refund_no}"
            next_refund_id += 1
            next_refund_no += 1
            new_refunds.append({
                'userId': USER_ID,
                'refund': {
                    'id': refund_id,
                    'orderId': order_id,
                    'refundNo': refund_no,
                    'refundAmount': paid_amount,
                    'reason': '商品质量问题，申请全额退款（调试数据）',
                    'status': '退款成功',
                    'evidenceImages': ['/assets/products/tomato.png'],
                    'userId': USER_ID,
                    'orderNo': order_no,
                    'source': 'USER',
                    'createdAt': (order_time + timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%S'),
                }
            })
        elif status == '退款中':
            # Pending refund (待审核)
            refund_id = next_refund_id
            refund_no = f"RF{next_refund_no}"
            next_refund_id += 1
            next_refund_no += 1
            new_refunds.append({
                'userId': USER_ID,
                'refund': {
                    'id': refund_id,
                    'orderId': order_id,
                    'refundNo': refund_no,
                    'refundAmount': paid_amount,
                    'reason': '收到商品损坏，申请退款（调试数据）',
                    'status': '待审核',
                    'evidenceImages': ['/assets/products/tomato.png'],
                    'userId': USER_ID,
                    'orderNo': order_no,
                    'source': 'USER',
                    'createdAt': (order_time + timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%S'),
                }
            })
        elif status == '部分退款':
            # Partial refund (退款成功, partial amount)
            refund_id = next_refund_id
            refund_no = f"RF{next_refund_no}"
            next_refund_id += 1
            next_refund_no += 1
            partial_amount = refunded_amount if refunded_amount > 0 else int(paid_amount * 0.3)
            new_refunds.append({
                'userId': USER_ID,
                'refund': {
                    'id': refund_id,
                    'orderId': order_id,
                    'refundNo': refund_no,
                    'refundAmount': partial_amount,
                    'reason': '部分商品质量问题，申请部分退款（调试数据）',
                    'status': '退款成功',
                    'evidenceImages': ['/assets/products/tomato.png'],
                    'userId': USER_ID,
                    'orderNo': order_no,
                    'source': 'USER',
                    'createdAt': (order_time + timedelta(hours=3)).strftime('%Y-%m-%dT%H:%M:%S'),
                }
            })
        elif status == '已完成' and i == 0 and count == 2:
            # Add one rejected refund for a completed order (已拒绝)
            refund_id = next_refund_id
            refund_no = f"RF{next_refund_no}"
            next_refund_id += 1
            next_refund_no += 1
            new_refunds.append({
                'userId': USER_ID,
                'refund': {
                    'id': refund_id,
                    'orderId': order_id,
                    'refundNo': refund_no,
                    'refundAmount': int(paid_amount * 0.2),
                    'reason': '申请退款（调试数据，被拒绝）',
                    'status': '已拒绝',
                    'evidenceImages': [],
                    'userId': USER_ID,
                    'orderNo': order_no,
                    'source': 'USER',
                    'createdAt': (order_time + timedelta(hours=5)).strftime('%Y-%m-%dT%H:%M:%S'),
                }
            })

# Add new orders to data
data['orders'].extend(new_orders)
print(f"\nGenerated {len(new_orders)} new orders for user {USER_ID}")
print(f"  Statuses: { {s: sum(1 for o in new_orders if o['status']==s) for s in set(o['status'] for o in new_orders)} }")

# Add new refunds
data['refunds'].extend(new_refunds)
print(f"Generated {len(new_refunds)} new refunds")
print(f"  Statuses: { {s: sum(1 for r in new_refunds if r['refund']['status']==s) for s in set(r['refund']['status'] for r in new_refunds)} }")

# Merge payment records
data.setdefault('paymentMethods', {}).update(new_payment_methods)
data.setdefault('paymentShares', {}).update(new_payment_shares)
data.setdefault('paymentTransactionIds', {}).update(new_payment_txids)
data.setdefault('paymentOrderNos', {}).update(new_payment_order_nos)
data.setdefault('paymentStartedAts', {}).update(new_payment_started_ats)
print(f"Payment records: {len(new_payment_methods)} methods, {len(new_payment_shares)} shares, "
      f"{len(new_payment_txids)} txids, {len(new_payment_order_nos)} order_nos, {len(new_payment_started_ats)} started_ats")

# ============================================================
# 5. Add cart items with multi-SKU products
# ============================================================
CART_ITEMS_TEMPLATE = [
    # (product_index, quantity, selected)
    (0, 2, True),   # 西红柿 2斤
    (1, 1, True),   # 西兰花
    (2, 3, False),  # 螺丝椒
    (4, 1, True),   # 紫皮洋葱
]

for prod_idx, qty, selected in CART_ITEMS_TEMPLATE:
    p = PRODUCT_POOL[prod_idx]
    cart_item = {
        'id': next_cart_id,
        'userId': USER_ID,
        'productId': p['id'],
        'skuId': p['skuId'],
        'quantity': qty,
        'selected': selected,
    }
    data['cartItems'].append(cart_item)
    print(f"  + Cart item id={next_cart_id}: {p['name']} x{qty} (sku={p['skuId']}, selected={selected})")
    next_cart_id += 1

# ============================================================
# 6. Validation
# ============================================================
print("\n=== VALIDATION ===")

# Check no duplicate order IDs
all_order_ids = [o['id'] for o in data['orders']]
assert len(all_order_ids) == len(set(all_order_ids)), "DUPLICATE ORDER IDs!"
print(f"  ✓ Order IDs unique ({len(all_order_ids)} total)")

# Check no duplicate refund IDs
all_refund_ids = [r['refund']['id'] for r in data['refunds']]
assert len(all_refund_ids) == len(set(all_refund_ids)), "DUPLICATE REFUND IDs!"
print(f"  ✓ Refund IDs unique ({len(all_refund_ids)} total)")

# Check user 10001 order status coverage
u10001_orders = [o for o in data['orders'] if o['userId'] == USER_ID]
u10001_statuses = set(o['status'] for o in u10001_orders)
expected_statuses = {'待支付', '已取消', '已关闭', '已完成', '已退款', '退款中', '部分退款'}
missing = expected_statuses - u10001_statuses
assert not missing, f"MISSING order statuses: {missing}"
print(f"  ✓ User {USER_ID} has all 7 order statuses: {sorted(u10001_statuses)}")
print(f"    Order count by status: { {s: sum(1 for o in u10001_orders if o['status']==s) for s in sorted(u10001_statuses)} }")

# Check user 10001 refund status coverage
u10001_refunds = [r for r in data['refunds'] if r['userId'] == USER_ID]
u10001_refund_statuses = set(r['refund']['status'] for r in u10001_refunds)
expected_refund_statuses = {'待审核', '已拒绝', '退款成功'}
missing_r = expected_refund_statuses - u10001_refund_statuses
assert not missing_r, f"MISSING refund statuses: {missing_r}"
print(f"  ✓ User {USER_ID} has all 3 refund statuses: {sorted(u10001_refund_statuses)}")

# Check no suspicious question marks (3+ consecutive ?)
json_str = json.dumps(data, ensure_ascii=False)
import re
if re.search(r'\?{3,}', json_str):
    print("  ✗ WARNING: Suspicious '???' found in data!")
    sys.exit(1)
else:
    print(f"  ✓ No suspicious '???' in data")

# Check financial consistency
for o in new_orders:
    s = o['status']
    if s in ('待支付', '已取消', '已关闭'):
        assert o['paidAmount'] == 0, f"Order {o['id']} ({s}): paidAmount should be 0, got {o['paidAmount']}"
        assert o['refundedAmount'] == 0, f"Order {o['id']} ({s}): refundedAmount should be 0"
    elif s == '已完成':
        assert o['paidAmount'] == o['payableAmount'], f"Order {o['id']} (已完成): paid != payable"
        assert o['refundedAmount'] == 0, f"Order {o['id']} (已完成): refunded should be 0"
    elif s == '已退款':
        assert o['paidAmount'] == o['payableAmount'], f"Order {o['id']} (已退款): paid != payable"
        assert o['refundedAmount'] == o['payableAmount'], f"Order {o['id']} (已退款): refunded != payable"
    elif s == '退款中':
        assert o['paidAmount'] == o['payableAmount'], f"Order {o['id']} (退款中): paid != payable"
        assert o['refundedAmount'] == 0, f"Order {o['id']} (退款中): refunded should be 0"
    elif s == '部分退款':
        assert o['paidAmount'] == o['payableAmount'], f"Order {o['id']} (部分退款): paid != payable"
        assert 0 < o['refundedAmount'] < o['payableAmount'], f"Order {o['id']} (部分退款): refunded should be partial"
print(f"  ✓ Financial consistency verified for all {len(new_orders)} new orders")

# Check all new orders reference valid user 10001 addresses
u10001_addr_ids = {a['address']['id'] for a in data['addresses'] if a['userId'] == USER_ID}
for o in new_orders:
    assert o['address']['id'] in u10001_addr_ids, f"Order {o['id']}: address id {o['address']['id']} not valid for user {USER_ID}"
print(f"  ✓ All new orders reference valid addresses (ids: {sorted(u10001_addr_ids)})")

print(f"\n=== SUMMARY ===")
print(f"  Total orders: {len(data['orders'])} (was {max_order_id - 1000 + 1000}, added {len(new_orders)})")
print(f"  Total refunds: {len(data['refunds'])} (added {len(new_refunds)})")
print(f"  Total addresses: {len(data['addresses'])} (added {len(NEW_ADDRESSES)})")
print(f"  Total cart items: {len(data['cartItems'])} (added {len(CART_ITEMS_TEMPLATE)})")
print(f"  User {USER_ID} orders: {len(u10001_orders)}")
print(f"  User {USER_ID} refunds: {len(u10001_refunds)}")

# ============================================================
# 7. Serialize and compute sha256
# ============================================================
# Use compact JSON (no indentation) for MySQL storage efficiency
# But for the JSON file, match existing format (indent=2)
json_compact = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
json_pretty = json.dumps(data, ensure_ascii=False, indent=2)

# Compute sha256 on UTF-8 bytes of the compact version (for MySQL)
payload_bytes = json_compact.encode('utf-8')
sha256_hash = hashlib.sha256(payload_bytes).hexdigest()

print(f"\n=== OUTPUT ===")
print(f"  Compact JSON: {len(json_compact)} chars, {len(payload_bytes)} bytes")
print(f"  Pretty JSON: {len(json_pretty)} chars")
print(f"  SHA-256: {sha256_hash}")

# Save to temp files for the next step
with open('/tmp/storefront_new_compact.json', 'w', encoding='utf-8') as f:
    f.write(json_compact)
with open('/tmp/storefront_new_pretty.json', 'w', encoding='utf-8') as f:
    f.write(json_pretty)
with open('/tmp/storefront_sha256.txt', 'w') as f:
    f.write(sha256_hash)

print(f"\n  Saved to /tmp/storefront_new_compact.json (for MySQL)")
print(f"  Saved to /tmp/storefront_new_pretty.json (for JSON file)")
print(f"  SHA-256 saved to /tmp/storefront_sha256.txt")
print(f"\n✅ Data generation complete. Ready to write to MySQL + JSON file.")
