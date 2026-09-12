"""Read the syntax colors straight out of a Burp Repeater screenshot.

The palette in SyntaxPalette was read by eye off a screenshot, which is why it is close but not
exact. This measures it instead: it walks the message editor area of the screenshot, drops the
greys and the background, and reports the colours that actually appear, most frequent first.

Usage: python palette_probe.py <screenshot.png> [--top N]
"""

import sys
from collections import Counter
from PIL import Image


def is_grey(rgb, tolerance=22):
    high, low = max(rgb), min(rgb)
    return high - low < tolerance


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    top = 25
    if "--top" in sys.argv:
        top = int(sys.argv[sys.argv.index("--top") + 1])

    image = Image.open(path).convert("RGB")
    width, height = image.size
    pixels = image.load()

    # The message editor sits below the Pretty/Raw/Hex toolbar and inside the two panes. The
    # margins skip the pane borders and the scrollbars, which carry their own colours.
    x0, x1 = int(width * 0.01), int(width * 0.99)
    y0, y1 = int(height * 0.14), int(height * 0.97)

    counts = Counter()
    for y in range(y0, y1):
        for x in range(x0, x1):
            rgb = pixels[x, y]
            if is_grey(rgb):
                continue
            counts[rgb] += 1

    total = sum(counts.values())
    print("%s  %dx%d  %d coloured pixels" % (path, width, height, total))
    print("%-10s %-9s %s" % ("hex", "count", "share"))
    for rgb, count in counts.most_common(top):
        print("#%02x%02x%02x  %-9d %.2f%%" % (rgb[0], rgb[1], rgb[2], count,
                                              100.0 * count / max(1, total)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
