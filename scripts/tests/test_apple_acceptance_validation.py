"""Strict acceptance of the complete eight-case UI inventory, never log counts."""
import copy
from pathlib import Path
import re
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from validate_apple_acceptance import EXPECTED_IDENTIFIERS, validate


FOCUSED_IDENTIFIERS = {
    "JellyInteractionUITests/testCancelledLensPressAndSettingsLinksPreserveCounts()",
    "JellyInteractionUITests/testPaintJellySliderPersistsWithoutChangingFoodAndBothWorldsStayPlayable()",
}


def fixture(identifiers=None):
    identifiers = EXPECTED_IDENTIFIERS if identifiers is None else identifiers
    summary = {"result": "Passed", "totalTestCount": len(identifiers), "passedTests": len(identifiers),
               "failedTests": 0, "skippedTests": 0, "expectedFailures": 0,
               "testFailures": []}
    cases = [{"nodeType": "Test Case", "name": identifier.split("/")[1],
              "nodeIdentifier": identifier, "result": "Passed"}
             for identifier in sorted(identifiers)]
    tests = {"testNodes": [{"nodeType": "Test Plan", "name": "Acceptance",
                           "children": [{"nodeType": "UI test bundle", "name": "FoodBlobUITests",
                                         "children": cases}]}]}
    return summary, tests, cases


class AppleAcceptanceValidationTests(unittest.TestCase):
    def test_explicit_focused_two_passes_but_cannot_claim_full_gate(self):
        summary, tests, _ = fixture(FOCUSED_IDENTIFIERS)
        result = validate(summary, tests, expected_identifiers=FOCUSED_IDENTIFIERS)
        self.assertEqual(result["passedTests"], 2)
        self.assertEqual(result["identifiers"], sorted(FOCUSED_IDENTIFIERS))
        with self.assertRaises(ValueError): validate(summary, tests)

    def test_focused_missing_or_wrong_case_is_rejected(self):
        for wrong in (False, True):
            summary, tests, cases = fixture(FOCUSED_IDENTIFIERS)
            if wrong:
                cases[0]["nodeIdentifier"] = next(iter(EXPECTED_IDENTIFIERS-FOCUSED_IDENTIFIERS))
            else:
                cases.pop()
            with self.subTest(wrong=wrong), self.assertRaises(ValueError):
                validate(summary, tests, expected_identifiers=FOCUSED_IDENTIFIERS)

    def test_empty_unknown_and_duplicate_expected_subsets_fail(self):
        summary, tests, _ = fixture(FOCUSED_IDENTIFIERS)
        for expected in (set(), {"Other/testCase()"}, [*FOCUSED_IDENTIFIERS, *FOCUSED_IDENTIFIERS], "invalid"):
            with self.subTest(expected=expected), self.assertRaises(ValueError):
                validate(summary, tests, expected_identifiers=expected)

    def test_focused_skipped_or_retried_case_is_still_rejected(self):
        for retried in (False, True):
            summary, tests, cases = fixture(FOCUSED_IDENTIFIERS)
            if retried:
                cases[0]["children"] = [{"nodeType": "Repetition", "name": "again", "result": "Passed"}]
            else:
                cases[0]["result"] = "Skipped"
            with self.subTest(retried=retried), self.assertRaises(ValueError):
                validate(summary, tests, expected_identifiers=FOCUSED_IDENTIFIERS)

    def test_inventory_matches_real_ui_source(self):
        source = (SCRIPTS.parent / "FoodBlobUITests/JellyInteractionUITests.swift").read_text()
        methods = re.findall(r"\bfunc (test\w+)\(\)", source)
        self.assertEqual(len(methods), 8)
        self.assertEqual(EXPECTED_IDENTIFIERS,
                         {f"JellyInteractionUITests/{name}()" for name in methods})

    def test_complete_nested_xcresult_tree_passes(self):
        summary, tests, _ = fixture()
        result = validate(summary, tests)
        self.assertEqual(result["passedTests"], 8)
        self.assertEqual(result["identifiers"], sorted(EXPECTED_IDENTIFIERS))

    def test_missing_partial_and_extra_cases_are_rejected(self):
        for mutation in (lambda cases: cases.clear(), lambda cases: cases.pop(),
                         lambda cases: cases.append(dict(cases[0], nodeIdentifier="Other/testExtra()"))):
            summary, tests, cases = fixture()
            mutation(cases)
            with self.subTest(cases=len(cases)), self.assertRaises(ValueError):
                validate(summary, tests)

    def test_duplicate_cannot_replace_missing_case(self):
        summary, tests, cases = fixture()
        cases[-1] = copy.deepcopy(cases[0])
        with self.assertRaises(ValueError): validate(summary, tests)

    def test_case_status_cannot_hide_behind_successful_summary(self):
        for status in ("Failed", "Skipped", "Expected Failure", "unknown", None):
            summary, tests, cases = fixture()
            cases[0]["result"] = status
            with self.subTest(status=status), self.assertRaises(ValueError):
                validate(summary, tests)

    def test_summary_disagreement_missing_fields_and_wrong_types_fail(self):
        changes = ({"passedTests": 7}, {"totalTestCount": 9}, {"skippedTests": 1},
                   {"failedTests": 1}, {"expectedFailures": 1}, {"result": "Failed"},
                   {"testFailures": [{"message": "failure"}]}, {"failedTests": False})
        for change in changes:
            summary, tests, _ = fixture(); summary.update(change)
            with self.subTest(change=change), self.assertRaises(ValueError): validate(summary, tests)
        summary, tests, _ = fixture(); del summary["passedTests"]
        with self.assertRaises(ValueError): validate(summary, tests)

    def test_repetition_or_retry_runs_are_not_single_clean_pass(self):
        for children in ([{"nodeType": "Repetition", "name": "Repetition 1", "result": "Passed"}],
                         [{"nodeType": "Test Case Run", "name": str(i), "result": "Passed"} for i in range(2)],
                         [{"nodeType": "Test Case Run", "name": "first", "result": "Failed"}]):
            summary, tests, cases = fixture(); cases[0]["children"] = children
            with self.subTest(children=children), self.assertRaises(ValueError): validate(summary, tests)

    def test_one_successful_case_run_detail_is_allowed(self):
        summary, tests, cases = fixture()
        cases[0]["children"] = [{"nodeType": "Test Case Run", "name": "Run 1", "result": "Passed"}]
        self.assertEqual(validate(summary, tests)["passedTests"], 8)

    def test_malformed_or_missing_tree_and_identifiers_fail(self):
        summary, _, _ = fixture()
        for tests in ({}, {"testNodes": None}, {"testNodes": [None]}):
            with self.subTest(tests=tests), self.assertRaises(ValueError): validate(summary, tests)
        summary, tests, cases = fixture(); del cases[0]["nodeIdentifier"]
        with self.assertRaises(ValueError): validate(summary, tests)


if __name__ == "__main__": unittest.main()
