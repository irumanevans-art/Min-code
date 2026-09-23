"""
读 `adb shell dumpsys gfxinfo <pkg> framestats`，按阶段统计 UI 线程耗时（毫秒）。

  py -3 tools/framestats.py <serial> <package> [标签]

只取最近 ~120 帧（系统环形缓冲的容量），所以要在想量的那段时间里调用。
阶段划分（列名见 Android 文档「Test UI performance」）：
  anim   = PerformTraversalsStart - AnimationStart   Choreographer 动画回调，Compose 重组在这里
  layout = DrawStart - PerformTraversalsStart         测量 + 布局
  draw   = SyncQueued - DrawStart                     录制绘制指令
  ui     = SyncQueued - IntendedVsync                 UI 线程这一帧的总账（含排队延迟）
"""
import statistics
import subprocess
import sys


def frames(serial: str, pkg: str) -> list[dict[str, int]]:
    out = subprocess.run(
        [r"C:\AndroidSDK\platform-tools\adb.exe", "-s", serial, "shell", "dumpsys", "gfxinfo", pkg, "framestats"],
        capture_output=True, text=True, check=True,
    ).stdout
    rows, header = [], None
    in_block = False
    for line in out.splitlines():
        if line.startswith("---PROFILEDATA---"):
            in_block = not in_block
            header = None
            continue
        if not in_block:
            continue
        cols = [c for c in line.strip().split(",") if c != ""]
        if header is None:
            header = cols
            continue
        if len(cols) != len(header):
            continue
        row = dict(zip(header, map(int, cols)))
        # Flags 非 0 的是首帧 / 窗口变化之类的特殊帧，不计
        if row.get("Flags", 0) == 0:
            rows.append(row)
    return rows


def pct(values: list[float], p: float) -> float:
    s = sorted(values)
    return s[min(len(s) - 1, int(len(s) * p))]


def main() -> None:
    serial, pkg = sys.argv[1], sys.argv[2]
    label = sys.argv[3] if len(sys.argv) > 3 else ""
    rows = frames(serial, pkg)
    if not rows:
        print(f"{label}: 没有帧")
        return
    stages = {
        "anim": ("AnimationStart", "PerformTraversalsStart"),
        "layout": ("PerformTraversalsStart", "DrawStart"),
        "draw": ("DrawStart", "SyncQueued"),
        "ui": ("IntendedVsync", "SyncQueued"),
    }
    parts = [f"{label} n={len(rows)}"]
    for name, (a, b) in stages.items():
        ms = [(r[b] - r[a]) / 1e6 for r in rows]
        parts.append(f"{name} p50={statistics.median(ms):.2f} p90={pct(ms, 0.9):.2f}")
    print("  ".join(parts))


if __name__ == "__main__":
    main()
