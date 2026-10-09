"""Compiled weakenings must fail contract assertions; every original byte is restored."""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument("--out", required=True)
p.add_argument("--init-script", required=True)
p.add_argument("--resume", action="store_true")
p.add_argument("--full-check", action="store_true")
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
out = Path(a.out).resolve()
out.mkdir(parents=True, exist_ok=True)
engine = "agent/workflow/src/main/kotlin/dev/min/agent/workflow/QuickJsWorkflowEngine.kt"
run = "agent/kernel/src/main/kotlin/dev/min/agent/kernel/WorkflowRun.kt"
executor = "agent/kernel/src/main/kotlin/dev/min/agent/kernel/ToolExecutor.kt"
tool = "agent/kernel/src/main/kotlin/dev/min/agent/kernel/WorkflowTool.kt"
turn = "agent/kernel/src/main/kotlin/dev/min/agent/kernel/TurnRunner.kt"

def case(name, file, old, new, module, test):
    return name, file, [(old, new)], module, test

cases = [
    case("memory", engine, "js.memoryLimit = 32L * 1024 * 1024", "// Native memory cap disabled for mutation", "workflow", "WorkflowSafetyTest.memoryLimitIsEnforced"),
    case("watchdog", engine, "if (inFlight.get() == 0 &&", "if (false && inFlight.get() == 0 &&", "workflow", "WorkflowSafetyTest.idleWatchdogInterruptsNativeLoop"),
    case("pipeline", engine, "value = await stage(value, item, i)", "value = await stage(item, i)", "workflow", "WorkflowContractsTest.multiStagePipelinePreservesOriginalItemAndNullSlots"),
    case("parallel-null", engine, ".catch(() => null)", ".catch(() => 0)", "workflow", "WorkflowContractsTest.parallelFailureKeepsItsNullSlot"),
    case("proto-args", engine, "JSON.parse(${JsonPrimitive(it.toString())})", "${it}", "workflow", "WorkflowContractsTest.protoArgumentIsAnOwnProperty"),
    case("prebridge-bytes", engine, "if (progressCount >= 4096 || progressBytes + message.length * 3 > 1048576)", "if (false)", "workflow", "WorkflowContractsTest.progressByteBudgetBoundsFiniteFloodBeforeBridge"),
    case("agent-options", engine, "['schema','label','model','phase','effort','isolation','agentType'].includes(key)", "['schema','label','model'].includes(key)", "workflow", "WorkflowContractsTest.supportedOptionsReachTheHostAndUnknownOptionsAreIgnored"),
    case("failure-null", run, "if (failed) JsonNull else", 'if (failed) workflowError("failed", "bad") else', "kernel", "WorkflowRunTest.schemaUsesDeliveredStructuredValueAndFailuresResolveNull"),
    case("commit-waiter", run, "appendFailure = e; next.value = Int.MAX_VALUE; throw e", "appendFailure = e; throw e", "kernel", "WorkflowRegressionTest.commitFailureWedgesLaterNodes"),
    case("host-progress-budget", run, "if (progressCount >= 4096 || progressBytes + bytes > 1024 * 1024)", "if (false)", "kernel", "WorkflowRunTest.progressBudgetSurvivesReplayAndAnnotatesOnlyOnce"),
    case("edited-prefix", run, "replayBefore = minOf(replayBefore, index)", "replayBefore = Int.MAX_VALUE", "kernel", "WorkflowRunTest.editedScriptReusesOnlyLongestUnchangedPrefix"),
    case("structured-tool", turn, "val plan = state.origin?.workflowSchema?.let(::StructuredOutputPlan) ?: deps.structuredOutput.takeIf { state.origin == null }", "val plan = deps.structuredOutput.takeIf { state.origin == null }", "kernel", "WorkflowRegressionTest.fencedJsonDoesNotReplaceStructuredOutputTool"),
    case("bypass-consent", executor, "if (p.tool.traits.alwaysAsk) return Verdict.Ask", "if (p.tool.traits.alwaysAsk || p.tool is WorkflowTool && rt.state.value.settings.mode == PermissionMode.BypassPermissions) return Verdict.Ask", "kernel", "WorkflowApprovalTest.bypassRunsWithoutPrompt"),
    ("manual-once-cache", tool, [
        ("if (rt.state.value.settings.mode == dev.min.agent.protocol.PermissionMode.Auto && !rt.workflowApproved(input.hash))", "if (!rt.workflowApproved(input.hash))"),
        ("state.value.settings.mode == dev.min.agent.protocol.PermissionMode.Auto &&", "true &&"),
    ], "kernel", "WorkflowApprovalTest.manualOnceDoesNotApproveTheNextRun"),
    case("real-replay", run, "val cached = completed[index]?.takeIf", "val cached = completed[index]?.takeIf { false }?.takeIf", "cli", "WorkflowE2ETest.realEngineReplaysCompletedPrefixAfterInterruptedTurn"),
]
old = """run.turnId != null && run.turnId == rt.state.value.lastTurn?.turnId &&
                    rt.state.value.lastTurn?.outcome is TurnOutcome.Interrupted &&
                    (run.runId !in completed || events.filterIsInstance<dev.min.agent.spi.WorkflowRunCompleted>()
                        .lastOrNull { it.runId == run.runId }?.status == "cancelled")"""
