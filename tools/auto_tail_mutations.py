"""削弱防护后必须编译成功、命中断言；每项结束都恢复原始字节。"""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--out', required=True)
p.add_argument('--init-script', required=True)
p.add_argument('--resume', action='store_true')
p.add_argument('--full-check', action='store_true')
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
out = Path(a.out).resolve()
out.mkdir(parents=True, exist_ok=True)
kernel = 'agent/kernel/src/main/kotlin/dev/min/agent/kernel/'
proxy = 'agent/host/src/main/kotlin/dev/min/agent/host/LoopbackNetworkProxy.kt'

cases = [
    ('idle', proxy, 'upstream.soTimeout = 0', 'upstream.soTimeout = socketTimeoutMs', 'host', 'LoopbackNetworkProxyTest.establishedConnectionsSurviveTwentySecondsIdle'),
    ('owned-pool', proxy, 'SupervisorJob() + dispatcher', 'SupervisorJob() + Dispatchers.IO', 'host', 'LoopbackNetworkProxyTest.blockingProxyWorkUsesOwnedPoolAndClosesIt'),
    ('ipv4', 'agent/spi/src/main/kotlin/dev/min/agent/spi/NetworkHost.kt', 'if (labels.all', 'if (false && labels.all', 'ext', 'config.NetworkRulesTest.ambiguousIpv4IsRejectedBeforeAllowRules'),
    ('unsigned-length', proxy, 'Regex("[0-9]+").matches(it) &&', 'true &&', 'host', 'LoopbackNetworkProxyTest.signedLengthAndAmbiguousIpv4CannotTriggerApproval'),
    ('broken-managed', kernel + 'NetworkSandbox.kt', 'if (snapshot.managedPolicyBroken)', 'if (false && snapshot.managedPolicyBroken)', 'kernel', 'NetworkSandboxTest.brokenManagedSettingsKeepLocalTurnsButCloseNetwork'),
    ('spawn-gate', kernel + 'SessionHub.kt', 'if (ps.settings.mode == PermissionMode.Auto)', 'if (false && ps.settings.mode == PermissionMode.Auto)', 'kernel', 'AutoSpawnTest.allThreeEntrancesRejectBeforeChildCreation'),
    ('spawn-mode-change', kernel + 'AutoActionGate.kt', 'if (state.value.settings.mode != context.mode)', 'if (false && state.value.settings.mode != context.mode)', 'kernel', 'AutoSpawnTest.permissionModeChangedDuringSpawnReviewCannotUseOldAutoAllow'),
    ('child-user-context', kernel + 'SessionHub.kt', 'AutoReviewer(parent).trustedUserTexts(ps, ToolExecutor(parent, HookDriver(parent)).policyContext())', 'emptyList()', 'kernel', 'AutoSpawnTest.childReceivesParentBoundaryRevocationWithoutTrustingItsPrompt'),
    ('handback-eligibility', kernel + 'SubagentHandbackTool.kt', '!it.fork &&', 'true &&', 'kernel', 'AutoHandbackTest.toolOnlyAppearsForOrdinaryAutoChildren'),
    ('handback-review', kernel + 'SubagentHandbackTool.kt', 'val verdict = rt.reviewReport(turnId, input)', 'val verdict = Verdict.Allow(DecidedBy.Classifier)', 'kernel', 'AutoHandbackTest.rejectedHandbackCanBeRevisedAndOnlyApprovedBodyIsDelivered'),
    ('fallback-withhold', kernel + 'SubagentHandbackTool.kt', 'SubagentReportReady(turnId, "[子 agent 报告未通过审查，正文未交付。]", "withheld")', 'SubagentReportReady(turnId, body, "withheld")', 'kernel', 'AutoHandbackTest.fallbackReviewsLastMessageAndWithholdsRejectedText'),
    ('unreviewed-partial', kernel + 'Delegation.kt', 'state.autoReportReviewRequired() && reviewed == null', 'false && state.autoReportReviewRequired() && reviewed == null', 'kernel', 'AutoHandbackTest.interruptedAutoChildCannotDeliverUnreviewedLastText'),
    ('old-boundary', kernel + 'AutoReviewer.kt', 'trustedUserTexts(state, context).map', 'emptyList<String>().map', 'kernel', 'AutoReviewerTest.boundariesSurviveSummaryAndLongConversationAndOnlyUserCanLiftThem'),
    ('origin-user-only', kernel + 'AutoUserContext.kt', 'it.origin == InputOrigin.User', 'true', 'kernel', 'AutoReviewerTest.boundariesSurviveSummaryAndLongConversationAndOnlyUserCanLiftThem'),
    ('workflow-identity', kernel + 'ModelView.kt', 'if (origin?.workflow == true &&', 'if (false && origin?.workflow == true &&', 'kernel', 'AutoSpawnTest.workflowAllowCreatesChildBeforeRunningItsTask'),
    ('headless-refusal-text', kernel + 'AutoActionGate.kt', 'Agent aborted: auto mode classifier request refused by the safety safeguard in headless mode', 'incorrect classifier refusal', 'kernel', 'AutoSpawnTest.headlessSafetyRefusalAndContextOverflowHaveOriginalErrorText'),
    ('stop-hook-note', kernel + 'TurnRunner.kt', 'closing.ifBlank { HANDBACK_NOTE }', 'closing.ifBlank { "incorrect note" }', 'kernel', 'AutoHandbackTest.stopHookReceivesShortHandbackNoteInsteadOfReport'),
    ('post-tooluse-note', kernel + 'ToolExecutor.kt', 'if (p.call.name == "Agent" && handbackChild != null)', 'if (p.call.name == "neverAgent" && handbackChild != null)', 'kernel', 'AutoSpawnTest.foregroundAgentPostToolUseGetsHandbackNoteObject'),
    ('structured-withheld-error', kernel + 'Delegation.kt', 'fun isError(outcome: TurnOutcome, state: SessionState? = null): Boolean = outcome !is TurnOutcome.Completed ||', 'fun isError(outcome: TurnOutcome, state: SessionState? = null): Boolean = outcome !is TurnOutcome.Completed || false &&', 'kernel', 'AutoSpawnTest.workflowStructuredReportIsReviewedAndRejectedValueIsNotDelivered'),
    ('review-card', 'app/src/main/java/dev/min/code/core/engine/min/MinItems.kt', 'if (report.handback && report.review == "approved")', 'if (false && report.handback && report.review == "approved")', 'app', 'MinAgentProjectionTest.handbackReportUsesExistingCardWithReviewLabelAndOriginalBody'),
    ('redacted-context-ask', kernel + 'AutoReviewer.kt', 'userContextRedacted(rt.state.value, context) ->', 'false && userContextRedacted(rt.state.value, context) ->', 'kernel', 'AutoReviewerTest.redactedInheritedUserContextCannotAutomaticallyApproveWithoutBoundaries'),
    ('failure-note-withhold', kernel + 'Delegation.kt', 'if (state.autoReportReviewRequired() && ended.outcome is TurnOutcome.Failed', 'if (false && state.autoReportReviewRequired() && ended.outcome is TurnOutcome.Failed', 'kernel', 'AutoHandbackTest.failedAutoChildCannotDeliverUnreviewedErrorText'),
    ('captured-auto-mode', kernel + 'SubagentHandbackTool.kt', 'settings.mode == PermissionMode.Auto || created.mode == PermissionMode.Auto', 'settings.mode == PermissionMode.Auto || false && created.mode == PermissionMode.Auto', 'kernel', 'AutoHandbackTest.autoCreatedChildStillReviewsReportAfterSwitchingToPlan'),
    ('message-contract', kernel + 'SubagentHandbackTool.kt', 'put("required", JsonArray(listOf(JsonPrimitive("message"))))', 'put("required", JsonArray(listOf(JsonPrimitive("report"))))', 'kernel', 'AutoHandbackTest.toolInputMatchesDocumentedMessageField'),
    ('workflow-runtime-spawn-gate', kernel + 'SessionHub.kt', 'if (ps.settings.mode == PermissionMode.Auto)', 'if (false && ps.settings.mode == PermissionMode.Auto)', 'kernel', 'AutoSpawnTest.workflowExecutionReachesCommonSpawnReviewBeforeNodeModel'),
    ('disabled-auto-report', kernel + 'AutoReviewer.kt', 'settings.disableAutoMode ->', 'false && settings.disableAutoMode ->', 'kernel', 'AutoReviewerTest.disabledAutoClassifierCannotApproveFormerAutoReport'),
    ('report-mode-race', kernel + 'AutoActionGate.kt', 'if (!readOnly || current is Verdict.Deny)', 'if (true)', 'kernel', 'AutoSpawnTest.modeChangeDuringReportReviewCannotReplaceClassifierDenialWithReadOnlyAllow'),
]
prefix = [str(root / 'gradlew.bat'), '-I', a.init_script, '--console=plain', '--max-workers=1', '--no-daemon']
env = os.environ.copy()
env['PATH'] = 'C:/Program Files/Git/bin;' + env['PATH']
results = json.loads((out / 'results.json').read_text()) if a.resume and (out / 'results.json').exists() else []
for name, file, old, new, module, short in cases:
    if any(x['mutation'] == name and x['status'] == 'KILLED' for x in results):
        continue
    path = root / file
    original = path.read_bytes()
    oldbytes, newbytes = old.encode(), new.encode()
    assert original.count(oldbytes) == 1, (name, old)
    dest = out / name
    dest.mkdir(exist_ok=True)
    android = module == 'app'
    target = ':app' if android else f':agent:{module}'
    modulepath = 'app' if android else f'agent/{module}'
    clean = f'{target}:cleanTestDebugUnitTest' if android else f'{target}:cleanTest'
    task = f'{target}:testDebugUnitTest' if android else f'{target}:test'
    xml = root / modulepath / 'build/test-results' / ('testDebugUnitTest' if android else 'test')
    test = f'dev.min.code.core.engine.min.{short}' if android else f'dev.min.agent.{module}.{short}'
    try:
        path.write_bytes(original.replace(oldbytes, newbytes))
        with (dest / 'gradle.log').open('w', encoding='utf-8') as log:
            proc = subprocess.run(prefix + [clean, task, '--tests', test], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
        failures = []
        logtext = (dest / 'gradle.log').read_text(encoding='utf-8', errors='replace')
        compiled = not any(x in logtext for x in ['Compilation error', 'compileKotlin FAILED', 'compileTestKotlin FAILED', 'compileDebugKotlin FAILED', 'compileDebugUnitTestKotlin FAILED'])
        for report in xml.glob('TEST-*.xml'):
            shutil.copy2(report, dest / report.name)
            for t in ET.parse(report).getroot().findall('testcase'):
                f = t.find('failure')
                if t.get('classname') + '.' + t.get('name') == test and f is not None:
                    failures.append(dict(test=test, type=f.get('type'), message=f.get('message')))
        assertion = any('Assertion' in (x['type'] or '') or 'ComparisonFailure' in (x['type'] or '') for x in failures)
        result = dict(mutation=name, gradle_exit=proc.returncode, compiled=compiled, status='KILLED' if compiled and proc.returncode != 0 and assertion else 'INVALID_OR_SURVIVED', failures=failures)
        (dest / 'exit.txt').write_text(f'GRADLE_EXIT={proc.returncode}\n', encoding='utf-8')
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        path.write_bytes(original)
        assert path.read_bytes() == original
        (out / 'results.json').write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding='utf-8')
    if result['status'] != 'KILLED':
        raise SystemExit(1)
