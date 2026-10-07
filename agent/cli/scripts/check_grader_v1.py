"""Offline grader validation for all 50 cases (the M1 check_grader discipline).

For every case: load the green mock-run evidence, copy the workspace (and the
sibling directory when one exists) plus the isolated home into a temp tree,
apply one deliberate mutation to the load-bearing fact, and assert the targeted
check turns red and the copy fails. The original evidence is never touched and
must grade green before the mutation. Writes grader-mutation-results.json into
the evidence directory.

Usage: py -3 check_grader_v1.py <evidence-dir>
"""
import copy
import json
import shutil
import sys
import tempfile
from pathlib import Path

import eval_cases
import evaluate


def events_path(home, row):
    return Path(home) / ".min-agent" / "sessions" / row["result"]["session"] / "events.jsonl"


def rewrite_records(home, row, records):
    path = events_path(home, row)
    header = path.read_text(encoding="utf-8").splitlines()[0]
    path.write_text("\n".join([header] + [json.dumps(r, ensure_ascii=False) for r in records]) + "\n",
                    encoding="utf-8")


def load_records(home, row):
    path = events_path(home, row)
    lines = path.read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines[1:]]


def drop_events(pred):
    def _mutate(work, home, row):
        rewrite_records(home, row, [r for r in load_records(home, row) if not pred(r["event"])])
    return _mutate


def transform_events(pred, transform):
    def _mutate(work, home, row):
        records = load_records(home, row)
        for record in records:
            if pred(record["event"]):
                record["event"] = transform(record["event"])
        rewrite_records(home, row, records)
    return _mutate


def inject_event(event):
    def _mutate(work, home, row):
        records = load_records(home, row)
        records.append({"seq": 10 ** 6, "atMs": 0, "event": event})
        rewrite_records(home, row, records)
    return _mutate


def replace_in_final(old, new):
    def _mutate(work, home, row):
        records = load_records(home, row)
        indexes = [i for i, r in enumerate(records) if r["event"].get("type") == "assistant_message_completed"]
        if indexes:
            event = records[indexes[-1]]["event"]
            for part in event.get("parts", []):
                if part.get("type") == "text" and old in part.get("text", ""):
                    part["text"] = part["text"].replace(old, new)
        rewrite_records(home, row, records)
    return _mutate


def outcome_error(pred, value=True):
    def event_pred(event):
        return event.get("type") == "tool_outcome" and pred(event)

    def transform(event):
        event["result"]["isError"] = value
        return event
    return transform_events(event_pred, transform)


def write_file(name, content):
    def _mutate(work, home, row):
        target = Path(work) / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    return _mutate


def write_sibling(name, content):
    """Case 37: the additional-directory sibling lives next to the workspace copy."""
    def _mutate(work, home, row):
        (Path(str(work) + "-shared") / name).write_text(content, encoding="utf-8")
    return _mutate


def set_row(**changes):
    def _mutate(work, home, row):
        result = row.setdefault("result", {})
        for key, value in changes.items():
            result[key] = value
    return _mutate


def planned_of(tool):
    return lambda event: event.get("type") == "tool_planned" and event.get("tool") == tool


def _bump_count(row):
    """38: 把最终回答里的行数 +1（机器相关的数字不能写死在变异表里）。"""
    import re
    final = row.get("final", "")
    match = re.search(r"on (\d+) lines", final)
    if match:
        row["final"] = final.replace(match.group(0), f"on {int(match.group(1)) + 1} lines")
    # final 存在 row 里，判分时从事件重算——所以还要改最后一条 assistant 消息本身。
    records = load_records(row["home"], row)
    indexes = [i for i, r in enumerate(records) if r["event"].get("type") == "assistant_message_completed"]
    if indexes:
        event = records[indexes[-1]]["event"]
        for part in event.get("parts", []):
            if part.get("type") == "text":
                m = re.search(r"on (\d+) lines", part.get("text", ""))
                if m:
                    part["text"] = part["text"].replace(m.group(0), f"on {int(m.group(1)) + 1} lines")
        rewrite_records(row["home"], row, records)


