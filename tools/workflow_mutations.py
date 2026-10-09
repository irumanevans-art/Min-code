"""Compile each weakening, require its named assertion to fail, restore bytes, then rerun baseline."""
from pathlib import Path
import argparse
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--init-script')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
out = root / 'build/workflow-evidence/mutations'
out.mkdir(parents=True, exist_ok=True)
prefix = [str(root / 'gradlew.bat')]
if args.init_script:
    prefix += ['-I', str(Path(args.init_script).resolve())]

cases = [
    ('determinism', 'agent/workflow/src/main/kotlin/dev/min/agent/workflow/QuickJsWorkflowEngine.kt',
     'js.evaluate<Unit>(SANDBOX)', 'js.evaluate<Unit>("void 0")',
     ':agent:workflow:cleanTestDebugUnitTest', ':agent:workflow:testDebugUnitTest',
     'dev.min.agent.workflow.QuickJsWorkflowEngineTest.deterministicApisCannotBeRecoveredThroughConstructors',
     'agent/workflow/build/test-results/testDebugUnitTest'),
    ('replay', 'agent/kernel/src/main/kotlin/dev/min/agent/kernel/WorkflowRun.kt',
     'val cached = completed[index]?.takeIf', 'val cached = completed[index]?.takeIf { false }?.takeIf',
     ':agent:kernel:cleanTest', ':agent:kernel:test',
     'dev.min.agent.kernel.WorkflowRunTest.durableCompletedNodesReplayAndInFlightNodesRunAgain',
     'agent/kernel/build/test-results/test'),
    ('concurrency', 'agent/kernel/src/main/kotlin/dev/min/agent/kernel/WorkflowRun.kt',
     'private val gate = Semaphore(run.concurrency)', 'private val gate = Semaphore(256)',
     ':agent:kernel:cleanTest', ':agent:kernel:test',
     'dev.min.agent.kernel.WorkflowRunTest.fourAndSixteenAreActualConcurrentNodeCaps',
     'agent/kernel/build/test-results/test'),
]
results = []
for name, file, old, new, clean, task, test, xml in cases:
    path = root / file
    original = path.read_bytes()
    assert original.count(old.encode()) == 1, name
    dest = out / name
    dest.mkdir(exist_ok=True)
    try:
        path.write_bytes(original.replace(old.encode(), new.encode()))
        with (dest / 'gradle.log').open('w', encoding='utf-8') as log:
            proc = subprocess.run(prefix + [clean, task, '--tests', test, '--console=plain'], cwd=root, stdout=log, stderr=subprocess.STDOUT)
        failures = []
        for report in (root / xml).glob('TEST-*.xml'):
            shutil.copy2(report, dest / report.name)
            for case in ET.parse(report).getroot().findall('testcase'):
                full = case.get('classname') + '.' + case.get('name')
                if full == test and case.find('failure') is not None:
                    failures.append(full)
        killed = proc.returncode != 0 and bool(failures)
        results.append(dict(mutation=name, gradle_exit=proc.returncode, status='KILLED' if killed else 'INVALID_OR_SURVIVED', failures=failures))
        print(json.dumps(results[-1]), flush=True)
    finally:
        path.write_bytes(original)
        assert path.read_bytes() == original

with (out / 'restored-baseline.log').open('w', encoding='utf-8') as log:
    baseline = subprocess.run(prefix + [':agent:workflow:cleanTestDebugUnitTest', ':agent:kernel:cleanTest',
        ':agent:workflow:testDebugUnitTest', ':agent:kernel:test', '--tests', '*Workflow*Test', '--console=plain'],
        cwd=root, stdout=log, stderr=subprocess.STDOUT)
summary = dict(mutations=results, restored_baseline_gradle_exit=baseline.returncode)
(out / 'results.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(json.dumps(summary), flush=True)
raise SystemExit(0 if baseline.returncode == 0 and all(x['status'] == 'KILLED' for x in results) else 1)
