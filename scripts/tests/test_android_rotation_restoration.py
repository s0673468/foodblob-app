import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hosted_android_acceptance import validate_rotation_restoration, window_rotation_cache

class RotationRestorationTests(unittest.TestCase):
    def setUp(self):
        self.expected = ["1.0", "1", "0", "free", "mUserRotationMode=USER_ROTATION_FREE mUserRotation=ROTATION_0"]
        self.original = dict(zip(("fontScaleSetting", "autoRotateSetting", "userRotationSetting", "windowUserRotation", "windowCachedRotation"), self.expected))
        self.convergence = dict(expected=self.expected, actual=self.expected, converged=True)

    def test_exact_original_settings_and_cache_survive_disconnect(self):
        self.assertTrue(validate_rotation_restoration(self.original, self.convergence, self.expected)["converged"])

    def test_observed_thaw_overwrite_cannot_claim_restoration(self):
        drift = self.expected.copy(); drift[2] = "1"; drift[4] = drift[4].replace("ROTATION_0", "ROTATION_90")
        with self.assertRaisesRegex(ValueError, "after UiAutomation disconnect"):
            validate_rotation_restoration(self.original, self.convergence, drift)

    def test_in_test_drift_cannot_hide_behind_later_equal_readback(self):
        failed = dict(self.convergence, actual=["1.0", "1", "1", "free", self.expected[4]], converged=False)
        with self.assertRaisesRegex(ValueError, "did not restore"):
            validate_rotation_restoration(self.original, failed, self.expected)

    def test_default_display_is_distinct_from_secondary_display(self):
        dump = "Display: mDisplayId=0\n mUserRotationMode=USER_ROTATION_FREE mUserRotation=ROTATION_0\nDisplay: mDisplayId=1\n mUserRotationMode=USER_ROTATION_LOCKED mUserRotation=ROTATION_90\n"
        self.assertEqual(window_rotation_cache(dump), self.expected[4])
        with self.assertRaises(ValueError): window_rotation_cache(dump.replace("mDisplayId=0", "mDisplayId=2"))
        with self.assertRaises(ValueError): window_rotation_cache(dump.replace("Display: mDisplayId=1", "other"))

if __name__ == "__main__": unittest.main()
