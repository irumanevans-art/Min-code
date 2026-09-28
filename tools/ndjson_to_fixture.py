#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 tools/out/probe_frames/ 的 NDJSON 普查录制转成 ClaudeCodeManager 特征测试的夹具。

背景：parallel_subagents.ndjson 里主回合的 result 之后，CLI 又两次**自己**开了新的一轮
（没有用户消息插入）。夹具格式（tools/record_cli_frames.py 头注释）按 `> user` 切轮，
CLI 自开的轮没地方放 —— 本脚本在两个后续轮的开头输出 `# turn` 标记、在固定观察点输出
`# pause <label>` 标记，配套的解析在 ClaudeCodeManagerHarness.kt 里（`#` 行对旧夹具无影响）。

用法（在仓库根目录）：
  py tools/ndjson_to_fixture.py > app/src/test/resources/claudecode/frames/background_agents_followup.txt

脚本 import probe_frames 复用它实际发送的载荷，保证「> 」行不是手抄。`> ` 行只有探针
真的发过的三条：initialize、set_max_thinking_tokens、那条用户消息；hook_callback 的应答
不进夹具 —— 回放时 Manager 自己会应答。其余全部是 CLI 的 stdout 原文（`< ` 行）。

pause 观察点（报告 busy 用）：
  t2/t3        —— CLI 自开轮的第一帧之前（测「上一条 result 之后」的 busy）
  t2init/t3init—— 该轮 system/init + system/status 之后（测「init 之后」的 busy）
  mid2/mid3    —— 该轮助手正文之后、result 之前（测「流式输出期间」的 busy）
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe_frames  # noqa: E402  复用 INITIALIZE_OPTIONS / user_frame / control_request


def convert(name: str) -> str:
    base = os.path.join(probe_frames.OUT_DIR, name)
    meta = json.load(open(base + ".meta.json", encoding="utf-8"))
    exit_code = meta.get("exit_code", 0)

    # 探针按顺序发过三条：init-1 → think-1 → user。应答回来之前 `>` 必须先出现，
    # 夹具解析器靠 request_id 把 `< control_response` 归到对应的 subtype 桶里
    sent_init = probe_frames.control_request("init-1", "initialize", probe_frames.INITIALIZE_OPTIONS)
    sent_think = json.dumps({
        "type": "control_request", "request_id": "think-1",
        "request": {"subtype": "set_max_thinking_tokens",
                    "max_thinking_tokens": None, "display": "summarized"},
    })
    sent_user = probe_frames.user_frame(meta["prompt"])

    out: list[str] = []
    out.append(f"# 由 tools/out/probe_frames/{name}.ndjson 经 tools/ndjson_to_fixture.py 生成，勿手改")
    out.append("# 真机 claude 2.1.283（--model claude-haiku-4-5-20251001）；主回合 result 之后")
    out.append("# CLI 又两次自己开轮（无用户消息）：# turn / # pause 见 ClaudeCodeManagerHarness.kt")
    out.append(f"# exit {exit_code}")

    pending: dict[str, str] = {"init-1": sent_init, "think-1": sent_think}
    user_sent = False
    results = 0          # 已经看到的 result 条数
    turn_opened = False  # 当前段是否已输出过 # turn + 起始 pause
    init_seen = False    # 当前段是否已过 system/init（tXinit 紧跟它后面的 status）

    for line in open(base + ".ndjson", encoding="utf-8"):
        obj = json.loads(line)
        t = obj.get("type")

        # 探针的用户消息不在 stdout 里，发在第一条非 control_response 的 stdout 帧之前
        if not user_sent and t != "control_response":
            out.append("> " + sent_user)
            user_sent = True

        if t == "control_response":
            rid = obj.get("response", {}).get("request_id")
            if rid in pending:
                out.append("> " + pending.pop(rid))
            out.append("< " + line.rstrip("\n"))
            continue

        if t == "result":
            results += 1
            if results >= 2:
                out.append(f"# pause mid{results}")  # 正文之后、result 之前
            out.append("< " + line.rstrip("\n"))
            turn_opened = False
            init_seen = False
            continue

        if results >= 1 and not turn_opened:
            out.append("# turn")
            out.append(f"# pause t{results + 1}")
            turn_opened = True
            init_seen = False

        if t == "system" and obj.get("subtype") == "init":
            init_seen = True
        elif t == "system" and obj.get("subtype") == "status" and init_seen and results >= 1:
            out.append("< " + line.rstrip("\n"))  # init+status 之后（status 是状态里可见的变化）
            out.append(f"# pause t{results + 1}init")
            init_seen = False
            continue

        out.append("< " + line.rstrip("\n"))

    if not user_sent:
        raise SystemExit("ndjson 里没有任何 stdout 帧？")
    if results != 3:
        raise SystemExit(f"预期 3 条 result（1 条主回合 + 2 条 CLI 自开轮），实际 {results}")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    print(convert(sys.argv[1] if len(sys.argv) > 1 else "parallel_subagents"), end="")
