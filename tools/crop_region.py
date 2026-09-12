"""Crop and magnify a region of a screenshot so the exact glyph colours are readable.

Eyeballing a downscaled screenshot is how the palette drifted in the first place. This saves a
2x crop of a named region so the colours can be attributed to the right token, and prints the
background colour so the theme of the shot is never in doubt.

Usage: python crop_region.py <image.png> <out.png> <x0> <y0> <x1> <y1> [scale]
"""

import sys
from collections import Counter
from PIL import Image


def main():
    if len(sys.argv) < 7:
        print(__doc__)
        return 1
    path, out = sys.argv[1], sys.argv[2]
    x0, y0, x1, y1 = (int(v) for v in sys.argv[3:7])
    scale = int(sys.argv[7]) if len(sys.argv) > 7 else 2

    image = Image.open(path).convert("RGB")
    print("%s  %dx%d" % (path, image.size[0], image.size[1]))

    whole = Counter(image.getdata())
    for rgb, count in whole.most_common(3):
        print("  background candidate #%02x%02x%02x  %d px" % (rgb[0], rgb[1], rgb[2], count))

    crop = image.crop((x0, y0, x1, y1))
    crop = crop.resize((crop.width * scale, crop.height * scale), Image.NEAREST)
    crop.save(out)
    print("  wrote %s  (%dx%d)" % (out, crop.width, crop.height))
    return 0


if __name__ == "__main__":
    sys.exit(main())
