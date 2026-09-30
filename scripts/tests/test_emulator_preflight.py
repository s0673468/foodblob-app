"""Exercise the workflow's real ELF linker guard, not a mocked ldd report."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(sys.platform.startswith("linux") and shutil.which("gcc") and shutil.which("ldd"),
                     "The ELF preflight regression runs on the ordinary Ubuntu repository gate")
class EmulatorPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="foodblob-elf-preflight-")
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.sdk = self.work / "sdk"
        self.bundle = self.sdk / "emulator/lib64/fixture"
        self.system = self.work / "system"
        self.binary = self.sdk / "emulator/qemu/linux-x86_64/qemu-system-x86_64"
        for directory in (self.bundle, self.system, self.binary.parent):
            directory.mkdir(parents=True)
        for name, directory in (("bundle", self.bundle), ("system", self.system)):
            source = self.work / (name + ".c")
            source.write_text(f"int owned_{name}(void) {{ return 0; }}\n")
            subprocess.run(["gcc", "-shared", "-fPIC", str(source), "-o",
                            str(directory / ("libowned_" + name + ".so"))], check=True, capture_output=True)
        source = self.work / "main.c"
        source.write_text("int owned_bundle(void); int owned_system(void);\n"
                          "int main(void) { return owned_bundle() + owned_system(); }\n")
        subprocess.run(["gcc", str(source), "-L" + str(self.bundle), "-L" + str(self.system),
                        "-lowned_bundle", "-lowned_system", "-o", str(self.binary)], check=True, capture_output=True)
        workflow = (ROOT / ".github/workflows/tests.yml").read_text()
        start = workflow.index('          emulator_libs=$(find ')
        end = workflow.index('          test -f "$ANDROID_HOME/system-images/', start)
        self.guard = "set -euo pipefail\n" + workflow[start:end]

    def preflight(self):
        env = dict(os.environ, ANDROID_HOME=str(self.sdk), RUNNER_TEMP=str(self.work))
        env.pop("LD_LIBRARY_PATH", None)
        result = subprocess.run(["bash", "-c", self.guard], env=env, text=True, capture_output=True)
        return result.returncode, (self.work / "emulator-libraries.txt").read_text()

    def test_sdk_bundled_libraries_are_resolved_by_the_actual_workflow_guard(self):
        nested = self.sdk / "emulator/lib64/qt/fixture"
        nested.mkdir(parents=True)
        shutil.copy2(self.system / "libowned_system.so", nested)
        code, report = self.preflight()
        self.assertEqual(code, 0, report)
        self.assertIn(str(self.bundle / "libowned_bundle.so"), report)
        self.assertIn(str(nested / "libowned_system.so"), report)

    def test_absent_system_dependency_still_fails_with_bundle_paths_present(self):
        code, report = self.preflight()
        self.assertEqual(code, 1, report)
        self.assertIn(str(self.bundle / "libowned_bundle.so"), report)
        self.assertIn("libowned_system.so => not found", report)
