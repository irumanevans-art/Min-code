# -*- coding: utf-8 -*-
"""
「陶」主题纹理：Anthropic 原生配色的取底。

    py -3 tools/build_clay_plate.py [源图.png] [--debug] [--seed N]

海是「蓝色取底」那张酒精墨，云是「灰白仙境」压出来的云海。陶没有现成的画 ——
Anthropic 的视觉语言里没有一张可以拿来当纹理的图，硬找一张暖色的照片顶上，
观感会立刻和另外两套脱节（它们都是**材质**，不是风景）。

所以这张是合成的：多倍频噪声 + 一次域扭曲，得到酒精墨那种涡流，再把灰阶映射到
陶土色阶上。合成的好处不只是「没有源图」——它天生匀质，不用像云那样先裁底、
再把画面里的实心暗物 inpaint 掉。

**唯一不能省的是第 3 步**：明度直方图按 CDF 逐级匹配到 sea_plate。
现有组件里每一处 alpha 都是照着海的分布调的，换一张底不该把它们全部重调一遍
（`Cloud.kt` 的头注释讲的是同一件事）。彩度另算：陶要有暖意，但不能艳到
喧宾夺主 —— 它是墙纸，不是主角。

给了源图就走和云完全一样的五步（裁底 → 抹暗物 → 化开 → 直方图匹配 → 色度挂回），
留这条路是因为将来真找到合适的素材时，不该为它再写一个脚本。

产物：
    app/src/main/res/drawable-nodpi/clay_plate.png
    work/clay/*.png   --debug 时的中间图
"""
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
DEBUG = "--debug" in sys.argv
SRC = ARGS[0] if ARGS else None
SEED = 20260920
for a in sys.argv[1:]:
    if a.startswith("--seed"):
        SEED = int(a.split("=", 1)[1]) if "=" in a else SEED

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NODPI = os.path.join(ROOT, "app/src/main/res/drawable-nodpi")
SEA_PLATE = os.path.join(NODPI, "sea_plate.png")
WORK = os.path.join(ROOT, "work/clay")
os.makedirs(NODPI, exist_ok=True)
os.makedirs(WORK, exist_ok=True)

# 和 sea_plate 同尺寸同构图：横的，1.3 倍铺宽之后靠 MIRROR 平铺补，
# 接缝在无方向的墨纹上看不出来（云那张是竖构图，才要 2.2 倍一次盖满）
SIZE = (1200, 1130)

# 倍频：每一档的高斯半径（占图宽的比例）与权重。
# 最粗那档定「几团墨」，最细那几档给纸颗粒与丝络；中间是涡流的主体。
#
# 高频档的权重不能像标准 fBm 那样一路对半掉 —— 那样出来的是一张糊的渐变，
# 而酒精墨的辨识度全在细部。这里从第三档起刻意收得慢
OCTAVES = [
    (0.22, 1.00), (0.11, 0.62), (0.055, 0.42),
    (0.026, 0.28), (0.012, 0.19), (0.0055, 0.12), (0.0024, 0.07),
]

# 反锐化的强度与半径（占图宽）。
# 域扭曲会把一切都抹软，而墨池之间本来是有边的 —— 这一步把那些边找回来。
# 做在灰阶上而不是成色之后：在 RGB 上锐化会沿边缘逼出彩边
SHARPEN = 1.0
SHARPEN_BLUR_FRACTION = 0.022

# 域扭曲：拿另一组噪声当偏移场去采样底噪。
# 酒精墨的形状不是圆的团块而是被推挤过的涡 —— 这一步就是那个推挤。
#
# **推两道**，粗的定大涡、细的打碎它的规整。只推一道的话，脉络那一层的脊线
# 仍然是一圈圈闭合的等值线（那是等值线的天然形状），看着像细胞不像墨。
# 推第二道才把环拉开成流丝。
#
# y 方向的幅度只有 x 的一半：墨在纸上是有流向的，各向同性的推挤出来是一团棉絮。
# (幅度占宽, 偏移场的模糊半径占宽)
WARPS = [(0.105, 0.20), (0.042, 0.055)]
WARP_Y_SCALE = 0.5

