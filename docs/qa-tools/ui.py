#!/usr/bin/env python3
"""UI helper for an adb-connected Android device (product-agnostic).

usage:
  ui.py dump                 print every node: y2 focused clickable class desc text(repr) bounds
  ui.py texts                same as dump, only nodes with text or content-desc
  ui.py has TEXT             exit 0 if a node's text/desc equals TEXT exactly, else 1
  ui.py tap TEXT [N]         tap center of the node whose text/desc equals TEXT (must be unique, or pick Nth)
  ui.py tapclass CLASS       tap center of the unique node of that class (e.g. android.widget.EditText)
  ui.py field                print codepoints of the unique EditText's text
  ui.py focus                print foreground window focus and whether the IME is shown
  ui.py selftest             check adb reachable and a dump parses (no taps)

Never strips text: trailing whitespace is exactly what needs to be seen.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def sh(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout


def nodes():
    raw = sh("exec-out", "uiautomator", "dump", "/dev/tty")
    start, end = raw.find("<?xml"), raw.rfind(">")
    if start < 0:
        sys.exit("ABORT: dump failed: " + raw[:200])
    root = ET.fromstring(raw[start:end + 1])
    out = []
    for n in root.iter("node"):
        b = [int(v) for v in re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))]
        out.append(dict(text=n.get("text") or "", desc=n.get("content-desc") or "", cls=n.get("class"),
                        focused=n.get("focused") == "true", clickable=n.get("clickable") == "true", b=b))
    return out


def show(ns, only_text=False):
    for n in ns:
        if only_text and not (n["text"] or n["desc"]):
            continue
        print(n["b"][3], "F" if n["focused"] else "-", "C" if n["clickable"] else "-", n["cls"],
              repr(n["desc"]), repr(n["text"]), n["b"])


def tapnode(n):
    x, y = (n["b"][0] + n["b"][2]) // 2, (n["b"][1] + n["b"][3]) // 2
    sh("shell", "input", "tap", str(x), str(y))
    print("tapped", repr(n["text"] or n["desc"]), x, y)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "texts"
    if cmd in ("dump", "texts"):
        show(nodes(), only_text=cmd == "texts")
    elif cmd == "has":
        sys.exit(0 if any(sys.argv[2] in (n["text"], n["desc"]) for n in nodes()) else 1)
    elif cmd == "tap":
        m = [n for n in nodes() if sys.argv[2] in (n["text"], n["desc"])]
        idx = int(sys.argv[3]) if len(sys.argv) > 3 else None
        if idx is None and len(m) != 1:
            print("ABORT: %d nodes match %r" % (len(m), sys.argv[2])); sys.exit(1)
        tapnode(m[idx or 0])
    elif cmd == "tapclass":
        m = [n for n in nodes() if n["cls"] == sys.argv[2]]
        if len(m) != 1:
            print("ABORT: %d nodes of class %s" % (len(m), sys.argv[2])); sys.exit(1)
        tapnode(m[0])
    elif cmd == "field":
        m = [n for n in nodes() if n["cls"] == "android.widget.EditText"]
        if len(m) != 1:
            print("ABORT: %d EditText" % len(m)); sys.exit(1)
        t = m[0]["text"]
        print("focused" if m[0]["focused"] else "unfocused", repr(t), [hex(ord(c)) for c in t])
    elif cmd == "focus":
        w = [l.strip() for l in sh("shell", "dumpsys", "window").splitlines() if "mCurrentFocus" in l][:1]
        i = [l.strip() for l in sh("shell", "dumpsys", "input_method").splitlines() if "mInputShown" in l][:1]
        print(w, i)
    elif cmd == "selftest":
        ns = nodes()
        print("selftest OK: adb reachable, dump parsed,", len(ns), "nodes")
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
