"""M5 定向变异：逐个弱化、运行对应需求断言、保存 XML，始终恢复源码。

在开发 worktree 执行；真实 Git 用例自行建临时仓库。只统计正常编译的断言失败。
使用 --only ID 可重跑单个变异；产物在 build/m5/mutations。
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
K = "agent/kernel/src/main/kotlin/dev/min/agent/kernel/"
C = "agent/cli/src/main/kotlin/dev/min/agent/cli/"
W = K + "Worktrees.kt"
F = K + "Fold.kt"
R = K + "SessionRuntime.kt"
H = K + "SessionHub.kt"
M = K + "McpManager.kt"
G = K + "WorktreeGuard.kt"
P = "agent/policy/src/main/kotlin/dev/min/agent/policy/PolicyEngine.kt"
CASES = []


def case(id, group, file, old, new, method, module="cli", cls="WorktreeIntegrationTest"):
    CASES.append(dict(id=id, group=group, edits=[(file, old, new)], module=module,
                      cls=cls, method=method))


create = "create enters CC path*"
dirty = "dirty tracked*"
cwd = "cwd change updates*"
child = "isolated child retains*"
clean_child = "clean isolated child*"
command = "command create takes*"
http = "HTTP create uses*"
remove = "remove hook failure*"
desktop = "desktop CLI launch*"

case("enter-path", "enter", W, '/.claude/worktrees")', '/.claude/trees")', create)
case("enter-branch", "enter", W, 'val branch = "worktree-$slug"', 'val branch = "wt-$slug"', create)
case("enter-ignore", "enter", W, 'old.trimEnd() + \"\\n/.claude/worktrees/\\n\"', 'old.trimEnd() + \"\\n/.claude/unused/\\n\"', create)
case("exit-directory", "exit", W, 'rt.changeCwd(binding.originalCwd, null)\n                rt.commit', 'rt.changeCwd(binding.path, null)\n                rt.commit', create)
case("exit-action", "exit", W, 'if (remove) remove(binding)', 'if (!remove) remove(binding)', create)
case("exit-log", "exit", W, 'WorktreeFinished(binding, remove)', 'WorktreeFinished(binding, !remove)', create)
case("dirty-tracked", "dirty", W, 'if (status.isNotBlank()) return true', 'if (status.startsWith("??")) return true', dirty)
case("dirty-untracked", "dirty", W, 'if (status.isNotBlank()) return true', 'if (status.isNotBlank() && !status.startsWith("??")) return true', dirty)
case("dirty-commits", "dirty", W, '.trim().toLong() != 0L', '.trim().toLong() == 0L', dirty)
case("nongit-check", "nongit", W, 'require(gitOrNull(cwd, "rev-parse", "--is-inside-work-tree")?.trim() == "true")', 'require(true)', 'no git fails*')
case("nongit-hook", "nongit", W, 'if (hooks.has(HookEvent.WorktreeCreate, null)) {', 'if (hooks.has(HookEvent.WorktreeCreate, null)) { require(gitOrNull(cwd, "rev-parse", "--is-inside-work-tree")?.trim() == "true")', command)
case("nongit-cli", "nongit", C + "DesktopApp.kt", 'exitCode = 1)', 'exitCode = 2)', 'no git fails*')
case("path-dots", "paths", W, 'require(text.split(\'/\').none { it == "." || it == ".." })', 'require(true)', 'create command failure*')
case("path-links", "paths", W, 'if (under(component, root)) require(', 'if (false) require(', 'real symlink*')
case("path-foreign", "paths", W, 'require(common == sourceCommon || (allowNested && under(common, root)))', 'require(true)', 'existing external*')
case("command-final", "command-hook", W, 'lineSequence().lastOrNull { it.isNotBlank() }', 'lineSequence().firstOrNull { it.isNotBlank() }', command)
case("command-ansi", "command-hook", W, 'stripAnsi(run.stdout).lineSequence()', 'run.stdout.lineSequence()', command)
case("command-exit", "command-hook", W, 'it.exitCode == 0 && !it.timedOut && !it.failedToStart', '!it.timedOut && !it.failedToStart', 'create command failure*')
case("http-event", "http-hook", W, 'require(specific?.get("hookEventName")?.jsonPrimitive?.content == "WorktreeCreate")', 'require(specific != null)', 'HTTP creation fails*')
case("http-field", "http-hook", W, 'specific["worktreePath"]', 'specific["path"]', http)
case("http-type", "http-hook", W, 'if (run.handler.type == "http")', 'if (false)', http)
case("remove-refusal", "remove-hook", W, 'require(!failed || deps.host.fs.stat(binding.path) == null)', 'require(true)', remove)
case("remove-trigger", "remove-hook", W, 'if (hooks.has(HookEvent.WorktreeRemove, null))', 'if (false)', remove)
case("remove-input", "remove-hook", W, 'WorktreeRemoveInput(deps.hookShellPath(binding.path))', 'WorktreeRemoveInput(deps.hookShellPath(binding.originalCwd))', http)
case("remove-success-fallback", "remove-hook", W, 'return\n        }\n        if (deps.host.fs.stat(binding.path)', 'if (binding.hookCreated) return\n        }\n        if (deps.host.fs.stat(binding.path)', 'successful remove hook*')
permission = "cwd grants new*"
case("permission-root", "cwd-permissions", K + "ToolExecutor.kt", 'Worktrees.under(actual, deps.canonicalPath(s.settings.cwd))', 'Worktrees.under(actual, deps.canonicalPath(s.created.cwd))', permission, "kernel", "CwdWorktreeTest")
case("permission-device", "cwd-permissions", P, 'it.kind == AccessKind.Write && it.scope == PathScope.Device', 'it.kind == AccessKind.Read && it.scope == PathScope.Device', 'Device scope*', "kernel", "CwdWorktreeTest")
case("permission-extras", "cwd-permissions", F, 'additionalDirectories = e.additionalDirectories ?: additionalDirectories', 'additionalDirectories = emptyList()', permission, "kernel", "CwdWorktreeTest")
case("checkpoint-store", "checkpoint", R, 'port.store(state.value.settings.cwd)', 'port.store(state.value.created.cwd)', cwd)
case("checkpoint-root", "checkpoint", K + "Rewind.kt", 'val root = rt.state.value.settings.cwd', 'val root = rt.state.value.created.cwd', cwd)
case("checkpoint-refusal", "checkpoint", K + "Rewind.kt", 'scope != RewindScope.Conversation && targetRoot != null && targetRoot != s.settings.cwd', 'false', cwd)
roots = "MCP roots list*"
case("roots-current", "mcp-roots", M, 'listOf(it.rt.state.value.settings.cwd)', 'listOf(it.rt.state.value.created.cwd)', roots, "kernel", "CwdWorktreeTest")
case("roots-extras", "mcp-roots", M, '+ it.rt.state.value.additionalDirectories', '+ emptyList<GuestPath>()', roots, "kernel", "CwdWorktreeTest")
case("roots-notification", "mcp-roots", H, 'mcp.refreshRoots(runtime)', 'Unit', roots, "kernel", "CwdWorktreeTest")
case("child-isolation", "subagent", K + "AgentCatalog.kt", 'isolationWorktree = def.isolation == "worktree"', 'isolationWorktree = false', child)
case("child-pinned", "subagent", H, 'null, pinned = true)', 'null, pinned = false)', child)
case("child-cleanup", "subagent", W, 'if (!hasWork(binding)) { remove(binding); true }', 'if (false) { remove(binding); true }', clean_child)
case("child-memory", "subagent", H, 'if (!definition!!.omitMemory) initial += MemoryLoaded((ps.memory + ps.cwdMemory).distinctBy { it.path }, autoMemory = ps.autoMemory)', 'if (false) initial += MemoryLoaded((ps.memory + ps.cwdMemory).distinctBy { it.path }, autoMemory = ps.autoMemory)', child)
case("child-cwd-memory", "subagent", H, '(ps.memory + ps.cwdMemory).distinctBy { it.path }', 'ps.memory', child)
case("cli-launch", "cli", C + "DesktopApp.kt", 'if (args.worktree) {', 'if (false) {', desktop)
case("cli-headless", "cli", C + "DesktopApp.kt", 'args.prompt != null || binding.pinned', 'binding.pinned', desktop)
case("cli-automatic", "cli", C + "DesktopApp.kt", 'clean && runtime.state.value.name == null && binding.owned', 'false', desktop)
case("memory-discovery", "memory-reminder", R, 'MemoryLoader.load(deps.host.fs, cwd, home, deps.memoryLimits).files', 'MemoryLoader.load(deps.host.fs, old, home, deps.memoryLimits).files', cwd)
case("memory-reminder", "memory-reminder", F, 'e.memoryFiles.map(dev.min.agent.context.MemoryRender::section)', 'emptyList<String>()', cwd)
case("memory-immediate", "memory-reminder", F, 'if (turn != null) history.lastOrNull { it is HistoryEntry.Assistant }?.messageId else null', 'null as dev.min.agent.protocol.MessageId?', 'model tools switch*')
case("cwd-trigger", "cwd-hook", R, 'run(HookEvent.CwdChanged, null,', 'run(HookEvent.Notification, null,', cwd)
case("cwd-input", "cwd-hook", R, 'CwdChangedInput(deps.hookShellPath(old), deps.hookShellPath(cwd))', 'CwdChangedInput(deps.hookShellPath(cwd), deps.hookShellPath(old))', cwd)
case("cwd-watches", "cwd-hook", R, 'CwdWatchPathsChanged(paths)', 'CwdWatchPathsChanged(emptyList())', cwd)
case("reopen-cwd", "reopen-log", R, 'val runtime = SessionRuntime(log, state, deps, scope, hub)', 'val runtime = SessionRuntime(log, state.copy(settings = state.settings.copy(cwd = state.created.cwd)), deps, scope, hub)', cwd)
case("reopen-binding", "reopen-log", R, 'val runtime = SessionRuntime(log, state, deps, scope, hub)', 'val runtime = SessionRuntime(log, state.copy(worktree = null), deps, scope, hub)', cwd)
case("reopen-validate", "reopen-log", H, 'runtime.worktrees.resumeBinding()', 'Unit', 'existing external*')
case("guard-edit", "isolation-guard", G, 'ask.tool in setOf("Edit", "Write", "NotebookEdit")', 'false', 'isolation rejects*', "kernel", "CwdWorktreeTest")
case("guard-git", "isolation-guard", G, 'if (inMain(path)) return refusal', 'if (false) return refusal', 'isolation rejects*', "kernel", "CwdWorktreeTest")
case("guard-shape", "isolation-guard", G, '?: return "Worktree isolation: command shape cannot be verified. Split it into plain commands in ${binding.path}."', '?: return null', 'isolation rejects*', "kernel", "CwdWorktreeTest")
case("filters-hooks", "git-execution", W, 'git -c core.hooksPath=/dev/null -c core.fsmonitor=false ', 'git -c core.fsmonitor=false ', 'checkout suppresses*')
case("filters-required", "git-execution", W, 'it.endsWith(".required")) "false"', 'it.endsWith(".required")) "true"', 'checkout suppresses*')
case("filters-override", "git-execution", W, '(config + args).toTypedArray()', 'args', 'checkout suppresses*')

case("policy-container", "tool-permissions", P, 'path.removePrefix(worktreeRoot) else path', 'path else path', 'only the active*', "policy", "WorktreePolicyTest")
case("policy-protected", "tool-permissions", P, 'return CommandSafety.isProtectedPath(relative)', 'return false', 'only the active*', "policy", "WorktreePolicyTest")
case("policy-readonly", "tool-permissions", "agent/tools/src/main/kotlin/dev/min/agent/tools/WorktreeTools.kt", 'override val name = "EnterWorktree"\n    override val traits = ToolTraits(resumePolicy', 'override val name = "EnterWorktree"\n    override val traits = ToolTraits(readOnly = true, resumePolicy', 'CLI worktree optional*')

A = "app/src/main/java/dev/min/code/core/engine/min/MinAgentStateMapper.kt"
case("android-branch", "android-state", A, 'it.path.value, it.branch, it.originalCwd.value, it.pinned', 'it.path.value, null, it.originalCwd.value, it.pinned', 'worktreeState*', "app", "MinAgentStateMapperTest")
case("android-origin", "android-state", A, 'it.path.value, it.branch, it.originalCwd.value, it.pinned', 'it.path.value, it.branch, root.settings.cwd.value, it.pinned', 'worktreeState*', "app", "MinAgentStateMapperTest")
case("android-pinned", "android-state", A, 'it.path.value, it.branch, it.originalCwd.value, it.pinned', 'it.path.value, it.branch, it.originalCwd.value, false', 'worktreeState*', "app", "MinAgentStateMapperTest")


def run():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", action="append")
    options = parser.parse_args()
    cases = [c for c in CASES if not options.only or c["id"] in options.only]
    if not cases:
        raise SystemExit("No matching mutations")
    original = {file: (ROOT / file).read_bytes() for c in cases for file, _, _ in c["edits"]}
    evidence = ROOT / "build/m5/mutations"
    evidence.mkdir(parents=True, exist_ok=True)
    summary = evidence / "summary.json"
    outcomes = json.loads(summary.read_text(encoding="utf-8")) if options.only and summary.exists() else []
    env = dict(os.environ)
    if os.name == "nt":
        env["PATH"] = r"C:\Program Files\Git\bin;" + env.get("PATH", "")
    try:
        for number, c in enumerate(cases, 1):
            for file, data in original.items():
                (ROOT / file).write_bytes(data)
            for file, old, new in c["edits"]:
                source = (ROOT / file).read_text(encoding="utf-8")
                if source.count(old) != 1:
                    raise RuntimeError(f"{c['id']}: expected one source anchor, got {source.count(old)}: {old}")
                (ROOT / file).write_text(source.replace(old, new), encoding="utf-8")
            dest = evidence / c["id"]
            dest.mkdir(exist_ok=True)
            argv = ["cmd.exe", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["./gradlew"]
            argv += [":app:testDebugUnitTest" if c["module"] == "app" else f":agent:{c['module']}:test", "--tests", f"*.{c['cls']}.{c['method']}"]
            xml = ROOT / f"agent/{c['module']}/build/test-results/test/TEST-dev.min.agent.{c['module']}.{c['cls']}.xml"
            if c["module"] == "app":
                xml = ROOT / f"app/build/test-results/testDebugUnitTest/TEST-dev.min.code.core.engine.min.{c['cls']}.xml"
            # 旧的红 XML 不能冒充本次测试结果，尤其编译失败时不会刷新它。
            xml.unlink(missing_ok=True)
            print(f"[{number}/{len(cases)}] {c['id']} ({c['group']})", flush=True)
            with (dest / "gradle.log").open("w", encoding="utf-8") as log:
                result = subprocess.run(argv, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            log_text = (dest / "gradle.log").read_text(encoding="utf-8", errors="replace")
            compiled = "Compilation error" not in log_text and re.search(r"> Task .*compile.* FAILED", log_text) is None
            failures = []
            if xml.exists():
                shutil.copy2(xml, dest / "test.xml")
                tree = ET.parse(xml)
                failures = [(t.attrib.get("name"), f.attrib.get("type"), f.attrib.get("message", "")) for t in tree.findall("testcase") for f in t.findall("failure")]
            killed = result.returncode != 0 and compiled and bool(failures) and all("AssertionError" in f[1] or "ComparisonFailure" in f[1] for f in failures)
            outcome = dict(id=c["id"], group=c["group"], exitCode=result.returncode, compiled=compiled, killed=killed, failures=failures)
            (dest / "result.json").write_text(json.dumps(outcome, ensure_ascii=False, indent=2), encoding="utf-8")
            outcomes = [old for old in outcomes if old["id"] != c["id"]] + [outcome]
            (evidence / "summary.json").write_text(json.dumps(outcomes, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"  exit={result.returncode}; compiled={compiled}; killed={killed}", flush=True)
    finally:
        for file, data in original.items():
            (ROOT / file).write_bytes(data)
        hashes = {f: hashlib.sha256((ROOT / f).read_bytes()).hexdigest() for f in original}
        (evidence / "restored-sha256.json").write_text(json.dumps(hashes, indent=2), encoding="utf-8")
    raise SystemExit(0 if all(c["killed"] for c in outcomes) else 1)


if __name__ == "__main__":
    run()
