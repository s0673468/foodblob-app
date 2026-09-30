#!/usr/bin/env python3
"""Manual hosted acceptance on one disposable API35 device; no build/device overlap."""
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import uuid
from validate_acceptance import android as validate

ROOT = Path(__file__).resolve().parents[1]
FLAGS = ("isolatedWidgetAcceptance", "captureSecondaryScreens", "captureThemeHighCounts",
         "capturePolishedMenus", "captureInteractionStates", "captureGrowthStates",
         "captureJellyMotion", "captureJellyPerformance")
FOCUSED = (
    "org.example.foodblob.quicklog.QuickLogActivityInstrumentedTest#confirmationActionRemainsReachableInLandscapeWithLargeText",
    "org.example.foodblob.ui.ConnectedBlobInteractionTest#capturePolishedMenus",
    "org.example.foodblob.ui.ConnectedBlobInteractionTest#heldColorDragOnlyLogsOnValidDropAndUndoRestoresIt",
    "org.example.foodblob.ui.ConnectedBlobInteractionTest#materialSliderPersistsAndPokeAndCancelledSliderNeverWriteFood",
    "org.example.foodblob.ui.ConnectedBlobInteractionTest#captureJellyTouchAndColourSequence",
    "org.example.foodblob.ui.ConnectedBlobInteractionTest#captureNativeInteractionStates",
    "org.example.foodblob.ui.FoodBlobComposeSmokeTest#historyShowsRecentDaysWithAccessibleCountsAndOpensTheSelectedDay",
    "org.example.foodblob.ui.ConnectedBlobInteractionTest#rapidAcceptedTapsPersistAndControlsStayReachable",
    "org.example.foodblob.ui.ConnectedBlobInteractionTest#accessiblePokeAndStretchNeverWriteFoodOrChangeDay",
)


def verify_selectors():
    for selector in FOCUSED:
        name, method = selector.split("#")
        source = ROOT / "android/app/src/androidTest/java" / (name.replace(".", "/") + ".kt")
        if not re.search(r"\bfun\s+" + re.escape(method) + r"\s*\(", source.read_text()):
            raise ValueError(f"Focused method missing: {selector}")


