"""逐项撤去门禁，编译成功且对应需求断言失败才算检出；恢复原始字节后复跑基线。"""
from pathlib import Path
import argparse
import fnmatch
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument("--out", required=True)
p.add_argument("--init-script", required=True)
p.add_argument("--resume", action="store_true")
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
out = Path(a.out).resolve()
out.mkdir(parents=True, exist_ok=True)
cases = json.loads((root / "tools/auto_plugin_r2_mutations.json").read_text(encoding="utf-8"))
env = dict(os.environ)
env["JAVA_HOME"] = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
env["PATH"] = r"C:\Program Files\Git\bin;" + env["PATH"]
prefix = [str(root / "gradlew.bat"), "-I", a.init_script, "--console=plain", "--max-workers=1"]
results_path = out / "results.json"
results = json.loads(results_path.read_text()) if a.resume and results_path.exists() else []
if a.resume:
    # Gradle 接受类名和方法名两种过滤；保存的 XML 必须按同样两种形式核对，避免漏计类级运行。
    by_name = {c["name"]: c for c in cases}
    for result in results:
        test = "*" + by_name[result["mutation"]]["test"]
        failures = []
        for report in (out / result["mutation"]).glob("TEST-*.xml"):
            for t in ET.parse(report).getroot().findall("testcase"):
                f = t.find("failure")
                fullname = t.get("classname") + "." + t.get("name")
                if f is not None and (fnmatch.fnmatchcase(fullname, test) or fnmatch.fnmatchcase(t.get("classname"), test)):
                    failures.append(dict(test=fullname, type=f.get("type"), message=f.get("message")))
        assertion = any("Assertion" in (f["type"] or "") or "ComparisonFailure" in (f["type"] or "") for f in failures)
        result["failures"] = failures
        result["status"] = "KILLED" if result["compiled"] and result["gradle_exit"] != 0 and assertion else "INVALID_OR_SURVIVED"
    results_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
for case in cases:
    name = case["name"]
    if any(r["mutation"] == name and r["status"] == "KILLED" for r in results):
        continue
    path = root / case["file"]
    original = path.read_bytes()
    old = case["old"].encode()
    assert original.count(old) > 0, (name, "missing mutation target", case["old"])
    changed = original.replace(old, case["replacement"].encode())
    dest = out / name
    dest.mkdir(exist_ok=True)
    target = ":agent:" + case["module"]
    test = "*" + case["test"]
    command = prefix + [target + ":cleanTest", target + ":test", "--tests", test]
    (dest / "command.json").write_text(json.dumps(command, indent=2), encoding="utf-8")
    try:
        path.write_bytes(changed)
        with (dest / "gradle.log").open("w", encoding="utf-8") as log:
            proc = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
        logtext = (dest / "gradle.log").read_text(encoding="utf-8", errors="replace")
        compiled = "Compilation error" not in logtext and "compileKotlin FAILED" not in logtext and "compileTestKotlin FAILED" not in logtext
        xml = root / "agent" / case["module"] / "build/test-results/test"
        failures = []
        for report in xml.glob("TEST-*.xml"):
            shutil.copy2(report, dest / report.name)
            for t in ET.parse(report).getroot().findall("testcase"):
                f = t.find("failure")
                fullname = t.get("classname") + "." + t.get("name")
                if f is not None and (fnmatch.fnmatchcase(fullname, test) or fnmatch.fnmatchcase(t.get("classname"), test)):
                    failures.append(dict(test=fullname, type=f.get("type"), message=f.get("message")))
        assertion = any("Assertion" in (f["type"] or "") or "ComparisonFailure" in (f["type"] or "") for f in failures)
        result = dict(mutation=name, gradle_exit=proc.returncode, compiled=compiled,
                      status="KILLED" if proc.returncode != 0 and compiled and assertion else "INVALID_OR_SURVIVED",
                      failures=failures)
        (dest / "exit.txt").write_text(f"GRADLE_EXIT={proc.returncode}\n", encoding="utf-8")
        results = [r for r in results if r["mutation"] != name] + [result]
        print(json.dumps(result, ensure_ascii=True), flush=True)
    finally:
        path.write_bytes(original)
        assert path.read_bytes() == original
        results_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
baseline = prefix + [":agent:ext:cleanTest", ":agent:ext:test", "--tests", "*PluginSecurityFixTest", "--tests", "*AutoModeSettingsTest",
                     ":agent:policy:cleanTest", ":agent:policy:test", "--tests", "*PolicyEngineTest",
                     ":agent:kernel:cleanTest", ":agent:kernel:test", "--tests", "*SettingsSecurityFixTest", "--tests", "*AutoReviewerTest", "--tests", "*HooksH3EventsTest"]
(out / "baseline-command.json").write_text(json.dumps(baseline, indent=2), encoding="utf-8")
with (out / "restored-baseline.log").open("w", encoding="utf-8") as log:
    proc = subprocess.run(baseline, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
(out / "restored-baseline.exit").write_text(f"GRADLE_EXIT={proc.returncode}\n", encoding="utf-8")
for module in ("ext", "policy", "kernel"):
    shutil.copytree(root / "agent" / module / "build/test-results/test", out / ("baseline-" + module + "-xml"), dirs_exist_ok=True)
summary = dict(total=len(cases), killed=sum(r["status"] == "KILLED" for r in results),
               restored_baseline_gradle_exit=proc.returncode)
(out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary), flush=True)
raise SystemExit(0 if proc.returncode == 0 and summary["killed"] == summary["total"] else 1)

