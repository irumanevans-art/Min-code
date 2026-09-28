#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""帧普查探针：headless stream-json 下，本机 Claude Code CLI 实际会发哪些帧。

对照 Min 的解析器（app/.../claudecode/ClaudeCodeProtocol.kt），找「CLI 在发、Min 丢了」的帧。
只读 app/ 代码；本脚本不进 git（tools/out/ 已 ignore）。

用法：
  py tools/probe_frames.py run <scenario>     # long_bash subagent parallel_subagents
                                              # grep_search bad_mcp fallback_flag all
  py tools/probe_frames.py stats [scenario...]  # (type, subtype) 计数 + stream_event 拆分

每个场景：临时目录里起 CLI（argv 照抄 claudeLaunchArgs，env 加 IS_SANDBOX=1），
先发 initialize control_request（载荷照抄 Min 的 subagentInitializeOptions()），
再发 set_max_thinking_tokens {max_thinking_tokens:null, display:"summarized"}（照抄 handshake），
然后发场景消息，等 result 后再收尾。stdout 原样进 tools/out/probe_frames/<场景>.ndjson，
每行到达时刻（monotonic 秒）进 <场景>.ts（按行号对齐），stderr 进 <场景>.stderr.log。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "probe_frames")
MODEL = "claude-haiku-4-5-20251001"
SCENARIO_TIMEOUT = 300.0
FALLBACK_TIMEOUT = 90.0
GRACE_AFTER_RESULT = 6.0

# ---------------------------------------------------------------------------
# Min 侧的处理方式标注（对照 ClaudeCodeProtocol.kt @ 2.1.283 校对版）
# 顶层 type 分发在 parseClaudeCodeEvents 的 when（360–477 行），else -> emptyList()。
# ---------------------------------------------------------------------------

TOP_HANDLING = {
    "system": "已处理（按 subtype 分流，见下）",
    "custom-title": "已处理",
    "ai-title": "已处理",
    "assistant": "已处理（parent_tool_use_id 非空则包成 Subagent）",
    "user": "已处理（只展开 tool_result；实时流文本 user 帧按设计不出现）",
    "stream_event": "已处理（主线程）；子 agent 的有意丢弃（注释：防冲掉主线程流式缓冲）",
    "result": "已处理",
    "control_request": "已处理（can_use_tool / hook_callback / 其余回 error 应答）",
    "control_response": "已处理（success / error；其他 subtype 落 else 丢）",
    "control_cancel_request": "已处理",
}

# systemNote() 显式处理的 system 子类型
SYSTEM_HANDLED = {
    "init", "status", "task_summary", "api_retry",
    "task_started", "task_progress", "task_updated", "task_notification",
    "background_tasks_changed",
    "local_command_output", "informational", "api_error",
    "model_fallback", "model_consent_fallback",
    "model_refusal_fallback", "model_refusal_no_fallback",
    "agents_killed", "worker_shutting_down", "compact_boundary",
}

# NOISE_SYSTEM_SUBTYPES（1381 行）：纯内部计量，故意丢
SYSTEM_NOISE = {
    "thinking_tokens", "turn_duration", "post_turn_summary", "thinking",
    "control_request_progress", "background_tasks",
    "commands_changed", "file_snapshot", "files_persisted", "seed_read_state",
    "session_state_changed", "vcs_state_changed", "hook_started", "hook_progress",
    "hook_response", "mcp_status", "memory_recall", "memory_saved", "message_rated",
    "code_change_published", "file_suggestions", "apply_flag_settings", "away_summary",
    "feedback_draft_queued",
    "per_turn_effort_changed", "session_metadata", "peer_message_hold", "turn_preempted",
}


def system_handling(subtype: str | None) -> str:
    if subtype == "init":
        return "已处理（Init）"
    if subtype is None:
        return "无 subtype → systemNote 丢"
    if subtype in SYSTEM_NOISE:
        return "进噪声表（故意丢）"
    if subtype in SYSTEM_HANDLED:
        return "已处理"
    return "未知 subtype → 只认 text/message 字段，否则丢"


