from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from validate_acceptance import android


def report(passed, skipped=0):
    rows = []
    for index in range(passed + skipped):
        bundle = (f"INSTRUMENTATION_STATUS: class=Fixture\n"
                  f"INSTRUMENTATION_STATUS: test=case{index}\n"
                  f"INSTRUMENTATION_STATUS: current={index + 1}\n")
        rows += [bundle + "INSTRUMENTATION_STATUS_CODE: 1\n",
                 bundle + f"INSTRUMENTATION_STATUS_CODE: {0 if index < passed else -4}\n"]
    return "".join(rows) + f"INSTRUMENTATION_RESULT: stream=\nOK ({passed + skipped} tests)\nINSTRUMENTATION_CODE: -1\n"


class AcceptanceValidationTests(unittest.TestCase):
    def test_complete_device_inventory_passes(self):
        self.assertEqual(android(report(90), 90), {"passed": 90, "skipped": 0, "failed": 0})

    def test_partial_run_cannot_claim_full_acceptance(self):
        with self.assertRaises(ValueError):
            android(report(89), 90)

    def test_opt_in_skip_cannot_claim_complete_inventory(self):
        with self.assertRaises(ValueError):
            android(report(89, 1), 90)

    def test_adb_success_cannot_hide_instrumentation_failure(self):
        with self.assertRaises(ValueError):
            android(report(90).replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"), 90)

