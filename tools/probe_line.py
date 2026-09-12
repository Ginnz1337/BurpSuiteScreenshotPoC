"""Report the colour of each token position along one line of a Repeater screenshot.

Splits the line into fixed-width buckets and prints the dominant text colour in each, so a
colour can be attributed to the token that sits there rather than guessed from a whole-line
histogram. Bucket boundaries are printed as absolute x so they can be matched against the line.

Usage: python probe_line.py <image.png> <x0> <x1> <y0> <y1> [bucket]
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
    bucket = int(sys.argv[6]) if len(sys.argv) > 6 else 60

    image = Image.open(path).convert("RGB")
    pixels = image.load()

    background = Counter()
    for y in range(y0, y1):
        for x in range(x0, x1):
            background[pixels[x, y]] += 1
    bg = background.most_common(1)[0][0]

    print("%s  line y=%d..%d  background #%02x%02x%02x"
          % (path, y0, y1, bg[0], bg[1], bg[2]))

    ink = 0
    for start in range(x0, x1, bucket):
        end = min(x1, start + bucket)
        colours = Counter()
        for y in range(y0, y1):
            for x in range(start, end):
                rgb = pixels[x, y]
                if is_grey(rgb):
                    continue
                if sum(abs(a - b) for a, b in zip(rgb, bg)) < 40:
                    continue
                colours[rgb] += 1
        if not colours:
            continue
        rgb, hits = colours.most_common(1)[0]
        ink += hits
        print("  x %4d-%-4d  #%02x%02x%02x  %d px" % (start, end, rgb[0], rgb[1], rgb[2], hits))
    print("  coloured ink: %d px" % ink)
    return 0


if __name__ == "__main__":
    sys.exit(main())