def top_handling(top: str) -> str:
    return TOP_HANDLING.get(top, "落顶层 else 被丢")


# ---------------------------------------------------------------------------
# Min 的 launch 形态（ClaudeCodeLaunch.kt claudeLaunchArgs + claudeSessionEnv）
# ---------------------------------------------------------------------------

def resolve_cli() -> str:
    """优先原生 claude.exe（npm shim 背后就是它），退回 shim。"""
    shim = shutil.which("claude")
    if not shim:
        raise SystemExit("claude not found on PATH")
    base = os.path.dirname(os.path.abspath(shim))
    exe = os.path.join(base, "node_modules", "@anthropic-ai", "claude-code", "bin", "claude.exe")
    return exe if os.path.isfile(exe) else shim


def launch_args(session_id: str, extra: list[str]) -> list[str]:
    # 顺序照抄 claudeLaunchArgs()：entry 之外的整段。--model 后插（Manager 里 options.model 的位置）。
    return [
        "-p",
        "--output-format", "stream-json",
        "--input-format", "stream-json",
        "--verbose",
        "--include-partial-messages",
        "--permission-prompt-tool", "stdio",
        "--session-id", session_id,
        "--model", MODEL,
        *extra,
        "--allow-dangerously-skip-permissions",
        "--permission-mode", "bypassPermissions",
    ]


def launch_env() -> dict:
    env = dict(os.environ)
    env["IS_SANDBOX"] = "1"
    return env


# Min 的握手帧（ClaudeCodeProtocol.kt encodeClaudeCodeInitialize + SubagentInbox.subagentInitializeOptions）
INITIALIZE_OPTIONS = {
    "hooks": {
        "PostToolUse": [{"hookCallbackIds": ["min-subagent-post-tool"], "timeout": 10}],
        "PostToolUseFailure": [{"hookCallbackIds": ["min-subagent-post-tool"], "timeout": 10}],
        "SubagentStop": [{"hookCallbackIds": ["min-subagent-stop"], "timeout": 10}],
    },
    "forwardSubagentText": True,
    "agentProgressSummaries": True,
}


def user_frame(text: str) -> str:
    # 照抄 encodeClaudeCodeUserMessage
    return json.dumps({
        "type": "user",
        "parent_tool_use_id": None,
        "message": {"role": "user", "content": [{"type": "text", "text": text}]},
        "origin": {"kind": "human"},
    }, ensure_ascii=False)


def control_request(request_id: str, subtype: str, body: dict | None = None) -> str:
    req = {"subtype": subtype}
    if body:
        req.update(body)
    return json.dumps({"type": "control_request", "request_id": request_id, "request": req})


def control_ok(request_id: str, payload) -> str:
    return json.dumps({"type": "control_response", "response": {
        "subtype": "success", "request_id": request_id, "response": payload}})


def control_err(request_id: str, error: str) -> str:
    return json.dumps({"type": "control_response", "response": {
        "subtype": "error", "request_id": request_id, "error": error}})


# ---------------------------------------------------------------------------
# 场景定义
# ---------------------------------------------------------------------------

PROMPT_A = [
    "Run exactly one Bash command and nothing else: for i in $(seq 1 12); do echo tick $i; sleep 5; done. "
    "Do not modify the command, do not run any other tool, and reply DONE after it finishes.",
    "Use the Bash tool to run this loop verbatim, then reply DONE:\n"
    "for i in $(seq 1 12); do echo tick $i; sleep 5; done\n"
    "Run no other tools.",
    "Please execute the following shell loop with the Bash tool exactly as written, wait for it to "
    "complete, then reply DONE:\nfor i in $(seq 1 12); do echo tick $i; sleep 5; done",
]

PROMPT_B = [
    "Use the Agent tool (the subagent launcher, formerly called Task) to launch exactly one "
    "subagent (subagent_type: general-purpose). Its prompt: "
    "read file1.txt and file2.txt in the current directory and give a one-sentence summary of each. "
    "Wait for it to finish, then reply DONE.",
    "Delegate the reading work: call the Agent tool once with subagent_type 'general-purpose' and a "
    "prompt telling it to Read ./file1.txt and ./file2.txt and summarize both files in one sentence "
    "each. When the subagent completes, reply DONE.",
    "Start one subagent with the Agent tool. The subagent must read the two files file1.txt "
    "and file2.txt in the working directory and summarize their contents. Wait for completion and "
    "reply DONE.",
]