# 四周多算多少像素再裁掉，见 [synthesize]。要盖得过两道扭曲的幅度之和
# （(0.105 + 0.042) × 1200 ≈ 177），留点余量
PAD = 200

# 脉络：ridged noise（1 - |2f-1|）做出来的细丝，对应海那张图上的金脉。
# 两档，粗的给走向、细的给分叉。只掺一点点 —— 它是偶然出现的结构，不是图案
VEINS = [(0.030, 0.13, 4.5), (0.011, 0.08, 6.5)]  # (模糊半径占宽, 权重, 脊线锐度)

# 陶土色阶。位置 → RGB，中间线性插值。
# 取自 Anthropic 的品牌令牌：clay #D97757 / clay-deep #C6613F / kraft #D4A27F /
# manilla #F5E3C7，两端各外推一档做阴影与高光
RAMP = [
    (0.00, (0x4A, 0x22, 0x16)),
    (0.30, (0x8C, 0x4A, 0x2F)),
    (0.55, (0xC6, 0x61, 0x3F)),
    (0.72, (0xD9, 0x77, 0x57)),
    (0.87, (0xD4, 0xA2, 0x7F)),
    (1.00, (0xF5, 0xE3, 0xC7)),
]

# 彩度倍率。合成出来的色阶本身就饱和（陶土是有颜色的东西，不像云那样接近中性灰），
# 所以这里是往下压而不是往上放 —— 目标是和海一个量级，见脚本末尾的对照打印
SATURATION = 0.72

# —— 以下只在给了源图时用到，语义同 build_cloud_plate.py ——
BLUR_FRACTION = 0.014
CROP_BOTTOM = 0.32
DARK_MASK_BELOW = 120
DARK_MASK_DILATE = 13
INPAINT_RADIUS = 48
SRC_SATURATION = 1.0


def dbg(name, arr):
    if not DEBUG:
        return
    a = np.clip(arr, 0, 255).astype(np.uint8)
    Image.fromarray(a).save(os.path.join(WORK, name + ".png"))


def luminance(a):
    return a.mean(axis=2)


def _box_blur(a, r, axis):
    """沿一轴的半径 r 均值。前缀和实现，和半径无关的 O(n)"""
    n = a.shape[axis]
    pad = [(0, 0)] * a.ndim
    pad[axis] = (r, r)
    cs = np.cumsum(np.pad(a, pad, mode="edge"), axis=axis)
    head = list(cs.shape)
    head[axis] = 1
    cs = np.concatenate([np.zeros(head, cs.dtype), cs], axis=axis)
    hi = np.take(cs, np.arange(2 * r + 1, 2 * r + 1 + n), axis=axis)
    lo = np.take(cs, np.arange(n), axis=axis)
    return (hi - lo) / (2 * r + 1)


def blur_f(a, sigma, passes=3):
    """对 float 数组做高斯。三次 box 卷积按中心极限定理收敛到高斯。

    PIL 的 GaussianBlur 只认 L / RGB，不收 "F"，而这里要在浮点上算。
    """
    r = max(1, int(round(sigma)))
    out = a.astype(np.float64)
    for _ in range(passes):
        out = _box_blur(out, r, 0)
        out = _box_blur(out, r, 1)
    return out


def dilate(mask, size):
    size = size | 1
    img = Image.fromarray((mask * 255).astype(np.uint8), mode="L")
    return (np.asarray(img.filter(ImageFilter.MaxFilter(size))) > 0).astype(np.uint8)


def inpaint(rgb, mask, radius):
    """归一化卷积填洞：用掩膜**外**的像素高斯加权插出掩膜内的值"""
    valid = (1 - mask).astype(np.float32)
    weight = np.maximum(blur_f(valid, radius), 1e-6)
    out = rgb.astype(np.float32).copy()
    alpha = np.clip(blur_f(mask.astype(np.float32), radius * 0.25), 0, 1)[..., None]
    for c in range(3):
        filled = blur_f(rgb[..., c].astype(np.float32) * valid, radius) / weight
        out[..., c] = out[..., c] * (1 - alpha[..., 0]) + filled * alpha[..., 0]
    return np.clip(out, 0, 255)


