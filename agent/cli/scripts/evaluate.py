"""Desktop evaluation v1: 50 independent tasks (the M1 twenty + 30 M3-skill
questions) with explicit, fact-only grades.

Uses the installed Min Agent CLI and writes evidence outside the repository.
Every case runs with an isolated user.home (per-case directory), so sessions,
user-level config and MCP state never touch the developer's real profile.
Reuse the dataset for same-model CC comparison; these scores alone cannot
satisfy the Claude/DeepSeek comparison gate in docs/agent/06-路线图.md.
"""
import json
import os
import sys
import time
from pathlib import Path
from live_smoke import args_parser, Runner, TraceProxy
import eval_cases


def cases():
    return eval_cases.all_cases()


def session_events(row):
    home = row.get("home") or (os.environ["USERPROFILE"] if os.name == "nt" else os.environ["HOME"])
    file = Path(home) / ".min-agent" / "sessions" / row["result"]["session"] / "events.jsonl"
    return [json.loads(line)["event"] for line in file.read_text(encoding="utf-8").splitlines()[1:]]


def grade(case, row, work):
    events = session_events(row)
    planned = {event["tool"] for event in events if event["type"] == "tool_planned"}
    assistant = [event for event in events if event["type"] == "assistant_message_completed"]
    final = "".join(part["text"] for part in assistant[-1]["parts"] if part["type"] == "text") if assistant else ""
    row["final"] = final
    checks = {"tools": set(case.get("tools", [])).issubset(planned)}
    if case.get("subtype"):
        # 期望到限/失败的题（如 --max-turns） completed 本来就是 False，改查「确实没完成」。
        checks["not_completed"] = row["exit"] != 0 or row["result"].get("completed") is False
    else:
        checks["completed"] = row["exit"] == 0 and row["result"].get("completed") is True
    for name, expected in case.get("expected", {}).items():
        checks[f"file:{name}"] = (work / name).is_file() and (work / name).read_bytes() == expected.encode("utf-8")
    # 测试输入不可被改写成迎合实现的假通过。
    for name, initial in case["files"].items():
        if name.startswith("test_"):
            checks[f"unchanged:{name}"] = (work / name).read_bytes() == initial.encode("utf-8")
    for name in case.get("frozen", []):
        checks[f"frozen:{name}"] = name in case["files"] and \
            (work / name).read_bytes() == case["files"][name].encode("utf-8")
    for name in case.get("absent", []):
        checks[f"absent:{name}"] = not (work / name).exists()
    for text in case.get("text", []):
        checks[f"text:{text}"] = text in final
    if case.get("denied"):
        checks["approval_denied"] = any(event["type"] == "approval_resolved" and event["choice"]["type"] == "deny" for event in events)
        checks["no_effect"] = not any(event["type"] == "tool_effect_pending" for event in events)
    if case.get("structured") is not None:
        checks["structured_output"] = row["result"].get("structured_output") == case["structured"]
    if case.get("subtype"):
        checks["subtype"] = row["result"].get("subtype") == case["subtype"]
    outcomes = [event for event in events if event["type"] == "tool_outcome"]
    if case.get("error_tool"):
        checks["reported_tool_error"] = any(event["result"].get("isError") for event in outcomes)
    if case.get("shell_pass"):
        # 只计题目指定的检查命令；之前的 ls 成功既不是测试失败，也不能把合法的红→改→绿判成失败。
        command = "py -3 -m py_compile hello.py" if case["id"] == "17-syntax" else "py -3 -m unittest test_calc"
        bash_ids = {event["callId"] for event in events if event["type"] == "tool_planned" and event["tool"] == "Bash" and command in event["input"]["command"]}
        bash_outcomes = [(index, event) for index, event in enumerate(events) if event["type"] == "tool_outcome" and event["callId"] in bash_ids]
        first_edit = next((index for index, event in enumerate(events) if event["type"] == "tool_planned" and event["tool"] == "Edit"), len(events))
        checks["initial_shell_failed"] = bool(bash_outcomes) and bash_outcomes[0][0] < first_edit and bash_outcomes[0][1]["result"].get("isError", False)
        checks["final_shell_success"] = len(bash_outcomes) >= 2 and bash_outcomes[-1][0] > first_edit and not bash_outcomes[-1][1]["result"].get("isError", False) and any("退出码 0" in part.get("text", "") for part in bash_outcomes[-1][1]["result"]["content"])
    if case.get("shell"):
        checks.update(shell_checks(case["shell"], events))
    if case.get("extra_checks"):
        checks.update(case["extra_checks"](case, events, work, row))
    return {"passed": all(checks.values()), "checks": checks, "final": final}