# case id -> [(description, mutation(work, home, row), expected red check)]
MUTATIONS = {
    "01-read": [("final answer loses the number", replace_in_final("21", "22"), "text:21")],
    "02-read-count": [("final answer wrong count", replace_in_final("2 ERROR", "3 ERROR"), "text:2")],
    "03-grep": [("final names the wrong file", replace_in_final("alpha.txt", "beta.txt"), "text:alpha.txt")],
    "04-glob": [("final drops a match", replace_in_final("a.py", "z.py"), "text:a.py")],
    "05-unicode-read": [("final names the wrong city", replace_in_final("上海", "北京"), "text:上海")],
    "06-write": [("note.txt has other content", write_file("note.txt", "WRONG\n"), "file:note.txt")],
    "07-edit": [("edit reverted", write_file("config.txt", "color=blue\nsize=large\n"), "file:config.txt")],
    "08-edit-all": [("only one occurrence replaced", write_file("list.txt", "new\nkeep\nold\n"), "file:list.txt")],
    "09-insert": [("insert missing", write_file("plan.md", "# Plan\n\n- Read\n- Test\n"), "file:plan.md")],
    "10-json": [("spacing mangled", write_file("settings.json", '{"enabled":true, "label": "keep"}\n'), "file:settings.json")],
    "11-unicode-edit": [("wrong city written", write_file("城市.txt", "出发：北京\n目的地：深圳\n"), "file:城市.txt")],
    "12-missing-match": [("tool error not reported", outcome_error(lambda e: True, value=False), "reported_tool_error")],
    "13-shell-exit": [("wrong exit code reported", replace_in_final("code is 7", "code is 8"), "text:7")],
    "14-shell-output": [("wrong output reported", replace_in_final("answer=42", "answer=43"), "text:answer=42")],
    "15-fix-add": [("fix not applied", write_file("calc.py", "def add(a, b):\n    return a - b\n"), "file:calc.py")],
    "16-fix-multiply": [("fix not applied", write_file("calc.py", "def mul(a, b):\n    return a + b\n"), "file:calc.py")],
    "17-syntax": [("colon not fixed", write_file("hello.py", "def greet()\n    return 'hi'\n"), "file:hello.py")],
    "18-two-files": [("second file untouched", write_file("b.txt", "version=1\n"), "file:b.txt")],
    "19-deny-write": [("denial event dropped", drop_events(lambda e: e.get("type") == "approval_resolved"), "approval_denied")],
    "20-deny-shell": [("side effect appeared", inject_event({"type": "tool_effect_pending", "callId": "x"}), "no_effect")],
    "21-memory-claude-md": [("convention marker missing", write_file("util.py", "def add(a, b):\n    return a + b\n"), "memory_convention")],
    "22-memory-rules": [
        ("keys unsorted", write_file("settings.json", '{\n  "port": 8080,\n  "debug": false,\n  "host": "local"\n}\n'), "keys_sorted"),
        ("wrong value", write_file("settings.json", '{\n  "debug": false,\n  "host": "local",\n  "port": 9090\n}\n'), "values"),
    ],
    "23-memory-import": [("naming rule ignored", write_file("product.txt", "This project's product is AuroraX.\n"), "product_name")],
    "24-skill-invoke": [("skill never loaded", drop_events(planned_of("Skill")), "skill_used")],
    "25-skill-slash-args": [("argument lost", replace_in_final("HELLO-World-FROM-MIN", "HELLO-FROM-MIN"), "text:HELLO-World-FROM-MIN")],
    "26-command-report": [("wrong env in file", write_file("check.txt", "DEPLOY-staging-CHECKED\n"), "file:check.txt")],
    "27-hook-deny-reroute": [("hook denial erased", drop_events(lambda e: e.get("type") == "hooks_ran" and e.get("event") == "PreToolUse"), "hook_denied_sort")],
    "28-hook-postformat": [("hook formatting missing", write_file("deps.yaml", "a: 1\n"), "formatted_bytes")],
    "29-hook-exit2-report": [("block reason not reported", replace_in_final("hook-says-no-eval-marker", "hook-says-no"), "reason_reported")],
    "30-mcp-lookup": [("mcp tool never called", drop_events(planned_of("mcp__evalstub__lookup")), "mcp_tool_used")],
    "31-mcp-pipeline": [("list step skipped", drop_events(planned_of("mcp__evalstub__list_ids")), "list_used")],
    "32-subagent-researcher": [("researcher never spawned", drop_events(lambda e: e.get("type") == "subagent_spawned" and e.get("agentType") == "researcher"), "researcher_spawned")],
    "33-subagent-explore": [("explore never spawned", drop_events(lambda e: e.get("type") == "subagent_spawned" and e.get("agentType") == "Explore"), "explore_spawned")],
    "34-plan-only": [("file was modified", write_file("greeting.txt", "hola\n"), "file_untouched")],
    "35-plan-approve-edit": [("exit plan never called", drop_events(planned_of("ExitPlanMode")), "exit_plan_used")],
    "36-perm-deny-vendor": [("deny outcome erased", outcome_error(
        lambda e: "禁止" in " ".join(p.get("text", "") for p in e.get("result", {}).get("content", [])
                                     if isinstance(p, dict)), value=False), "deny_rule_fired"),
        ("changelog entry missing", write_file("CHANGELOG.md", "# Changelog\n"), "changelog_entry")],
    "37-perm-additional-dirs": [("audit line missing", write_sibling("audit.log", ""), "audit_appended")],
    "38-perm-system-read": [
        ("answered count altered", lambda work, home, row: _bump_count(row), "count_answered"),
        ("read outcome failed", outcome_error(lambda e: True, value=True), "read_ok"),
    ],
    "39-json-schema-total": [("structured answer altered", set_row(structured_output={"total": 999}), "structured")],
    "40-json-schema-shape": [("structured answer altered", set_row(structured_output={"name": "X", "level": "low"}), "structured")],
    "41-max-turns": [("limit subtype replaced", set_row(subtype="success"), "subtype")],
    "42-notebook-edit": [("cell left broken", write_file("analysis.ipynb", eval_cases.NB_IPYNB.replace("x = 40", "x = 999")), "cell2_fixed")],
    "43-webfetch-local": [("webfetch never attempted", drop_events(planned_of("WebFetch")), "webfetch_attempted")],
    "44-task-list": [("one task missing", drop_events(lambda e: e.get("type") == "task_created" and e.get("subject") == "ship it"), "three_created")],
    "45-rename-refactor": [("old name reintroduced", write_file("b.py", "from core import fetch_all\n\ndef run(xs):\n    return fetch_all(xs) + ['b']\n"), "old_name_gone")],
    "46-bug-via-tests": [
        ("red phase erased", outcome_error(lambda e: True, value=False), "initial_shell_failed"),
        ("final run still failing", outcome_error(lambda e: True, value=True), "final_shell_success"),
    ],
    "47-js-fix": [("output altered", transform_events(
        lambda e: e.get("type") == "tool_outcome" and "15" in json.dumps(e.get("result", {})),
        lambda e: {**e, "result": {**e["result"], "content": [{"type": "text", "text": "16\n退出码 0"}]}}), "shell_output:15")],
    "48-kotlin-fix": [("nullability not fixed", write_file("Greet.kt", "fun main() {\n    val name: String = null\n    println(\"Hi \" + name)\n}\n"), "kotlin_bytes")],
    "49-shell-fix": [("final run failing", outcome_error(lambda e: True, value=True), "final_shell_success")],
    "50-large-edit": [("wrong port written", write_file("server.conf", eval_cases.server_conf().replace("listen_port=8080", "listen_port=8444")), "exact_bytes")],
}