def match_histogram(src, ref):
    """把 [src] 的灰度分布搬到 [ref] 的分布上：两边各算一次 CDF，按分位数查表。

    比线性拉伸稳的地方在于它不假设分布形状。合成噪声是近正态的，海也是，
    但两者的宽窄不同 —— 只对齐两端的话，中间段的对比度会整体偏软。
    """
    bins = np.arange(257)
    src_hist, _ = np.histogram(src, bins=bins)
    ref_hist, _ = np.histogram(ref, bins=bins)
    src_cdf = np.cumsum(src_hist).astype(np.float64) / src.size
    ref_cdf = np.cumsum(ref_hist).astype(np.float64) / ref.size
    lut = np.interp(src_cdf, ref_cdf, np.arange(256))
    return np.interp(src, np.arange(256), lut)


def normalize01(a):
    lo, hi = a.min(), a.max()
    return (a - lo) / max(hi - lo, 1e-9)


def fbm(rng, shape, octaves, width):
    """多倍频噪声：白噪声按几档半径各模糊一次，按权重叠起来。

    不用 Perlin / simplex ——「模糊过的白噪声」在这个尺度上和它们看不出区别，
    而且不必为一个只跑一次的脚本引第三个依赖。
    """
    out = np.zeros(shape, dtype=np.float64)
    for frac, weight in octaves:
        field = rng.random(shape)
        out += weight * normalize01(blur_f(field, width * frac))
    return normalize01(out)


def warp(field, dx, dy):
    """按偏移场双线性重采样。这一步把圆团推成涡"""
    h, w = field.shape
    ys, xs = np.meshgrid(np.arange(h, dtype=np.float64), np.arange(w, dtype=np.float64), indexing="ij")
    sx = np.clip(xs + dx, 0, w - 1.001)
    sy = np.clip(ys + dy, 0, h - 1.001)
    x0, y0 = np.floor(sx).astype(np.int64), np.floor(sy).astype(np.int64)
    x1, y1 = x0 + 1, y0 + 1
    fx, fy = sx - x0, sy - y0
    top = field[y0, x0] * (1 - fx) + field[y0, x1] * fx
    bottom = field[y1, x0] * (1 - fx) + field[y1, x1] * fx
    return top * (1 - fy) + bottom * fy


def apply_ramp(gray01):
    """灰阶 → 陶土色阶。逐通道线性插值，位置就是 RAMP 里那几个停靠点"""
    xs = np.array([p for p, _ in RAMP])
    out = np.zeros(gray01.shape + (3,), dtype=np.float64)
    for c in range(3):
        ys = np.array([rgb[c] for _, rgb in RAMP], dtype=np.float64)
        out[..., c] = np.interp(gray01, xs, ys)
    return out


