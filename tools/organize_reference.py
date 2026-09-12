"""Arrange the comparison screenshots into reference/ so old and new can be compared.

The images were recovered from the session transcript with timestamp+hash names, which say
nothing about what they show. This moves each one to a name that states its role, and keeps the
originals that are duplicates or unrelated out of the way rather than deleting them.

Run from the project root:  python tools/organize_reference.py
"""

import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(ROOT, "reference")
BUILD = os.path.join(ROOT, "build")

# Repeater shots: 03/04 are full resolution (2080x751 / 2088x741); 15/16 are the copies that came
# attached to the last message, downscaled to 2000px wide. Keep both, measure only on 03/04.
REPEATER = {
    "03_20260912T122103_4e11f42575.png": "repeater-light.png",
    "04_20260912T122103_64d6658a82.png": "repeater-dark.png",
    "15_20260912T132906_eefa4f7d0e.jpg": "repeater-light-downscaled.jpg",
    "16_20260912T132906_c50dec79f5.jpg": "repeater-dark-downscaled.jpg",
}

BEFORE = {
    "01_20260912T121039_12bdef9464.png": "studio-ui-old.png",
    "02_20260912T121039_5d6c2d96ec.png": "poc-render-old.png",
    "14_20260912T132906_9cd98d0dd2.png": "poc-render-old-repaste.png",
}

# Renders produced during the refactor at earlier points. Kept so the progression is visible,
# but they are not the current output.
ITERATIONS = {
    "05_20260912T124229_8140c49652.png": "render-dark-iter1.png",
    "06_20260912T124311_b25bea3681.png": "render-dark-iter2.png",
    "07_20260912T124323_13b68ca9f9.png": "render-light-iter1.png",
    "08_20260912T124458_1a5274199e.png": "render-light-iter2.png",
    "09_20260912T124502_951a464501.png": "render-dark-iter3.png",
    "11_20260912T125951_f6dd78cb94.png": "render-dark-iter4.png",
    "12_20260912T125955_1d7bce10d6.png": "render-light-iter3.png",
}

# Byte-identical to build/palette_dark.png, and a screenshot of a different product entirely.
ARCHIVE = {
    "10_20260912T125950_f238676884.png": "palette-dark-duplicate.png",
    "17_20260912T114225_6a6ad2b34f.png": "unrelated-claude-skills-ui.png",
}

AFTER = {
    "test_poc_dark.png": "poc-render-dark.png",
    "test_poc_light.png": "poc-render-light.png",
    "palette_dark.png": "palette-dark.png",
    "palette_light.png": "palette-light.png",
}

# Magnified crops, made by tools/crop_region.py. These are the images the palette is read from,
# so they belong next to the screenshots they came from.
ZOOMS = {
    "zoom_light_req1a.png": "zoom-request-line-light.png",
    "zoom_dark_req1a.png": "zoom-request-line-dark.png",
    "zoom_light_body.png": "zoom-html-body-light.png",
    "zoom_dark_body.png": "zoom-html-body-dark.png",
    "view_light.png": "pane-light.png",
    "view_dark.png": "pane-dark.png",
}


def place(source_dir, mapping, target_dir, copy=False):
    os.makedirs(target_dir, exist_ok=True)
    moved = 0
    for old, new in mapping.items():
        src = os.path.join(source_dir, old)
        dest = os.path.join(target_dir, new)
        if not os.path.exists(src):
            if os.path.exists(dest):
                print("  already placed %s" % new)
                continue
            print("  skip (absent) %s" % old)
            continue
        # The build outputs are copied, not moved: the verification test rewrites them there and
        # the README points at that path.
        (shutil.copy2 if copy else shutil.move)(src, dest)
        moved += 1
    print("  %-40s %d files" % (os.path.relpath(target_dir, ROOT), moved))


def main():
    if not os.path.isdir(REF):
        print("no reference directory at %s" % REF)
        return 1

    print("organising %s" % REF)
    place(REF, REPEATER, os.path.join(REF, "burp-repeater"))
    place(REF, BEFORE, os.path.join(REF, "before"))
    place(REF, ITERATIONS, os.path.join(REF, "archive"))
    place(REF, ARCHIVE, os.path.join(REF, "archive"))
    place(BUILD, AFTER, os.path.join(REF, "after"), copy=True)
    place(BUILD, ZOOMS, os.path.join(REF, "burp-repeater", "zoom"))

    leftovers = [n for n in os.listdir(REF) if os.path.isfile(os.path.join(REF, n))]
    print("left in reference/: %s" % (leftovers if leftovers else "nothing"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
