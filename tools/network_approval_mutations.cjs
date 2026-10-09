const fs = require('fs');
const cp = require('child_process');
const path = require('path');
const root = path.resolve(__dirname, '..');
const out = process.argv[2] || 'D:/kimi/workspace/research/tasks/netapr-evidence/mutations';
fs.mkdirSync(out, {recursive:true});
const rules = 'agent/ext/src/main/kotlin/dev/min/agent/ext/config/NetworkSettings.kt';
const proxy = 'agent/host/src/main/kotlin/dev/min/agent/host/LoopbackNetworkProxy.kt';
const kernel = 'agent/kernel/src/main/kotlin/dev/min/agent/kernel/NetworkSandbox.kt';
const editor = 'agent/ext/src/main/kotlin/dev/min/agent/ext/config/NetworkSettingsEditor.kt';
const executor = 'agent/kernel/src/main/kotlin/dev/min/agent/kernel/ToolExecutor.kt';
const cases = [
 ['deny-priority', rules, 'if (settings.deniedDomains.any { matches(it, target) })', 'if (false && settings.deniedDomains.any { matches(it, target) })', 'ext', 'NetworkRulesTest.denyWinsOverAllowAndBypassAndCommand'],
 ['wildcard-boundary', rules, 'actual.endsWith(host.drop(1))', 'actual.endsWith(host.drop(2))', 'ext', 'NetworkRulesTest.exactWildcardAndPort'],
 ['managed-lock', rules, 'if (settings.allowManagedDomainsOnly)', 'if (false && settings.allowManagedDomainsOnly)', 'ext', 'NetworkRulesTest.managedAndStrictLocksCannotBeWidenedByModeOrCommand'],
 ['human-decision-log', kernel, '    commitIf(listOf(NetworkDecision(pending.callId', '    return@withLock true\n    commitIf(listOf(NetworkDecision(pending.callId', 'kernel', 'NetworkSandboxTest.shellInjectsProxyAndHumanDecisionIsLoggedAndReplayed'],
 ['proxy-credentials', proxy, 'if (head.values("proxy-authorization") != listOf(credential))', 'if (false && head.values("proxy-authorization") != listOf(credential))', 'host', 'LoopbackNetworkProxyTest.missingAndWrongCredentialsCannotTriggerApproval'],
 ['proxy-timeout-allow', proxy, '?: NetworkVerdict(NetworkOutcome.Deny, NetworkSource.Timeout, "网络批准超时")', '?: NetworkVerdict(NetworkOutcome.Allow, NetworkSource.Timeout, "网络批准超时")', 'host', 'LoopbackNetworkProxyTest.timeoutMustDenyWithoutConnecting'],
 ['kernel-timeout-allow', kernel, 'NetworkOutcome.Deny, NetworkSource.Timeout, "网络批准超时，连接已拒绝"', 'NetworkOutcome.Allow, NetworkSource.Timeout, "网络批准超时，连接已拒绝"', 'kernel', 'NetworkSandboxTest.timeoutIsDeniedLoggedAndLateAnswerIsStale'],
 ['auto-skips-classifier', executor, 'rt.state.value.networkSettings.enabled && (p.input', 'false && rt.state.value.networkSettings.enabled && (p.input', 'kernel', 'NetworkSandboxTest.autoDomainsForceClassifierEvenWithBashAllowRule'],
 ['managed-merges-user', rules, 'if (managedOnly) it.source == ConfigSource.Managed else !adminRequired', 'if (managedOnly) true else !adminRequired', 'ext', 'NetworkRulesTest.actualSettingsMergeManagedTrustWebFetchAndCustomPorts'],
 ['revoke-domain-noop', editor, 'domains.filterNot { (it as? JsonPrimitive)?.content == entry.domain }', 'domains.filterNot { false && (it as? JsonPrimitive)?.content == entry.domain }', 'ext', 'NetworkSettingsEditorTest.allowsRemovalAndWebFetchRevocationWithoutLosingOtherSettings'],
 ['revoke-webfetch-noop', editor, 'rules.filterNot { (it as? JsonPrimitive)?.content == entry.permissionRule }', 'rules.filterNot { false && (it as? JsonPrimitive)?.content == entry.permissionRule }', 'ext', 'NetworkSettingsEditorTest.allowsRemovalAndWebFetchRevocationWithoutLosingOtherSettings'],
 ['effective-switch', editor, 'edit(fs, target) { raw ->', 'edit(fs, GuestPath("${home.value}/.claude/settings.json")) { raw ->', 'ext', 'NetworkSettingsEditorTest.enabledSwitchEditsTheEffectiveTrustedLayer'],
 ['managed-toggle', editor, 'check(!view(fs, cwd, home, trust).settings.enabledManaged)', 'check(true || !view(fs, cwd, home).settings.enabledManaged)', 'ext', 'NetworkSettingsEditorTest.managedValuesRemainReadOnly'],
];
const originals = new Map(cases.map(c => [c[1], fs.readFileSync(path.join(root,c[1]))]));
const results=[];
function run(name,module,test) {
 const dir=path.join(out,name); fs.mkdirSync(dir,{recursive:true});
 const args=[`:agent:${module}:cleanTest`,`:agent:${module}:test`,'--tests',`dev.min.agent.${module === 'ext'?'ext.config':module}.${test}`,'--no-daemon'];
 fs.writeFileSync(path.join(dir,'command.json'),JSON.stringify(args));
 const fd=fs.openSync(path.join(dir,'gradle.log'),'w');
 let r;
 try { r=cp.spawnSync('cmd.exe',['/d','/c',path.join(root,'gradlew.bat'),...args],{cwd:root,stdio:['ignore',fd,fd],env:{...process.env,PATH:'C:\\Program Files\\Git\\bin;C:\\Program Files\\Git\\mingw64\\bin;'+process.env.PATH},timeout:300000}); }
 finally {fs.closeSync(fd)}
 fs.writeFileSync(path.join(dir,'exit.txt'),String(r.status));
 const xmlDir=path.join(root,`agent/${module}/build/test-results/test`);
 let assertions=0,failures=0;
 if(fs.existsSync(xmlDir)) for(const filename of fs.readdirSync(xmlDir).filter(x=>x.endsWith('.xml'))) {
  const xml=fs.readFileSync(path.join(xmlDir,filename),'utf8');
  fs.copyFileSync(path.join(xmlDir,filename),path.join(dir,filename));
  for(const m of xml.matchAll(/<failure\b[^>]*\btype="([^"]+)"/g)){failures++; if(m[1].includes('AssertionError')||m[1].includes('ComparisonFailure'))assertions++}
 }
 const log=fs.readFileSync(path.join(dir,'gradle.log'),'utf8');
 const compiled=!/compile(?:Test)?Kotlin FAILED|Compilation error/.test(log)&&(/tests? completed/.test(log) || /BUILD SUCCESSFUL/.test(log));
 return {name,exit:r.status,compiled,failures,assertions,killed:r.status!==0&&compiled&&assertions>0};
}
try {
 for(const [name,file,from,to,module,test] of cases.filter(c => !process.argv[3] || process.argv[3].split(",").includes(c[0]))) {
  const orig=originals.get(file).toString();
  if(!orig.includes(from))throw Error(`Missing mutation site ${name}`);
  fs.writeFileSync(path.join(root,file),orig.replace(from,to));
  try {const r=run(name,module,test);results.push(r);console.log(JSON.stringify(r));fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2));}
  finally {fs.writeFileSync(path.join(root,file),originals.get(file))}
 }
} finally {for(const [file,bytes]of originals)fs.writeFileSync(path.join(root,file),bytes)}
fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify({total:results.length,killed:results.filter(r=>r.killed).length,restored:[...originals].every(([f,b])=>fs.readFileSync(path.join(root,f)).equals(b))},null,2));
process.exitCode=results.every(r=>r.killed)?0:1;