modules = ['protocol', 'spi', 'mcp', 'store', 'llm', 'policy', 'context', 'tools', 'ext', 'host', 'kernel', 'testkit', 'cli']
baseline = [':agent:kernel:cleanTest', ':agent:kernel:test', '--tests', '*Auto*', ':agent:host:cleanTest', ':agent:host:test', '--tests', '*LoopbackNetworkProxyTest*']
if a.full_check:
    baseline = [f':agent:{m}:cleanTest' for m in modules] + [':agent:workflow:cleanTestDebugUnitTest', ':app:cleanTestDebugUnitTest'] + [f':agent:{m}:check' for m in modules] + [':agent:workflow:check', ':app:testDebugUnitTest', ':app:assembleDebug']
(out / 'baseline-command.json').write_text(json.dumps(prefix + baseline, indent=2), encoding='utf-8')
with (out / 'restored-baseline.log').open('w', encoding='utf-8') as log:
    proc = subprocess.run(prefix + baseline, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
(out / 'restored-baseline.exit').write_text(f'GRADLE_EXIT={proc.returncode}\n', encoding='utf-8')
for module in ['app'] + ['agent/' + m for m in modules] + ['agent/workflow']:
    for report in (root / module / 'build/test-results').glob('**/TEST-*.xml'):
        dest = out / 'restored-baseline-xml' / module / report.parent.name
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copy2(report, dest / report.name)
summary = dict(mutations=results, restored_baseline_gradle_exit=proc.returncode)
(out / 'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False), flush=True)
raise SystemExit(proc.returncode)
