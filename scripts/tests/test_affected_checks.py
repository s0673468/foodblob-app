import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("affected_checks", ROOT / "scripts/affected_checks.py")
affected = importlib.util.module_from_spec(spec)
spec.loader.exec_module(affected)


class AffectedPlanTests(unittest.TestCase):
    def test_android_source_does_not_start_apple(self):
        plan = affected.plan_checks(["android/app/src/main/java/org/example/foodblob/domain/FoodModels.kt"])
        self.assertEqual(plan["lanes"]["portable"], ["release-tests", "android-check"])
        self.assertEqual(plan["lanes"]["apple"], ["workflow-lint"])
        self.assertTrue(plan["acceptance"])

    def test_android_unit_test_edit_uses_unit_lane(self):
        plan = affected.plan_checks(["android/app/src/test/java/org/example/foodblob/domain/FoodCountsTest.kt"])
        self.assertEqual(plan["lanes"]["portable"][-1], "android-unit")
        self.assertFalse(plan["acceptance"])

    def test_mixed_android_test_and_source_uses_one_combined_gradle_gate(self):
        plan = affected.plan_checks(["android/app/src/test/a.kt", "android/app/src/main/a.kt"])
        self.assertEqual(plan["lanes"]["portable"].count("android-check"), 1)
        self.assertNotIn("android-unit", plan["lanes"]["portable"])

    def test_apple_shared_code_runs_apple_and_cheap_cross_platform_contracts(self):
        plan = affected.plan_checks(["Shared/FoodModels.swift"])
        self.assertEqual(plan["lanes"]["apple"], ["workflow-lint", "apple-check"])
        self.assertEqual(plan["lanes"]["portable"], ["release-tests"])

    def test_native_ui_marks_acceptance_without_running_it(self):
        plan = affected.plan_checks(["FoodBlob/Features/Today/TodayView.swift"])
        self.assertEqual(plan["acceptance"], ["ios-acceptance"])
        self.assertNotIn("ios-acceptance", plan["lanes"]["apple"])

    def test_instrumentation_edit_builds_and_requests_runtime_acceptance(self):
        plan = affected.plan_checks(["android/app/src/androidTest/java/org/example/foodblob/storage/Test.kt"])
        self.assertIn("android-check", plan["lanes"]["portable"])
        self.assertIn("android-device-test", plan["acceptance"])

    def test_documentation_still_runs_contracts_that_read_docs(self):
        for path in ["README.md", "docs/android.md"]:
            with self.subTest(path=path):
                plan = affected.plan_checks([path])
                self.assertEqual(plan["lanes"]["portable"], ["release-tests"])
                self.assertEqual(plan["lanes"]["apple"], ["workflow-lint"])

    def test_unknown_global_or_unsafe_path_falls_back_to_full(self):
        for path in ["new-module/thing.swift", "Makefile", ".github/workflows/tests.yml", "tools/generate_project.rb", "FoodBlob.xcodeproj/project.pbxproj", "../android/thing.kt", "/android/thing.kt"]:
            with self.subTest(path=path):
                plan = affected.plan_checks([path])
                self.assertTrue(plan["full_fallback"])
                self.assertEqual(plan["lanes"]["apple"], ["workflow-lint", "project-check", "apple-check"])
                self.assertIn("android-check", plan["lanes"]["portable"])

    def test_git_failure_falls_back_even_with_explicit_paths(self):
        plan = affected.plan_checks(["docs/android.md"], discovery_error="missing base")
        self.assertTrue(plan["full_fallback"])
        self.assertIn("missing base", plan["reasons"])

    def test_execution_is_one_command_for_only_the_selected_lane(self):
        plan = affected.plan_checks(["Makefile"])
        with patch.object(affected.subprocess, "run") as run:
            run.return_value.returncode = 17
            result = affected.execute_plan(plan, Path("/fixture"), "portable")
        self.assertEqual(result, 17)
        run.assert_called_once_with(["make", "release-tests", "android-check"], cwd=Path("/fixture"))

    def test_empty_platform_does_not_execute(self):
        with patch.object(affected.subprocess, "run") as run:
            self.assertEqual(affected.execute_plan({"lanes": {"apple": []}}, Path("/fixture"), "apple"), 0)
        run.assert_not_called()

    def test_execute_requires_explicit_platform(self):
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit) as error:
            affected.main(["--execute"])
        self.assertEqual(error.exception.code, 2)


class GitDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test Fixture")
        self.write("android/old.kt", "old\n")
        self.write("Shared/changed.swift", "old\n")
        self.write("docs/remove.md", "old\n")
        self.git("add", ".")
        self.git("commit", "-qm", "fixture base")
        self.git("branch", "baseline")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.STDOUT)

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)

    def test_committed_staged_unstaged_untracked_deleted_and_both_rename_sides(self):
        self.git("mv", "android/old.kt", "docs/renamed.md")
        self.git("commit", "-qm", "rename")
        self.write("android/staged.kt", "staged\n")
        self.git("add", "android/staged.kt")
        self.write("Shared/changed.swift", "new\n")
        (self.root / "docs/remove.md").unlink()
        self.write("android/untracked name\nwith newline.kt", "new\n")
        paths, error = affected.changed_paths(self.root, "baseline")
        self.assertIsNone(error)
        self.assertEqual(set(paths), {"android/old.kt", "docs/renamed.md", "android/staged.kt", "Shared/changed.swift", "docs/remove.md", "android/untracked name\nwith newline.kt"})

    def test_missing_base_is_an_explicit_broad_fallback(self):
        paths, error = affected.changed_paths(self.root, "missing-ref")
        self.assertEqual(paths, [])
        self.assertIn("Cannot establish changed paths", error)
        self.assertTrue(affected.plan_checks(paths, discovery_error=error)["full_fallback"])

    def test_explicit_paths_supplement_untracked_files_in_cli(self):
        self.write("surprise/unknown.txt", "new\n")
        output = io.StringIO()
        with patch("sys.stdout", new=output):
            self.assertEqual(affected.main(["--root", str(self.root), "--base", "baseline", "--path", "docs/android.md", "--plan"]), 0)
        plan = json.loads(output.getvalue())
        self.assertTrue(plan["full_fallback"])
        self.assertIn("docs/android.md", plan["changed_paths"])
        self.assertIn("surprise/unknown.txt", plan["changed_paths"])

    def test_execution_preserves_make_command_line_overrides(self):
        self.write("Makefile", "release-tests workflow-lint:\n\t@echo '$(PYTHON)|$(ANDROID_GRADLE_ARGS)' >> forwarded.txt\n")
        with patch.dict(os.environ, {"MAKEFLAGS": "PYTHON=custom-python ANDROID_GRADLE_ARGS=--max-workers=2"}):
            self.assertEqual(affected.execute_plan(affected.plan_checks(["README.md"]), self.root, "portable"), 0)
        self.assertEqual((self.root / "forwarded.txt").read_text().splitlines(), ["custom-python|--max-workers=2"])

    def test_clean_worktree_still_runs_cheap_checks(self):
        paths, error = affected.changed_paths(self.root, "baseline")
        self.assertEqual(paths, [])
        self.assertIsNone(error)
        self.assertEqual(affected.plan_checks(paths)["lanes"]["portable"], ["release-tests"])


if __name__ == "__main__":
    unittest.main()
