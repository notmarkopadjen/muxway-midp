#!/usr/bin/env python3
"""Draws the app's one-bit pictures into res/: the icon and the frames of
the wait bar (a box with a block running along inside it). PNGs are written
by hand so nothing beyond Python is needed."""

import struct
import zlib
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "res"

ICON = """
................
.##############.
.#............#.
.#.#..........#.
.#..#.........#.
.#...#........#.
.#..#.........#.
.#.#...#####..#.
.#............#.
.#............#.
.##############.
.#....#..#....#.
.#....#..#....#.
.##############.
................
................
"""

BAR_W, BAR_H, CELLS, BLOCK = 64, 9, 12, 3


def png(rows):
    w, h = len(rows[0]), len(rows)
    raw = b""
    for row in rows:
        bits = "".join("0" if c else "1" for c in row).ljust((w + 7) // 8 * 8, "1")
        raw += b"\x00" + int(bits, 2).to_bytes(len(bits) // 8, "big")

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 1, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def bar(at):
    cell = (BAR_W - 4) // CELLS
    px = [[0] * BAR_W for _ in range(BAR_H)]
    for x in range(BAR_W):
        px[0][x] = px[BAR_H - 1][x] = 1
    for y in range(BAR_H):
        px[y][0] = px[y][BAR_W - 1] = 1
    for c in range(at, min(at + BLOCK, CELLS)):
        for y in range(2, BAR_H - 2):
            for x in range(2 + c * cell, 2 + (c + 1) * cell):
                px[y][x] = 1
    return px


if __name__ == "__main__":
    RES.mkdir(exist_ok=True)
    icon = [[1 if c == "#" else 0 for c in row] for row in ICON.strip("\n").split("\n")]
    (RES / "icon.png").write_bytes(png(icon))
    for n in range(CELLS):
        (RES / f"wait{n}.png").write_bytes(png(bar(n)))
