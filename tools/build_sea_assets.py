# -*- coding: utf-8 -*-
"""
「海」主题素材：纹理取样 + 两色对角线字标图标。

    python tools/build_sea_assets.py [素材目录] [--debug]

素材目录里要有：
    白色取色.jpg   底色白从它的背景取（中值 #F8FDF7）
    蓝色取底.jpg   一切蓝色的来源：酒精墨蓝纹理（带金脉）。UI 里的蓝色形状都是「把纸镂空盖在它上面」

图标不再读手绘「软件图标.png」。构图仍是左上海、右下沙，分界是画布对角线
（右上到左下）。字是 Cormorant Garamond 斜体 Bold：M 偏右上（高）、in 偏左下（低），
i 的点坐在分界线上做成太极，太极的阴阳交界与分界线连成一条。海上是沙，沙上是海。
填充只有两种颜色：平色海 #2B7FD6 与沙 #EFE2C9（抗锯齿会在沿上出过渡像素）。

产物：
    app/src/main/res/drawable-nodpi/sea_plate.png            蓝色取底裁掉底部水印后的整张纹理
    app/src/main/res/mipmap-*/ic_launcher_foreground.png     自适应图标前景（108dp）
    app/src/main/res/mipmap-*/ic_launcher.png / _round.png   旧式图标（48dp）
    app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png  主题图标用的单色层
    app/src/main/java/dev/min/code/ui/theme/SeaMarkPaths.kt  对角线与字标轮廓
    work/sea/*.png                                           --debug 时的中间图
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
DEBUG = "--debug" in sys.argv
SRC = ARGS[0] if ARGS else os.path.expanduser("~/Desktop/详情")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
NODPI = os.path.join(RES, "drawable-nodpi")
WORK = os.path.join(ROOT, "work/sea")
FONT = os.path.join(ROOT, "tools/fonts/CormorantGaramond-Italic.ttf")
os.makedirs(NODPI, exist_ok=True)
os.makedirs(WORK, exist_ok=True)

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# 图标只用这两种填充。海是 LightSea.seaFlat，沙是原来岸上的干沙。
SEA_FLAT = (0x2B, 0x7F, 0xD6)
SAND = (0xEF, 0xE2, 0xC9)

MASTER = 1728  # 108dp × 16
DP = MASTER / 108.0
# 自适应图标安全区半径 66dp / 108dp。内容收在 0.96 倍里面，圆角裁切吃不到字。
SAFE_RADIUS = 66 / 108 / 2
FIT_RADIUS = SAFE_RADIUS * 0.98
FONT_WEIGHT = 700


def dbg(name, arr):
    if not DEBUG:
        return
    a = arr
    if a.dtype != np.uint8:
        a = np.clip(a * 255 if a.max() <= 1.0 else a, 0, 255).astype(np.uint8)
    Image.fromarray(a).save(os.path.join(WORK, name))


# ---------------------------------------------------------------------------
# 纹理与取色（给 UI 的 Color 槽，不是给图标）
# ---------------------------------------------------------------------------

def load_texture():
    tex = np.asarray(Image.open(os.path.join(SRC, "蓝色取底.jpg")).convert("RGB"))
    return tex[:1130]


def paper_white():
    w = np.asarray(Image.open(os.path.join(SRC, "白色取色.jpg")).convert("RGB")).astype(int)
    bg = w[w.min(axis=2) > 235]
    return tuple(int(v) for v in np.median(bg, axis=0))


def swatches(tex):
    f = tex.astype(np.float32)
    lum = f.mean(axis=2)

    def med(mask):
        return tuple(int(v) for v in np.median(f[mask], axis=0))

    deep = med(lum < np.percentile(lum, 12))
    mid = med((lum > np.percentile(lum, 40)) & (lum < np.percentile(lum, 60)))
    bright = med(f[..., 1] > np.percentile(f[..., 1], 88))
    foam = med(lum > np.percentile(lum, 98.5))
    goldm = (f[..., 0] > f[..., 2] * 0.9) & (f[..., 0] > 120)
    gold = med(goldm) if goldm.sum() > 100 else (214, 178, 120)
    return {"seaDeep": deep, "sea": mid, "seaBright": bright, "seaFoam": foam, "seaGold": gold}


def hexs(rgb):
    return "#%02X%02X%02X" % tuple(rgb)


# ---------------------------------------------------------------------------
# 字标与对角线
# ---------------------------------------------------------------------------

def cormorant(size):
    font = ImageFont.truetype(FONT, size)
    try:
        font.set_variation_by_axes([FONT_WEIGHT])
    except Exception:
        pass
    return font


def render_glyph(text, px, size):
    img = Image.new("L", (size * 2, size * 2), 0)
    ImageDraw.Draw(img).text((40, 40), text, font=cormorant(px), fill=255)
    bbox = img.getbbox()
    return np.asarray(img.crop(bbox)).astype(np.float32) / 255.0


def find_tittle(arr):
    """「in」里最高、最小的那一团就是 i 的点。"""
    n, lab, st, _ = cv2.connectedComponentsWithStats((arr > 0.5).astype(np.uint8), 8)
    best = None
    for j in range(1, n):
        _area, x, y, w, h = st[j, 4], st[j, 0], st[j, 1], st[j, 2], st[j, 3]
        if max(w, h) < arr.shape[0] * 0.45 and y < arr.shape[0] * 0.45:
            cy = y + h / 2.0
            if best is None or cy < best[0]:
                best = (cy, x + w / 2.0, y + h / 2.0, max(w, h) / 2.0, lab == j)
    if best is None:
        raise RuntimeError("could not find the i-dot in 'in'")
    return best[1], best[2], best[3], best[4]


def stamp(dst, src, x, y):
    h, w = src.shape
    x, y = int(round(x)), int(round(y))
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(dst.shape[1], x + w), min(dst.shape[0], y + h)
    if x1 <= x0 or y1 <= y0:
        return
    sx0, sy0 = x0 - x, y0 - y
    dst[y0:y1, x0:x1] = np.maximum(
        dst[y0:y1, x0:x1],
        src[sy0:sy0 + (y1 - y0), sx0:sx0 + (x1 - x0)],
    )


def taiji_masks(size, cx, cy, radius):
    """太极的阴阳交界**就是**海沙分界线的延续，两条鱼分别落在海、沙里。

    坐标先转到分界线自己的方向上：[along] 沿分界线（右上→左下），
    [normal] 是它的法向（指向沙那一侧）。

    阴阳的直线分割必须落在 along 轴上（normal = 0 这条线就是分界线本身），
    S 形交界才能和画布上那条对角线接成一条连续的线。
    以前用的是另一条对角线，太极的交界与分界线**垂直**，看着就是"点上另画了个记号"。
    两条鱼于是沿分界线排开：一条在海侧、一条在沙侧。
    """
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    dx, dy = xx - cx, yy - cy
    # 分界线 x + y = size 的法向是 (1,1)/√2；沿线方向是 (1,-1)/√2
    normal = (dx + dy) / np.sqrt(2.0)
    along = (dx - dy) / np.sqrt(2.0)
    disc = dx * dx + dy * dy <= radius * radius
    # 两个半圆的心沿分界线错开，交界因此落在 normal = 0 上，与分界线共线
    lobe_sand = (along - radius / 2.0) ** 2 + normal * normal <= (radius / 2.0) ** 2
    lobe_sea = (along + radius / 2.0) ** 2 + normal * normal <= (radius / 2.0) ** 2
    # normal < 0 是海的那一侧（圆心就在分界线上，所以判正负即可）。
    # 沙色的那半必须坐在**海**里 —— 这是「海上是沙，沙上是海」。
    # 写成 normal > 0 就成了沙上涂沙、海上涂海，主圆和底色同色，整枚太极只剩凸角看得见。
    yang = disc & ((normal < 0) | lobe_sand) & ~lobe_sea  # 沙色，住在海里
    yin = disc & ~yang  # 海色，住在沙里
    eye_r = radius / 5.8
    yang_eye = (along - radius / 2.0) ** 2 + normal * normal <= eye_r * eye_r  # 海色点
    yin_eye = (along + radius / 2.0) ** 2 + normal * normal <= eye_r * eye_r  # 沙色点
    # 鱼眼的圆心：沿分界线方向 (1,-1)/√2 各走半径的一半
    half = (radius / 2.0) / np.sqrt(2.0)
    yang_eye_cx = cx + half
    yang_eye_cy = cy - half
    yin_eye_cx = cx - half
    yin_eye_cy = cy + half
    return {
        "yang": yang,
        "yin": yin,
        "yang_eye": yang_eye,
        "yin_eye": yin_eye,
        "disc": disc,
        "cx": cx,
        "cy": cy,
        "r": radius,
        "eye_r": eye_r,
        "yang_eye_c": (yang_eye_cx, yang_eye_cy),
        "yin_eye_c": (yin_eye_cx, yin_eye_cy),
    }


def fit_to_safe(letters, taiji, size):
    """把字 + 太极居中、缩进安全圆，最后把太极重新钉回对角线。

    三件事的顺序是有讲究的：
    1. 居中 —— 以前只绕画布中心缩放，字的外接框本来就偏下，缩完仍然偏下，
       而且"最远的那个角"提前顶到安全圆，字被压得比需要的小。
    2. 缩放 —— 圆角裁切不能吃到 M 的左脚和 n 的右脚。
    3. 重新钉点 —— **太极必须坐在海沙分界线上**，它的阴阳交界才能和对角线连成一条。
       前面的错位平移和这里的居中都会把点推离那条线，所以最后沿分界线的法向补一次平移。
       法向平移不改变"点到线的方向"，只改变距离，所以钉完仍然居中在视觉上成立。
    """
    occupied = (letters > 0.2) | taiji["disc"]
    ys, xs = np.where(occupied)
    if len(xs) == 0:
        return letters, taiji
    cx = cy = size / 2.0

    # 1) 居中：外接框的中心对到画布中心
    bx = (xs.min() + xs.max()) / 2.0
    by = (ys.min() + ys.max()) / 2.0
    dx, dy = cx - bx, cy - by

    # 2) 居中之后再量最远点，算需要多少缩放
    max_r = np.sqrt((xs + dx - cx) ** 2 + (ys + dy - cy) ** 2).max()
    target = FIT_RADIUS * size
    scale = min(1.0, target / max_r) if max_r > 0 else 1.0

    # 缩放之后太极圆心会落在哪
    ncx = cx + (taiji["cx"] + dx - cx) * scale
    ncy = cy + (taiji["cy"] + dy - cy) * scale

    # 3) 沿法向把它推回分界线 x + y = size。
    #    点到该线的有向距离是 (x + y - size) / √2，法向单位向量是 (1,1)/√2，
    #    所以两个轴各减 (x + y - size) / 2 就正好落到线上。
    pin = (ncx + ncy - size) / 2.0
    ncx -= pin
    ncy -= pin

    M = np.float32([
        [scale, 0, cx * (1 - scale) + dx * scale - pin],
        [0, scale, cy * (1 - scale) + dy * scale - pin],
    ])
    letters = cv2.warpAffine(letters, M, (size, size), flags=cv2.INTER_LINEAR, borderValue=0)
    return letters, taiji_masks(size, ncx, ncy, taiji["r"] * scale)


def render_min_mask(size):
    """一整词 Min：M 沿右上（高）、in 沿左下（低）各移一点；i 的点做成坐在分界线上的太极。"""
    word = render_glyph("Min", int(size * 0.50), size)
    dcx, dcy, dr, dmask = find_tittle(word)
    h, w = word.shape
    x0 = (size - w) / 2.0
    y0 = (size - h) / 2.0
    tcx, tcy = x0 + dcx, y0 + dcy
    pin = (size - (tcx + tcy)) / 2.0
    x0 += pin
    y0 += pin
    tcx, tcy = x0 + dcx, y0 + dcy

    canvas = np.zeros((size, size), np.float32)
    nodot = word.copy()
    nodot[dmask] = 0
    stamp(canvas, nodot, x0, y0)

    n, lab, st, _ = cv2.connectedComponentsWithStats((canvas > 0.5).astype(np.uint8), 8)
    blobs = sorted(
        [j for j in range(1, n) if st[j, 4] > 200],
        key=lambda j: st[j, 0],
    )
    m_id = blobs[0]
    m_mask = np.where(lab == m_id, canvas, 0.0).astype(np.float32)
    in_mask = np.where((lab != 0) & (lab != m_id), canvas, 0.0).astype(np.float32)

    # 沿对角线各走图标高度的 1/30：M 往右上（高）、in 往左下（低）。
    # 1.0.9 是 5%，这里收到三分之二。屏幕坐标 y 向下，所以「右上」= x 加、y 减。
    s = size * 0.0333 / np.sqrt(2.0)
    shift = np.float32([[1, 0, s], [0, 1, -s]])
    m_shift = cv2.warpAffine(m_mask, shift, (size, size), flags=cv2.INTER_LINEAR, borderValue=0)
    shift_in = np.float32([[1, 0, -s], [0, 1, s]])
    in_shift = cv2.warpAffine(in_mask, shift_in, (size, size), flags=cv2.INTER_LINEAR, borderValue=0)
    letters = np.clip(m_shift + in_shift, 0, 1)
    # i 的点跟着 in 走
    tcx -= s
    tcy += s

    taiji = taiji_masks(size, tcx, tcy, dr)
    letters, taiji = fit_to_safe(letters, taiji, size)
    return letters, taiji


def sea_mask(size):
    """左上为海：对角线从右上 (1,0) 到左下 (0,1)，即 x + y = size。"""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    dist = (xx + yy - size) / np.sqrt(2.0)
    return np.clip(0.5 - dist / 1.2, 0, 1)


def contours_of(mask, epsilon=1.4, min_area=80):
    """外轮廓正向、洞反向，Kotlin / VectorDrawable 用 NonZero 填充。"""
    cs, hier = cv2.findContours(mask.astype(np.uint8), cv2.RETR_CCOMP, cv2.CHAIN_APPROX_NONE)
    if hier is None:
        return []
    polys = []
    for idx, c in enumerate(cs):
        if cv2.contourArea(c) < min_area:
            continue
        a = cv2.approxPolyDP(c, epsilon, True)[:, 0, :].astype(np.float64)
        if len(a) < 3:
            continue
        hole = hier[0][idx][3] >= 0
        x, y = a[:, 0], a[:, 1]
        area = 0.5 * np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)
        if (area > 0) == hole:
            a = a[::-1]
        polys.append(a)
    return polys


def path_data_unit(polys, scale, ox=0.0, oy=0.0):
    parts = []
    for p in polys:
        pts = [(x * scale + ox, y * scale + oy) for x, y in p]
        parts.append("M" + " L".join("%.2f,%.2f" % q for q in pts) + " Z")
    return " ".join(parts)


def compose_icon(letters, taiji, size):
    in_sea = sea_mask(size)
    let = letters * (1.0 - taiji["disc"].astype(np.float32))
    sea_amt = in_sea * (1.0 - let) + (1.0 - in_sea) * let
    sea = np.array(SEA_FLAT, np.float32) / 255
    sand = np.array(SAND, np.float32) / 255
    rgb = sea * sea_amt[..., None] + sand * (1.0 - sea_amt)[..., None]
    yf = taiji["yang"].astype(np.float32)
    nf = taiji["yin"].astype(np.float32)
    rgb = rgb * (1 - yf[..., None]) + sand * yf[..., None]
    rgb = rgb * (1 - nf[..., None]) + sea * nf[..., None]
    ye = taiji["yang_eye"].astype(np.float32)
    ne = taiji["yin_eye"].astype(np.float32)
    rgb = rgb * (1 - ye[..., None]) + sea * ye[..., None]
    rgb = rgb * (1 - ne[..., None]) + sand * ne[..., None]
    return np.clip(rgb * 255, 0, 255).astype(np.uint8)


def build_icon():
    letters, taiji = render_min_mask(MASTER)
    rgb = compose_icon(letters, taiji, MASTER)
    fg = np.dstack([rgb, np.full(rgb.shape[:2], 255, np.uint8)])
    dbg("icon_master.png", rgb)
    dbg("icon_letters.png", letters)
    dbg("icon_taiji.png", taiji["disc"].astype(np.uint8) * 255)

    for name, mult in DENSITIES.items():
        px = int(108 * mult)
        d = os.path.join(RES, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        Image.fromarray(fg).resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"))
        legacy = int(48 * mult)
        im = Image.fromarray(rgb).resize((legacy, legacy), Image.LANCZOS).convert("RGBA")
        for fname, radius in (("ic_launcher.png", legacy * 0.2), ("ic_launcher_round.png", legacy / 2)):
            m = Image.new("L", (legacy, legacy), 0)
            ImageDraw.Draw(m).rounded_rectangle((0, 0, legacy - 1, legacy - 1), radius=radius, fill=255)
            out = im.copy()
            out.putalpha(m)
            out.save(os.path.join(d, fname))

    in_sea = sea_mask(MASTER) > 0.5
    let = letters > 0.5
    disc = taiji["disc"]
    xor = ((in_sea != let) & ~disc) | taiji["yang"] | taiji["yin_eye"]
    xor = xor & ~taiji["yin"] & ~taiji["yang_eye"]
    xor8 = xor.astype(np.uint8) * 255
    Image.fromarray(np.dstack([np.zeros_like(xor8), np.zeros_like(xor8), np.zeros_like(xor8), xor8])) \
        .resize((432, 432), Image.LANCZOS).save(os.path.join(NODPI, "ic_launcher_monochrome.png"))

    letter_polys = contours_of((letters > 0.5).astype(np.uint8), epsilon=1.2)

    def norm(polys):
        return [[(round(float(x) / MASTER, 5), round(float(y) / MASTER, 5)) for x, y in p] for p in polys]

    return {
        "aspect": 1.0,
        "sea": [(0.0, 0.0), (1.0, 0.0), (0.0, 1.0)],
        "sand": [(1.0, 0.0), (1.0, 1.0), (0.0, 1.0)],
        "letters": norm(letter_polys),
        "taiji": {
            "cx": taiji["cx"] / MASTER,
            "cy": taiji["cy"] / MASTER,
            "r": taiji["r"] / MASTER,
            "eye_r": taiji["eye_r"] / MASTER,
            "yang_eye": (taiji["yang_eye_c"][0] / MASTER, taiji["yang_eye_c"][1] / MASTER),
            "yin_eye": (taiji["yin_eye_c"][0] / MASTER, taiji["yin_eye_c"][1] / MASTER),
        },
        "xor": xor8,
    }


def write_paths(paths):
    def arr(polys):
        return ",\n".join(
            "        floatArrayOf(" + ", ".join("%.5ff, %.5ff" % (x, y) for x, y in p) + ")" for p in polys
        )

    sea = paths["sea"]
    sand = paths["sand"]
    t = paths["taiji"]
    body = f"""package dev.min.code.ui.theme

