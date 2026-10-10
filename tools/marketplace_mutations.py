"""逐项削弱真实实现；只统计正常编译且由需求断言检出的变异，结束恢复原始字节。"""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--out', required=True)
parser.add_argument('--resume', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
out = Path(args.out).resolve()
out.mkdir(parents=True, exist_ok=True)
host = 'agent/host/src/main/kotlin/dev/min/agent/host/FilePluginSources.kt'
market = 'agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginMarketplace.kt'
bundle = 'agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'
handback = 'agent/kernel/src/main/kotlin/dev/min/agent/kernel/SubagentHandbackTool.kt'
gate = 'agent/kernel/src/main/kotlin/dev/min/agent/kernel/AutoActionGate.kt'
cases = [
    ('archive-traversal', host, [("path.split('/').none { it == \"..\" || it.isEmpty() }", 'true'), ('require(target.startsWith(root))', 'require(true)')], 'host', 'FilePluginSourcesTest.archiveTraversalIsRejectedBeforeWritingOutside'),
    ('archive-symlink', host, [('require(mode and 0xf000 != 0xa000)', 'require(true)'), ('require(mode and 0xf000 in setOf(0, 0x8000, 0x4000))', 'require(true)')], 'host', 'FilePluginSourcesTest.archiveUnixSymlinkIsRejectedBeforeExtraction'),
    ('archive-size', host, [('require(u32(pos + 24) <= limits.expandedBytes)', 'require(true)'), ('require(total <= limits.expandedBytes)', 'require(true)')], 'host', 'FilePluginSourcesTest.expandedSizeLimitBlocksCompressedBomb'),
    ('third-party-add-confirm', market, [('require(source == OFFICIAL_MARKETPLACE_SOURCE || confirmed)', 'require(true)')], 'cli', 'PluginMarketplaceTest.unconfirmedThirdPartyCannotAddOrInstall'),
    ('third-party-install-confirm', market, [('require(result.source == OFFICIAL_MARKETPLACE_SOURCE && name == OFFICIAL_MARKETPLACE || result.confirmed)', 'require(true)')], 'cli', 'PluginMarketplaceTest.unconfirmedThirdPartyCannotAddOrInstall'),
    ('managed-allowlist', market, [('require(strict == null || (strict as JsonArray).any { (it as? JsonObject)?.let { rule -> matches(rule, source) } == true })', 'require(true)')], 'cli', 'PluginMarketplaceTest.managedAllowlistCheckedBeforeFetchAndBeforeInstall'),
    ('untrusted-project-settings', market, [('if (layer.source in setOf(ConfigSource.Project, ConfigSource.Local) && !trusted()) continue', 'if (false) continue')], 'cli', 'PluginMarketplaceTest.untrustedProjectSettingsCannotAddFetchOrInstall'),
    ('untrusted-project-install', market, [('require(scope == "user" || trusted())', 'require(true)')], 'cli', 'PluginMarketplaceTest.untrustedProjectSettingsCannotAddFetchOrInstall'),
    ('untrusted-project-enable-merge', bundle, [('val effectiveLayers = settings.layers.filter { it.source !in setOf(ConfigSource.Project, ConfigSource.Local) || trust(cwd) }', 'val effectiveLayers = settings.layers')], 'cli', 'PluginMarketplaceTest.untrustedProjectCannotOverridePersonalPluginEnablement'),
    ('install-preview-unchanged', market, [('require(fingerprint(preview.staging, preview.entry) == preview.fingerprint)', 'require(true)'), ('require(fingerprint(target, preview.entry) == preview.fingerprint)', 'require(true)')], 'cli', 'PluginMarketplaceTest.confirmationFreezesPreviewAndCancellationPropagates'),
    ('handback-warning', handback, [('"warning" -> REPORT_SECURITY_WARNING + "\\n\\n"', '"warning" -> ""')], 'kernel', 'AutoHandbackTest.rejectedHandbackDeliversOriginalWithWarningWithoutRewrite'),
    ('handback-unavailable-note', handback, [('"unavailable" -> REPORT_VERIFY_NOTE + "\\n\\n"', '"unavailable" -> ""')], 'kernel', 'AutoHandbackTest.unavailableAndSafetyRefusedReviewsDeliverWithoutApproval'),
    ('fallback-delivery', handback, [('commit(listOf(reportReady(turnId, body, verdict)), Durability.Sync)', 'commit(listOf(reportReady(turnId, "WITHHELD", verdict)), Durability.Sync)')], 'kernel', 'AutoHandbackTest.fallbackReviewsLastMessageAndDeliversFlaggedTextWithWarning'),
    ('warning-card', 'app/src/main/java/dev/min/code/core/engine/min/MinItems.kt', [('"warning" -> text.warningReport() + "\\n\\n" + report.body', '"warning" -> report.body')], 'app', 'MinAgentProjectionTest.handbackReportUsesExistingCardWithReviewLabelAndOriginalBody'),
]
prefix = ['cmd', '/c', str(root / 'gradlew.bat'), '--no-daemon', '--max-workers=1', '--console=plain', '-I', 'D:/kimi/workspace/research/tasks/workflow-evidence/maven-init.gradle.kts']
env = dict(os.environ)
env['PATH'] = 'C:/Program Files/Git/bin;C:/Program Files/Git/mingw64/bin;' + env['PATH']
results = json.loads((out / 'results.json').read_text()) if args.resume and (out / 'results.json').exists() else []
for name, file, replacements, module, short in cases:
    if any(r['name'] == name and r['status'] == 'KILLED' for r in results):
        continue
    source = root / file
    original = source.read_bytes()
    changed = original
    for before, after in replacements:
        old, new = before.encode(), after.encode()
        assert changed.count(old) == 1, (name, before, changed.count(old))
        changed = changed.replace(old, new)
    dest = out / name
    dest.mkdir(exist_ok=True)
    target = ':app' if module == 'app' else ':agent:' + module
    task = 'testDebugUnitTest' if module == 'app' else 'test'
    report_dir = root / ('app' if module == 'app' else 'agent/' + module) / 'build/test-results' / task
    test = ('dev.min.code.core.engine.min.' if module == 'app' else f'dev.min.agent.{module}.') + short
    try:
        source.write_bytes(changed)
        command = prefix + [target + ':clean' + task[0].upper() + task[1:], target + ':' + task, '--tests', test]
        (dest / 'command.json').write_text(json.dumps(command), encoding='utf-8')
        with (dest / 'gradle.log').open('w', encoding='utf-8') as log:
            proc = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
        text = (dest / 'gradle.log').read_text(encoding='utf-8', errors='replace')
        compiled = not any(fragment in text for fragment in ['Compilation error', 'compileKotlin FAILED', 'compileTestKotlin FAILED', 'compileDebugKotlin FAILED', 'compileDebugUnitTestKotlin FAILED'])
        failures = []
        for xml in report_dir.glob('TEST-*.xml'):
            shutil.copy2(xml, dest / xml.name)
            for case in ET.parse(xml).getroot().findall('testcase'):
                fail = case.find('failure')
                if case.get('classname') + '.' + case.get('name') == test and fail is not None:
                    failures.append(dict(type=fail.get('type'), message=fail.get('message')))
        assertion = any('Assertion' in (f['type'] or '') or 'ComparisonFailure' in (f['type'] or '') for f in failures)
        result = dict(name=name, gradle_exit=proc.returncode, compiled=compiled, status='KILLED' if compiled and proc.returncode != 0 and assertion else 'INVALID_OR_SURVIVED', failures=failures)
        results.append(result)
        (dest / 'exit.txt').write_text(f'GRADLE_EXIT={proc.returncode}\n', encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        source.write_bytes(original)
        assert source.read_bytes() == original
        (out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    if result['status'] != 'KILLED':
        raise SystemExit(1)
print(f'KILLED {len(cases)}/{len(cases)}; source restored', flush=True)