def diagnostic_counts(report):
    """Observed terminal statuses, not a substitute for the strict validator."""
    counts = dict(passed=0, failed=0, errors=0, skipped=0)
    bundle = {}; statuses = []
    for line in report.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if sep: bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try: code = int(line.partition(":")[2])
            except ValueError: code = None
            if "class" in bundle and "test" in bundle:
                statuses.append({**bundle, "status_code": code})
                key = {0: "passed", -1: "errors", -2: "failed", -3: "skipped", -4: "skipped"}.get(code)
                if key: counts[key] += 1
            bundle = {}
    return {**counts, "terminal_statuses": statuses, "scope": "raw observed status bundles; strict validation required"}


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true", "Only a disposable hosted job may run this fixture"
    out = Path(os.environ["ACCEPTANCE_OUTPUT"]); out.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ["ANDROID_HOME"])
    env = os.environ.copy(); env["ANDROID_AVD_HOME"] = str(Path(os.environ["RUNNER_TEMP"]) / "foodblob-owned-avd")
    avdhome = Path(env["ANDROID_AVD_HOME"]); avdhome.mkdir(exist_ok=True)
    avd = "FoodBlobAcceptance_" + uuid.uuid4().hex[:10]
    serial = "emulator-5582"; emulator = None; receipt = {"exit_code": 1, "flags": dict.fromkeys(FLAGS, "true"), "focused_selector": FOCUSED}
    started = time.monotonic(); phase = "preflight"; cleanup_errors = []
    def run(args, timeout=90, check=True):
        result = subprocess.run(args, env=env, cwd=ROOT, text=True, capture_output=True, timeout=timeout)
        if check and result.returncode: raise RuntimeError(f"{args}: {result.stdout}{result.stderr}")
        return result
    def adb(*args, **kwargs): return run([str(sdk / "platform-tools/adb"), "-s", serial, *args], **kwargs)
    def capture_files(label):
        base = "/sdcard/Android/data/org.example.foodblob/files"
        evidence = {"files": [], "pulled": [], "errors": [], "representatives": {}}
        try:
            result = adb("shell", "find", base, "-name", "*.png", timeout=20, check=False)
            evidence["inventory_returncode"] = result.returncode
            if result.returncode: evidence["errors"].append(result.stderr or result.stdout)
            evidence["files"] = sorted(p for p in result.stdout.splitlines() if p.startswith(base + "/") and p.endswith(".png") and ".." not in Path(p).parts)
            priorities = {"landscape": ("landscape",), "today": ("today-controls", "interaction-captures"), "calendar": ("calendar", "history"), "held": ("held", "paint-held"), "stretch": ("stretched", "stretch"), "baseline": ("empty", "rest", "before")}
            selected = []
            for kind, tags in priorities.items():
                match = next((p for p in evidence["files"] if p not in selected and any(tag in p for tag in tags)), None)
                if match: selected.append(match)
            selected += [p for p in evidence["files"] if p not in selected][:6-len(selected)]
            evidence["selected"] = selected
            for path in selected:
                local = out / f"{label}-captures" / Path(path).relative_to(base)
                local.parent.mkdir(parents=True, exist_ok=True)
                try:
                    pulled = adb("pull", path, str(local), timeout=20, check=False)
                    if pulled.returncode or not local.is_file() or not local.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                        raise RuntimeError(f"PNG pull failed: {path}: {pulled.stdout}{pulled.stderr}")
                    evidence["pulled"].append({"remote": path, "local": str(local.relative_to(out)), "sha256": hashlib.sha256(local.read_bytes()).hexdigest()})
                    for kind, tags in priorities.items():
                        if any(tag in path for tag in tags): evidence["representatives"].setdefault(kind, str(local.relative_to(out)))
                except Exception as exc: evidence["errors"].append(str(exc))
        except Exception as exc: evidence["errors"].append(str(exc))
        finally:
            (out / f"{label}-capture-inventory.json").write_text(json.dumps(evidence, indent=2) + "\n")
        return evidence
    def interrupted(signum, frame):
        raise InterruptedError(f"Hosted action interrupted by signal {signum}")
    old_handlers = {s: signal.signal(s, interrupted) for s in (signal.SIGTERM, signal.SIGINT)}
    try:
        verify_selectors()
        source = run(["git", "rev-parse", "HEAD"]).stdout.strip()
        assert source == os.environ["GITHUB_SHA"], "Checkout is not the hosted event head"
        assert not run(["git", "status", "--porcelain", "--untracked-files=no"]).stdout, "Tracked source is dirty"
        receipt.update(source_sha=source, device={"avd": avd, "serial": serial, "image": "system-images;android-35;google_apis;x86_64", "cores": 1, "memory_mib": 2048},
                       java=run(["java", "-version"]).stderr, emulator_version=run([str(sdk / "emulator/emulator"), "-version"]).stdout,
                       adb_version=run([str(sdk / "platform-tools/adb"), "version"]).stdout,
                       performance_scope="Correctness assertions on a hosted emulator; no representative hardware performance claim")
        apks = [ROOT / "android/app/build/outputs/apk/debug/app-debug.apk", ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
        receipt["apk_sha256"] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in apks}
        signer = sdk / "build-tools/36.0.0/apksigner"
        signatures = [run([str(signer), "verify", "--print-certs", str(p)]).stdout for p in apks]
        receipt["apk_signatures"] = signatures
        certificates = [re.findall(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", s) for s in signatures]
        assert all(len(c) == 1 for c in certificates) and certificates[0] == certificates[1], "APK signer mismatch or missing certificate"
        receipt["certificate_sha256"] = certificates[0][0]
        assert not [line for line in run([str(sdk / "platform-tools/adb"), "devices"]).stdout.splitlines()[1:] if line.strip()], "Unexpected device already present"
        phase = "avd-create"
        command = [str(sdk / "cmdline-tools/latest/bin/avdmanager"), "create", "avd", "--name", avd, "--package", receipt["device"]["image"], "--device", "pixel_6"]
        result = subprocess.run(command, input="no\n", env=env, text=True, capture_output=True, timeout=90)
        (out / "avd-create.log").write_text(result.stdout + result.stderr); assert result.returncode == 0
        ownership = {"avd_name": avd, "serial": serial, "ANDROID_AVD_HOME": str(avdhome), "avd_home": str(avdhome), "emulator_pid": None, "source_sha": source}
        (out / "owned-device.json").write_text(json.dumps(ownership, indent=2) + "\n")
        command = [str(sdk / "emulator/emulator"), "-avd", avd, "-port", "5582", "-cores", "1", "-memory", "2048", "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader", "-feature", "-Vulkan", "-accel", "on"]
        receipt["emulator_command"] = command
        with (out / "emulator.log").open("w") as log:
            emulator = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        receipt["emulator_pid"] = emulator.pid; phase = "boot"
        ownership["emulator_pid"] = emulator.pid
        (out / "owned-device.json").write_text(json.dumps(ownership, indent=2) + "\n")
        deadline = time.monotonic() + 240
        while True:
            try: booted = adb("shell", "getprop", "sys.boot_completed", timeout=8, check=False).stdout.strip() == "1"
            except subprocess.TimeoutExpired: booted = False
            if booted: break
            assert emulator.poll() is None, "Owned emulator exited during boot"
            assert time.monotonic() < deadline, "Owned emulator boot timed out"
            time.sleep(2)
        assert [line.split() for line in run([str(sdk / "platform-tools/adb"), "devices"]).stdout.splitlines()[1:] if line.strip()] == [[serial, "device"]], "Expected only the owned emulator"
        for key in ("ro.build.fingerprint", "ro.build.version.sdk", "ro.product.cpu.abi", "ro.kernel.qemu"):
            receipt["device"][key] = adb("shell", "getprop", key).stdout.strip()
        assert receipt["device"]["ro.kernel.qemu"] == "1" and receipt["device"]["ro.build.version.sdk"] == "35" and receipt["device"]["ro.product.cpu.abi"] == "x86_64"
        adb("shell", "svc", "wifi", "disable"); adb("shell", "svc", "data", "disable"); receipt["offline_guest"] = True
        phase = "install"
        for apk in apks:
            installed = adb("install", "-t", str(apk), timeout=120)
            assert "Success" in installed.stdout, "APK installation was not confirmed"
        for label, selector, expected in [("focused", ",".join(FOCUSED), 9), ("full", None, 90)]:
            phase = label; adb("shell", "pm", "clear", "org.example.foodblob")
            command = [str(sdk / "platform-tools/adb"), "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "clearPackageData", "false"]
            for flag in FLAGS: command += ["-e", flag, "true"]
            if selector: command += ["-e", "class", selector]
            command += ["org.example.foodblob.test/androidx.test.runner.AndroidJUnitRunner"]
            report = ""; transport = None
            try:
                result = run(command, timeout=1200, check=False); report = result.stdout + result.stderr; transport = result.returncode
            except subprocess.TimeoutExpired as exc:
                report = (exc.stdout or b"") + (exc.stderr or b"") if isinstance(exc.stdout or exc.stderr, bytes) else (exc.stdout or "") + (exc.stderr or "")
                if isinstance(report, bytes): report = report.decode(errors="replace")
                receipt[label] = {"timeout": True}
                raise
            finally:
                (out / f"{label}-instrumentation.log").write_text(report)
                receipt.setdefault(label, {}).update(observed=diagnostic_counts(report), transport_returncode=transport, captures=capture_files(label))
            assert transport == 0, f"adb transport failed: {transport}"
            receipt[label]["validated"] = validate(report, expected)
            print(f"{label}: {receipt[label]['validated']}", flush=True)
        receipt["tracked_clean_after"] = not run(["git", "status", "--porcelain", "--untracked-files=no"]).stdout
        assert receipt["tracked_clean_after"], "Tracked source changed during acceptance"
        receipt["exit_code"] = 0
    except BaseException as exc:
        receipt.update(exit_code=1, failed_phase=phase, error=str(exc)); print(str(exc), file=sys.stderr)
    finally:
        # A second cancellation must not interrupt targeted cleanup or erase the receipt.
        for sig in old_handlers: signal.signal(sig, signal.SIG_IGN)
        if emulator:
            try: adb("emu", "kill", timeout=15, check=False)
            except Exception as exc: cleanup_errors.append(f"adb shutdown: {exc}")
            try: emulator.wait(timeout=30)
            except subprocess.TimeoutExpired:
                try: os.killpg(emulator.pid, signal.SIGTERM)
                except ProcessLookupError: pass
                try: emulator.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    try: os.killpg(emulator.pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                    try: emulator.wait(timeout=10)
                    except subprocess.TimeoutExpired: cleanup_errors.append("Owned emulator did not terminate")
            receipt["owned_emulator_terminal"] = emulator.poll() is not None
        else: receipt["owned_emulator_terminal"] = True
        if receipt["owned_emulator_terminal"] and ((avdhome / (avd + ".ini")).exists() or (avdhome / (avd + ".avd")).exists()):
            try:
                deleted = run([str(sdk / "cmdline-tools/latest/bin/avdmanager"), "delete", "avd", "--name", avd], timeout=30, check=False)
                receipt["owned_avd_deleted"] = deleted.returncode == 0 and not (avdhome / (avd + ".ini")).exists() and not (avdhome / (avd + ".avd")).exists()
                if not receipt["owned_avd_deleted"]: cleanup_errors.append("Owned AVD deletion failed")
            except Exception as exc: cleanup_errors.append(f"Owned AVD deletion: {exc}")
        try:
            readback = run([str(sdk / "platform-tools/adb"), "devices"], timeout=10, check=False)
            receipt["devices_after"] = readback.stdout
            if readback.returncode or any(line.split()[0] == serial for line in readback.stdout.splitlines()[1:] if line.strip()):
                cleanup_errors.append("Owned emulator device remains or device readback failed")
        except Exception as exc: cleanup_errors.append(f"Device readback: {exc}")
        receipt["cleanup_errors"] = cleanup_errors
        if cleanup_errors or not receipt["owned_emulator_terminal"]: receipt["exit_code"] = 1
        receipt["elapsed_seconds"] = time.monotonic() - started
        (out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n"); print(json.dumps(receipt), flush=True)
        for sig, handler in old_handlers.items(): signal.signal(sig, handler)
    return receipt["exit_code"]


if __name__ == "__main__": sys.exit(main())