PROMPT_C = [
    "In ONE assistant message, make TWO parallel Agent tool calls (the subagent launcher, formerly "
    "called Task) with subagent_type 'general-purpose': "
    "the first subagent reads only file1.txt and summarizes it in one sentence; the second reads only "
    "file2.txt and summarizes it in one sentence. Wait for both, then reply DONE.",
    "Launch two subagents simultaneously using two Agent tool calls in the same message. Subagent A: "
    "read file1.txt, one-sentence summary. Subagent B: read file2.txt, one-sentence summary. Both "
    "general-purpose. Reply DONE only after both return.",
    "Run both of these in parallel via the Agent tool (two calls in a single message): agent 1 "
    "summarizes ./file1.txt, agent 2 summarizes ./file2.txt. Reply DONE when both have finished.",
]

PROMPT_D = [
    "Use the Grep tool to search the directory haystack for the string needle_1999. Tell me how many "
    "files contain it. Do not use any tool other than Grep for the search.",
    "Search the folder ./haystack with the Grep tool for the pattern needle_1999 and report the number "
    "of matching files.",
    "Find every file under haystack containing needle_1999 — use the Grep tool for this, not Read or "
    "Bash — and give me the count.",
]

PROMPT_E = ["Reply with exactly one word: hello."]


def setup_files(work: str) -> None:
    with open(os.path.join(work, "file1.txt"), "w", encoding="utf-8") as fh:
        fh.write("file1: The Eiffel Tower is in Paris. It was completed in 1889.\n")
    with open(os.path.join(work, "file2.txt"), "w", encoding="utf-8") as fh:
        fh.write("file2: The Great Barrier Reef lies off the coast of Queensland, Australia.\n")


def setup_haystack(work: str) -> None:
    d = os.path.join(work, "haystack")
    os.makedirs(d, exist_ok=True)
    fillers = ["apple banana cherry", "delta gamma epsilon", "ocean mountain river",
               "silver golden copper", "winter summer autumn"]
    for i in range(3000):
        name = os.path.join(d, f"f_{i:04d}.txt")
        if i % 428 == 7:
            body = fillers[i % 5] + " needle_1999 hidden here\n"
        else:
            body = fillers[i % 5] + "\n"
        with open(name, "w", encoding="utf-8") as fh:
            fh.write(body * 3)


def setup_mcp(work: str) -> None:
    cfg = {"mcpServers": {
        "broken": {"type": "stdio", "command": "definitely-not-a-real-cmd-xyz", "args": []}
    }}
    with open(os.path.join(work, "mcp.json"), "w", encoding="utf-8") as fh:
        json.dump(cfg, fh)


SCENARIOS: dict[str, dict] = {
    "long_bash": {
        "prompts": PROMPT_A,
        "setup": None,
        "extra_args": [],
        "check": lambda frames: has_tool(frames, "Bash", contains="seq 1 12"),
        "timeout": SCENARIO_TIMEOUT,
    },
    "subagent": {
        "prompts": PROMPT_B,
        "setup": setup_files,
        "extra_args": [],
        "check": lambda frames: has_tool(frames, "Agent") and distinct_parents(frames) >= 1,
        "timeout": SCENARIO_TIMEOUT,
    },
    "parallel_subagents": {
        "prompts": PROMPT_C,
        "setup": setup_files,
        "extra_args": [],
        "check": lambda frames: tool_count(frames, "Agent") >= 2 and distinct_parents(frames) >= 2,
        "timeout": SCENARIO_TIMEOUT,
    },
    "grep_search": {
        "prompts": PROMPT_D,
        "setup": setup_haystack,
        "extra_args": [],
        "check": lambda frames: has_tool(frames, "Grep"),
        "timeout": SCENARIO_TIMEOUT,
    },
    "bad_mcp": {
        "prompts": PROMPT_E,
        "setup": setup_mcp,
        "extra_args": [],  # --mcp-config 在 run 时按 workdir 拼进 extra
        "check": lambda frames: True,
        "timeout": SCENARIO_TIMEOUT,
    },
    "fallback_flag": {
        "prompts": [],  # 不发消息，只验证启动
        "setup": None,
        "extra_args": ["--fallback-model", MODEL],
        "check": lambda frames: True,
        "timeout": FALLBACK_TIMEOUT,
    },
}


