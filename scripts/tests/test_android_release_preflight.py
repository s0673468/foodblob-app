"""Exercise the installer boundary using inert SDK tools and synthetic APKs."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
APP_ID = "org.example.foodblob"
TRACK_ID = "org.example.track"
DIGEST = "a" * 64

TOOL = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['FAKE_CALLS'], 'a') as stream:
    stream.write(json.dumps([name, *args]) + '\n')
if name == 'java':
    if args == ['-version']:
        print('openjdk version "17.0.1"', file=sys.stderr)
    else:
        mode = args[-4]
        if mode == os.environ.get('FAKE_INCOMPATIBLE_MODE'):
            raise SystemExit(1)
elif name == 'aapt2':
    print("package: name='%s' versionCode='%s' versionName='1.5.1'" % (
        os.environ.get('FAKE_APK_PACKAGE', os.environ['FAKE_APP_ID']),
        os.environ.get('FAKE_APK_VERSION', '18')))
    if os.environ.get('FAKE_DEBUGGABLE'):
        print('application-debuggable')
elif name == 'apksigner':
    if os.environ.get('FAKE_UNSIGNED'):
        raise SystemExit(1)
    print('Signer #1 certificate SHA-256 digest: ' + os.environ.get('FAKE_DIGEST', 'a' * 64))
elif name == 'android_gradle.sh':
    pass
elif name == 'adb':
    assert args[:2] == ['-s', 'synthetic-device'], args
    args = args[2:]
    if args == ['get-state']:
        print('device')
    elif args[:2] == ['shell', 'getprop']:
        print({'ro.product.model': 'Synthetic phone',
               'ro.build.fingerprint': 'example/device/test',
               'ro.build.version.sdk': '36'}[args[2]])
    elif args[:3] == ['shell', 'pm', 'path']:
        package = args[3]
        absent = os.environ.get('FAKE_NO_APP') if package == os.environ['FAKE_APP_ID'] else os.environ.get('FAKE_NO_TRACK')
        allowed = {os.environ['FAKE_APP_ID'], os.environ['FAKE_TRACK_ID']}
        if package not in allowed:
            raise SystemExit('Unexpected package identity: ' + package)
        if not absent:
            print('package:/synthetic/' + package + '/base.apk')
    elif args[:3] == ['shell', 'dumpsys', 'package']:
        calls = [json.loads(line) for line in pathlib.Path(os.environ['FAKE_CALLS']).read_text().splitlines()]
        installed = any(row[:4] == ['adb', '-s', 'synthetic-device', 'install'] for row in calls)
        version = '18' if installed else os.environ.get('FAKE_INSTALLED_VERSION', '17')
        print('versionCode=' + version + ' minSdk=26 targetSdk=36')
        print('versionName=1.5.1')
        print('pkgFlags=[ HAS_CODE ]')
        print('firstInstallTime=2026-01-01 00:00:00')
    elif args[:1] == ['pull']:
        pathlib.Path(args[2]).write_bytes(b'synthetic apk')
    elif args[:1] == ['install']:
        assert args[1] == '-r', args
        print('Success')
    else:
        raise SystemExit('Unexpected adb operation: ' + repr(args))
else:
    raise SystemExit('Unexpected tool: ' + name)
'''


class ReleasePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        scripts = self.repo / "scripts"
        scripts.mkdir(parents=True)
        self.installer = scripts / "android_release_install.sh"
        shutil.copy2(ROOT / "scripts/android_release_install.sh", self.installer)
        shutil.copy2(ROOT / "scripts/ApkSignatureCompatibility.java", scripts)
        self.calls = self.root / "calls.jsonl"
        self.calls.touch()
        sdk = self.root / "sdk"
        java = self.root / "jdk"
        tools = [sdk / "platform-tools/adb", sdk / "build-tools/36.0.0/apksigner",
                 sdk / "build-tools/36.0.0/aapt2", java / "bin/java",
                 scripts / "android_gradle.sh"]
        for path in tools:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(TOOL)
            path.chmod(0o755)
        jar = sdk / "build-tools/36.0.0/lib/apksigner.jar"
        jar.parent.mkdir(parents=True)
        jar.touch()
        self.apk = self.repo / "android/app/build/outputs/apk/release/app-release.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b"synthetic release apk")
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(("FAKE_", "FOODBLOB_", "ANDROID_", "JAVA_"))}
        self.env.update(ANDROID_SDK_ROOT=str(sdk), ANDROID_JAVA_HOME=str(java),
                        FAKE_CALLS=str(self.calls), FAKE_APP_ID=APP_ID,
                        FAKE_TRACK_ID=TRACK_ID, TMPDIR=str(self.root))

    def tearDown(self):
        self.temp.cleanup()

    def run_installer(self, overrides=None, *, preflight=True, arguments=None):
        env = self.env | (overrides or {})
        if arguments is None:
            arguments = ["--preflight", "synthetic-device", str(self.apk)] if preflight else ["synthetic-device"]
        result = subprocess.run(["bash", str(self.installer), *arguments],
                                env=env, text=True, capture_output=True, timeout=15)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        if preflight:
            self.assertFalse(any(row[0] == "android_gradle.sh" for row in calls), calls)
            for row in calls:
                if row[0] != "adb":
                    continue
                operation = row[3:]
                self.assertTrue(operation == ["get-state"] or operation[:1] == ["pull"]
                                or operation[:2] == ["shell", "getprop"]
                                or operation[:3] in (["shell", "pm", "path"], ["shell", "dumpsys", "package"]), row)
        self.assertFalse(list(self.root.glob("foodblob-release-install.*")))
        return result, calls

    def test_matching_installed_app_and_matching_track_are_read_only(self):
        result, calls = self.run_installer()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Preflight passed", result.stdout)
        self.assertIn(["adb", "-s", "synthetic-device", "shell", "pm", "path", TRACK_ID], calls)
        modes = [row[-4] for row in calls if row[0] == "java" and row[1] != "-version"]
        self.assertEqual(["installed-data", "signature-permission"], modes)

    def test_first_install_accepts_an_explicit_matching_digest(self):
        result, _ = self.run_installer({"FAKE_NO_APP": "1", "FAKE_NO_TRACK": "1", "FOODBLOB_EXPECTED_SIGNER_SHA256": DIGEST})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_unanchored_first_install_is_refused(self):
        result, _ = self.run_installer({"FAKE_NO_APP": "1", "FAKE_NO_TRACK": "1"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No trusted Food Blob signing anchor", result.stderr)

    def test_wrong_package_is_refused_before_trust_anchor_lookup(self):
        result, calls = self.run_installer({"FAKE_APK_PACKAGE": "example.unrelated"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("package does not match", result.stderr)
        self.assertFalse(any(row[3:6] == ["shell", "pm", "path"] for row in calls))

    def test_debuggable_apk_is_refused(self):
        result, _ = self.run_installer({"FAKE_DEBUGGABLE": "1"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("debuggable APK", result.stderr)

    def test_downgrade_is_refused(self):
        result, _ = self.run_installer({"FAKE_INSTALLED_VERSION": "19"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Refusing to downgrade", result.stderr)

    def test_same_version_can_be_checked(self):
        result, _ = self.run_installer({"FAKE_INSTALLED_VERSION": "18"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_unsigned_apk_is_refused(self):
        result, _ = self.run_installer({"FAKE_UNSIGNED": "1"})
        self.assertNotEqual(0, result.returncode)

    def test_changed_app_signer_is_refused(self):
        result, _ = self.run_installer({"FAKE_INCOMPATIBLE_MODE": "installed-data"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not compatible with installed Food Blob", result.stderr)

    def test_incompatible_track_signer_is_refused(self):
        result, _ = self.run_installer({"FAKE_INCOMPATIBLE_MODE": "signature-permission"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not compatible with installed Track", result.stderr)

    def test_mismatched_explicit_digest_is_refused(self):
        result, _ = self.run_installer({"FOODBLOB_EXPECTED_SIGNER_SHA256": "b" * 64})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not match the expected trust anchor", result.stderr)

    def test_missing_apk_is_refused_without_build(self):
        self.apk.unlink()
        result, _ = self.run_installer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Release APK does not exist", result.stderr)

    def test_incomplete_preflight_arguments_never_fall_back_to_install(self):
        result, calls = self.run_installer(arguments=["--preflight"])
        self.assertEqual(2, result.returncode)
        self.assertEqual([], calls)

    def test_default_mode_preserves_build_replace_and_readback(self):
        result, calls = self.run_installer(preflight=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("data-preservingly", result.stdout)
        self.assertEqual(1, sum(row[0] == "android_gradle.sh" for row in calls))
        self.assertEqual([["adb", "-s", "synthetic-device", "install", "-r", str(self.apk)]],
                         [row for row in calls if row[3:4] == ["install"]])
        self.assertFalse(any("uninstall" in row or "clear" in row for row in calls))


if __name__ == "__main__":
    unittest.main()
