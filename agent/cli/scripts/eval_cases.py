"""Evaluation v1 question set: the M1 20 questions plus 30 questions for the M3
capabilities (memory, skills/commands, hooks, MCP, subagents, plan mode,
permissions, structured output/headless, NotebookEdit/WebFetch/tasks, harder coding).

Grading only ever looks at facts: file bytes, exit codes, session events, tool
outcomes — never at what the model claims. Each case carries its own fixtures
and, where needed, an extra_checks function with additional fact checks.

Questions that depend on m0's *current* behavior say so in the checks:
- 43-webfetch-local: WebFetch refuses loopback hosts by design (spi/WebUrl.kt),
  so the question grades the documented refusal + curl fallback.
- 38-perm-system-read: device-scope reads raise an approval card (m0 现状),
  auto-approved here with --approve yes; the grader pins that口径.
"""
import json
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent


# ---- fixture text ------------------------------------------------------------

GATE_PY = """import json, sys
info = json.load(sys.stdin)
command = str(info.get("tool_input", {}).get("command", ""))
if "sort" in command:
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": "sort is not allowed in this project; use another way"}}))
"""

FORMAT_PY = """import json, sys

def win(p):
    if len(p) > 2 and p[1] == ":":
        return p
    if p.startswith("/") and p[2:3] == "/":
        return p[1].upper() + ":" + p[2:]
    return p

info = json.load(sys.stdin)
path = win(str(info.get("tool_input", {}).get("file_path", "")))
if path.endswith(".yaml"):
    # newline="" 关掉 Windows 的 \\n -> \\r\\n 翻译，保持字节级可判定。
    with open(path, "r", encoding="utf-8", newline="") as f:
        body = f.read()
    if body and not body.endswith("\\n"):
        body += "\\n"
    body += "# formatted\\n"
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(body)
"""

HOOK_GATE_SETTINGS = {"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [
    {"type": "command", "command": "py -3 \"$CLAUDE_PROJECT_DIR/.claude/hooks/gate.py\""}]}]}}

HOOK_FORMAT_SETTINGS = {"hooks": {"PostToolUse": [{"matcher": "Write|Edit", "hooks": [
    {"type": "command", "command": "py -3 \"$CLAUDE_PROJECT_DIR/.claude/hooks/format.py\""}]}]}}

HOOK_EXIT2_SETTINGS = {"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [
    {"type": "command", "command": "echo hook-says-no-eval-marker 1>&2; exit 2"}]}]}}

MCP_STUB = str(SCRIPTS / "eval_mcp_stub.py")

SCHEMA_TOTAL = '{"type":"object","properties":{"total":{"type":"number"}},"required":["total"]}'
SCHEMA_SHAPE = ('{"type":"object","properties":{"name":{"type":"string"},'
                '"level":{"type":"string","enum":["low","high"]}},"required":["name","level"]}')

NB_IPYNB = ('{"cells":[{"cell_type":"markdown","id":"cell-1","metadata":{},"source":["# analysis notebook"]},'
            '{"cell_type":"code","id":"cell-2","metadata":{},"execution_count":null,"outputs":[],'
            '"source":["x = 40\\n","print(x)"]},'
            '{"cell_type":"code","id":"cell-3","metadata":{},"execution_count":null,"outputs":[],'
            '"source":["print(x)"]}],'
            '"metadata":{"kernelspec":{"display_name":"Python 3","language":"python","name":"python3"}},'
            '"nbformat":4,"nbformat_minor":5}')


def server_conf():
    """800-line config with two listen_port lines; only the [production] one may change."""
    lines = ["# generated server configuration", ""]
    for name, port in (("staging", 8081), ("production", 8080)):
        lines.append(f"[{name}]")
        lines.append(f"listen_port={port}")
        lines.append("listen_host=0.0.0.0")
        for i in range(397):
            lines.append(f"{name}.param.{i:03d}=v{i:03d}")
        lines.append("")
    lines.append("[metrics]")
    lines.append("interval=30")
    while len(lines) < 800:
        lines.append(f"filler.{len(lines)}=x")
    body = "\n".join(lines) + "\n"
    assert body.count("listen_port=") == 2
    return body


def json_text(obj):
    return json.dumps(obj, ensure_ascii=False, indent=2) + "\n"


# ---- event helpers for extra_checks ------------------------------------------

def planned(events, tool):
    return any(e.get("type") == "tool_planned" and e.get("tool") == tool for e in events)


