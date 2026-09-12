"""Pull pasted screenshots back out of the Claude Code transcript.

The images the user sends live only inside the conversation transcript as base64 blocks.
This walks every line of every transcript in the project, finds those blocks, and writes each
distinct image to disk so it can be kept as a reference next to the code.

Usage: python extract_images.py <transcript_dir> <out_dir>
"""

import base64
import hashlib
import json
import os
import sys

EXT = {"image/png": "png", "image/jpeg": "jpg", "image/webp": "webp", "image/gif": "gif"}


def walk(node, found):
    """Collect every base64 image block, wherever it sits in the object."""
    if isinstance(node, dict):
        if node.get("type") == "image" and isinstance(node.get("source"), dict):
            src = node["source"]
            if src.get("type") == "base64" and src.get("data"):
                found.append((src.get("media_type", "image/png"), src["data"]))
        for value in node.values():
            walk(value, found)
    elif isinstance(node, list):
        for value in node:
            walk(value, found)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    transcript_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)

    seen = {}
    order = 0
    for name in sorted(os.listdir(transcript_dir)):
        if not name.endswith(".jsonl"):
            continue
        path = os.path.join(transcript_dir, name)
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if not line or "base64" not in line:
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    continue

                found = []
                walk(record, found)
                if not found:
                    continue

                stamp = record.get("timestamp", "")
                for media_type, data in found:
                    order += 1
                    try:
                        blob = base64.b64decode(data)
                    except Exception as error:
                        print("skip undecodable block: %s" % error)
                        continue
                    digest = hashlib.sha1(blob).hexdigest()[:10]
                    if digest in seen:
                        print("dup  %s (seen as %s)" % (digest, seen[digest]))
                        continue
                    ext = EXT.get(media_type, "png")
                    safe_stamp = stamp.replace(":", "").replace("-", "").replace(".", "")[:15]
                    filename = "%02d_%s_%s.%s" % (order, safe_stamp or "notime", digest, ext)
                    seen[digest] = filename
                    with open(os.path.join(out_dir, filename), "wb") as out:
                        out.write(blob)
                    print("wrote %s  %d bytes  from %s" % (filename, len(blob), name))
    print("distinct images: %d" % len(seen))
    return 0


if __name__ == "__main__":
    sys.exit(main())
