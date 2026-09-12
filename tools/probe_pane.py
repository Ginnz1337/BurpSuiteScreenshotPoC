"""Detect the text lines of a Repeater pane and report the colours each one uses.

Guessing y offsets is what made the first pass read the wrapped URL row instead of the header
rows. This finds the rows that actually carry ink, groups them into lines, and reports the
colours per line, so a colour can be tied to a token by the line it came from.

Usage: python probe_pane.py <image.png> <x0> <x1> <y0> <y1>
"""

import sys
from collections import Counter
from PIL import Image


def is_grey(rgb, tolerance=18):
    return max(rgb) - min(rgb) < tolerance


def main():
    if len(sys.argv) < 6:
        print(__doc__)
        return 1
    path = sys.argv[1]
    x0, x1, y0, y1 = (int(v) for v in sys.argv[2:6])

    image = Image.open(path).convert("RGB")
    pixels = image.load()

    background = Counter()
    for y in range(y0, y1):
        for x in range(x0, x1):
            background[pixels[x, y]] += 1
    bg = background.most_common(1)[0][0]
    print("%s  pane x=%d..%d y=%d..%d  background #%02x%02x%02x"
          % (path, x0, x1, y0, y1, bg[0], bg[1], bg[2]))

    def row_colours(y):
        colours = Counter()
        for x in range(x0, x1):
            rgb = pixels[x, y]
            if is_grey(rgb):
                continue
            if sum(abs(a - b) for a, b in zip(rgb, bg)) < 45:
                continue
            colours[rgb] += 1
        return colours

    lines = []
    current = None
    for y in range(y0, y1):
        colours = row_colours(y)
        if colours:
            if current is None:
                current = [y, Counter()]
            current[1].update(colours)
        elif current is not None:
            current.append(y - 1)
            lines.append(current)
            current = None
    if current is not None:
        current.append(y1 - 1)
        lines.append(current)

    for index, (top, colours, bottom) in enumerate(lines, start=1):
        total = sum(colours.values())
        parts = []
        for rgb, hits in colours.most_common(5):
            if hits < max(12, total * 0.10):
                continue
            parts.append("#%02x%02x%02x:%d" % (rgb[0], rgb[1], rgb[2], hits))
        print("  row %-3d y=%3d..%-3d  %s" % (index, top, bottom, "  ".join(parts)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
