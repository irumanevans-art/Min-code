#!/usr/bin/env python3
"""录真 CLI 的 stream-json 帧，给 ClaudeCodeManager 的特征测试当夹具。

usage: py -3 tools/record_cli_frames.py <serial> [场景名 ...]

前提：模拟器已 `adb root`，App（debug 包）里正开着一个 Claude Code 会话 —— 命令行和
环境变量（含 token）从那个进程的 /proc 现取，不写进仓库。脚本扮演 Min：握手、发消息、
批准权限请求、收尾后读用量，双向每一行原样记进
app/src/test/resources/claudecode/frames/<场景>.txt：

    > {...}   Min → CLI
    < {...}   CLI → Min
    ! ...     CLI 的 stderr（和 stdout 之间的先后只是大致的）

CLI 以 App 的 uid 跑（run-as），不然 rootfs 里会留下 root 属主的文件，App 之后写不动。
录完删掉设备上的临时脚本（里面有 token）和这几段测试会话的 transcript。
"""
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import queue
import time
import uuid

PKG = "dev.min.code.debug"
ADB = shutil.which("adb") or os.path.join(
    os.environ.get("ANDROID_SDK_ROOT", "C:/AndroidSDK"), "platform-tools", "adb.exe")
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "claudecode", "frames")
TURN_TIMEOUT_S = 240

SCENARIOS = {
    # 普通回复：流式正文 + result
    "plain_reply": dict(prompts=["Reply with exactly the two letters OK and nothing else."]),
    # 工具调用：default 模式下会先来 can_use_tool，批准后才有 tool_use / tool_result
    "bash_with_permission": dict(prompts=[
        "Run this exact Bash command and nothing else: "
        "mkdir -p /tmp/minfx && echo hello-from-min | tee /tmp/minfx/out.txt . "
        "Then reply with one word: done."
    ]),
    # 子 agent：parent_tool_use_id 不为空的帧 + task_* 系统帧
    "subagent": dict(prompts=[
        "Use your Agent (Task) tool to launch one general-purpose subagent whose prompt is: "
        "'Reply with the number 4 and nothing else.' Then tell me in one short sentence what it returned."
    ]),
    # 错误收尾：中转站没有这个模型
    "model_not_found": dict(prompts=["Say hi."], extra_args=["--model", "claude-nonexistent-9"]),
    # 停止键打断一轮：正文已经开始流了
    "interrupt_midturn": dict(prompts=["Count from 1 to 300, one number per line, no other text."],
                              interrupt_after="delta"),
    # 停止键撤回：消息刚发出、一个字都还没吐
    "interrupt_before_output": dict(prompts=["Write a haiku about the sea."], interrupt_after="sent"),
    # 停止键打在权限请求挂着的时候（托管服务等结论期间就是这样）：can_use_tool 不应答直接 interrupt，
    # 收尾之后再补一条迟到的 deny，看 CLI 认不认
    "interrupt_during_permission": dict(prompts=[
        "Run this exact Bash command and nothing else: ls / . Then reply with one word: done."
    ], interrupt_after="permission"),
}


def adb(serial, *args, **kw):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, **kw)


def shell_quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def find_cli_proot(serial):
    ps = adb(serial, "shell", "ps -A -o PID,USER,NAME,ARGS").stdout.decode()
    for line in ps.splitlines():
        if "libproot_exec.so" in line:
            pid = line.split()[0]
            cmd = adb(serial, "shell", f"cat /proc/{pid}/cmdline").stdout
            if b"stream-json" in cmd:
                env = adb(serial, "shell", f"cat /proc/{pid}/environ").stdout
                # cmdline 以 NUL 结尾，切完最后一段是空串；只去掉它，参数里合法的空串要留着
                return [a.decode() for a in cmd.split(b"\0")[:-1]], [e.decode() for e in env.split(b"\0") if e]
    sys.exit("没找到 App 的 CLI 进程：先在 App 里启动一个 Claude Code 会话")


def build_script(argv, environ, session_id, extra_args):
    # bash -c 的位置参数依次是 $0=rikkahub、$1=cwd、$2=那条 claude 命令、$3=PATH
    at = argv.index("rikkahub") + 2
    cmd = argv[at]
    cmd = re.sub(r"'--session-id' '[^']*'", f"'--session-id' '{session_id}'", cmd)
    cmd = re.sub(r"'--resume' '[^']*'", f"'--session-id' '{session_id}'", cmd)
    cmd += "".join(" " + shell_quote(a) for a in extra_args)
    argv = argv[:at] + [cmd] + argv[at + 1:]
    keep = [e for e in environ if e.split("=", 1)[0] in ("PROOT_TMP_DIR", "PROOT_LOADER", "TMPDIR")]
    files_dir = next(a.split(":", 1)[0] for a in argv if a.endswith(":/workspace"))
    lines = ["#!/system/bin/sh", f"cd {shell_quote(files_dir)}"]
    lines += [f"export {shell_quote(e)}" for e in keep]
    lines.append("exec " + " ".join(shell_quote(a) for a in argv))
    return "\n".join(lines) + "\n", argv


def secrets_of(argv):
    out = []
    for a in argv:
        m = re.match(r"(ANTHROPIC_AUTH_TOKEN|ANTHROPIC_API_KEY|ANTHROPIC_BASE_URL)=(.+)", a)
        if m:
            out.append(m.group(2))
    return out


def control(rid, subtype, **body):
    return json.dumps({"type": "control_request", "request_id": rid, "request": {"subtype": subtype, **body}},
                      ensure_ascii=False)