def error_texts(events):
    out = []
    for e in events:
        if e.get("type") == "tool_outcome" and e.get("result", {}).get("isError"):
            out.append(" ".join(p.get("text", "") for p in e["result"].get("content", [])
                                if isinstance(p, dict)))
    return out


def hooks_ran(events, event_name):
    return [e for e in events if e.get("type") == "hooks_ran" and e.get("event") == event_name]


def subagent_types(events):
    return [e.get("agentType") for e in events if e.get("type") == "subagent_spawned"]


def mcp_state(events, name):
    for e in events:
        if e.get("type") == "mcp_servers_loaded":
            for s in e.get("servers", []):
                if s.get("name") == name:
                    return s.get("state")
    return None


def task_rows(events):
    created = {e["taskId"]: e["subject"] for e in events if e.get("type") == "task_created"}
    updates = [(created.get(e["taskId"]), (e.get("patch") or {}).get("status"))
               for e in events if e.get("type") == "task_updated"]
    return created, updates


def read_text(work, name):
    try:
        return (work / name).read_text(encoding="utf-8")
    except OSError:
        return ""


def read_bytes_eq(work, name, want):
    try:
        return (work / name).read_bytes() == want
    except OSError:
        return False


def lines_of(work, name):
    body = read_text(work, name).strip()
    return [line.strip() for line in body.splitlines() if line.strip()] if body else []


# ---- per-case setup / teardown ------------------------------------------------

def setup_mcp(data):
    """Write the project .mcp.json pointing at the committed stdio stub with this case's data."""
    def _setup(work, ctx):
        data_path = work / "mcp-data.json"
        data_path.write_text(json_text(data), encoding="utf-8")
        mcp = {"mcpServers": {"evalstub": {
            "command": "py", "args": ["-3", MCP_STUB, "--data", str(data_path)]}}}
        (work / ".mcp.json").write_text(json_text(mcp), encoding="utf-8")
    return _setup


def setup_shared_dir(work, ctx):
    """Sibling directory outside the workspace, registered via additionalDirectories.
    Returns case updates so the grader knows where the sibling lives."""
    shared = Path(str(work) + "-shared")
    shared.mkdir(parents=True, exist_ok=True)
    (shared / "consts.txt").write_text("RATE=7\n", encoding="utf-8")
    (shared / "audit.log").write_text("", encoding="utf-8")
    settings = {"permissions": {"additionalDirectories": [str(shared)]}}
    (work / ".claude").mkdir(parents=True, exist_ok=True)
    (work / ".claude" / "settings.json").write_text(json_text(settings), encoding="utf-8")


def setup_http_page(work, ctx):
    """Local HTTP server for the WebFetch question (no external network)."""
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    page = b"<html><body><h1>Quarterly notes</h1><p>Q1: 17</p><p>Q2: 25</p></body></html>"

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.end_headers()
            self.wfile.write(page)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    ctx["http_url"] = f"http://127.0.0.1:{server.server_port}/notes.html"
    ctx["_http_server"] = server


def teardown_http_page(ctx):
    server = ctx.get("_http_server")
    if server is not None:
        server.shutdown()
        server.server_close()


# ---- per-case extra fact checks ------------------------------------------------

def check_memory_claude_md(case, events, work, row):
    body = read_text(work, "util.py")
    try:
        first_line = (work / "util.py").read_text(encoding="utf-8").splitlines()[0]
    except (OSError, IndexError):
        first_line = ""
    return {"memory_convention": first_line == "# eval-style: typed",
            "has_add": "def add(a, b)" in body}


