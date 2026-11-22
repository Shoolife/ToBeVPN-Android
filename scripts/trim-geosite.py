#!/usr/bin/env python3
"""Keeps only the Russian categories of a geosite.dat (the router's
"Russian sites direct" list), in their original order.

geosite.dat is a protobuf GeoSiteList: repeated GeoSite entry = 1, each
GeoSite starting with its country_code (field 1, string). Whole entries are
copied, so the result is a valid, much smaller geosite.dat: the phone uses
the file only for the router's list (the app itself has no geosite rules).

Usage: trim-geosite.py <full geosite.dat> <output>
The categories must match RouterAgent.RU_DIRECT_DOMAIN_GROUPS.
"""
import sys

GROUPS = [
    "category-bank-ru",
    "category-betting-ru",
    "category-ecommerce-ru",
    "category-entertainment-ru",
    "category-gov-ru",
    "category-media-ru",
    "category-medicine-ru",
    "category-retail-ru",
    "category-ru",
    "category-travel-ru",
    "ru-available-only-inside",
]


def varint(data: bytes, index: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        if index >= len(data):
            raise ValueError("geosite.dat is damaged")
        byte = data[index]
        index += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if byte < 0x80:
            return result, index


def trim(data: bytes) -> bytes:
    kept, found, index = [], set(), 0
    while index < len(data):
        start = index
        key, index = varint(data, index)
        if key != (1 << 3 | 2):
            raise ValueError("geosite.dat is damaged")
        length, index = varint(data, index)
        body = data[index:index + length]
        index += length
        tag, offset = varint(body, 0)
        code = ""
        if tag == (1 << 3 | 2):
            size, offset = varint(body, offset)
            code = body[offset:offset + size].decode("utf-8", "replace").lower()
        if code in GROUPS:
            kept.append(data[start:index])
            found.add(code)
    missing = [group for group in GROUPS if group not in found]
    if missing:
        raise ValueError(f"geosite.dat lacks: {', '.join(missing)}")
    return b"".join(kept)


if __name__ == "__main__":
    source, output = sys.argv[1], sys.argv[2]
    with open(source, "rb") as handle:
        trimmed = trim(handle.read())
    with open(output, "wb") as handle:
        handle.write(trimmed)
    print(f"{output}: {len(trimmed)} bytes, {len(GROUPS)} categories")
