# -*- coding: utf-8 -*-
"""
「云」主题纹理：Codex 那一支的取底。

    py -3 tools/build_cloud_plate.py [灰白仙境.png] [--debug]

「海」是蓝色取底那张酒精墨，「云」是这张灰白仙境的云海。两者在界面上是同一个动作——
把纸镂空成形状、盖在纹理上——所以这张图必须和 sea_plate **明度分布一致**，
否则镂出来的字要么糊在纸上、要么黑得发死：现有组件里每一处 alpha 都是照着海的
分布调的，换一张底不该把它们全部重调一遍。

两件事：

1. **化开具象**。原图是一幅画：楼阁、白花树、瀑布、几只鹭。纹理窗口只露出图的一小块，
   露出半座楼阁比露出一片云气难看得多，也不是这套界面要的东西。所以先按图宽的百分比
   做一次高斯，把楼阁与枝条这些高频溶回云里，只留下大尺度的云涡。
2. **压回中调**。原图 mean 194（p50=196），是一张亮图；sea_plate 是 mean 148。
   直接拿去镂空，白纸上什么都看不见。这里做的是**直方图匹配**而不是两端拉伸：
   云是一张左偏的图（大片亮云、少量暗岩），只把 p1/p99 对齐的话中间段仍旧偏亮
   （实测 mean 会停在 180）。按 CDF 逐级匹配到海的明度直方图，每个百分位才真的落在一起。
   彩度另算：原图 sat 均值只有 7.6，明度压下来会连这一点冷也一起压没，
   所以反过来放大一点——灰要是冷灰，不是死灰。

产物：
    app/src/main/res/drawable-nodpi/cloud_plate.png
    work/cloud/*.png   --debug 时的中间图
"""
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
DEBUG = "--debug" in sys.argv
SRC = ARGS[0] if ARGS else os.path.expanduser("~/Desktop/灰白仙境.png")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NODPI = os.path.join(ROOT, "app/src/main/res/drawable-nodpi")
SEA_PLATE = os.path.join(NODPI, "sea_plate.png")
WORK = os.path.join(ROOT, "work/cloud")
os.makedirs(NODPI, exist_ok=True)
os.makedirs(WORK, exist_ok=True)

# 高斯半径占图宽的比例。楼阁与枝条在 1% 上就化得差不多，云涡要到 4% 才开始塌
BLUR_FRACTION = 0.014
# 底部裁掉多少。左下那株白花树和它底下的岩是画里最大的一块实心暗物，高斯化不开
# （模糊只会把它摊成一块更大的灰斑），压到中调之后就成了纹理上一处突兀的黑。
# 海当初裁底部水印是同一个动作：纹理要的是匀质的料，不是画面。
CROP_BOTTOM = 0.32
# 暗物掩膜的明度阈值与膨胀半径（px）。楼阁底下的岩石散在整幅图里，裁不完。
#
# 这里必须用 inpaint 而不是「把暗部提亮」：直方图匹配是**保序**的，
# 不管把那些像素提到多亮，它们仍旧是分布里最暗的一批，匹配之后照样被拉回最暗——
# 明度变换治不了空间上的斑。只有把岩石替换成周围的云气，斑才真的不在了。
DARK_MASK_BELOW = 120
DARK_MASK_DILATE = 13
# 填洞的高斯半径。要压得过最大那块暗物（楼阁连着的岩体约 60px），
# 否则洞心的有效权重趋近 0，填出来的是一摊平色
INPAINT_RADIUS = 48
# 彩度倍率：0 是死灰，1 是原样。原图 sat 均值才 7.6，明度压到中调会把这点冷也压掉，
# 所以放大一点，落在 10 上下——看得出是冷灰，又不至于变成有颜色的东西
SATURATION = 1.6


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
    """对 float 数组做高斯。

    PIL 的 GaussianBlur 只认 L / RGB，不收 "F"，而归一化卷积要在浮点上算
    （分子分母都会出现远超 255 的中间值）。三次 box 卷积按中心极限定理收敛到高斯，
    宽度取 w ≈ 2σ 时等效 σ 就对得上——这里只是用来插值，不必追求核形状精确。
    """
    r = max(1, int(round(sigma)))
    out = a.astype(np.float64)
    for _ in range(passes):
        out = _box_blur(out, r, 0)
        out = _box_blur(out, r, 1)
    return out


def dilate(mask, size):
    """二值膨胀 = 最大值滤波。size 取奇数"""
    size = size | 1
    img = Image.fromarray((mask * 255).astype(np.uint8), mode="L")
    return (np.asarray(img.filter(ImageFilter.MaxFilter(size))) > 0).astype(np.uint8)