def synthesize():
    """没有源图时：合成一张匀质的陶土墨纹。

    先按四周各放出 [PAD] 的尺寸合成，最后裁回中心。扭曲那一步的采样点会被推出
    画面，[warp] 只能把它们 clip 在边上 —— 于是四条边各拖出一道横向的抹痕。
    单看是四条淡带，可这张图是 MIRROR 平铺的，抹痕会在接缝处照镜子变成一条
    亮带，整屏一眼就看见。多算一圈再裁掉是最省事的解法。
    """
    w, h = SIZE[0] + 2 * PAD, SIZE[1] + 2 * PAD
    shape = (h, w)
    rng = np.random.default_rng(SEED)

    base = fbm(rng, shape, OCTAVES, w)
    dbg("1-base", np.dstack([base * 255] * 3))

    # 脉络：ridged noise 的脊线。海那张图上的金脉是同一种东西 —— 偶然的细丝结构。
    # **在扭曲之前**掺进去：脊线本身是等值线（闭合的环），只有跟着底一起被推挤，
    # 才会拉成流丝
    veins = np.zeros(shape, dtype=np.float64)
    for frac, weight, power in VEINS:
        src = normalize01(blur_f(rng.random(shape), w * frac))
        veins += weight * (1.0 - np.abs(2.0 * src - 1.0)) ** power
    field = normalize01(base + veins)
    dbg("2-veins", np.dstack([normalize01(veins) * 255] * 3))

    # 推两道。偏移场本身要足够平滑，否则推出来的是噪点不是涡
    for i, (amp_frac, blur_frac) in enumerate(WARPS):
        amp = w * amp_frac
        dx = (normalize01(blur_f(rng.random(shape), w * blur_frac)) - 0.5) * 2 * amp
        dy = (normalize01(blur_f(rng.random(shape), w * blur_frac)) - 0.5) * 2 * amp * WARP_Y_SCALE
        field = normalize01(warp(field, dx, dy))
        dbg("3-warp%d" % (i + 1), np.dstack([field * 255] * 3))

    # 把墨池之间的边找回来。域扭曲会把一切抹软，而这张图的辨识度全在那些边上
    field = normalize01(
        field + SHARPEN * (field - blur_f(field, w * SHARPEN_BLUR_FRACTION))
    )
    dbg("4-sharpen", np.dstack([field * 255] * 3))

    # 裁回中心，把四条边上的抹痕丢掉
    field = field[PAD:PAD + SIZE[1], PAD:PAD + SIZE[0]]
    return apply_ramp(normalize01(field))


def from_source(path):
    """给了源图：和 build_cloud_plate.py 一模一样的前两步"""
    src = Image.open(path).convert("RGB")
    print("source", path, src.size)
    keep = int(round(src.size[1] * (1 - CROP_BOTTOM)))
    src = src.crop((0, 0, src.size[0], keep))

    rgb = np.asarray(src)
    lum0 = luminance(rgb.astype(np.float32))
    mask = dilate((lum0 < DARK_MASK_BELOW).astype(np.uint8), DARK_MASK_DILATE)
    a = inpaint(rgb, mask, INPAINT_RADIUS)
    radius = src.size[0] * BLUR_FRACTION
    a = np.asarray(
        Image.fromarray(a.astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius=radius))
    ).astype(np.float64)
    return a


def main():
    if SRC:
        a = from_source(SRC)
        saturation = SRC_SATURATION
    else:
        print("no source image: synthesizing (seed=%d, %dx%d)" % (SEED, SIZE[0], SIZE[1]))
        a = synthesize()
        saturation = SATURATION
    dbg("5-coloured", a)

    # 明度直方图匹配到 sea_plate。这一步是整张图能直接换进现有界面的全部理由
    sea = np.asarray(Image.open(SEA_PLATE).convert("RGB")).astype(np.float64)
    sea_lum = luminance(sea)
    lum = luminance(a)
    mapped = match_histogram(lum, sea_lum)
    print("lum mean %.1f -> %.1f (sea %.1f)" % (lum.mean(), mapped.mean(), sea_lum.mean()))

    # 色度挂回去。按差值而不是按比例搬：比例会把暗处的色噪一起放大
    chroma = (a - lum[..., None]) * saturation
    out = np.clip(mapped[..., None] + chroma, 0, 255).astype(np.uint8)
    dbg("6-matched", out)

    path = os.path.join(NODPI, "clay_plate.png")
    Image.fromarray(out).save(path, optimize=True)

    final = luminance(out.astype(np.float64))
    mx, mn = out.astype(np.float64).max(axis=2), out.astype(np.float64).min(axis=2)
    print("\nclay_plate", out.shape[1], "x", out.shape[0], "%.1f KB" % (os.path.getsize(path) / 1024))
    print("        clay    sea")
    for p in (1, 5, 25, 50, 75, 95, 99):
        print("  p%-4s %6.1f %6.1f" % (p, np.percentile(final, p), np.percentile(sea_lum, p)))
    print("  mean  %6.1f %6.1f" % (final.mean(), sea_lum.mean()))
    print("  std   %6.1f %6.1f" % (final.std(), sea_lum.std()))
    print("  sat   %6.1f %6.1f" % ((mx - mn).mean(), (sea.max(axis=2) - sea.min(axis=2)).mean()))


if __name__ == "__main__":
    main()