/**
 * 两色对角线字标（由 tools/build_sea_assets.py 从 Cormorant Garamond Italic 生成，勿手改）。
 * 坐标以画布宽为 1，y 向下。[sea] 是左上三角（对角线从右上到左下），[sand] 是右下三角，
 * [letters] 是 M 与 in 的闭合轮廓（不含 i 的点）。i 的点是对角线上的太极，圆心 [taijiCx]/[taijiCy]。
 * 海上的字是沙，沙上的字是海。
 */
object SeaMarkPaths {{
    const val ASPECT = {paths['aspect']}f

    val sea: FloatArray = floatArrayOf({", ".join("%.5ff, %.5ff" % p for p in sea)})

    val sand: FloatArray = floatArrayOf({", ".join("%.5ff, %.5ff" % p for p in sand)})

    const val taijiCx = {t['cx']:.5f}f
    const val taijiCy = {t['cy']:.5f}f
    const val taijiR = {t['r']:.5f}f
    const val taijiEyeR = {t['eye_r']:.5f}f
    const val yangEyeX = {t['yang_eye'][0]:.5f}f
    const val yangEyeY = {t['yang_eye'][1]:.5f}f
    const val yinEyeX = {t['yin_eye'][0]:.5f}f
    const val yinEyeY = {t['yin_eye'][1]:.5f}f

