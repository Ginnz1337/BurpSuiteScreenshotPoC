"""Print the coloured runs along one text row, left to right.

This is the decisive probe: it shows where each colour starts and stops along the row, so a
colour can be matched to the token sitting at that x. Nothing here is inferred from a whole-row
histogram or from looking at a downscaled picture.

Usage: python probe_runs.py <image.png> <x0> <x1> <y0> <y1>
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
    print("%s  y=%d..%d  background #%02x%02x%02x" % (path, y0, y1, bg[0], bg[1], bg[2]))

    # A column is inked if any pixel in the band is far enough from the background. Grey is NOT
    # filtered here on purpose: the neutral value text is grey in both themes, and dropping it was
    # what hid half of every header row.
    column = []
    for x in range(x0, x1):
        colours = Counter()
        for y in range(y0, y1):
            rgb = pixels[x, y]
            if sum(abs(a - b) for a, b in zip(rgb, bg)) < 60:
                continue
            colours[rgb] += 1
        column.append(colours.most_common(1)[0][0] if colours else None)

    runs = []
    start = None
    current = None
    for offset, rgb in enumerate(column):
        if rgb != current:
            if current is not None:
                runs.append((start, x0 + offset - 1, current))
            start = x0 + offset if rgb is not None else None
            current = rgb
    if current is not None:
        runs.append((start, x1 - 1, current))

    for left, right, rgb in runs:
        if right - left < 1:
            continue
        print("  x %4d-%-4d  %5d px  #%02x%02x%02x" % (left, right, right - left + 1,
                                                       rgb[0], rgb[1], rgb[2]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