def main():
    evidence = Path(sys.argv[1])
    rows = json.loads((evidence / "evaluation-results.json").read_text(encoding="utf-8"))
    by_id = {case["id"]: case for case in eval_cases.all_cases()}
    results = []
    failures = []
    for row in rows:
        case = {k: v for k, v in by_id[row["name"]].items() if k not in ("setup", "teardown")}
        work = Path(row["workspace"])
        original = evaluate.grade(case, copy.deepcopy(row), work)
        if not original["passed"]:
            failures.append({"case": row["name"], "problem": "baseline not green", "checks": original["checks"]})
            continue
        for desc, mutate, red in MUTATIONS.get(row["name"], []):
            with tempfile.TemporaryDirectory() as tmp:
                wc = Path(tmp) / "work"
                shutil.copytree(work, wc)
                # 工作区的「<名字>-后缀」兄弟目录是题目 fixture 的一部分（37 的 -shared 附加目录、
                # 38 的 -system hosts fixture），一起拷走，判分才能在拷贝里看到同样的输入。
                for sibling in sorted(work.parent.glob(work.name + "-*")):
                    if sibling.is_dir():
                        shutil.copytree(sibling, Path(str(wc) + sibling.name[len(work.name):]))
                hc = Path(tmp) / "home"
                if row.get("home"):
                    shutil.copytree(row["home"], hc)
                rcopy = copy.deepcopy(row)
                rcopy["workspace"] = str(wc)
                if row.get("home"):
                    rcopy["home"] = str(hc)
                mutate(wc, hc, rcopy)
                verdict = evaluate.grade(case, rcopy, wc)
                ok = not verdict["passed"] and verdict["checks"].get(red) is False
                if not ok:
                    failures.append({"case": row["name"], "mutation": desc, "expected_red": red,
                                     "checks": verdict["checks"]})
                results.append({"case": row["name"], "mutation": desc, "red_check": red, "ok": ok})
    (evidence / "grader-mutation-results.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"mutations": len(results), "failures": failures}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