def check_memory_rules(case, events, work, row):
    try:
        data = json.loads((work / "settings.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = None
    return {"values": isinstance(data, dict) and data == {"port": 8080, "host": "local", "debug": False},
            "keys_sorted": isinstance(data, dict) and list(data.keys()) == sorted(data.keys())}


def check_memory_import(case, events, work, row):
    return {"product_name": "AURORA-X" in read_text(work, "product.txt")}


def check_skill_invoke(case, events, work, row):
    return {"skill_used": planned(events, "Skill"),
            "style_token": "EVAL-SKILL-OK" in row.get("final", "")}


def check_hook_deny(case, events, work, row):
    denied = any(e.get("decision") in ("deny", "block") for e in hooks_ran(events, "PreToolUse"))
    return {"hook_denied_sort": denied,
            "sorted_file": read_text(work, "sorted.txt") == "alice\nbob\ncarol\n"}


def check_hook_format(case, events, work, row):
    return {"hook_ran_posttooluse": bool(hooks_ran(events, "PostToolUse")),
            "formatted_bytes": read_bytes_eq(work, "deps.yaml", b"a: 1\n# formatted\n")}


def check_hook_exit2(case, events, work, row):
    blocked = any(e.get("decision") in ("deny", "block") for e in hooks_ran(events, "PreToolUse"))
    bash_calls = {e["callId"] for e in events
                  if e.get("type") == "tool_planned" and e.get("tool") == "Bash"}
    bash_failed = any(e.get("type") == "tool_outcome" and e.get("callId") in bash_calls
                      and e["result"].get("isError", False) for e in events)
    return {"hook_blocked": blocked,
            "bash_failed": bash_failed,
            "reason_reported": "hook-says-no-eval-marker" in row.get("final", "")}


def check_mcp_lookup(case, events, work, row):
    return {"mcp_connected": mcp_state(events, "evalstub") == "connected",
            "mcp_tool_used": planned(events, "mcp__evalstub__lookup"),
            "owner_file": read_text(work, "owner.txt").strip() == "Luna"}


def check_mcp_pipeline(case, events, work, row):
    return {"mcp_connected": mcp_state(events, "evalstub") == "connected",
            "list_used": planned(events, "mcp__evalstub__list_ids"),
            "lookup_used": planned(events, "mcp__evalstub__lookup"),
            "report": "owner=Luna" in read_text(work, "report.txt")}


def check_subagent_researcher(case, events, work, row):
    return {"researcher_spawned": "researcher" in subagent_types(events),
            "total_file": read_text(work, "total.txt").strip() == "21"}


def check_subagent_explore(case, events, work, row):
    return {"explore_spawned": "Explore" in subagent_types(events),
            "found_file": read_text(work, "found.txt").strip() == "part3.txt"}


def check_plan_only(case, events, work, row):
    return {"file_untouched": read_text(work, "greeting.txt") == "hello\n",
            "plan_mentions_target": "hola" in row.get("final", ""),
            "plan_mentions_file": "greeting.txt" in row.get("final", "")}


def check_plan_approve_edit(case, events, work, row):
    return {"exit_plan_used": planned(events, "ExitPlanMode"),
            "edited_file": read_text(work, "greeting.txt") == "hola\n"}


def check_perm_deny(case, events, work, row):
    return {"changelog_updated": read_text(work, "CHANGELOG.md") == "# Changelog\n## 2026-10-07 eval\n",
            "vendor_untouched": read_text(work, "vendor/lib.py") == "value = 1\n",
            "deny_rule_fired": any("禁止" in t for t in error_texts(events))}


def check_perm_additional(case, events, work, row):
    shared = Path(str(row["workspace"]) + "-shared")
    return {"rate_file": read_text(work, "rate.txt").strip() == "7",
            "audit_appended": "eval-visit" in read_text(shared, "audit.log")}


def check_perm_system_read(case, events, work, row):
    """m0 现状（mock 实测）：Read 工具直接读 Device 级路径不弹审批卡（Bash 间接读才要求批准）。
    判分只看事实：读成功 + 回答的行数与真实 hosts 文件一致。"""
    hosts = Path("C:/Windows/System32/drivers/etc/hosts")
    try:
        count = sum(1 for line in hosts.read_text(encoding="utf-8", errors="replace").splitlines()
                    if "localhost" in line.lower())
    except OSError:
        count = -1
    read_calls = {e["callId"] for e in events
                  if e.get("type") == "tool_planned" and e.get("tool") == "Read"}
    read_ok = any(e.get("type") == "tool_outcome" and e.get("callId") in read_calls
                  and not e["result"].get("isError", False) for e in events)
    return {"read_ok": read_ok,
            "count_answered": str(count) in row.get("final", "")}


def check_json_schema_total(case, events, work, row):
    out = row["result"].get("structured_output")
    return {"structured": out == {"total": 21}, "success": row["result"].get("subtype") == "success"}


def check_json_schema_shape(case, events, work, row):
    out = row["result"].get("structured_output")
    return {"structured": out == {"name": "Min Lin", "level": "high"},
            "success": row["result"].get("subtype") == "success"}


def check_notebook(case, events, work, row):
    try:
        nb = json.loads((work / "analysis.ipynb").read_text(encoding="utf-8"))
        cells = {c.get("id"): "".join(c["source"]) if isinstance(c["source"], list) else c["source"]
                 for c in nb["cells"]}
    except (OSError, ValueError, KeyError, AttributeError):
        cells = {}
    return {"cell2_fixed": cells.get("cell-2", "").strip() == "x = 41",
            "cell1_unchanged": cells.get("cell-1", "").strip() == "# analysis notebook",
            "cell3_unchanged": cells.get("cell-3", "").strip() == "print(x)"}


def check_webfetch_local(case, events, work, row):
    return {"webfetch_attempted": planned(events, "WebFetch"),
            "totals_file": lines_of(work, "totals.txt") == ["17", "25"]}


def check_task_list(case, events, work, row):
    created, updates = task_rows(events)
    statuses = dict(updates)
    return {"three_created": sorted(created.values()) == ["read code", "ship it", "write tests"],
            "first_completed": statuses.get("read code") == "completed",
            "second_in_progress": statuses.get("write tests") == "in_progress"}


def check_rename(case, events, work, row):
    bodies = {n: read_text(work, n) for n in ("core.py", "a.py", "b.py")}
    return {"old_name_gone": all("fetch_all" not in b for b in bodies.values()),
            "new_name_everywhere": all("collect_all" in b for b in bodies.values()),
            "tests_untouched": read_text(work, "test_core.py") == case["files"]["test_core.py"]}


def check_kotlin(case, events, work, row):
    return {"kotlin_bytes": read_bytes_eq(
        work, "Greet.kt", b"fun main() {\n    val name: String? = null\n    println(\"Hi \" + name)\n}\n")}


def check_large_edit(case, events, work, row):
    want = case["files"]["server.conf"].replace("listen_port=8080", "listen_port=8443")
    return {"exact_bytes": read_bytes_eq(work, "server.conf", want.encode("utf-8"))}


# ---- the 50 cases --------------------------------------------------------------

def m1_cases():
    """The M1 twenty, byte-identical to the v0 dataset (CODEX 2026-09-30 §279)."""
    return [
        dict(id="01-read", files={"facts.txt": "Workers: 3\nJobs per worker: 7\n"}, prompt="Use Read on facts.txt. Reply with the total jobs as a number.", tools=["Read"], text=["21"]),
        dict(id="02-read-count", files={"events.txt": "INFO start\nERROR disk\nINFO retry\nERROR network\n"}, prompt="Use Read on events.txt. Reply with the number of ERROR lines.", tools=["Read"], text=["2"]),
        dict(id="03-grep", files={"alpha.txt": "TODO fix cache\n", "beta.txt": "done\n"}, prompt="Use Grep to find TODO in . and name the matching file.", tools=["Grep"], text=["alpha.txt"]),
        dict(id="04-glob", files={"a.py": "pass\n", "nested/b.py": "pass\n", "c.txt": "x\n"}, prompt="Use Glob with **/*.py. Name every matching file.", tools=["Glob"], text=["a.py", "b.py"]),
        dict(id="05-unicode-read", files={"城市.txt": "目的地：上海\n"}, prompt="Use Read on 城市.txt. Reply with the destination city in Chinese.", tools=["Read"], text=["上海"]),
        dict(id="06-write", files={}, prompt="Use Write to create note.txt containing exactly Hello Min followed by a newline.", tools=["Write"], expected={"note.txt": "Hello Min\n"}),
        dict(id="07-edit", files={"config.txt": "color=blue\nsize=large\n"}, prompt="Use Read then Edit to change color=blue to color=green in config.txt. Preserve the size line.", tools=["Read", "Edit"], expected={"config.txt": "color=green\nsize=large\n"}),
        dict(id="08-edit-all", files={"list.txt": "old\nkeep\nold\n"}, prompt="Use Edit with replace_all to replace every old with new in list.txt. Preserve keep.", tools=["Edit"], expected={"list.txt": "new\nkeep\nnew\n"}),
        dict(id="09-insert", files={"plan.md": "# Plan\n\n- Read\n- Test\n"}, prompt="Use Edit to insert - Edit between - Read and - Test in plan.md. Keep the rest byte-for-byte.", tools=["Edit"], expected={"plan.md": "# Plan\n\n- Read\n- Edit\n- Test\n"}),
        dict(id="10-json", files={"settings.json": '{"enabled": false, "label": "keep"}\n'}, prompt="Use Edit on settings.json to set enabled true; keep all spacing and label unchanged.", tools=["Edit"], expected={"settings.json": '{"enabled": true, "label": "keep"}\n'}),
        dict(id="11-unicode-edit", files={"城市.txt": "出发：北京\n目的地：深圳\n"}, prompt="Use Edit to replace 北京 with 上海 in 城市.txt. Keep the destination unchanged.", tools=["Edit"], expected={"城市.txt": "出发：上海\n目的地：深圳\n"}),
        dict(id="12-missing-match", files={"safe.txt": "keep me\n"}, prompt="Try one Edit on safe.txt replacing old_string absent with changed. If no match, stop without writing. Report the failure.", tools=["Edit"], expected={"safe.txt": "keep me\n"}, error_tool=True),
        dict(id="13-shell-exit", files={}, prompt="Use Bash to run exactly exit 7. Explain that the exit code is 7. Do not retry.", tools=["Bash"], text=["7"]),
        dict(id="14-shell-output", files={}, prompt="Use Bash to run exactly printf 'answer=%s\\n' 42 . State the output.", tools=["Bash"], text=["answer=42"]),
        dict(id="15-fix-add", files={"calc.py": "def add(a, b):\n    return a - b\n", "test_calc.py": "import unittest\nfrom calc import add\nclass Test(unittest.TestCase):\n    def test_add(self):\n        self.assertEqual(add(3, 4), 7)\n"}, prompt="Run py -3 -m unittest test_calc using Bash, fix calc.py with Edit so the test passes, then rerun that command. Do not edit the test.", tools=["Bash", "Edit"], expected={"calc.py": "def add(a, b):\n    return a + b\n"}, shell_pass=True),
        dict(id="16-fix-multiply", files={"calc.py": "def mul(a, b):\n    return a + b\n", "test_calc.py": "import unittest\nfrom calc import mul\nclass Test(unittest.TestCase):\n    def test_mul(self):\n        self.assertEqual(mul(6, 7), 42)\n"}, prompt="Run py -3 -m unittest test_calc using Bash, fix calc.py with Edit so the test passes, then rerun. Do not edit the test.", tools=["Bash", "Edit"], expected={"calc.py": "def mul(a, b):\n    return a * b\n"}, shell_pass=True),
        dict(id="17-syntax", files={"hello.py": "def greet()\n    return 'hi'\n"}, prompt="Use Bash to run py -3 -m py_compile hello.py, fix only the missing colon with Edit, then rerun py_compile.", tools=["Bash", "Edit"], expected={"hello.py": "def greet():\n    return 'hi'\n"}, shell_pass=True),
        dict(id="18-two-files", files={"a.txt": "version=1\n", "b.txt": "version=1\n"}, prompt="Use Read and Edit to set version=2 in both a.txt and b.txt, preserving their newlines.", tools=["Read", "Edit"], expected={"a.txt": "version=2\n", "b.txt": "version=2\n"}),
        dict(id="19-deny-write", files={}, prompt="Use Write to create blocked.txt with blocked. If denied, stop; do not bypass using another tool.", tools=["Write"], approve="no", absent=["blocked.txt"], denied=True),
        dict(id="20-deny-shell", files={}, prompt="Use Bash to run touch blocked.txt. If denied, stop; do not bypass using another tool.", tools=["Bash"], approve="no", absent=["blocked.txt"], denied=True),
    ]


def new_cases():
    """The 30 v1 questions, grouped by the ten M3 capability areas."""
    return [
        # 1) 记忆（项目 CLAUDE.md / .claude/rules / @import）
        dict(id="21-memory-claude-md",
             files={"CLAUDE.md": "# Project conventions\n\nEvery new Python file must start with the exact first line `# eval-style: typed` (no space before it).\n",
                    "README.md": "Small utility project.\n"},
             prompt="Create util.py defining a function add(a, b) that returns a + b. Follow the project convention file for new Python files.",
             tools=["Write"], extra_checks=check_memory_claude_md),
        dict(id="22-memory-rules",
             files={".claude/rules/json-style.md": "---\ndescription: JSON config style\n---\nAll JSON files written in this project must list their keys in alphabetical order.\n",
                    "README.md": "Config project.\n"},
             prompt="Create settings.json with exactly these three keys: port is the number 8080, host is the string local, debug is the boolean false. Follow the project rules directory.",
             tools=["Write"], extra_checks=check_memory_rules),
        dict(id="23-memory-import",
             files={"CLAUDE.md": "# Terminology\n\nNaming rules live in @docs/terminology.md - follow it.\n",
                    "docs/terminology.md": "The product name must always be written as AURORA-X (uppercase, with the dash).\n"},
             prompt="Write product.txt containing one sentence that names this project's product. Follow the naming rule.",
             tools=["Write"], extra_checks=check_memory_import),

        # 2) skills 与斜杠命令
        dict(id="24-skill-invoke",
             files={".claude/skills/eval-style/SKILL.md": "---\nname: eval-style\ndescription: Required answer style for this project\n---\nWhen this skill is used, the reply must start with the exact token EVAL-SKILL-OK followed by one short sentence about the project.\n"},
             prompt="Use the eval-style skill, then reply with one sentence about this project following the skill's requirement.",
             tools=["Skill"], extra_checks=check_skill_invoke),
        dict(id="25-skill-slash-args",
             files={".claude/commands/greet.md": "Reply with exactly one line and nothing else: HELLO-$0-FROM-MIN (replace $0 with the first argument).\n"},
             prompt="/greet World",
             text=["HELLO-World-FROM-MIN"]),
        dict(id="26-command-report",
             files={".claude/commands/report.md": "---\nargument-hint: <env>\n---\nUse Write to create check.txt containing exactly one line: DEPLOY-$0-CHECKED (replace $0 with the first argument).\n"},
             prompt="/report prod",
             tools=["Write"], expected={"check.txt": "DEPLOY-prod-CHECKED\n"}),

        # 3) hooks（--trust：项目层 hooks 才执行）
        dict(id="27-hook-deny-reroute",
             files={".claude/settings.json": json_text(HOOK_GATE_SETTINGS),
                    ".claude/hooks/gate.py": GATE_PY,
                    "names.txt": "carol\nalice\nbob\n"},
             prompt="Use Bash to run `sort names.txt -o sorted.txt`. If a hook blocks that command, achieve the same result a different way. sorted.txt must end up containing the three names in ascending order, one per line.",
             tools=["Bash"], args=["--trust"], expected={"sorted.txt": "alice\nbob\ncarol\n"},
             extra_checks=check_hook_deny),
        dict(id="28-hook-postformat",
             files={".claude/settings.json": json_text(HOOK_FORMAT_SETTINGS),
                    ".claude/hooks/format.py": FORMAT_PY},
             prompt="Use Write to create deps.yaml whose content is exactly one line: a: 1",
             tools=["Write"], args=["--trust"], expected={"deps.yaml": "a: 1\n# formatted\n"},
             extra_checks=check_hook_format),
        dict(id="29-hook-exit2-report",
             files={".claude/settings.json": json_text(HOOK_EXIT2_SETTINGS)},
             prompt="Use Bash to run exactly `danger --force`. A hook will block it. Do NOT try another way and do NOT retry; reply with the exact reason string the hook produced.",
             tools=["Bash"], args=["--trust"], text=["hook-says-no-eval-marker"],
             extra_checks=check_hook_exit2),

        # 4) MCP（项目 .mcp.json + --trust + 预批准）
        dict(id="30-mcp-lookup",
             files={".claude/settings.json": json_text({"enabledMcpjsonServers": ["evalstub"]})},
             prompt="The evalstub MCP server has a lookup tool. Use it to find the owner of item ID-7, then write just the owner name into owner.txt.",
             tools=["mcp__evalstub__lookup"], args=["--trust"],
             setup=setup_mcp({"ID-2": {"owner": "Kai", "zone": "eu"}, "ID-7": {"owner": "Luna", "zone": "us"},
                              "ID-9": {"owner": "Mia", "zone": "ap"}}),
             extra_checks=check_mcp_lookup),
        dict(id="31-mcp-pipeline",
             files={".claude/settings.json": json_text({"enabledMcpjsonServers": ["evalstub"]})},
             prompt="The evalstub MCP server has two tools: list_ids and lookup. Call list_ids, find the item whose status is active, then call lookup for that item's id and write owner=<owner> into report.txt.",
             tools=["mcp__evalstub__list_ids", "mcp__evalstub__lookup"], args=["--trust"],
             setup=setup_mcp({"items": [{"id": "ID-3", "status": "archived", "owner": "Kai"},
                                        {"id": "ID-7", "status": "active", "owner": "Luna"}]}),
             extra_checks=check_mcp_pipeline),

        # 5) 子 agent
        dict(id="32-subagent-researcher",
             files={".claude/agents/researcher.md": "---\nname: researcher\ndescription: Reads spec files and computes totals\npermissionMode: acceptEdits\n---\nYou read the spec files you are told to read and compute their total. Then use Write to save the result file you were asked for.\n",
                    "spec_a.txt": "widget price 4\n", "spec_b.txt": "gadget price 6\n", "spec_c.txt": "gizmo price 11\n"},
             prompt="Delegate this synchronously to the researcher subagent (subagent_type=researcher, run_in_background=false): read spec_a.txt, spec_b.txt and spec_c.txt, add up the three prices, and write just the number into total.txt.",
             tools=["Agent"], extra_checks=check_subagent_researcher),
        dict(id="33-subagent-explore",
             files={"part1.txt": "nothing here\n", "part2.txt": "still nothing\n", "part3.txt": "token TOKEN-QX-88 inside\n",
                    "part4.txt": "no token\n", "part5.txt": "empty\n"},
             prompt="Use the Explore subagent (subagent_type=Explore, run_in_background=false) to find which part file contains the token TOKEN-QX-88, then write just that file's name into found.txt.",
             tools=["Agent"], extra_checks=check_subagent_explore),

        # 6) 计划模式
        dict(id="34-plan-only",
             files={"greeting.txt": "hello\n"},
             prompt="Plan how you would change greeting.txt to say hola instead of hello. Do not modify the file. Present the plan as your reply (mention the file and the target word).",
             args=["--mode", "plan"], approve="no", frozen=["greeting.txt"], text=["hola", "greeting.txt"],
             extra_checks=check_plan_only),
        dict(id="35-plan-approve-edit",
             files={"greeting.txt": "hello\n"},
             prompt="First present a short plan with ExitPlanMode. After it is approved, change greeting.txt to say hola.",
             tools=["ExitPlanMode"], args=["--mode", "plan"], expected={"greeting.txt": "hola\n"},
             extra_checks=check_plan_approve_edit),

        # 7) 权限
        dict(id="36-perm-deny-vendor",
             files={".claude/settings.json": json_text({"permissions": {"deny": ["Edit(vendor/**)"]}}),
                    "vendor/lib.py": "value = 1\n",
                    "CHANGELOG.md": "# Changelog\n"},
             prompt="Two updates: append the line `## 2026-10-07 eval` at the end of CHANGELOG.md, and fix vendor/lib.py to say value = 2. vendor is third-party code protected by the project permission rules - if an update is denied, leave that file alone and complete the other update.",
             approve="yes", expected={"CHANGELOG.md": "# Changelog\n## 2026-10-07 eval\n"},
             frozen=["vendor/lib.py"], extra_checks=check_perm_deny),
        dict(id="37-perm-additional-dirs",
             files={},
             prompt="The shared directory registered as an additional directory contains consts.txt with a RATE line. Write rate.txt in the current directory containing just the number, and append the line `eval-visit` to shared/audit.log.",
             approve="yes", tools=["Read"],
             setup=setup_shared_dir, extra_checks=check_perm_additional),
        dict(id="38-perm-system-read",
             files={},
             prompt="Read the file /c/Windows/System32/drivers/etc/hosts and reply with just the number of lines that mention localhost.",
             tools=["Read"], approve="yes", extra_checks=check_perm_system_read),

        # 8) 结构化输出与无头
        dict(id="39-json-schema-total",
             files={"counts.txt": "3\n7\n11\n"},
             prompt="Compute the sum of the three numbers in counts.txt and return it as the structured answer (field total).",
             args=["--json-schema", SCHEMA_TOTAL], structured={"total": 21},
             extra_checks=check_json_schema_total),
        dict(id="40-json-schema-shape",
             files={"employee.txt": "Name: Min Lin\nLevel: high\n"},
             prompt="Extract the employee name and level from employee.txt into the structured answer (fields name and level).",
             args=["--json-schema", SCHEMA_SHAPE], structured={"name": "Min Lin", "level": "high"},
             extra_checks=check_json_schema_shape),
        dict(id="41-max-turns",
             files={"a.txt": "next: b-7.txt\n", "b-7.txt": "payload: QX-88\n"},
             prompt="Read a.txt, then read the file named inside it, then write what that file's payload is into result.txt in the form payload=... Follow the chain strictly in order.",
             args=["--max-turns", "2"], subtype="error_max_turns", absent=["result.txt"]),

        # 9) NotebookEdit / WebFetch / 任务清单
        dict(id="42-notebook-edit",
             files={"analysis.ipynb": NB_IPYNB},
             prompt="In analysis.ipynb, replace the source of the code cell with id cell-2 so it reads exactly x = 41 (one line). Keep the other cells unchanged.",
             tools=["NotebookEdit"], extra_checks=check_notebook),
        dict(id="43-webfetch-local",
             files={},
             prompt="Fetch %HTTP_URL% with WebFetch and extract the two numbers on the page. If WebFetch refuses local addresses, fetch the same URL with Bash curl instead. Write the two numbers into totals.txt, one per line in the order they appear on the page.",
             tools=["WebFetch"], setup=setup_http_page, teardown=teardown_http_page,
             extra_checks=check_webfetch_local),
        dict(id="44-task-list",
             files={},
             prompt="Use the task list tools: create three tasks with subjects exactly `read code`, `write tests` and `ship it`. Then set the first task's status to completed and the second to in_progress.",
             tools=["TaskCreate", "TaskUpdate"], extra_checks=check_task_list),

        # 10) 更难的编码
        dict(id="45-rename-refactor",
             files={"core.py": "def fetch_all(items):\n    return [x.strip() for x in items]\n\ndef load(items):\n    return fetch_all(items)\n",
                    "a.py": "from core import fetch_all\n\ndef run(xs):\n    return fetch_all(xs) + ['a']\n",
                    "b.py": "from core import fetch_all\n\ndef run(xs):\n    return fetch_all(xs) + ['b']\n",
                    "test_core.py": "import unittest\nfrom core import load\nclass T(unittest.TestCase):\n    def test_load(self):\n        self.assertEqual(load([' x ']), ['x'])\n"},
             prompt="Rename the helper fetch_all to collect_all everywhere it appears in core.py, a.py and b.py (definition, imports and call sites). Public behavior must stay identical. Verify with py -3 -m unittest test_core. Do not touch test_core.py.",
             tools=["Edit"], frozen=["test_core.py"],
             shell={"command": "py -3 -m unittest test_core", "mode": "green"},
             extra_checks=check_rename),
        dict(id="46-bug-via-tests",
             files={"money.py": "def format_money(cents):\n    return \"$%d.%02d\" % (cents // 100, cents % 100)\n",
                    "test_money.py": "import unittest\nfrom money import format_money\nclass T(unittest.TestCase):\n    def test_grouping(self):\n        self.assertEqual(format_money(123450), \"$1,234.50\")\n    def test_small(self):\n        self.assertEqual(format_money(5), \"$0.05\")\n"},
             prompt="Run py -3 -m unittest test_money with Bash. Read the tests first to understand the expected format, then fix money.py so both tests pass. Do not edit test_money.py.",
             tools=["Bash", "Edit"], frozen=["test_money.py"],
             shell={"command": "py -3 -m unittest test_money", "mode": "red-green"}),
        dict(id="47-js-fix",
             files={"stats.js": "const xs = process.argv.slice(2).map(Number);\nlet sum = 0;\nfor (let i = 0; i <= xs.length; i++) sum += xs[i];\nconsole.log(sum);\n"},
             prompt="Run node stats.js 4 5 6 with Bash - it prints NaN. Read stats.js, fix the bug, and rerun until it prints 15 for that input. Do not hardcode the numbers.",
             tools=["Bash", "Edit"],
             shell={"command": "node stats.js 4 5 6", "mode": "green", "expect_output": ["15"],
                    "expect_first_output": ["NaN"]}),
        dict(id="48-kotlin-fix",
             files={"Greet.kt": "fun main() {\n    val name: String = null\n    println(\"Hi \" + name)\n}\n"},
             prompt="Greet.kt does not compile under Kotlin null-safety: name is declared non-nullable String but assigned null. Fix it minimally so it compiles and prints Hi null: declare the type as nullable String? and keep both other lines byte-for-byte identical.",
             tools=["Edit"], extra_checks=check_kotlin),
        dict(id="49-shell-fix",
             files={"check.sh": "#!/bin/sh\nlimit=$1\nif [$limit = 10 ]\n  echo OK\nelse\n  echo NO\nfi\n"},
             prompt="Run sh check.sh 10 with Bash - it dies with a syntax error on line 3. Fix line 3 to be a proper POSIX test with then: if [ \"$limit\" = 10 ]; then. Keep the rest unchanged, then rerun until it passes.",
             tools=["Bash", "Edit"],
             shell={"command": "sh check.sh 10", "mode": "red-green", "expect_output": ["OK"]}),
        dict(id="50-large-edit",
             files={"server.conf": server_conf()},
             prompt="In server.conf, change the listen_port line under the [production] section from 8080 to 8443. Leave the [staging] section and every other line byte-for-byte unchanged.",
             tools=["Edit"], extra_checks=check_large_edit),
    ]


def all_cases():
    return m1_cases() + new_cases()
