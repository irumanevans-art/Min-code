#!/usr/bin/env python3
"""把霞鹜文楷 GB 取成子集，给 APK 减重。

完整字体 25.8 MB，打包进 APK 不压缩（ttf 压缩不了多少，还拖慢启动）。
App 用它画标题、输入框和用户气泡，内容是任意中文，所以子集按 GB2312 全集
（6763 个汉字）加 ASCII、Latin-1、标点与常用符号区来收，缺的字由系统逐字回退。

OFL 允许取子集；字体版权行里没有 Reserved Font Name，所以名字保持原样，
NOTICE 里注明这是子集。

usage: py -3 tools/subset_wenkai.py <完整字体.ttf> [输出路径]
       输出默认是 app/src/main/res/font/lxgw_wenkai_gb.ttf
"""
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

# 标点与符号区：通用标点、CJK 标点、全角、箭头、带圈数字、几何图形
RANGES = [(0x20, 0x7F), (0xA0, 0x100), (0x2000, 0x206F), (0x3000, 0x303F),
          (0xFF00, 0xFFEF), (0x2190, 0x21FF), (0x2460, 0x24FF), (0x25A0, 0x25FF)]


def wanted():
    chars = set()
    for hi in range(0xA1, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(ord(bytes([hi, lo]).decode("gb2312")))
            except UnicodeDecodeError:
                pass
    for start, end in RANGES:
        chars.update(range(start, end + 1))
    return chars


def main():
    src = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(__file__), "..", "app", "src", "main", "res", "font", "lxgw_wenkai_gb.ttf")
    font = TTFont(src)
    present = font.getBestCmap()
    unicodes = sorted(c for c in wanted() if c in present)
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = False
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=unicodes)
    sub.subset(font)
    font.save(out)
    print(f"{len(unicodes)} 个字符 → {os.path.normpath(out)}（{os.path.getsize(out) / 1e6:.2f} MB）")


if __name__ == "__main__":
    main()
