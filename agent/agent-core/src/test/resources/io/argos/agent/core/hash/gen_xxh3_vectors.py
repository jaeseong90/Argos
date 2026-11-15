# SPDX-License-Identifier: Apache-2.0
#
# Generates xxh3-vectors.txt, the reference values of Xxh3Test (10 U-17, DSN-10-18).
#
# The expected values come from the reference xxHash C library through python-xxhash, not from
# the Java code under test. Run it in an isolated virtual environment:
#
#   python -m venv venv && venv/bin/python -m pip install xxhash==3.5.0
#   venv/bin/python -I gen_xxh3_vectors.py > xxh3-vectors.txt
#
# python-xxhash 3.5.0 bundles xxHash 0.8.2; the script refuses other versions so that the fixture
# header always names the library that produced the values.
import sys

import xxhash

if xxhash.VERSION != "3.5.0" or xxhash.XXHASH_VERSION != "0.8.2":
    sys.exit("expected python-xxhash 3.5.0 (xxHash 0.8.2), found %s (%s)"
             % (xxhash.VERSION, xxhash.XXHASH_VERSION))


def pattern(length):
    # Deterministic bytes that the Java test rebuilds: b[i] = (i * 131 + 17) % 251.
    return bytes((i * 131 + 17) % 251 for i in range(length))


def h(data):
    return "%016x" % xxhash.xxh3_64_intdigest(data, seed=0)


# Every length class of XXH3-64: 0, 1-3, 4-8, 9-16, 17-128 (each 32-byte step), 129-240,
# > 240 (one stripe, several stripes, one 1,024-byte block, several blocks, partial last block).
PATTERN_LENGTHS = [
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 16,
    17, 18, 31, 32, 33, 48, 63, 64, 65, 80, 95, 96, 97, 112, 127, 128,
    129, 130, 144, 160, 192, 200, 224, 238, 239, 240,
    241, 242, 255, 256, 257, 300, 511, 512, 513, 1000, 1023, 1024, 1025,
    1087, 1088, 1089, 1100, 2047, 2048, 2049, 2240, 3000, 4096, 5000, 8192, 10000, 65536, 100003,
]

FILLS = [(0x00, 1), (0x00, 17), (0x00, 300), (0x00, 1024), (0xFF, 3), (0xFF, 129), (0xFF, 2049)]

TEXTS = [
    "",
    "a",
    "abc",
    "_other_",
    "<unparsed>",
    "GET /api/orders/{id}",
    "SELECT * FROM orders WHERE id = ?",
    "주문 조회",  # Korean "order lookup"
    "\U0001F600 emoji",
    "com.example.shop.OrderService.find(OrderService.java:42)",
    "SELECT o.id, o.status, c.name FROM orders o JOIN customers c ON c.id = o.customer_id"
    " WHERE o.created_at > ? AND o.status IN (?) ORDER BY o.created_at DESC LIMIT ?"
    " /* page */ UNION ALL SELECT a.id, a.status, b.name FROM archived_orders a JOIN"
    " customers b ON b.id = a.customer_id WHERE a.created_at > ? AND a.status IN (?)",
]

print("# SPDX-License-Identifier: Apache-2.0")
print("# XXH3-64 (seed 0) reference values for Xxh3Test.")
print("# Produced by python-xxhash %s (bundles the reference xxHash C library %s):"
      % (xxhash.VERSION, xxhash.XXHASH_VERSION))
print("#   xxhash.xxh3_64_intdigest(data, seed=0), script gen_xxh3_vectors.py in this directory.")
print("# Lines: 'pattern <length> <hash>' with data[i] = (i * 131 + 17) % 251,")
print("#        'fill <byte hex> <length> <hash>', 'utf8 <UTF-8 bytes hex or -> <hash>'.")
print("# Hashes are the raw 64-bit XXH3 values as 16 hex digits (before the 0 -> 1 id remap).")
for length in PATTERN_LENGTHS:
    print("pattern %d %s" % (length, h(pattern(length))))
for value, length in FILLS:
    print("fill %02x %d %s" % (value, length, h(bytes([value]) * length)))
for text in TEXTS:
    data = text.encode("utf-8")
    print("utf8 %s %s" % (data.hex() if data else "-", h(data)))
