"""Exercise the real device wrapper with inert adb/build tools and raw reports."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


def status(code, name="countsAreDurable", current=1):
    return (f"INSTRUMENTATION_STATUS: class=org.example.foodblob.FixtureTest\n"
            f"INSTRUMENTATION_STATUS: test={name}\n"
            f"INSTRUMENTATION_STATUS: current={current}\n"
            f"INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def summary(count, code=-1):
    return (f"INSTRUMENTATION_RESULT: stream=\nTime: 0.01\n\n"
            f"OK ({count} {'test' if count == 1 else 'tests'})\n\n"
            f"INSTRUMENTATION_CODE: {code}\n")


PASS = status(1) + status(0) + summary(1)
TOOL = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['FAKE_CALLS'], 'a') as stream:
    stream.write(json.dumps([name, *args]) + '\n')
if name == 'android_gradle.sh':
    raise SystemExit(0)
assert name == 'adb'
assert args[:2] == ['-s', 'synthetic-device'], args
args = args[2:]
if args == ['get-state']:
    print('device')
elif args[:1] == ['install']:
    assert args[1:3] == ['-r', '-t'], args
elif args[:3] == ['shell', 'am', 'instrument']:
    sys.stdout.write(pathlib.Path(os.environ['FAKE_REPORT']).read_text())
    raise SystemExit(int(os.environ.get('FAKE_ADB_EXIT', '0')))
elif args == ['uninstall', 'org.example.foodblob.test']:
    pass
elif args == ['shell', 'pm', 'path', 'org.example.foodblob']:
    print('package:/fixture/app.apk')
else:
    raise SystemExit('Unexpected device operation: ' + repr(args))
'''


class AndroidDeviceRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts = self.root / "repo/scripts"
        scripts.mkdir(parents=True)
        self.runner = scripts / "android_device_test.sh"
        shutil.copy2(ROOT / "scripts/android_device_test.sh", self.runner)
        parser = ROOT / "scripts/validate_instrumentation.py"
        if parser.exists():
            shutil.copy2(parser, scripts)
        sdk = self.root / "sdk"
        for tool in [sdk / "platform-tools/adb", scripts / "android_gradle.sh"]:
            tool.parent.mkdir(parents=True, exist_ok=True)
            tool.write_text(TOOL)
            tool.chmod(0o755)
        self.calls = self.root / "calls.jsonl"
        self.calls.touch()
        self.report = self.root / "report.txt"
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(("FAKE_", "ANDROID_"))}
        self.env.update(ANDROID_SDK_ROOT=str(sdk), FAKE_CALLS=str(self.calls), FAKE_REPORT=str(self.report), TMPDIR=str(self.root))

    def run_report(self, report, exit_code=0):
        self.report.write_text(report)
        result = subprocess.run(["bash", str(self.runner), "synthetic-device", "org.example.foodblob.FixtureTest"],
                                env=self.env | {"FAKE_ADB_EXIT": str(exit_code)}, capture_output=True, text=True, timeout=15)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertEqual(report, result.stdout)
        self.assertIn(["adb", "-s", "synthetic-device", "uninstall", "org.example.foodblob.test"], calls)
        self.assertFalse(any(row[3:5] == ["uninstall", "org.example.foodblob"] or "clear" in row for row in calls))
        self.assertFalse(list(self.root.glob("foodblob-device-test.*")))
        return result, calls

    def test_positive_executed_count_passes_and_keeps_selector_and_app(self):
        result, calls = self.run_report(PASS)
        self.assertEqual(0, result.returncode, result.stderr)
        instrumentation = next(row for row in calls if row[3:6] == ["shell", "am", "instrument"])
        self.assertIn("org.example.foodblob.FixtureTest", instrumentation)
        self.assertIn(["adb", "-s", "synthetic-device", "shell", "pm", "path", "org.example.foodblob"], calls)

    def test_test_failure_fails_even_when_adb_succeeds(self):
        result, _ = self.run_report(status(1) + status(-2) + "INSTRUMENTATION_RESULT: stream=\nFAILURES!!!\nTests run: 1, Failures: 1\nINSTRUMENTATION_CODE: -1\n")
        self.assertNotEqual(0, result.returncode)

    def test_process_crash_fails_even_after_a_completed_test(self):
        result, _ = self.run_report(status(1) + status(0) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n")
        self.assertNotEqual(0, result.returncode)

    def test_zero_tests_fails(self):
        result, _ = self.run_report(summary(0))
        self.assertNotEqual(0, result.returncode)

    def test_all_assumption_skipped_tests_fail_despite_positive_summary(self):
        result, _ = self.run_report(status(1) + status(-4) + summary(1))
        self.assertNotEqual(0, result.returncode)

    def test_all_ignored_tests_fail(self):
        result, _ = self.run_report(status(1) + status(-3) + summary(0))
        self.assertNotEqual(0, result.returncode)

    def test_mixed_pass_and_skip_reports_skips_without_claiming_all_executed(self):
        result, _ = self.run_report(status(1) + status(0) + status(1, "requiresFlag", 2) + status(-4, "requiresFlag", 2) + summary(2))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("1 passed, 1 skipped", result.stderr)

    def test_pass_with_ignored_test_uses_junit_executed_count(self):
        result, _ = self.run_report(status(1) + status(0) + status(1, "ignored", 2) + status(-3, "ignored", 2) + summary(1))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("1 passed, 1 skipped", result.stderr)

    def test_androidx_stream_only_status_does_not_count_as_a_pass(self):
        auxiliary = "INSTRUMENTATION_STATUS: stream=fixture diagnostics\nINSTRUMENTATION_STATUS_CODE: 0\n"
        result, _ = self.run_report(auxiliary + PASS)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("1 passed, 0 skipped", result.stderr)

    def test_success_summary_with_inconsistent_count_fails(self):
        result, _ = self.run_report(status(1) + status(0) + summary(9))
        self.assertNotEqual(0, result.returncode)

    def test_duplicate_final_report_fails(self):
        result, _ = self.run_report(PASS + summary(1))
        self.assertNotEqual(0, result.returncode)

    def test_transport_failure_preserves_adb_exit_code_and_stdout(self):
        result, calls = self.run_report(PASS, exit_code=23)
        self.assertEqual(23, result.returncode)
        self.assertNotIn(["adb", "-s", "synthetic-device", "shell", "pm", "path", "org.example.foodblob"], calls)

    def test_unrelated_ok_text_cannot_make_a_result_pass(self):
        result, _ = self.run_report("Some tool said OK (9 tests)\nINSTRUMENTATION_CODE: -1\n")
        self.assertNotEqual(0, result.returncode)

    def test_truncated_run_with_a_pass_is_not_success(self):
        result, _ = self.run_report(status(1) + status(0))
        self.assertNotEqual(0, result.returncode)

    def test_fake_success_summary_cannot_hide_a_failed_status(self):
        result, _ = self.run_report(status(1) + status(-2) + summary(1))
        self.assertNotEqual(0, result.returncode)

    def test_incomplete_test_cannot_hide_behind_an_earlier_pass(self):
        result, _ = self.run_report(status(1) + status(0) + status(1, "neverFinished", 2) + summary(1))
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
