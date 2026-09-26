#!/usr/bin/env python3
"""Builds packaging/hdsl.ico from the launcher's own PNG icons.

Windows reads an .ico that carries PNG-encoded images directly (Vista and
later), so the four sizes the launcher already ships — icon.png, @2x, @4x and
@8x, 32/64/128/256 px — are packed as they are, without re-encoding.

Run from the repository root:

    python3 packaging/make-ico.py
"""

from __future__ import annotations

import struct
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
IMAGES = [
    ("src/main/resources/assets/img/icon.png", 32),
    ("src/main/resources/assets/img/icon@2x.png", 64),
    ("src/main/resources/assets/img/icon@4x.png", 128),
    ("src/main/resources/assets/img/icon@8x.png", 256),
]
OUTPUT = REPO / "packaging" / "hdsl.ico"


def main() -> None:
    blobs: list[tuple[int, bytes]] = []
    for relative, size in IMAGES:
        data = (REPO / relative).read_bytes()
        width, height = struct.unpack(">II", data[16:24])
        if (width, height) != (size, size):
            raise SystemExit(
                f"{relative} is {width}x{height}, expected {size}x{size}"
            )
        blobs.append((size, data))

    header = struct.pack("<HHH", 0, 1, len(blobs))
    entries = bytearray()
    images = bytearray()
    offset = 6 + 16 * len(blobs)
    for size, data in blobs:
        # 256 does not fit the byte Windows reserved for the size, and 0 is
        # the value it specified for it instead.
        byte = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", byte, byte, 0, 0, 1, 32, len(data), offset)
        images += data
        offset += len(data)

    OUTPUT.write_bytes(header + bytes(entries) + bytes(images))
    print(f"wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
