#!/usr/bin/env python3
"""Tiny UI automation helper for the emulator: tap/find by text via uiautomator dump.

usage: uitap.py <serial> tap <text>          tap the first node whose text/desc contains <text>
       uitap.py <serial> exists <text>       exit 0 if found
       uitap.py <serial> dump                print visible texts
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = "C:/AndroidSDK/platform-tools/adb.exe"


def dump(serial: str) -> ET.Element:
    subprocess.run([ADB, "-s", serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True, timeout=60)
    xml = subprocess.run([ADB, "-s", serial, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, timeout=60).stdout
    return ET.fromstring(xml.decode("utf-8", "replace"))


def nodes(root):
    for n in root.iter("node"):
        yield n


def find(root, text: str):
    for n in nodes(root):
        if text in (n.get("text") or "") or text in (n.get("content-desc") or ""):
            return n
    return None


def center(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds"))
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    serial, cmd = sys.argv[1], sys.argv[2]
    root = dump(serial)
    if cmd == "dump":
        for n in nodes(root):
            t = (n.get("text") or n.get("content-desc") or "").strip()
            if t:
                print(n.get("bounds"), t[:80])
        return
    text = sys.argv[3]
    n = find(root, text)
    if n is None:
        print(f"NOT FOUND: {text}")
        sys.exit(1)
    if cmd == "exists":
        print("FOUND", n.get("bounds"))
        return
    x, y = center(n)
    subprocess.run([ADB, "-s", serial, "shell", "input", "tap", str(x), str(y)], timeout=30)
    print(f"tapped {text} at {x},{y}")


if __name__ == "__main__":
    main()
