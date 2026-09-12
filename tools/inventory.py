"""List size and dominant colours for every image in a folder.

Used to tell the reference screenshots apart by measurement, because two Repeater shots that look
similar in a thumbnail differ only by background, and a whole-set view is what makes the light and
dark pair obvious.

Usage: python inventory.py <folder>
"""

import glob
import os
import sys
from collections import Counter
from PIL import Image


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else "."
    for path in sorted(glob.glob(os.path.join(folder, "*"))):
        if os.path.splitext(path)[1].lower() not in (".png", ".jpg", ".jpeg"):
            continue
        image = Image.open(path).convert("RGB")
        top = Counter(image.getdata()).most_common(3)
        rendered = "  ".join("#%02x%02x%02x %5.1f%%" % (rgb[0], rgb[1], rgb[2],
                                                        100.0 * n / (image.size[0] * image.size[1]))
                             for rgb, n in top)
        print("%-46s %5dx%-5d  %s" % (os.path.basename(path), image.size[0], image.size[1], rendered))
    return 0


if __name__ == "__main__":
    sys.exit(main())