# ---------------------------------------------------------------------------
# 运行器
# ---------------------------------------------------------------------------

class Probe:
    def __init__(self, name: str, spec: dict, attempt: int, prompt: str | None):
        self.name = name
        self.spec = spec
        self.attempt = attempt
        self.prompt = prompt
        self.work = tempfile.mkdtemp(prefix=f"probe_frames_{name}_")
        if spec.get("setup"):
            spec["setup"](self.work)
        extra = list(spec["extra_args"])
        if name == "bad_mcp":
            extra = ["--mcp-config", os.path.join(self.work, "mcp.json")]
        self.session_id = str(uuid.uuid4())
        self.cmd = [resolve_cli()] + launch_args(self.session_id, extra)
        self.stamps: list[float] = []
        self.raw: list[bytes] = []
        self.init_response: dict | None = None
        self.init_event = threading.Event()
        self.result_event = threading.Event()
        self.results = 0
        self.proc: subprocess.Popen | None = None
        self.stdin_lock = threading.Lock()
        self.t0 = time.monotonic()
        self.exit_ok_init = False

    def stamp(self) -> float:
        return round(time.monotonic() - self.t0, 3)

    def send(self, line: str) -> None:
        with self.stdin_lock:
            try:
                assert self.proc and self.proc.stdin
                self.proc.stdin.write((line + "\n").encode("utf-8"))
                self.proc.stdin.flush()
                self.log(f">>> {line[:120]}")
            except (OSError, ValueError, AssertionError):
                self.log(">>> (stdin closed, send failed)")

    def log(self, msg: str) -> None:
        print(f"[{self.stamp():7.3f}] {msg}", flush=True)

    # ---- stdout 读取：原样落盘 + 解析分派 ----
    def pump_out(self) -> None:
        assert self.proc and self.proc.stdout
        for raw in self.proc.stdout:
            self.stamps.append(self.stamp())
            self.raw.append(raw)
            line = raw.decode("utf-8", errors="replace").strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                self.log(f"<<< (unparsed) {line[:160]}")
                continue
            self.dispatch(obj, line)

    def dispatch(self, obj: dict, line: str) -> None:
        t = obj.get("type")
        if t == "stream_event":
            return  # 太吵，只进文件
        if t == "system":
            self.log(f"<<< system/{obj.get('subtype')}")
        elif t == "assistant":
            parts = []
            for b in obj.get("message", {}).get("content", []):
                if isinstance(b, dict):
                    if b.get("type") == "tool_use":
                        parts.append(f"tool_use:{b.get('name')}")
                    elif b.get("type") == "text":
                        parts.append("text:" + b.get("text", "")[:60].replace("\n", " "))
                    else:
                        parts.append(str(b.get("type")))
            self.log("<<< assistant " + " | ".join(parts))
        elif t == "user":
            self.log("<<< user (tool_result)")
        elif t == "result":
            self.results += 1
            self.log(f"<<< RESULT #{self.results} subtype={obj.get('subtype')}")
            self.result_event.set()
        elif t == "control_request":
            req = obj.get("request", {})
            self.log(f"<<< control_request/{req.get('subtype')} id={obj.get('request_id')}")
            sub = req.get("subtype")
            rid = obj.get("request_id", "")
            if sub == "hook_callback":
                # Min 对不表态的 hook 回 {}（encodeClaudeCodeControlSuccess）
                self.send(control_ok(rid, {}))
            elif sub == "can_use_tool":
                # bypassPermissions 下不该出现；兜底 allow，避免卡死
                self.log("!!! unexpected can_use_tool, answering allow")
                self.send(control_ok(rid, {"behavior": "allow"}))
            else:
                # Min 对不支持的 control_request 回 error，避免 CLI 空等
                self.send(control_err(rid, "probe: unsupported control request"))
        elif t == "control_response":
            resp = obj.get("response", {})
            rid = resp.get("request_id")
            if rid and str(rid).startswith("init-"):
                self.init_response = resp.get("response")
                self.init_event.set()
                self.log("<<< control_response for initialize (saved)")
            else:
                self.log(f"<<< control_response {resp.get('subtype')} id={rid}")
        else:
            self.log(f"<<< {t}")

    def pump_err(self) -> None:
        assert self.proc and self.proc.stderr
        self.stderr_bytes = b""
        for raw in self.proc.stderr:
            self.stderr_bytes += raw
            line = raw.decode("utf-8", errors="replace").rstrip()
            if line:
                self.log(f"err {line[:200]}")

    def run(self) -> dict:
        self.log(f"cmd = {' '.join(self.cmd)}")
        self.log(f"work = {self.work}")
        self.stderr_bytes = b""
        self.proc = subprocess.Popen(
            self.cmd, cwd=self.work, env=launch_env(),
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        threading.Thread(target=self.pump_out, daemon=True).start()
        threading.Thread(target=self.pump_err, daemon=True).start()

        # 1. initialize（照抄 Min 握手）
        self.send(control_request("init-1", "initialize", INITIALIZE_OPTIONS))
        got_init = self.init_event.wait(timeout=60)
        timeout = self.spec.get("timeout", SCENARIO_TIMEOUT)
        deadline = time.monotonic() + timeout

        if self.prompt:
            if not got_init:
                self.log("!!! no initialize response in 60s, continuing anyway")
            # 2. 照抄 handshake 里的思考开关
            self.send(json.dumps({"type": "control_request", "request_id": "think-1",
                                  "request": {"subtype": "set_max_thinking_tokens",
                                              "max_thinking_tokens": None,
                                              "display": "summarized"}}))
            time.sleep(0.3)
            # 3. 场景消息
            self.send(user_frame(self.prompt))
            # 4. 等 result；超时先 interrupt（照抄 Min 的停法）再杀
            while not self.result_event.wait(timeout=1.0):
                if time.monotonic() > deadline:
                    self.log("!!! scenario timeout, sending interrupt")
                    self.send(control_request("intr-1", "interrupt", {"cancel_queued": True}))
                    self.result_event.wait(timeout=15)
                    break
        else:
            # fallback_flag：只看启动。system/init 到了就算启动成功
            wait_deadline = time.monotonic() + timeout
            while not self.init_event.wait(timeout=0.5):
                if self.proc.poll() is not None:
                    break
                if time.monotonic() > wait_deadline:
                    break
            self.exit_ok_init = self.init_event.is_set()
            time.sleep(GRACE_AFTER_RESULT)

        if self.prompt:
            # result 之后多收一会儿尾巴（task_notification 之类可能压着 result 到）
            time.sleep(GRACE_AFTER_RESULT)
        try:
            with self.stdin_lock:
                if self.proc.stdin:
                    self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=10)

        # ---- 落盘：stdout 原样 / 时间戳对齐行号 / stderr / init 应答 / 元数据 ----
        os.makedirs(OUT_DIR, exist_ok=True)
        suffix = "" if self.attempt == 1 else f".fail{self.attempt - 1}"
        base = os.path.join(OUT_DIR, self.name + suffix)
        with open(base + ".ndjson", "wb") as fh:
            fh.writelines(self.raw)
        with open(base + ".ts", "w", encoding="utf-8") as fh:
            for s in self.stamps:
                fh.write(f"{s}\n")
        with open(base + ".stderr.log", "wb") as fh:
            fh.write(self.stderr_bytes)
        if self.init_response is not None:
            with open(base + ".init.json", "w", encoding="utf-8") as fh:
                json.dump(self.init_response, fh, ensure_ascii=False, indent=2)
        meta = {
            "scenario": self.name, "attempt": self.attempt, "cmd": self.cmd,
            "work": self.work, "session_id": self.session_id,
            "prompt": self.prompt, "lines": len(self.raw),
            "started_ok": self.init_event.is_set(),
            "exit_code": self.proc.returncode,
        }
        with open(base + ".meta.json", "w", encoding="utf-8") as fh:
            json.dump(meta, fh, ensure_ascii=False, indent=2)
        self.log(f"raw NDJSON -> {base}.ndjson ({len(self.raw)} lines)")
        return meta