def inpaint(rgb, mask, radius):
    """归一化卷积填洞：用**掩膜外**的像素高斯加权插出掩膜内的值。

        filled = blur(img · valid) / blur(valid)

    对云这种连续介质比 TELEA 那类沿等照度线推进的算法更合适——要的本来就是
    「这块地方如果是云会是什么样」，而不是把边缘的纹理顶进去。
    分母是有效像素的权重和，洞越大越小，所以半径要压得过洞的尺寸。
    """
    valid = (1 - mask).astype(np.float32)
    weight = np.maximum(blur_f(valid, radius), 1e-6)
    out = rgb.astype(np.float32).copy()
    # 边界羽化：掩膜自身模糊一档做 alpha，硬边会在后面的直方图匹配里显出来
    alpha = np.clip(blur_f(mask.astype(np.float32), radius * 0.25), 0, 1)[..., None]
    for c in range(3):
        filled = blur_f(rgb[..., c].astype(np.float32) * valid, radius) / weight
        out[..., c] = out[..., c] * (1 - alpha[..., 0]) + filled * alpha[..., 0]
    return np.clip(out, 0, 255)


def match_histogram(src, ref):
    """把 [src] 的灰度分布搬到 [ref] 的分布上：两边各算一次 CDF，按分位数查表。

    比线性拉伸稳的地方在于它不假设分布形状——云是左偏的，海是近正态的，
    线性拉伸只能对齐两端，中间段该亮的还是亮。
    """
    bins = np.arange(257)
    src_hist, _ = np.histogram(src, bins=bins)
    ref_hist, _ = np.histogram(ref, bins=bins)
    src_cdf = np.cumsum(src_hist).astype(np.float64) / src.size
    ref_cdf = np.cumsum(ref_hist).astype(np.float64) / ref.size
    # 对每个源灰阶，找 ref 里累计概率相同的那一级
    lut = np.interp(src_cdf, ref_cdf, np.arange(256))
    return np.interp(src, np.arange(256), lut)


def main():
    src = Image.open(SRC).convert("RGB")
    print("source", SRC, src.size)

    # 0. 先裁掉底部的树与岩，再模糊——反过来的话暗物已经被摊开，裁不干净
    keep = int(round(src.size[1] * (1 - CROP_BOTTOM)))
    src = src.crop((0, 0, src.size[0], keep))
    print("crop bottom %.0f%% -> %s" % (CROP_BOTTOM * 100, src.size))

    # 1. 抹掉暗物：岩石与楼阁的阴面用周围云气填回去
    rgb = np.asarray(src)
    lum0 = luminance(rgb.astype(np.float32))
    mask = dilate((lum0 < DARK_MASK_BELOW).astype(np.uint8), DARK_MASK_DILATE)
    a = inpaint(rgb, mask, INPAINT_RADIUS)
    dbg("1-mask", np.dstack([mask * 255] * 3))
    dbg("1-inpaint", a)
    print("dark mask lum<%d dilated %dpx covers %.1f%%, inpaint radius %d" % (
        DARK_MASK_BELOW, DARK_MASK_DILATE, mask.mean() * 100, INPAINT_RADIUS))

    # 2. 化开具象：楼阁、枝条、鹭溶回云里，留大尺度云涡
    radius = src.size[0] * BLUR_FRACTION
    a = np.asarray(
        Image.fromarray(a.astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius=radius))
    ).astype(np.float32)
    dbg("2-blur", a)
    print("blur radius %.1fpx (%.1f%% of width)" % (radius, BLUR_FRACTION * 100))

    # 3. 明度直方图匹配到 sea_plate
    sea = np.asarray(Image.open(SEA_PLATE).convert("RGB")).astype(np.float32)
    sea_lum = luminance(sea)
    lum = luminance(a)
    mapped = match_histogram(lum, sea_lum)
    print("lum mean %.1f -> %.1f (sea %.1f)" % (lum.mean(), mapped.mean(), sea_lum.mean()))

    # 4. 色度挂回去。按差值而不是按比例搬：比例会让暗处的色噪跟着放大，
    #    而云图最暗的地方正是岩缝里那些脏像素
    chroma = (a - lum[..., None]) * SATURATION
    a = np.clip(mapped[..., None] + chroma, 0, 255)
    dbg("4-mapped", a)

    out = a.astype(np.uint8)
    path = os.path.join(NODPI, "cloud_plate.png")
    Image.fromarray(out).save(path, optimize=True)

    final = luminance(out.astype(np.float32))
    mx, mn = out.astype(np.float32).max(axis=2), out.astype(np.float32).min(axis=2)
    print("\ncloud_plate", out.shape[1], "x", out.shape[0], "%.1f KB" % (os.path.getsize(path) / 1024))
    print("        cloud   sea")
    for p in (1, 5, 25, 50, 75, 95, 99):
        print("  p%-4s %6.1f %6.1f" % (p, np.percentile(final, p), np.percentile(sea_lum, p)))
    print("  mean  %6.1f %6.1f" % (final.mean(), sea_lum.mean()))
    print("  std   %6.1f %6.1f" % (final.std(), sea_lum.std()))
    print("  sat   %6.1f %6.1f" % ((mx - mn).mean(), (sea.max(axis=2) - sea.min(axis=2)).mean()))


if __name__ == "__main__":
    main()
