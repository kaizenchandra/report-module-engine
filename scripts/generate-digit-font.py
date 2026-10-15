"""Original seven-segment TrueType test/example font, no third-party glyphs (CC0).

Uses only Python's standard library. OpenType table reference:
https://learn.microsoft.com/en-us/typography/opentype/spec/otff
Run from the repository root; generates regular and wide variants with the same family name.
"""
import math
import struct
from pathlib import Path


def pack(fmt, *args):
    return struct.pack('>' + fmt, *args)


def checksum(data):
    padded = data + b'\0' * (-len(data) % 4)
    return sum(struct.unpack('>' + 'I' * (len(padded) // 4), padded)) & 0xffffffff


def build(width):
    segments = {
        'a': (100, 780, 500, 860), 'b': (480, 450, 560, 780),
        'c': (480, 120, 560, 450), 'd': (100, 40, 500, 120),
        'e': (40, 120, 120, 450), 'f': (40, 450, 120, 780),
        'g': (100, 410, 500, 490),
    }
    digits = ['abcdef', 'bc', 'abdeg', 'abcdg', 'bcfg', 'acdfg', 'acdefg', 'abc', 'abcdefg', 'abcdfg']
    rectangles = {32: [], 44: [(240, -40, 320, 40)], 45: [segments['g']], 46: [(240, 40, 320, 120)]}
    rectangles.update({48 + i: [segments[s] for s in value] for i, value in enumerate(digits)})
    codes = sorted(rectangles)
    glyphs = [[(40, 40, 560, 860)]] + [rectangles[c] for c in codes]
    glyf, offsets = b'', [0]
    for rects in glyphs:
        points = []
        for x0, y0, x1, y1 in rects:
            x0, x1 = round(x0 * width / 600), round(x1 * width / 600)
            points += [(x0, y0), (x0, y1), (x1, y1), (x1, y0)]
        xs, ys = [p[0] for p in points] or [0], [p[1] for p in points] or [0]
        data = pack('hhhhh', len(rects), min(xs), min(ys), max(xs), max(ys))
        data += b''.join(pack('H', 4 * i + 3) for i in range(len(rects))) + pack('H', 0)
        data += bytes([1] * len(points))
        for axis in (0, 1):
            previous = 0
            for point in points:
                data += pack('h', point[axis] - previous)
                previous = point[axis]
        glyf += data + b'\0' * (-len(data) % 4)
        offsets.append(len(glyf))
    count = len(glyphs)
    tables = {
        'glyf': glyf,
        'loca': b''.join(pack('I', n) for n in offsets),
        'hmtx': pack('Hh', width, 0) * count,
        'head': pack('IIIIHHQQhhhhHHhhh', 0x10000, 0x10000, 0, 0x5f0f3cf5, 3, 1000, 0, 0, 0, -100, width, 900, 0, 8, 2, 1, 0),
        'hhea': pack('IhhhH', 0x10000, 900, -100, 0, width) + pack('11hH', 0, 0, width, 1, 0, 0, 0, 0, 0, 0, 0, count),
        'maxp': pack('IH13H', 0x10000, count, 28, 7, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0),
        'post': pack('IIhhIIIII', 0x30000, 0, -75, 50, 1, 0, 0, 0, 0),
        'OS/2': pack('HhHHH', 0, width, 400, 5, 0) + pack('11h', 650, 600, 0, 75, 650, 600, 0, 350, 50, 300, 0) + bytes(10) + pack('4I', 1, 0, 0, 0) + b'RME ' + pack('HHHhhhHH', 64, 32, 57, 900, -100, 0, 900, 100),
    }
    segs = len(codes) + 1
    power = 2 ** int(math.log2(segs))
    sub = pack('7H', 4, 16 + 8 * segs, 0, 2 * segs, 2 * power, int(math.log2(power)), 2 * (segs - power))
    sub += b''.join(pack('H', c) for c in codes + [65535]) + pack('H', 0)
    sub += b''.join(pack('H', c) for c in codes + [65535])
    sub += b''.join(pack('H', (i + 1 - c) & 65535) for i, c in enumerate(codes)) + pack('H', 1)
    sub += pack('H', 0) * segs
    tables['cmap'] = pack('HHHHIHHI', 0, 2, 0, 3, 20, 3, 1, 20) + sub
    names = {0: 'Original ReportDigits synthetic fixture. CC0.', 1: 'ReportDigits', 2: 'Regular', 3: 'ReportDigits-Regular-1', 4: 'ReportDigits Regular', 5: 'Version 1.0', 6: 'ReportDigits-Regular'}
    records, strings = b'', b''
    for key, value in names.items():
        encoded = value.encode('utf-16-be')
        records += pack('6H', 3, 1, 0x409, key, len(encoded), len(strings))
        strings += encoded
    tables['name'] = pack('3H', 0, len(names), 6 + 12 * len(names)) + records + strings
    n = len(tables)
    power = 2 ** int(math.log2(n))
    directory = pack('I4H', 0x10000, n, power * 16, int(math.log2(power)), (n - power) * 16)
    body, positions = b'', {}
    for tag, data in sorted(tables.items()):
        offset = 12 + n * 16 + len(body)
        positions[tag] = offset
        directory += tag.encode('ascii') + pack('III', checksum(data), offset, len(data))
        body += data + b'\0' * (-len(data) % 4)
    result = bytearray(directory + body)
    struct.pack_into('>I', result, positions['head'] + 8, (0xb1b0afba - checksum(result)) & 0xffffffff)
    return result


if __name__ == '__main__':
    for name, width in [('digits.ttf', 600), ('digits-wide.ttf', 750)]:
        path = Path('examples') / name
        path.write_bytes(build(width))
        print(f'{path}: {path.stat().st_size} bytes')
