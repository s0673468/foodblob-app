#!/usr/bin/env python3
"""Manual hosted FoodBlobAcceptance on a caller-owned zero-count simulator."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import signal
import shlex
import subprocess
import sys
import time
import uuid
from validate_apple_acceptance import EXPECTED_IDENTIFIERS, validate

ROOT = Path(__file__).resolve().parents[1]


def validate_compiler_log(log):
    """WMO plans one frontend; -jN may bound dependency planning on older Xcode."""
    invocations = []
    for line in log.splitlines():
        if not line.strip().startswith("builtin-SwiftDriver -- "):
            continue
        args = shlex.split(line)
        threads = [args[i + 1] for i, arg in enumerate(args[:-1]) if arg == "-num-threads"]
        if "-whole-module-optimization" not in args or not threads or any(n != "1" for n in threads):
            raise ValueError("Require single-threaded whole-module Swift compilation")
        if "-enable-batch-mode" in args or "-Onone" not in args:
            raise ValueError("Require non-batch Debug compilation")
        invocations.append({"module": args[args.index("-module-name") + 1],
                            "planning_jobs": [arg for arg in args if arg.startswith("-j")],
                            "frontend_threads": threads})
    if not invocations:
        raise ValueError("No emitted Swift driver proof")
    return invocations


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true", "Only a disposable hosted job may run this fixture"
    out = Path(os.environ["ACCEPTANCE_OUTPUT"]); out.mkdir(parents=True, exist_ok=True)
    work = Path(os.environ["RUNNER_TEMP"]) / "foodblob-apple-acceptance"; work.mkdir(exist_ok=True)
    derived = work / "DerivedData"; bundle = None; udid = None; captures = []
    receipt = {"expected_cases": 8, "runner_flag": "FOODBLOB_JELLY_ACCEPTANCE=1 in FoodBlobAcceptance",
               "fixture": "new owned simulator, completed onboarding, zero counts/default paint",
               "performance_scope": "Correctness assertions on a hosted simulator; no representative hardware performance claim"}
    start = time.monotonic()
    def interrupted(signum, frame):
        raise RuntimeError(f"Hosted job interrupted by signal {signum}")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    def execute(command, log_path, timeout):
        with log_path.open("w") as log:
            process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            begin = time.monotonic()
            seen = {process.pid}; peak = 0
            try:
                while process.poll() is None:
                    if log_path.name == "build.log":
                        inventory = subprocess.check_output(["ps", "-axo", "pid=,ppid=,comm="], text=True)
                        rows = {int(p): (int(pp), name) for p, pp, name in (line.split(None, 2) for line in inventory.splitlines())}
                        while True:
                            children = {p for p, (pp, _) in rows.items() if pp in seen}
                            if children <= seen: break
                            seen |= children
                        workers = sum(name.endswith("/swift-frontend") for p, (_, name) in rows.items() if p in seen)
                        peak = max(peak, workers)
                        receipt["observed_max_owned_swift_frontends"] = peak
                        if workers > 1: raise RuntimeError("Owned Swift frontend worker bound exceeded")
                    if time.monotonic() - begin > timeout: raise TimeoutError("Owned xcodebuild phase timed out")
                    time.sleep(.25)
                return process.wait()
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try: process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL); process.wait()

    def run(args, timeout=120, check=True):
        r = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, timeout=timeout)
        if check and r.returncode: raise RuntimeError(f"{args}: {r.stdout}{r.stderr}")
        return r
    def sim(*args, **kwargs): return run(["xcrun", "simctl", *args], **kwargs)
    def products_hashes():
        products = derived / "Build/Products"
        return {str(p.relative_to(products)): hashlib.sha256(p.read_bytes()).hexdigest() for p in products.rglob('*') if p.is_file()}
    common = ["-project", "FoodBlob.xcodeproj", "-scheme", "FoodBlobAcceptance", "-configuration", "Debug", "-derivedDataPath", str(derived), "-jobs", "1", "-parallel-testing-enabled", "NO", "CODE_SIGNING_ALLOWED=YES", "CODE_SIGN_IDENTITY=-", "CODE_SIGNING_REQUIRED=YES"]
    try:
        receipt.update(source_sha=run(["git", "rev-parse", "HEAD"]).stdout.strip(), xcode=run(["xcodebuild", "-version"]).stdout,
                       metal_component=json.loads(run(["xcodebuild", "-showComponent", "MetalToolchain", "-json"]).stdout))
        command = ["xcodebuild", "build-for-testing", *common, "-destination", "generic/platform=iOS Simulator", "SWIFT_COMPILATION_MODE=wholemodule", "SWIFT_USE_PARALLEL_WHOLE_MODULE_OPTIMIZATION=NO", "SWIFT_USE_PARALLEL_WMO_TARGETS=NO", "SWIFT_ENABLE_BATCH_MODE=NO", "SWIFT_OPTIMIZATION_LEVEL=-Onone", "OTHER_SWIFT_FLAGS=$(inherited) -j1 -num-threads 1"]
        receipt["build_command"] = command
        build_code = execute(command, out / "build.log", 900)
        receipt["build_exit_code"] = build_code
        assert build_code == 0, f"Build failed: {build_code}"
        receipt["swift_driver_invocations"] = validate_compiler_log((out / "build.log").read_text())
        receipt["compiled_product_sha256"] = products_hashes()
        app = derived / "Build/Products/Debug-iphonesimulator/Food Blob.app"
        widget = app / "PlugIns/FoodBlobWidgets.appex"; assert widget.is_dir()
        # Xcode embeds simulator entitlement sections, while its default ad-hoc
        # signature is empty. Sign only these task products with public entitlements.
        for target, entitlements in [(widget, ROOT / "FoodBlobWidgets/FoodBlobWidgets.entitlements"), (app, ROOT / "FoodBlob/FoodBlob.entitlements")]:
            run(["codesign", "--force", "--sign", "-", "--entitlements", str(entitlements), str(target)])
            run(["codesign", "--verify", "--deep", "--strict", str(target)])
            r = subprocess.run(["codesign", "-d", "--entitlements", ":-", str(target)], capture_output=True, check=True)
            assert "group.org.example.foodblob" in plistlib.loads(r.stdout)["com.apple.security.application-groups"]
            (out / (target.suffix[1:] + "-entitlements.plist")).write_bytes(r.stdout)
        receipt["ready_product_sha256"] = products_hashes()
        runtimes = json.loads(sim("list", "runtimes", "--json").stdout)["runtimes"]
        available = [r for r in runtimes if r.get("isAvailable") and '.iOS-' in r['identifier']]
        runtime = max(available, key=lambda r: tuple(map(int, r['version'].split('.'))))
        kind = "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
        udid = sim("create", "FoodBlobAcceptance-" + uuid.uuid4().hex[:10], kind, runtime['identifier']).stdout.strip(); uuid.UUID(udid)
        receipt["device"] = {"udid": udid, "type": kind, "runtime": runtime}
        (out / "owned-simulator.json").write_text(json.dumps(receipt["device"]))
        focused = {identifier for identifier in EXPECTED_IDENTIFIERS if any(name in identifier for name in (
            "testCancelledLensPressAndSettingsLinksPreserveCounts",
            "testPaintJellySliderPersistsWithoutChangingFoodAndBothWorldsStayPlayable"))}
        receipt["phases"] = {}
        for phase, expected in [("focused", focused), ("full", EXPECTED_IDENTIFIERS)]:
            if phase == "full":
                # Erase only our owned simulator: the complete suite starts from
                # a new zero-count/default-material fixture, independent of focus.
                sim("shutdown", udid); sim("erase", udid)
            sim("boot", udid); sim("bootstatus", udid, "-b", timeout=180); sim("install", udid, str(app))
            container = Path(sim("get_app_container", udid, "org.example.foodblob", "data").stdout.strip())
            prefs = container / "Library/Preferences/org.example.foodblob.plist"; prefs.parent.mkdir(parents=True, exist_ok=True)
            prefs.write_bytes(plistlib.dumps({"has_completed_onboarding": True}, fmt=plistlib.FMT_BINARY))
            group = Path(sim("get_app_container", udid, "org.example.foodblob", "group.org.example.foodblob").stdout.strip()); assert group.is_dir()
            receipt["app_group_container_available"] = True
            sim("launch", udid, "org.example.foodblob"); time.sleep(3)
            sim("io", udid, "screenshot", str(out / (phase + "-synthetic-baseline.png")))
            sim("terminate", udid, "org.example.foodblob")
            bundle = work / (phase + ".xcresult")
            command = ["xcodebuild", "test-without-building", *common, "-destination", f"platform=iOS Simulator,id={udid}", "-maximum-concurrent-test-simulator-destinations", "1", "-resultBundlePath", str(bundle)]
            if phase == "focused":
                command += ["-only-testing:FoodBlobUITests/" + identifier.removesuffix("()") for identifier in sorted(expected)]
            phase_receipt = {"expected_identifiers": sorted(expected), "command": command, "fresh_fixture": True,
                             "app_group_container_available": True}
            receipt["phases"][phase] = phase_receipt
            test_code = execute(command, out / (phase + "-tests.log"), 650 if phase == "focused" else 1500)
            phase_receipt["xcode_test_exit_code"] = test_code
            summary = json.loads(run(["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(bundle), "--compact"]).stdout)
            (out / (phase + "-summary.json")).write_text(json.dumps(summary, indent=2))
            tests = json.loads(run(["xcrun", "xcresulttool", "get", "test-results", "tests", "--path", str(bundle), "--compact"]).stdout)
            (out / (phase + "-tests.json")).write_text(json.dumps(tests, indent=2))
            attachments = work / (phase + "-attachments")
            exported = run(["xcrun", "xcresulttool", "export", "attachments", "--path", str(bundle), "--output-path", str(attachments)], timeout=120, check=False)
            phase_receipt["attachment_export_exit_code"] = exported.returncode
            manifest_entries = []
            for manifest in attachments.rglob('*.json'):
                data = json.loads(manifest.read_text())
                (out / (phase + "-" + manifest.name)).write_text(json.dumps(data, indent=2))
                if isinstance(data, list): manifest_entries += data
            described = {}
            for entry in manifest_entries:
                for attachment in entry.get("attachments", []):
                    described[attachment["exportedFileName"]] = {"testIdentifier": entry.get("testIdentifier"), **attachment}
            for filename, metadata in described.items():
                if "cancelled-lens-geometry" in metadata.get("suggestedHumanReadableName", ""):
                    candidates = list(attachments.rglob(filename))
                    if len(candidates) == 1 and candidates[0].stat().st_size < 32 * 1024:
                        shutil.copy2(candidates[0], out / (phase + "-cancelled-lens-geometry.txt"))
            pngs = sorted(attachments.rglob('*.png')); phase_receipt["attachment_png_count"] = len(pngs)
            for png in pngs:
                metadata = described.get(png.name, {})
                name = metadata.get("suggestedHumanReadableName", "")
                priority = 2 if metadata.get("isAssociatedWithFailure") else int(any(n in name for n in ("cancelled-lens", "material-request", "paint-default")))
                captures.append((priority, phase, png, metadata))
            selected = sorted(captures, key=lambda c: (-c[0], c[1], str(c[2])))[:6]
            capture_manifest = []
            for index, (priority, capture_phase, png, metadata) in enumerate(selected):
                filename = f"interaction-{index}.png"; shutil.copy2(png, out / filename)
                capture_manifest.append({"file": filename, "phase": capture_phase, "priority": priority, **metadata})
            (out / "selected-screenshots.json").write_text(json.dumps(capture_manifest, indent=2))
            phase_receipt["result"] = validate(summary, tests, expected)
            assert test_code == 0, f"Apple {phase} UI execution failed: {test_code}"
        receipt["result"] = receipt["phases"]["full"]["result"]
        receipt["exit_code"] = 0
    except BaseException as exc:
        receipt.update(exit_code=1, error=str(exc)); print(str(exc), file=sys.stderr)
    finally:
        if udid:
            for action in ("shutdown", "delete"):
                try: receipt["owned_simulator_" + action] = sim(action, udid, timeout=60, check=False).returncode == 0
                except Exception as exc: receipt["owned_simulator_" + action + "_error"] = str(exc)
            if not receipt.get("owned_simulator_delete"):
                receipt["exit_code"] = 1
        receipt["elapsed_seconds"] = time.monotonic() - start
        try:
            receipt["devices_after"] = json.loads(sim("list", "devices", "--json", check=False).stdout)
            if udid: assert all(d["udid"] != udid for ds in receipt["devices_after"]["devices"].values() for d in ds)
        except Exception as exc: receipt.update(exit_code=1, cleanup_readback_error=str(exc))
        if bundle and bundle.is_dir():
            size = sum(p.stat().st_size for p in bundle.rglob('*') if p.is_file())
            receipt["xcresult_bytes"] = size
            if size < 16 * 1024**2: shutil.make_archive(str(out / "tests-xcresult"), 'zip', bundle.parent, bundle.name)
            else: receipt["xcresult_archive_omitted"] = "Raw bundle exceeds16MiB; retain exact xcresult summary/test tree and representative UI attachments"
        (out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt), flush=True)
    return receipt["exit_code"]


if __name__ == "__main__": sys.exit(main())
