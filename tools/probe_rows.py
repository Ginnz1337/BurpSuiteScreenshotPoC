"""Report the text colours of one line at a time in a Burp Repeater screenshot.

Reading a colour off a downscaled image by eye is how the palette drifted. This splits a pane
into text lines and, for each line, prints the colours that are neither background nor grey.
Which token owns a colour then follows from which line it came from.

Usage: python probe_rows.py <image.png> <x0> <x1> <y0> <line_height> <line_count> [first_line]
"""

import sys
from collections import Counter
from PIL import Image


def is_grey(rgb, tolerance=18):
    return max(rgb) - min(rgb) < tolerance


def main():
    if len(sys.argv) < 7:
        print(__doc__)
        return 1
    path = sys.argv[1]
    x0, x1, y0, line_height, count = (int(v) for v in sys.argv[2:7])
    first = int(sys.argv[7]) if len(sys.argv) > 7 else 1

    image = Image.open(path).convert("RGB")
    pixels = image.load()

    # The background is whatever fills most of the pane; anything else on a line is text.
    background = Counter()
    for y in range(y0, min(image.size[1], y0 + line_height * count)):
        for x in range(x0, x1):
            background[pixels[x, y]] += 1
    bg = background.most_common(1)[0][0]
    print("%s  background #%02x%02x%02x" % (path, bg[0], bg[1], bg[2]))

    for index in range(count):
        top = y0 + index * line_height
        bottom = min(image.size[1], top + line_height)
        colours = Counter()
        for y in range(top, bottom):
            for x in range(x0, x1):
                rgb = pixels[x, y]
                if is_grey(rgb):
                    continue
                if sum(abs(a - b) for a, b in zip(rgb, bg)) < 40:
                    continue
                colours[rgb] += 1

        total = sum(colours.values())
        rendered = []
        for rgb, hits in colours.most_common(4):
            # Antialiased edges are a blend towards the background, so only a colour that owns a
            # real share of the line is the colour the glyph was drawn in.
            if hits < max(12, total * 0.08):
                continue
            rendered.append("#%02x%02x%02x:%d" % (rgb[0], rgb[1], rgb[2], hits))
        print("  line %-3d %s" % (first + index, "  ".join(rendered) if rendered else "(none)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
