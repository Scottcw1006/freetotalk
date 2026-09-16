#!/usr/bin/env python3
"""Canonical content listing of the JSON files in an app's private directory.

usage:
  jsondir.py PKG DIR            print "<name>\\t<canonical json>" per file, sorted by name
  jsondir.py selftest PKG DIR   check the listing is non-empty and matches the device file count

Why this exists (product-agnostic): a byte-level hash cannot answer "did the CONTENT
change", because an app re-serialising the same data can legitimately produce different
bytes (indentation, key order, escaping).  Take one listing before an operation and one
after, then `diff` them: a difference here is a real content change.

Files that do not parse as JSON are listed with their sha256 instead, so a corrupt or
non-JSON file still shows up (and still shows up as changed if it changes).

Requires the app to be debuggable (uses `run-as`).  Set ANDROID_SERIAL when more than one
device is attached.
"""
import hashlib
import json
import subprocess
import sys


def sh(*args):
    return subprocess.run(["adb", *args], capture_output=True, stdin=subprocess.DEVNULL)


def names(pkg, directory):
    out = sh("exec-out", "run-as", pkg, "sh", "-c", "ls -1 %s" % directory).stdout
    return sorted(n for n in out.decode("utf-8", "replace").replace("\r", "").split("\n") if n)


def listing(pkg, directory):
    rows = []
    for name in names(pkg, directory):
        raw = sh("exec-out", "run-as", pkg, "cat", "%s/%s" % (directory, name)).stdout
        try:
            value = json.dumps(json.loads(raw.decode("utf-8")), sort_keys=True, ensure_ascii=False)
        except Exception:
            value = "<unparseable sha256:%s>" % hashlib.sha256(raw).hexdigest()
        rows.append("%s\t%s" % (name, value))
    return rows


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "selftest":
        pkg, directory = sys.argv[2], sys.argv[3]
        rows = listing(pkg, directory)
        count = len(names(pkg, directory))
        if not rows or len(rows) != count:
            sys.exit("ABORT: %d rows for %d files" % (len(rows), count))
        print("selftest OK: %d json files listed under %s" % (len(rows), directory))
        return
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    print("\n".join(listing(sys.argv[1], sys.argv[2])))


if __name__ == "__main__":
    main()