def user_message(text):
    return json.dumps({"type": "user", "parent_tool_use_id": None,
                       "message": {"role": "user", "content": [{"type": "text", "text": text}]},
                       "origin": {"kind": "human"}}, ensure_ascii=False)


class Session:
    def __init__(self, serial, script_path, log):
        self.p = subprocess.Popen([ADB, "-s", serial, "shell", "-T", "run-as", PKG, "sh", script_path],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.lines = queue.Queue()
        self.log = log
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._stderr, daemon=True).start()

    def _read(self):
        for raw in self.p.stdout:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line.strip():
                self.log.append("< " + line)
                self.lines.put(line)
        self.lines.put(None)

    def _stderr(self):
        for raw in self.p.stderr:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line.strip():
                self.log.append("! " + line)
                sys.stderr.write("  stderr: " + line + "\n")

    def send(self, line):
        self.log.append("> " + line)
        self.p.stdin.write((line + "\n").encode())
        self.p.stdin.flush()

    def next(self, timeout):
        return self.lines.get(timeout=timeout)

    def until(self, pred, timeout=TURN_TIMEOUT_S):
        """读到满足 pred 的帧为止；途中的 can_use_tool 一律批准"""
        deadline = time.time() + timeout
        while True:
            line = self.next(max(1, deadline - time.time()))
            if line is None:
                raise RuntimeError("CLI 提前退出")
            try:
                obj = json.loads(line)
            except ValueError:
                continue
            if obj.get("type") == "control_request" and obj.get("request", {}).get("subtype") == "can_use_tool":
                self.send(json.dumps({"type": "control_response", "response": {
                    "subtype": "success", "request_id": obj["request_id"], "response": {"behavior": "allow"}}}))
                continue
            if pred(obj):
                return obj

    def call(self, subtype, **body):
        rid = "rec-" + subtype + "-" + uuid.uuid4().hex[:6]
        self.send(control(rid, subtype, **body))
        return self.until(lambda o: o.get("type") == "control_response"
                          and o.get("response", {}).get("request_id") == rid, timeout=60)


def record(serial, name, spec, argv, environ):
    session_id = str(uuid.uuid4())
    script, full_argv = build_script(argv, environ, session_id, spec.get("extra_args", []))
    remote = "files/rec_frames.sh"
    push = subprocess.run([ADB, "-s", serial, "shell", "-T", "run-as", PKG, "sh", "-c", f"'cat > {remote}'"],
                          input=script.encode(), capture_output=True)
    if push.returncode != 0:
        sys.exit(push.stderr.decode())
    log = []
    try:
        s = Session(serial, remote, log)
        # 和 ClaudeCodeManager.handshake 同一套握手
        s.call("initialize")
        s.call("set_max_thinking_tokens", max_thinking_tokens=None, thinking_display="summarized")
        s.call("get_settings")
        s.call("get_context_usage")
        s.call("get_session_cost")
        for prompt in spec["prompts"]:
            s.send(user_message(prompt))
            interrupt = spec.get("interrupt_after")
            pending_permission = None
            if interrupt == "permission":
                while pending_permission is None:
                    line = s.next(TURN_TIMEOUT_S)
                    if line is None:
                        raise RuntimeError("CLI 提前退出")
                    try:
                        obj = json.loads(line)
                    except ValueError:
                        continue
                    if obj.get("type") == "control_request" and obj.get("request", {}).get("subtype") == "can_use_tool":
                        pending_permission = obj["request_id"]
            if interrupt == "delta":
                s.until(lambda o: o.get("type") == "stream_event"
                        and o.get("event", {}).get("type") == "content_block_delta")
            if interrupt:
                # 和 encodeClaudeCodeInterrupt 同形；应答不等，它和 result 的先后由 CLI 定
                s.send(control("rec-interrupt-" + uuid.uuid4().hex[:6], "interrupt", cancel_queued=True))
            s.until(lambda o: o.get("type") == "result")
            if pending_permission:
                s.send(json.dumps({"type": "control_response", "response": {
                    "subtype": "success", "request_id": pending_permission,
                    "response": {"behavior": "deny", "message": "late deny after interrupt"}}}))
            # Result 收尾：refreshUsage + 首轮拟名
            s.call("get_context_usage")
            s.call("get_session_cost")
            s.call("generate_session_title", description=prompt, persist=True)
        s.p.stdin.close()
        s.p.wait(timeout=30)
        log.append(f"# exit {s.p.returncode}")
    finally:
        adb(serial, "shell", f"run-as {PKG} rm -f {remote}")
        # 测试会话的 transcript 别留在 App 的会话列表里
        # （同名目录里是子 agent 的记录）
        adb(serial, "shell", f"run-as {PKG} sh -c 'rm -rf files/workspaces/*/linux/root/.claude/projects/*/{session_id}.jsonl "
                             f"files/workspaces/*/linux/root/.claude/projects/*/{session_id}'")

    text = "\n".join(log) + "\n"
    for secret in secrets_of(full_argv):
        text = text.replace(secret, "<redacted>")
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name + ".txt")
    header = (f"# {name}：真 CLI 录制（tools/record_cli_frames.py），"
              f"'>' = Min → CLI，'<' = CLI → Min\n")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header + text)
    print(f"{name}: {len(log)} 行 → {os.path.normpath(path)}")


def main():
    serial = sys.argv[1]
    names = sys.argv[2:] or list(SCENARIOS)
    argv, environ = find_cli_proot(serial)
    for name in names:
        record(serial, name, SCENARIOS[name], argv, environ)


if __name__ == "__main__":
    main()
