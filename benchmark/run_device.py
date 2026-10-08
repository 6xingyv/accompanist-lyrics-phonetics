"""Run the fixed corpus in fresh processes; saves JSON, never touches sample app state."""
from pathlib import Path
import argparse, hashlib, json, subprocess
ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--output", type=Path, default=ROOT / "benchmark/results")
parser.add_argument("--phases", action="store_true", help="Separate fresh-process loading/header diagnostic; changes initialization order")
parser.add_argument("--apk", type=Path, default=ROOT / "device-benchmark/build/outputs/apk/debug/device-benchmark-debug.apk", help="Explicit benchmark artifact for repeatable version comparisons")
args = parser.parse_args()
package = "com.mocharealm.accompanist.phonetics.benchmark"
apk = args.apk
args.output.mkdir(parents=True, exist_ok=True)

def adb(*arguments):
    response = subprocess.run([args.adb, "-s", args.serial, *arguments], capture_output=True, encoding="utf-8", errors="replace", timeout=90)
    if response.returncode: raise RuntimeError(response.stdout + response.stderr)
    return response.stdout

print(adb("install", "-r", str(apk)), flush=True)
for locale in ("zh-CN", "zh-TW", "yue-HK", "ja-JP"):
    signatures = []
    for capacity in (0, 256):
        adb("shell", "am", "force-stop", package)
        log = adb("shell", "am", "instrument", "-w", "-r", "-e", "cache", str(capacity), "-e", "locale", locale,
                  "-e", "phases", str(args.phases).lower(),
                  f"{package}/com.mocharealm.accompanist.lyrics.phonetics.devicebenchmark.PhoneticsInstrumentation")
        if "INSTRUMENTATION_CODE: -1" not in log: raise RuntimeError(log)
        report = json.loads(adb("exec-out", "run-as", package, "cat", "files/phonetics-benchmark.json"))
        report["apkSha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        assert not report["failures"], report["failures"]
        metric = report["metrics"][0]
        signatures.append(metric["resultSignature"])
        safe_device = "".join(c if c.isalnum() else "-" for c in report["device"])
        filename = args.output / f"{safe_device}-{locale}-cache{capacity}.json"
        filename.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", "utf-8")
        print(f"{report['device']} {locale} cache={capacity}: {metric['correct']}/{metric['total']}, first={metric['firstParseNs']/1e6:.2f}ms hot={metric['hotMedianNs']/1e3:.1f}us PSS={report['memoryAfter']['pssKb']}KiB", flush=True)
    assert signatures[0] == signatures[1], f"Cache changed result: {locale}"
