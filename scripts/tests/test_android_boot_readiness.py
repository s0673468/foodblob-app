import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hosted_android_acceptance import boot_services_ready

class BootServiceReadinessTests(unittest.TestCase):
    def test_boot_completed_does_not_hide_missing_phone_service(self):
        self.assertFalse(boot_services_ready("1", {"phone": "Service phone: not found", "wifi": "Service wifi: found"}))

    def test_both_named_services_and_boot_completion_are_required(self):
        services = {"phone": "Service phone: found", "wifi": "Service wifi: found"}
        self.assertTrue(boot_services_ready("1", services))
        self.assertFalse(boot_services_ready("0", services))
        self.assertFalse(boot_services_ready("1", {"phone": services["phone"]}))
        self.assertFalse(boot_services_ready("1", {"phone": "cmd: Can't find service: phone", "wifi": services["wifi"]}))

if __name__ == "__main__": unittest.main()