# ---- 场景合规检查（assistant tool_use + stream_event 的 content_block_start） ----

def iter_tool_uses(frames: list[dict]):
    for obj in frames:
        if obj.get("type") == "assistant":
            for b in obj.get("message", {}).get("content", []):
                if isinstance(b, dict) and b.get("type") == "tool_use":
                    yield b
        elif obj.get("type") == "stream_event":
            ev = obj.get("event", {})
            if ev.get("type") == "content_block_start":
                b = ev.get("content_block", {})
                if b.get("type") == "tool_use":
                    yield b


def has_tool(frames: list[dict], name: str, contains: str | None = None) -> bool:
    for b in iter_tool_uses(frames):
        if b.get("name") == name and (contains is None or contains in json.dumps(b.get("input", {}))):
            return True
    return False


def tool_count(frames: list[dict], name: str) -> int:
    return sum(1 for b in iter_tool_uses(frames) if b.get("name") == name)


def distinct_parents(frames: list[dict]) -> int:
    return len({p for o in frames
                if (p := o.get("parent_tool_use_id")) is not None})


def load_ndjson(path: str) -> list[dict]:
    out = []
    with open(path, "rb") as fh:
        for raw in fh:
            try:
                out.append(json.loads(raw.decode("utf-8", errors="replace")))
            except json.JSONDecodeError:
                pass
    return out


