"""Keep the installed Watch identity and shared notebook across target upgrades."""
from pathlib import Path
import plistlib
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]


def plist(relative):
    with (ROOT / relative).open("rb") as stream:
        return plistlib.load(stream)


class WatchProjectTests(unittest.TestCase):
    def test_single_app_keeps_the_companion_and_watch_identity(self):
        info = plist("FoodBlobWatch/Info.plist")
        self.assertIs(info["WKApplication"], True)
        self.assertEqual(info["WKCompanionAppBundleIdentifier"], "org.example.foodblob")
        self.assertNotIn("WKWatchKitApp", info)
        self.assertNotIn("NSExtension", info)
        project = (ROOT / "FoodBlob.xcodeproj/project.pbxproj").read_text()
        targets = project.split("/* Begin PBXNativeTarget section */")[1].split(
            "/* End PBXNativeTarget section */")[0]
        watch = re.search(r"[A-F0-9]+ /\* FoodBlobWatch \*/ = \{(.*?)\n\t\t\};",
                          targets, re.S).group(1)
        self.assertIn('productType = "com.apple.product-type.application";', watch)
        self.assertNotIn("watchkit2", targets)
        self.assertEqual(project.count("PRODUCT_BUNDLE_IDENTIFIER = org.example.foodblob.watchkitapp;"), 2)
        self.assertEqual(project.count("PRODUCT_BUNDLE_IDENTIFIER = org.example.foodblob.watchkitapp.widgets;"), 2)

    def test_app_and_widgets_retain_the_existing_shared_notebook(self):
        expected = ["group.org.example.foodblob.watch"]
        for path in ("FoodBlobWatch/FoodBlobWatch.entitlements",
                     "FoodBlobWatchWidgets/FoodBlobWatchWidgets.entitlements"):
            self.assertEqual(plist(path)["com.apple.security.application-groups"], expected)
        store = (ROOT / "WatchShared/FoodWatchOutboxStore.swift").read_text()
        self.assertIn("forSecurityApplicationGroupIdentifier: FoodWatchConstants.appGroupIdentifier", store)

    def test_watch_scheme_builds_the_app_and_its_widgets(self):
        scheme = ET.parse(ROOT / "FoodBlob.xcodeproj/xcshareddata/xcschemes/FoodBlobWatch.xcscheme")
        products = {node.attrib["BuildableName"] for node in scheme.findall(".//BuildActionEntry/BuildableReference")}
        self.assertEqual(products, {"FoodBlobWatch.app", "FoodBlobWatchWidgets.appex"})
        launch = scheme.find(".//LaunchAction/BuildableProductRunnable/BuildableReference")
        self.assertEqual(launch.attrib["BuildableName"], "FoodBlobWatch.app")

    def test_iphone_archive_still_includes_the_watch_app(self):
        scheme = ET.parse(ROOT / "FoodBlob.xcodeproj/xcshareddata/xcschemes/FoodBlob.xcscheme")
        watch = [entry for entry in scheme.findall(".//BuildActionEntry")
                 if entry.find("BuildableReference").attrib["BuildableName"] == "FoodBlobWatch.app"]
        self.assertEqual(len(watch), 1)
        self.assertEqual(watch[0].attrib["buildForArchiving"], "YES")


if __name__ == "__main__":
    unittest.main()