cases.append(case("stale-resume", turn, old, "run.runId.isNotEmpty()", "kernel", "WorkflowRegressionTest.staleUnfinishedRunHijacksALaterResume"))
cases.append(case("android-concurrency", "app/src/main/java/dev/min/code/core/engine/min/MinKernelDeps.kt", "workflowConcurrency = 4", "workflowConcurrency = 16", "app", "MinSnapshotCompositionTest.android_budget_and_real_snapshot_port_follow_current_filesystem_generation"))
prefix = [str(root / "gradlew.bat"), "-I", a.init_script, "--console=plain", "--max-workers=1"]
env = os.environ.copy()
env["PATH"] = "C:/Program Files/Git/bin;" + env["PATH"]
results = json.loads((out / "results.json").read_text()) if a.resume and (out / "results.json").exists() else []
for name, file, changes, module, short in cases:
    if any(x["mutation"] == name and x["status"] == "KILLED" for x in results):
        continue
    path = root / file
    original = path.read_bytes()
    changed = original
    for old, new in changes:
        oldbytes = old.encode().replace(b"\n", b"\r\n") if b"\r\n" in original else old.encode()
        newbytes = new.encode().replace(b"\n", b"\r\n") if b"\r\n" in original else new.encode()
        assert changed.count(oldbytes) == 1, (name, old)
        changed = changed.replace(oldbytes, newbytes)
    dest = out / name
    dest.mkdir(exist_ok=True)
    android = module in ("workflow", "app")
    target = ":app" if module == "app" else f":agent:{module}"
    modulepath = "app" if module == "app" else f"agent/{module}"
    clean = f"{target}:cleanTestDebugUnitTest" if android else f"{target}:cleanTest"
    task = f"{target}:testDebugUnitTest" if android else f"{target}:test"
    xml = root / modulepath / "build/test-results" / ("testDebugUnitTest" if android else "test")
    test = f"dev.min.code.core.engine.min.{short}" if module == "app" else f"dev.min.agent.{module}.{short}"
    try:
        path.write_bytes(changed)
        with (dest / "gradle.log").open("w", encoding="utf-8") as log:
            proc = subprocess.run(prefix + [clean, task, "--tests", test], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
        failures = []
        logtext = (dest / "gradle.log").read_text(encoding="utf-8", errors="replace")
        compiled = "Compilation error" not in logtext and "compileKotlin FAILED" not in logtext and "compileTestKotlin FAILED" not in logtext
        for report in xml.glob("TEST-*.xml"):
            shutil.copy2(report, dest / report.name)
            for t in ET.parse(report).getroot().findall("testcase"):
                f = t.find("failure")
                if t.get("classname") + "." + t.get("name") == test and f is not None:
                    failures.append(dict(test=test, type=f.get("type"), message=f.get("message")))
        assertion = any("Assertion" in (x["type"] or "") or "ComparisonFailure" in (x["type"] or "") for x in failures)
        result = dict(mutation=name, gradle_exit=proc.returncode, compiled=compiled, status="KILLED" if compiled and proc.returncode != 0 and assertion else "INVALID_OR_SURVIVED", failures=failures)
        (dest / "exit.txt").write_text(f"GRADLE_EXIT={proc.returncode}\n", encoding="utf-8")
        results.append(result)
        print(json.dumps(result), flush=True)
    finally:
        path.write_bytes(original)
        assert path.read_bytes() == original
        (out / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
modules=["protocol","spi","mcp","store","llm","policy","context","tools","ext","host","kernel","testkit","cli"]
baseline=[":agent:kernel:cleanTest",":agent:kernel:test","--tests","*Workflow*",":agent:cli:cleanTest",":agent:cli:test","--tests","*WorkflowE2ETest*",":agent:workflow:cleanTestDebugUnitTest",":agent:workflow:testDebugUnitTest","--tests","*Workflow*"]
if a.full_check:
    baseline=[f":agent:{m}:cleanTest" for m in modules]+[":agent:workflow:cleanTestDebugUnitTest",":app:cleanTestDebugUnitTest"]+[f":agent:{m}:check" for m in modules]+[":agent:workflow:check",":app:testDebugUnitTest",":app:assembleDebug"]
(out/"baseline-command.json").write_text(json.dumps(prefix+baseline,indent=2),encoding="utf-8")
with (out / "restored-baseline.log").open("w", encoding="utf-8") as log:
    proc = subprocess.run(prefix+baseline,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
(out/"restored-baseline.exit").write_text(f"GRADLE_EXIT={proc.returncode}\n",encoding="utf-8")
summary = dict(mutations=results, restored_baseline_gradle_exit=proc.returncode)
(out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary), flush=True)
raise SystemExit(0 if proc.returncode == 0 and all(x["status"] == "KILLED" for x in results) else 1)