    val letters: List<FloatArray> = listOf(
{arr(paths['letters'])},
    )

}}
"""
    with open(os.path.join(ROOT, "app/src/main/java/dev/min/code/ui/theme/SeaMarkPaths.kt"), "w", encoding="utf-8") as f:
        f.write(body)


def oval(cx, cy, r):
    return "M%.2f,%.2f A%.2f,%.2f 0 1,1 %.2f,%.2f A%.2f,%.2f 0 1,1 %.2f,%.2f Z" % (
        cx - r, cy, r, r, cx + r, cy, r, r, cx - r, cy,
    )


def write_splash_and_stat(paths):
    """启动页矢量（系统 SplashScreen 只吃 drawable）与通知小图标（纯白剪影）"""
    sea = hexs(SEA_FLAT)
    sand = hexs(SAND)
    letters = path_data_unit(paths["letters"], 240)
    t = paths["taiji"]
    cx, cy, r = t["cx"] * 240, t["cy"] * 240, t["r"] * 240
    er = t["eye_r"] * 240
    yex, yey = t["yang_eye"][0] * 240, t["yang_eye"][1] * 240
    nex, ney = t["yin_eye"][0] * 240, t["yin_eye"][1] * 240
    # 两条鱼（半圆凸角）与各自的眼**同心**，圆心就是 yang_eye / yin_eye。
    # 以前这里另算一组沿 (1,1) 的坐标，换轴之后就和鱼眼错开了，
    # 凸角和眼睛各画各的，太极看着是散的。
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/build_sea_assets.py 生成：两色对角线，Cormorant 斜体 Min，i 的点是太极。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="240dp"
    android:height="240dp"
    android:viewportWidth="240"
    android:viewportHeight="240">
  <group>
    <clip-path android:pathData="M120,40 A80,80 0 1,1 120,200 A80,80 0 1,1 120,40 Z"/>
    <path android:fillColor="{sand}" android:pathData="M0,0 H240 V240 H0 Z"/>
    <path android:fillColor="{sea}" android:pathData="M0,0 L240,0 L0,240 Z"/>
    <group>
      <clip-path android:pathData="M0,0 L240,0 L0,240 Z"/>
      <path android:fillColor="{sand}" android:fillType="nonZero" android:pathData="{letters}"/>
    </group>
    <group>
      <clip-path android:pathData="M240,0 L240,240 L0,240 Z"/>
      <path android:fillColor="{sea}" android:fillType="nonZero" android:pathData="{letters}"/>
    </group>
    <path android:fillColor="{sand}" android:pathData="{oval(cx, cy, r)}"/>
    <group>
      <clip-path android:pathData="M240,0 L240,240 L0,240 Z"/>
      <path android:fillColor="{sea}" android:pathData="{oval(cx, cy, r)}"/>
    </group>
    <path android:fillColor="{sand}" android:pathData="{oval(yex, yey, r / 2)}"/>
    <path android:fillColor="{sea}" android:pathData="{oval(nex, ney, r / 2)}"/>
    <path android:fillColor="{sea}" android:pathData="{oval(yex, yey, er)}"/>
    <path android:fillColor="{sand}" android:pathData="{oval(nex, ney, er)}"/>
  </group>
</vector>
"""
    with open(os.path.join(RES, "drawable/splash_mark.xml"), "w", encoding="utf-8") as f:
        f.write(xml)

    xor = paths["xor"].astype(np.float32) / 255.0
    for name, mult in DENSITIES.items():
        px = int(24 * mult)
        inner = int(round(20 * mult))
        small = cv2.resize(xor, (inner, inner), interpolation=cv2.INTER_AREA)
        a = np.zeros((px, px), np.float32)
        pad = (px - inner) // 2
        a[pad:pad + inner, pad:pad + inner] = small
        a8 = np.clip(a * 255, 0, 255).astype(np.uint8)
        rgba = np.dstack([np.full_like(a8, 255), np.full_like(a8, 255), np.full_like(a8, 255), a8])
        d = os.path.join(RES, "drawable-" + name)
        os.makedirs(d, exist_ok=True)
        Image.fromarray(rgba).save(os.path.join(d, "ic_stat_min.png"))


def main():
    tex = load_texture()
    Image.fromarray(tex).save(os.path.join(NODPI, "sea_plate.png"), optimize=True)
    paper = paper_white()
    sw = swatches(tex)
    print("paper", hexs(paper))
    for k, v in sw.items():
        print(k, hexs(v))
    print("icon sea", hexs(SEA_FLAT), "sand", hexs(SAND))
    paths = build_icon()
    write_paths(paths)
    write_splash_and_stat(paths)
    print("letter polys", len(paths["letters"]),
          "points", sum(len(p) for p in paths["letters"]))
    with open(os.path.join(WORK, "swatches.json"), "w") as f:
        json.dump({"paper": hexs(paper), **{k: hexs(v) for k, v in sw.items()},
                   "iconSea": hexs(SEA_FLAT), "iconSand": hexs(SAND)}, f, indent=2)


if __name__ == "__main__":
    main()