def run_scenario(name: str) -> dict:
    spec = SCENARIOS[name]
    prompt_variants = spec["prompts"]
    # 无 prompt 的场景（fallback_flag）：只验证启动，跑一次
    attempts = [(i + 1, prompt_variants[i]) for i in range(min(3, len(prompt_variants)))]
    if not prompt_variants:
        attempts = [(1, None)]
    last_meta = None
    for attempt, prompt in attempts:
        print(f"\n===== {name} attempt {attempt}/3 =====", flush=True)
        probe = Probe(name, spec, attempt, prompt)
        last_meta = probe.run()
        frames = load_ndjson(_ndjson_path(name, attempt))
        if spec["check"](frames):
            print(f"[ok] {name}: scenario complied on attempt {attempt}", flush=True)
            # 把成功的 run 顶到正式文件名上
            if attempt > 1:
                _promote(name, attempt)
            last_meta["complied"] = True
            return last_meta
        print(f"[miss] {name}: model did not comply on attempt {attempt}", flush=True)
    if last_meta is not None:
        last_meta["complied"] = False
    return last_meta


def _ndjson_path(name: str, attempt: int) -> str:
    suffix = "" if attempt == 1 else f".fail{attempt - 1}"
    return os.path.join(OUT_DIR, name + suffix + ".ndjson")


def _promote(name: str, attempt: int) -> None:
    for ext in (".ndjson", ".ts", ".stderr.log", ".init.json", ".meta.json"):
        src = os.path.join(OUT_DIR, name + ("" if attempt == 1 else f".fail{attempt - 1}") + ext)
        dst = os.path.join(OUT_DIR, name + ext)
        if os.path.isfile(src):
            shutil.move(src, dst)
        elif os.path.isfile(dst):
            os.remove(dst)


# ---------------------------------------------------------------------------
# 统计
# ---------------------------------------------------------------------------

def frame_key(obj: dict) -> tuple[str, str]:
    t = obj.get("type", "?")
    if t == "control_request":
        sub = obj.get("request", {}).get("subtype", "")
    elif t == "control_response":
        sub = obj.get("response", {}).get("subtype", "")
    else:
        sub = obj.get("subtype", "")
    return t, sub if isinstance(sub, str) else ""


