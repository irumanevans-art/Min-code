"""Compare real release APKs and verify bundled native ABIs and upstream notices."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument('--evidence', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets/licenses'
apk_dir = root / 'app/build/outputs/apk/release'
notices = ['QuickJS-MIT.txt', 'quickjs-kt-Apache-2.0.txt', 'quickjs-SOURCES.txt']
evidence = args.evidence.resolve()
results = []

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

for abi in ['universal', 'arm64-v8a', 'x86_64']:
    name = f'app-{abi}-release-unsigned.apk'
    before, after = evidence / name, apk_dir / name
    delta = after.stat().st_size - before.stat().st_size
    assert delta <= 5_000_000, (abi, delta)
    with ZipFile(after) as apk:
        native_abis = sorted({p.split('/')[1] for p in apk.namelist() if p.startswith('lib/')})
        expected = ['arm64-v8a', 'x86_64'] if abi == 'universal' else [abi]
        assert native_abis == expected, native_abis
        quickjs = sorted(p for p in apk.namelist() if p.endswith('/libquickjs.so'))
        assert quickjs == [f'lib/{a}/libquickjs.so' for a in expected], quickjs
        for notice in notices:
            assert apk.read(f'assets/licenses/{notice}') == (assets / notice).read_bytes(), notice
    shutil.copy2(after, evidence / ('final-' + name))
    results.append(dict(abi=abi, before_bytes=before.stat().st_size, after_bytes=after.stat().st_size,
        delta_bytes=delta, delta_mib=delta / 1024**2, before_sha256=sha(before), after_sha256=sha(after),
        native_abis=native_abis, quickjs_libraries=quickjs, bundled_notices_byte_identical=True))

# 除了仓库副本，也逐字比对固定上游下载，防止两处一起被错误修改。
assert (assets / notices[0]).read_bytes() == (evidence / 'QuickJS-MIT.txt').read_bytes()
assert (assets / notices[1]).read_bytes() == (evidence / 'Apache-2.0.txt').read_bytes()
with ZipFile(evidence / 'quickjs-kt-android-1.0.15.aar') as aar:
    aar_notices = [p for p in aar.namelist() if any(n in p.lower() for n in ['license', 'notice', 'copyright'])]
    aar_sources = [p for p in aar.namelist() if p.endswith(('.c', '.h'))]
result = dict(apks=results, aar_notice_entries=aar_notices, aar_native_source_entries=aar_sources,
    aar_sha256=sha(evidence / 'quickjs-kt-android-1.0.15.aar'),
    notice_sha256={n: sha(assets / n) for n in notices},
    gate='PASS: APK delta <= 5,000,000 bytes; notices preserved and source explicitly attributed')
(evidence / 'release-audit.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