def shell_checks(spec, events):
    """v1 shell 判分：green = 最后一次指定命令成功；red-green = 第一次在最后一次 Edit 之前失败且最后一次成功；
    expect_first_output = 第一次运行的输出必须包含这些串（如先看到 NaN 再修好）。"""
    command = spec["command"]
    ids = {event["callId"] for event in events
           if event["type"] == "tool_planned" and event["tool"] == "Bash" and command in event["input"]["command"]}
    attempts = [event for event in events if event["type"] == "tool_outcome" and event["callId"] in ids]
    checks = {}
    if spec.get("mode") == "red-green":
        first_edit = next((index for index, event in enumerate(events)
                           if event["type"] == "tool_planned" and event["tool"] == "Edit"), len(events))
        first_index = next((index for index, event in enumerate(events)
                            if event["type"] == "tool_outcome" and event["callId"] in ids), len(events))
        checks["initial_shell_failed"] = bool(attempts) and first_index < first_edit and attempts[0]["result"].get("isError", False)
    for expect in spec.get("expect_first_output", []):
        checks[f"first_output:{expect}"] = bool(attempts) and \
            any(expect in part.get("text", "") for part in attempts[0]["result"]["content"])
    checks["final_shell_success"] = bool(attempts) and not attempts[-1]["result"].get("isError", False) and \
        any("退出码 0" in part.get("text", "") for part in attempts[-1]["result"]["content"])
    for expect in spec.get("expect_output", []):
        checks[f"shell_output:{expect}"] = bool(attempts) and \
            any(expect in part.get("text", "") for part in attempts[-1]["result"]["content"])
    return checks


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--regrade":
        evidence = Path(sys.argv[2])
        file = evidence / "evaluation-results.json"
        rows = json.loads(file.read_text(encoding="utf-8"))
        by_id = {case["id"]: case for case in cases()}
        for row in rows:
            row.update(grade(by_id[row["name"]], row, Path(row["workspace"])))
        file.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"passed": sum(row["passed"] for row in rows), "total": len(rows),
                          "failed": [{"name": row["name"], "checks": row["checks"]} for row in rows if not row["passed"]]}, ensure_ascii=False))
        return
    args = args_parser().parse_args()
    if args.evidence.exists() and any(args.evidence.iterdir()):
        raise ValueError("use a fresh evidence directory so earlier files cannot satisfy grades")
    proxy = TraceProxy(args)
    runner = Runner(args, proxy)
    results = []
    try:
        for case in cases():
            work = args.evidence / "workspaces" / case["id"]
            work.mkdir(parents=True)
            home = args.evidence / "home" / case["id"]
            home.mkdir(parents=True)
            for name, content in case["files"].items():
                file = work / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(content.encode("utf-8"))
            ctx = {}
            started = time.time()
            try:
                if case.get("setup"):
                    case.update(case["setup"](work, ctx) or {})
                prompt = case["prompt"].replace("%HTTP_URL%", ctx.get("http_url", "")) \
                                       .replace("%SYSTEM_HOSTS%", ctx.get("system_hosts", ""))
                if os.environ.get("EVAL_MOCK_URL"):
                    # 模拟模型跑全量调通：每题开跑前把该题的脚本灌进 mock 服务（真模型跑不设这个变量）。
                    import mock_scripts
                    mock_scripts.load_scripts(case, work, ctx, prompt)
                row = runner.run(case["id"], prompt, case.get("approve", "yes"), cwd=work,
                                 extra_args=case.get("args"), home=home,
                                 timeout=case.get("timeout", 600))
                row["duration_ms"] = int((time.time() - started) * 1000)
                verdict = grade(case, row, work)
                if case.get("teardown"):
                    case["teardown"](ctx)
            except Exception as error:  # 单题崩溃只记失败，不拖垮整轮（真模型只有一次完整机会）。
                row = {"name": case["id"], "approval": case.get("approve", "yes"), "exit": -1,
                       "result": {"completed": False, "error": f"{type(error).__name__}: {error}"},
                       "requests": 0, "workspace": str(work), "home": str(home),
                       "duration_ms": int((time.time() - started) * 1000)}
                verdict = {"passed": False, "checks": {"crashed": False}, "final": ""}
            results.append({**row, **verdict})
            runner.safe_write(args.evidence / "evaluation-results.json", json.dumps(results, ensure_ascii=False, indent=2))
            print(json.dumps({"id": case["id"], "passed": verdict["passed"],
                              "duration_ms": row["duration_ms"],
                              "failed_checks": [key for key, passed in verdict["checks"].items() if not passed]}, ensure_ascii=False), flush=True)
        print(json.dumps({"passed": sum(row["passed"] for row in results), "total": len(results)}), flush=True)
    finally:
        proxy.close()


if __name__ == "__main__":
    main()