def stats(names: list[str]) -> None:
    files = []
    for n in names:
        p = os.path.join(OUT_DIR, n + ".ndjson")
        if os.path.isfile(p):
            files.append((n, p))
        else:
            print(f"(no {p})")
    if not names:
        for f in sorted(os.listdir(OUT_DIR)):
            if f.endswith(".ndjson") and ".fail" not in f:
                files.append((f[:-7], os.path.join(OUT_DIR, f)))

    agg: dict[tuple[str, str], int] = {}
    per: dict[str, dict[tuple[str, str], int]] = {}
    stream_main = 0
    stream_sub = 0
    stream_event_types: dict[str, int] = {}
    tool_progress: dict[str, list] = {}
    report = ["# probe_frames 统计\n"]

    for name, path in files:
        frames = load_ndjson(path)
        counts: dict[tuple[str, str], int] = {}
        for obj in frames:
            key = frame_key(obj)
            counts[key] = counts.get(key, 0) + 1
            agg[key] = agg.get(key, 0) + 1
            if key[0] == "stream_event":
                if obj.get("parent_tool_use_id"):
                    stream_sub += 1
                else:
                    stream_main += 1
                et = obj.get("event", {}).get("type", "?")
                stream_event_types[et] = stream_event_types.get(et, 0) + 1
            if key == ("tool_progress", "") or (key[0] == "system" and key[1] == "tool_progress"):
                tool_progress.setdefault(name, []).append(obj)
        per[name] = counts

    report.append("## (type, subtype) 计数（全部场景合计）\n")
    report.append("| type | subtype | 条数 | Min 处理方式 |")
    report.append("|---|---|---:|---|")
    for (t, s), c in sorted(agg.items(), key=lambda kv: (-kv[1], kv[0])):
        how = system_handling(s) if t == "system" else top_handling(t)
        report.append(f"| {t} | {s or '—'} | {c} | {how} |")

    report.append("\n## 分场景计数\n")
    for name, _ in files:
        report.append(f"### {name}\n")
        report.append("| type | subtype | 条数 | Min 处理方式 |")
        report.append("|---|---|---:|---|")
        for (t, s), c in sorted(per[name].items(), key=lambda kv: (-kv[1], kv[0])):
            how = system_handling(s) if t == "system" else top_handling(t)
            report.append(f"| {t} | {s or '—'} | {c} | {how} |")
        report.append("")

    report.append(f"## stream_event 按 parent_tool_use_id\n")
    report.append(f"- 主线程（null）: {stream_main}")
    report.append(f"- 子 agent（非空）: {stream_sub}")
    report.append("\n按 event.type:\n")
    for et, c in sorted(stream_event_types.items(), key=lambda kv: -kv[1]):
        report.append(f"- {et}: {c}")

    if tool_progress:
        report.append("\n## tool_progress\n")
        for name, frames in tool_progress.items():
            ts_path = os.path.join(OUT_DIR, name + ".ts")
            report.append(f"### {name}: {len(frames)} 条")
            report.append("字段: " + ", ".join(sorted({k for f in frames for k in f})))

    out_md = "\n".join(report) + "\n"
    print(out_md)
    with open(os.path.join(OUT_DIR, "stats.md"), "w", encoding="utf-8") as fh:
        fh.write(out_md)
    print(f"stats -> {os.path.join(OUT_DIR, 'stats.md')}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["run", "stats"])
    ap.add_argument("scenarios", nargs="*",
                    default=["long_bash", "subagent", "parallel_subagents",
                             "grep_search", "bad_mcp", "fallback_flag"])
    args = ap.parse_args()
    os.makedirs(OUT_DIR, exist_ok=True)

    if args.mode == "stats":
        stats(args.scenarios or [])
        return 0

    names = list(args.scenarios)
    if names == ["all"]:
        names = list(SCENARIOS)
    for n in names:
        if n not in SCENARIOS:
            print(f"unknown scenario {n}", file=sys.stderr)
            return 2
    metas = {}
    for n in names:
        metas[n] = run_scenario(n)
    print("\n===== summary =====")
    for n, m in metas.items():
        if m:
            print(f"{n}: lines={m['lines']} complied={m.get('complied')} work={m['work']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
