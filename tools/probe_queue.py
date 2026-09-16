#!/usr/bin/env python3
"""一次性探针：CLI 在 headless stream-json 下，busy 时写进 stdin 的 user 帧什么时候被插进去。

要回答三件事（见计划 §0）：
  A. 插入发生在本轮 `result` **之前**吗？
  B. 插入时 CLI 会不会回吐一条 {"type":"user"} 文本帧？
  C. 一次插入产生 1 个还是 2 个 `result`？

用法：python tools/probe_queue.py [--cli <path>] [--out <ndjson>]
跑完把原始 NDJSON 留在 --out，控制台打一份带相对时间戳的摘要。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid

FIRST = (
    "Create three files in the current directory, one at a time, each with its own Write call: "
    "a.txt containing 'a', then b.txt containing 'b', then c.txt containing 'c'. "
    "Do not batch them. After all three, reply DONE."
)
SECOND = "STOP. Forget b.txt and c.txt. Only a.txt was wanted. Reply ACK-QUEUED."


def user_frame(text: str) -> str:
    return json.dumps(
        {
            "type": "user",
            "message": {"role": "user", "content": [{"type": "text", "text": text}]},
            "parent_tool_use_id": None,
            "session_id": "probe",
        },
        ensure_ascii=False,
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cli", default=shutil.which("claude"))
    ap.add_argument("--out", default="probe_queue.ndjson")
    ap.add_argument("--timeout", type=float, default=300.0)
    args = ap.parse_args()

    if not args.cli:
        print("claude not found on PATH; pass --cli", file=sys.stderr)
        return 2

    work = tempfile.mkdtemp(prefix="probe_queue_")
    print(f"cli      = {args.cli}")
    print(f"work dir = {work}")

    cmd = [
        args.cli,
        "--print",
        "--input-format", "stream-json",
        "--output-format", "stream-json",
        "--include-partial-messages",
        "--verbose",
        "--permission-mode", "acceptEdits",
        "--allowedTools", "Write",
        "--session-id", str(uuid.uuid4()),
    ]

    env = dict(os.environ)
    env["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"

    proc = subprocess.Popen(
        cmd,
        cwd=work,
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        bufsize=1,
    )

    t0 = time.monotonic()
    lines: list[tuple[float, str]] = []
    sent_second = threading.Event()
    done = threading.Event()

    def stamp() -> float:
        return round(time.monotonic() - t0, 3)

    def send(text: str) -> None:
        assert proc.stdin
        proc.stdin.write(user_frame(text) + "\n")
        proc.stdin.flush()
        lines.append((stamp(), json.dumps({"_probe": "sent", "text": text[:40]})))
        print(f"[{stamp():7.3f}] >>> sent: {text[:50]}")

    def pump_err() -> None:
        assert proc.stderr
        for line in proc.stderr:
            line = line.rstrip("\n")
            if line:
                print(f"[{stamp():7.3f}] err {line[:200]}")

    threading.Thread(target=pump_err, daemon=True).start()

    def pump_out() -> None:
        assert proc.stdout
        results = 0
        for line in proc.stdout:
            line = line.rstrip("\n")
            if not line:
                continue
            lines.append((stamp(), line))
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                print(f"[{stamp():7.3f}] <<< (unparsed) {line[:160]}")
                continue

            kind = obj.get("type")
            if kind == "stream_event":
                continue  # 太吵，只留在文件里

            summary = kind
            if kind == "assistant":
                blocks = obj.get("message", {}).get("content", [])
                parts = []
                for b in blocks:
                    if b.get("type") == "tool_use":
                        parts.append(f"tool_use:{b.get('name')}")
                    elif b.get("type") == "text":
                        parts.append("text:" + b.get("text", "").strip()[:60].replace("\n", " "))
                    else:
                        parts.append(b.get("type", "?"))
                summary = "assistant " + " | ".join(parts)
            elif kind == "user":
                blocks = obj.get("message", {}).get("content", [])
                if isinstance(blocks, str):
                    summary = "user text:" + blocks[:60]
                else:
                    parts = []
                    for b in blocks:
                        if b.get("type") == "tool_result":
                            parts.append("tool_result")
                        elif b.get("type") == "text":
                            parts.append("TEXT:" + str(b.get("text", ""))[:60].replace("\n", " "))
                        else:
                            parts.append(b.get("type", "?"))
                    summary = "user " + " | ".join(parts)
            elif kind == "system":
                summary = f"system/{obj.get('subtype')}"
            elif kind == "result":
                results += 1
                summary = (
                    f"RESULT #{results} subtype={obj.get('subtype')} "
                    f"turns={obj.get('num_turns')} dur={obj.get('duration_ms')}"
                )

            print(f"[{stamp():7.3f}] <<< {summary}")

            # 第一个 tool_use 之后马上补发第二条：这正是 App 里「生成中追加一句」的时刻
            if (
                not sent_second.is_set()
                and kind == "assistant"
                and any(b.get("type") == "tool_use" for b in obj.get("message", {}).get("content", []))
            ):
                sent_second.set()
                send(SECOND)

            if kind == "result" and results >= 2:
                done.set()
                break
            if kind == "result" and sent_second.is_set():
                # 只有一个 result 的情况：再等一小会儿看会不会有第二个
                threading.Timer(12.0, done.set).start()

        done.set()

    threading.Thread(target=pump_out, daemon=True).start()

    send(FIRST)
    done.wait(timeout=args.timeout)

    try:
        if proc.stdin:
            proc.stdin.close()
    except Exception:
        pass
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()

    with open(args.out, "w", encoding="utf-8") as fh:
        for ts, line in lines:
            fh.write(f"{ts}\t{line}\n")

    print(f"\nraw NDJSON -> {args.out}  ({len(lines)} lines)")
    print(f"files left in work dir: {sorted(os.listdir(work))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
