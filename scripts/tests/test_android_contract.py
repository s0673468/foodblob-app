import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"


class AndroidContractTests(unittest.TestCase):
    def setUp(self):
        self.manifest_path = ROOT / "android/app/src/main/AndroidManifest.xml"
        self.manifest = ET.parse(self.manifest_path).getroot()

    def test_health_export_is_signature_protected_and_query_only(self):
        permissions = {
            item.attrib[f"{ANDROID}name"]: item
            for item in self.manifest.findall("permission")
        }
        permission = permissions["org.example.foodblob.permission.READ_HEALTH_EXPORT"]
        self.assertEqual("signature", permission.attrib[f"{ANDROID}protectionLevel"])

        providers = self.manifest.find("application").findall("provider")
        self.assertEqual(1, len(providers))
        provider = providers[0]
        self.assertEqual("org.example.foodblob.health_export", provider.attrib[f"{ANDROID}authorities"])
        self.assertEqual("true", provider.attrib[f"{ANDROID}exported"])
        self.assertEqual("false", provider.attrib[f"{ANDROID}grantUriPermissions"])
        self.assertEqual(
            "org.example.foodblob.permission.READ_HEALTH_EXPORT",
            provider.attrib[f"{ANDROID}readPermission"],
        )
        self.assertNotIn(f"{ANDROID}writePermission", provider.attrib)

    def test_android_app_has_no_network_or_backup_surface(self):
        requested = {
            item.attrib[f"{ANDROID}name"]
            for item in self.manifest.findall("uses-permission")
            if item.attrib.get(f"{TOOLS}node") != "remove"
        }
        self.assertNotIn("android.permission.INTERNET", requested)
        self.assertNotIn("android.permission.ACCESS_NETWORK_STATE", requested)

        removed = {
            item.attrib[f"{ANDROID}name"]
            for item in self.manifest.findall("uses-permission")
            if item.attrib.get(f"{TOOLS}node") == "remove"
        }
        self.assertEqual(
            {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"},
            removed,
        )

        application = self.manifest.find("application")
        self.assertEqual("false", application.attrib[f"{ANDROID}allowBackup"])
        self.assertEqual("false", application.attrib[f"{ANDROID}usesCleartextTraffic"])
        self.assertEqual("@xml/backup_rules", application.attrib[f"{ANDROID}fullBackupContent"])
        self.assertEqual("@xml/data_extraction_rules", application.attrib[f"{ANDROID}dataExtractionRules"])

    def test_android_app_explicitly_keeps_predictive_back_enabled(self):
        application = self.manifest.find("application")
        self.assertEqual("true", application.attrib[f"{ANDROID}enableOnBackInvokedCallback"])

    def test_application_id_and_sdk_are_stable(self):
        build = (ROOT / "android/app/build.gradle.kts").read_text()
        self.assertRegex(build, r'applicationId\s*=\s*"org\.example\.foodblob"')
        self.assertNotIn("applicationIdSuffix", build)
        self.assertRegex(build, r'compileSdk\s*=\s*36')
        self.assertRegex(build, r'targetSdk\s*=\s*36')
        self.assertNotRegex(build.lower(), r"supabase|firebase|analytics|retrofit|okhttp")

    def test_android_release_version_is_monotonic_and_hardened(self):
        build = (ROOT / "android/app/build.gradle.kts").read_text()
        version_code = re.search(r"versionCode\s*=\s*(\d+)", build)
        self.assertIsNotNone(version_code)
        self.assertGreaterEqual(int(version_code.group(1)), 18)
        self.assertRegex(build, r'versionName\s*=\s*"1\.5\.1"')

        release = re.search(
            r"release\s*\{(?P<body>.*?)\n\s*\}",
            build,
            re.DOTALL,
        )
        self.assertIsNotNone(release)
        release_body = release.group("body")
        self.assertIn("isDebuggable = false", release_body)
        self.assertIn("isMinifyEnabled = true", release_body)
        self.assertIn("isShrinkResources = true", release_body)
        self.assertIn('signingConfigs.getByName("sharedLocal")', build)
        self.assertGreaterEqual(build.count('signingConfigs.getByName("debug")'), 2)

        benchmark = (
            ROOT
            / "android/benchmark/src/main/java/org/example/foodblob/benchmark/FoodBlobMacrobenchmark.kt"
        ).read_text()
        self.assertIn("fun shelfFrames()", benchmark)
        self.assertIn('tap("history-tab")', benchmark)
        self.assertIn('"history-title"', benchmark)

    def test_release_keeps_distinct_glance_widget_runtime_types(self):
        rules = (ROOT / "android/app/proguard-rules.pro").read_text()
        self.assertIn(
            "-keep class org.example.foodblob.widget.** extends "
            "androidx.glance.appwidget.GlanceAppWidget { *; }",
            rules,
        )
        self.assertIn(
            "-keep class * extends androidx.work.InputMerger { public <init>(); }",
            rules,
        )

    def test_both_exact_today_deep_links_are_declared(self):
        activity = self.manifest.find("application").find("activity")
        data_shapes = {
            (
                data.attrib.get(f"{ANDROID}scheme"),
                data.attrib.get(f"{ANDROID}host"),
                data.attrib.get(f"{ANDROID}ssp"),
            )
            for intent_filter in activity.findall("intent-filter")
            for data in intent_filter.findall("data")
        }
        self.assertIn(("foodblob", "today", None), data_shapes)
        self.assertIn(("foodblob", None, "///today"), data_shapes)

    def test_quick_logging_shortcuts_target_only_a_private_confirmation_activity(self):
        application = self.manifest.find("application")
        activities = {
            activity.attrib[f"{ANDROID}name"]: activity
            for activity in application.findall("activity")
        }
        quick_log = activities[".quicklog.QuickLogActivity"]
        self.assertEqual("false", quick_log.attrib[f"{ANDROID}exported"])
        self.assertEqual("false", quick_log.attrib[f"{ANDROID}directBootAware"])
        self.assertEqual("true", quick_log.attrib[f"{ANDROID}excludeFromRecents"])
        self.assertEqual([], quick_log.findall("intent-filter"))

        services = application.findall("service")
        self.assertFalse(
            any(
                service.attrib.get(f"{ANDROID}permission")
                == "android.permission.BIND_QUICK_SETTINGS_TILE"
                for service in services
            )
        )

        shortcut_source = (
            ROOT
            / "android/app/src/main/java/org/example/foodblob/quicklog/FoodBlobQuickShortcuts.kt"
        ).read_text()
        self.assertIn("ShortcutManager", shortcut_source)
        self.assertIn("QuickLogActivity::class.java", shortcut_source)
        self.assertIn("QuickLogContract.definitions.mapIndexed", shortcut_source)
        self.assertIn("dynamicShortcuts =", shortcut_source)
        self.assertNotIn("TileService", shortcut_source)

        activity_source = (
            ROOT
            / "android/app/src/main/java/org/example/foodblob/quicklog/QuickLogActivity.kt"
        ).read_text()
        self.assertIn("private fun confirmQuickLog", activity_source)
        self.assertIn("QuickLogContract.colorForIntent", activity_source)
        self.assertNotIn("intent?.getStringExtra(QuickLogContract.STATE_EVENT_ID)", activity_source)

    def test_quick_logging_is_localized_in_every_supported_language(self):
        required = {
            "shortcut_add_green",
            "shortcut_add_yellow",
            "shortcut_add_red",
            "quick_log_saving",
            "quick_log_confirm_green",
            "quick_log_confirm_yellow",
            "quick_log_confirm_red",
            "quick_log_success_green",
            "quick_log_success_yellow",
            "quick_log_success_red",
            "quick_log_already_saved",
            "quick_log_failed_title",
            "quick_log_failed_detail",
            "quick_log_done",
        }
        for resource_path in (
            ROOT / "android/app/src/main/res/values/strings.xml",
            ROOT / "android/app/src/main/res/values-pt-rBR/strings.xml",
        ):
            names = {
                item.attrib["name"]
                for item in ET.parse(resource_path).getroot()
                if "name" in item.attrib
            }
            self.assertTrue(required <= names, f"Missing from {resource_path}: {required - names}")

    def test_room_schema_is_committed_and_versioned(self):
        schema = ROOT / "android/app/schemas/org.example.foodblob.storage.FoodBlobDatabase/2.json"
        payload = json.loads(schema.read_text())
        self.assertEqual(2, payload["database"]["version"])
        tables = {entity["tableName"] for entity in payload["database"]["entities"]}
        self.assertEqual(
            {"days", "settings", "undo_actions", "widget_events", "consumed_widget_events"},
            tables,
        )
        database_source = (ROOT / "android/app/src/main/java/org/example/foodblob/storage/FoodBlobDatabase.kt").read_text()
        self.assertNotIn("fallbackToDestructiveMigration", database_source)
        self.assertIn("MIGRATION_1_2", database_source)
        self.assertIn("widget_epoch", database_source)

        store_source = (ROOT / "android/app/src/main/java/org/example/foodblob/storage/FoodStore.kt").read_text()
        recovery = store_source[store_source.index("private fun createServices"):]
        recovery = recovery[:recovery.index("private fun quarantineDatabase")]
        self.assertIn('rawQuery("PRAGMA quick_check", null)', recovery)
        self.assertIn("catch (_: SQLiteDatabaseCorruptException)", recovery)
        self.assertIn("catch (_: VerifiedDatabaseCorruptionException)", recovery)
        self.assertIn("DatabaseErrorHandler", store_source)
        self.assertNotIn("catch (_: RuntimeException)", recovery)

    def test_device_validation_preserves_installed_app_data(self):
        build = (ROOT / "android/app/build.gradle.kts").read_text()
        self.assertNotIn('testInstrumentationRunnerArguments["clearPackageData"] = "true"', build)

        device_runner = (ROOT / "scripts/android_device_test.sh").read_text()
        self.assertIn('install -r -t "$app_apk"', device_runner)
        self.assertIn('install -r -t "$test_apk"', device_runner)
        self.assertIn("am instrument", device_runner)
        self.assertIn("clearPackageData false", device_runner)
        self.assertNotIn("uninstall org.example.foodblob\n", device_runner)

        docs = (ROOT / "docs/android.md").read_text()
        self.assertIn("scripts/android_device_test.sh <adb-serial>", docs)
        self.assertNotIn("scripts/android_gradle.sh :app:connectedDebugAndroidTest", docs)

        release_installer = (ROOT / "scripts/android_release_install.sh").read_text()
        self.assertIn("getprop ro.build.version.sdk", release_installer)
        self.assertIn('--max-sdk-version "$device_sdk"', release_installer)
        self.assertIn('"$compatibility_mode"', release_installer)
        self.assertIn('"$device_sdk"', release_installer)
        signer_compatibility = (ROOT / "scripts/ApkSignatureCompatibility.java").read_text()
        self.assertIn("setMaxCheckedPlatformVersion(targetApi)", signer_compatibility)

        benchmark = (
            ROOT
            / "android/benchmark/src/main/java/org/example/foodblob/benchmark/FoodBlobMacrobenchmark.kt"
        ).read_text()
        frame_test = re.search(
            r"fun loggingAndNavigationFrames\(\).*?private fun MacrobenchmarkScope\.prepareOnboarding",
            benchmark,
            flags=re.DOTALL,
        )
        self.assertIsNotNone(frame_test)
        self.assertEqual(2, frame_test.group(0).count('tap("undo")'))

    def test_widget_actions_rotate_pending_intent_identity_and_keep_legacy_retry_ids(self):
        widgets = (
            ROOT
            / "android/app/src/main/java/org/example/foodblob/widget/FoodBlobWidgets.kt"
        ).read_text()
        self.assertIn('ActionParameters.Key<String>("event_id")', widgets)
        self.assertIn("parameters[eventIdKey]", widgets)
        self.assertNotIn("id = FoodWidgetContract.newEventId()", widgets)
        self.assertIn("remember(color, delta, revision)", widgets)
        action_adapter = widgets[
            widgets.index("private fun WidgetAction("):
            widgets.index("private fun OpenRegion(")
        ]
        self.assertIn("ActionRegion(", action_adapter)
        self.assertIn("revision = revision", action_adapter)
        self.assertGreaterEqual(widgets.count("WidgetAction(counts"), 6)

        callback = widgets[
            widgets.index("class ChangeFoodCountWidgetAction"):
            widgets.index("internal object FoodBlobWidgetUpdater")
        ]
        self.assertIn("FoodBlobWidgetUpdater.updateAfterAction(context, glanceId, variant)", callback)
        self.assertIn("FoodBlobWidgetUpdater.updateAll(context)", callback)
        variant_parse = callback[
            callback.index("val variant = parameters[variantKey]"):
            callback.index("if (!FoodWidgetContract.isSupportedDelta")
        ]
        self.assertNotIn("?: return", variant_parse)
        self.assertNotIn("if (!inserted)", callback)

        action_receiver = next(
            receiver
            for receiver in self.manifest.find("application").findall("receiver")
            if receiver.attrib[f"{ANDROID}name"] == ".widget.FoodBlobWidgetActionReceiver"
        )
        self.assertEqual("false", action_receiver.attrib[f"{ANDROID}exported"])
        self.assertIn("actionSendBroadcast(actionIntent)", widgets)
        self.assertIn("FoodWidgetActionIntent.uri(UUID.fromString(eventId))", widgets)
        self.assertIn("class FoodBlobWidgetActionReceiver : BroadcastReceiver()", widgets)

        updater = widgets[
            widgets.index("internal object FoodBlobWidgetUpdater"):
            widgets.index("internal class SkyMeadowSmallWidget")
        ]
        self.assertLess(updater.index("primary.update(applicationContext, glanceId)"), updater.index("primary.updateAll"))

    def test_widget_composition_observes_committed_store_and_explicit_refreshes(self):
        widgets = (
            ROOT
            / "android/app/src/main/java/org/example/foodblob/widget/FoodBlobWidgets.kt"
        ).read_text()
        provider = widgets[
            widgets.index("internal abstract class FoodBlobWidget"):
            widgets.index("@Composable\nprivate fun FoodBlobWidgetContent")
        ]
        self.assertIn("store.snapshots.collectAsState", provider)
        self.assertIn("FoodBlobWidgetRefreshSignal.ticks.collectAsState", provider)
        self.assertIn("FoodBlobWidgetRefreshSignal.advance()", widgets)
        self.assertNotIn("val state = loadState(context)", provider)

    def test_onboarding_and_shrine_streak_keep_accessible_ios_identity(self):
        app = (
            ROOT
            / "android/app/src/main/java/org/example/foodblob/ui/FoodBlobApp.kt"
        ).read_text()
        onboarding = app[app.index("private fun Onboarding"):app.index("private fun FoodColor.labelId")]
        self.assertIn("FoodBlobTheme(SkinId.SKY_MEADOW)", onboarding)
        self.assertIn("WorldBackground(SkinId.SKY_MEADOW", onboarding)
        self.assertIn("!state.onboardingComplete || state.selectedSkin == SkinId.SKY_MEADOW", app)

        streak = app[app.index("private fun ShrineStreakPill"):app.index("private fun BlobTotal")]
        self.assertIn("pluralStringResource(R.plurals.streak_days", streak)
        self.assertIn("clearAndSetSemantics", streak)

    def test_android_launcher_uses_the_exact_ios_mascot_art(self):
        ios_icon = ROOT / "FoodBlob/Assets.xcassets/AppIcon.appiconset/FoodBlobIcon-v3.png"
        android_icon = ROOT / "android/app/src/main/res/drawable-nodpi/foodblob_icon.png"
        self.assertEqual(ios_icon.read_bytes(), android_icon.read_bytes())

        foreground = (
            ROOT / "android/app/src/main/res/drawable/ic_launcher_foreground.xml"
        ).read_text()
        self.assertIn('@drawable/foodblob_icon', foreground)

    def test_in_app_widget_previews_use_the_runtime_renderer(self):
        app = (
            ROOT / "android/app/src/main/java/org/example/foodblob/ui/FoodBlobApp.kt"
        ).read_text()
        preview = app[
            app.index("private fun WidgetPreviewCard"):
            app.index("private fun Onboarding")
        ]
        self.assertIn("FoodWidgetArtworkRenderer.render", preview)
        self.assertIn("FoodWidgetGeometry.resolve", preview)
        self.assertNotIn("LivingFoodBlob(", preview)

    def test_device_ui_acceptance_restores_local_state_exactly(self):
        smoke = (
            ROOT
            / "android/app/src/androidTest/java/org/example/foodblob/ui/FoodBlobComposeSmokeTest.kt"
        ).read_text()
        self.assertIn("originalDay", smoke)
        self.assertIn("deleteUndoAfter(originalLatestUndoId)", smoke)
        self.assertIn("upsertSettings(originalSettings)", smoke)
        self.assertIn("originalOnboardingPreferenceExists", smoke)
        self.assertIn("editor.commit()", smoke)

    def test_benchmark_uses_the_supported_local_signer_when_present(self):
        build = (ROOT / "android/app/build.gradle.kts").read_text()
        benchmark_block = re.search(
            r'create\("benchmark"\) \{.*?\n\s*\}',
            build,
            flags=re.DOTALL,
        )
        self.assertIsNotNone(benchmark_block)
        self.assertIn("localSigningFile.isFile", benchmark_block.group(0))
        self.assertIn('signingConfigs.getByName("sharedLocal")', benchmark_block.group(0))

    def test_release_install_is_signer_version_and_data_continuity_guarded(self):
        installer = (ROOT / "scripts/android_release_install.sh").read_text()
        self.assertIn('install -r "$release_apk"', installer)
        self.assertNotIn(" uninstall ", installer)
        self.assertNotIn(" pm clear ", installer)
        self.assertIn("built_version_code < installed_version_code", installer)
        self.assertIn("ApkSignatureCompatibility.java", installer)
        self.assertIn("apksigner.jar", installer)
        self.assertIn("FOODBLOB_EXPECTED_SIGNER_SHA256", installer)
        self.assertIn('shell pm path "$package_name" 2>/dev/null || true', installer)
        self.assertIn(
            'verify_installed_anchor "$app_id" "installed Food Blob" "installed-data"',
            installer,
        )
        self.assertIn(
            'verify_installed_anchor "$track_id" "installed Track" "signature-permission"',
            installer,
        )
        self.assertIn("No trusted Food Blob signing anchor is available", installer)
        self.assertNotIn("${built_digest,,}", installer)
        self.assertIn("firstInstallTime", installer)
        self.assertIn("DEBUGGABLE", installer)
        self.assertIn("apksigner", installer)

        lineage_verifier = (ROOT / "scripts/ApkSignatureCompatibility.java").read_text()
        self.assertIn("getSigningCertificateLineage", lineage_verifier)
        self.assertIn("hasPermission()", lineage_verifier)
        self.assertIn("hasInstalledData()", lineage_verifier)
        self.assertIn("hasRollback()", lineage_verifier)
        self.assertIn("ownerCurrent.size() != 1", lineage_verifier)
        self.assertNotIn("certificate SHA-256", lineage_verifier)

    def test_android_resources_keep_the_full_phone_experience(self):
        required = {
            "onboarding_skip",
            "onboarding_green_detail",
            "onboarding_yellow_detail",
            "onboarding_red_detail",
            "onboarding_reflection_note",
            "skin_preservation_title",
            "skin_preservation_detail",
            "settings_disclaimer",
            "settings_import",
            "settings_restore_previous",
            "import_preview_title",
            "import_replace",
            "restore_previous_title",
            "restore_previous_action",
            "privacy_no_account_title",
            "privacy_no_network_title",
            "privacy_export_title",
            "widget_setup_step_one_title",
            "widget_setup_step_two_title",
            "widget_setup_step_three_title",
        }
        for resource_path in (
            ROOT / "android/app/src/main/res/values/strings.xml",
            ROOT / "android/app/src/main/res/values-pt-rBR/strings.xml",
        ):
            names = {
                item.attrib["name"]
                for item in ET.parse(resource_path).getroot()
                if "name" in item.attrib
            }
            self.assertTrue(required <= names, f"Missing from {resource_path}: {required - names}")

    def test_android_launcher_icon_preserves_the_ios_mascot_in_an_adaptive_safe_zone(self):
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            adaptive = ET.parse(
                ROOT / "android/app/src/main/res/mipmap-anydpi-v26" / name
            ).getroot()
            self.assertEqual(
                "@drawable/ic_launcher_background",
                adaptive.find("background").attrib[f"{ANDROID}drawable"],
            )
            self.assertEqual(
                "@drawable/ic_launcher_foreground",
                adaptive.find("foreground").attrib[f"{ANDROID}drawable"],
            )
            self.assertEqual(
                "@drawable/ic_launcher_monochrome",
                adaptive.find("monochrome").attrib[f"{ANDROID}drawable"],
            )

        foreground = ET.parse(
            ROOT / "android/app/src/main/res/drawable/ic_launcher_foreground.xml"
        ).getroot()
        self.assertEqual("bitmap", foreground.tag)
        self.assertEqual("@drawable/foodblob_icon", foreground.attrib[f"{ANDROID}src"])
        self.assertEqual("fill", foreground.attrib[f"{ANDROID}gravity"])

        background = ET.parse(
            ROOT / "android/app/src/main/res/drawable/ic_launcher_background.xml"
        ).getroot()
        gradient = background.find("gradient")
        self.assertEqual("#A6DDEE", gradient.attrib[f"{ANDROID}startColor"].upper())
        self.assertEqual("#FFD4A0", gradient.attrib[f"{ANDROID}endColor"].upper())

    def test_only_four_fixed_skin_widget_receivers_are_registered(self):
        receivers = [
            receiver
            for receiver in self.manifest.find("application").findall("receiver")
            if receiver.find("meta-data") is not None
        ]
        names = {receiver.attrib[f"{ANDROID}name"] for receiver in receivers}
        self.assertEqual(
            {
                ".widget.SkyMeadowSmallWidgetReceiver",
                ".widget.ShrineSmallWidgetReceiver",
                ".widget.SkyMeadowMediumWidgetReceiver",
                ".widget.ShrineMediumWidgetReceiver",
            },
            names,
        )
        self.assertTrue(all(receiver.attrib[f"{ANDROID}exported"] == "false" for receiver in receivers))

    def test_widget_day_rollover_uses_a_private_scheduled_receiver(self):
        permissions = {
            item.attrib[f"{ANDROID}name"]
            for item in self.manifest.findall("uses-permission")
        }
        self.assertIn("android.permission.RECEIVE_BOOT_COMPLETED", permissions)

        receivers = {
            receiver.attrib[f"{ANDROID}name"]: receiver
            for receiver in self.manifest.find("application").findall("receiver")
        }
        rollover = receivers[".widget.WidgetDayRolloverReceiver"]
        self.assertEqual("false", rollover.attrib[f"{ANDROID}exported"])
        actions = {
            action.attrib[f"{ANDROID}name"]
            for action in rollover.find("intent-filter").findall("action")
        }
        self.assertEqual(
            {
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.TIME_SET",
                "android.intent.action.TIMEZONE_CHANGED",
            },
            actions,
        )

        for name, receiver in receivers.items():
            if name in {
                ".widget.WidgetDayRolloverReceiver",
                ".widget.FoodBlobWidgetActionReceiver",
            }:
                continue
            widget_actions = {
                action.attrib[f"{ANDROID}name"]
                for action in receiver.find("intent-filter").findall("action")
            }
            self.assertEqual({"android.appwidget.action.APPWIDGET_UPDATE"}, widget_actions)

    def test_widget_metadata_has_the_platform_minimum_refresh_fallback(self):
        widget_metadata = ROOT / "android/app/src/main/res/xml"
        for path in (
            widget_metadata / "widget_small_info.xml",
            widget_metadata / "widget_small_shrine_info.xml",
            widget_metadata / "widget_medium_info.xml",
            widget_metadata / "widget_medium_shrine_info.xml",
        ):
            provider = ET.parse(path).getroot()
            self.assertEqual("1800000", provider.attrib[f"{ANDROID}updatePeriodMillis"], path)

    def test_widget_metadata_uses_android_native_default_cells(self):
        widget_metadata = ROOT / "android/app/src/main/res/xml"
        expected = {
            "widget_small_info.xml": ("2", "2", "152dp", "156dp", "108dp", "48dp"),
            "widget_small_shrine_info.xml": ("2", "2", "152dp", "156dp", "108dp", "48dp"),
            "widget_medium_info.xml": ("4", "2", "250dp", "168dp", "250dp", "108dp"),
            "widget_medium_shrine_info.xml": ("4", "2", "250dp", "168dp", "250dp", "108dp"),
        }
        attributes = (
            "targetCellWidth",
            "targetCellHeight",
            "minWidth",
            "minHeight",
            "minResizeWidth",
            "minResizeHeight",
        )
        for name, values in expected.items():
            provider = ET.parse(widget_metadata / name).getroot()
            if "medium" in name:
                # Pixel Launcher intersects resize bounds across portrait and
                # landscape. A fixed dp width cap can reject the requested four
                # cells and silently substitute a tall three-by-three widget.
                self.assertNotIn(f"{ANDROID}maxResizeWidth", provider.attrib, name)
            self.assertEqual(
                values,
                tuple(provider.attrib[f"{ANDROID}{attribute}"] for attribute in attributes),
                name,
            )

    def test_fixed_skin_widgets_have_distinct_remote_views_safe_previews(self):
        receivers = {
            receiver.attrib[f"{ANDROID}name"]: receiver
            for receiver in self.manifest.find("application").findall("receiver")
            if receiver.find("meta-data") is not None
        }
        provider_resources = {}
        for name, receiver in receivers.items():
            metadata = receiver.find("meta-data")
            provider_resources[name] = metadata.attrib[f"{ANDROID}resource"]

        self.assertEqual("@xml/widget_small_info", provider_resources[".widget.SkyMeadowSmallWidgetReceiver"])
        self.assertEqual("@xml/widget_small_shrine_info", provider_resources[".widget.ShrineSmallWidgetReceiver"])
        self.assertEqual("@xml/widget_medium_info", provider_resources[".widget.SkyMeadowMediumWidgetReceiver"])
        self.assertEqual("@xml/widget_medium_shrine_info", provider_resources[".widget.ShrineMediumWidgetReceiver"])

        for layout in (ROOT / "android/app/src/main/res/layout").glob("widget_preview*.xml"):
            root = ET.parse(layout).getroot()
            self.assertFalse(any(element.tag == "Space" for element in root.iter()), layout)

        shrine_world = (
            ROOT / "android/app/src/main/res/drawable/widget_preview_shrine_world.xml"
        ).read_text()
        self.assertIn("#0A1421", shrine_world)
        self.assertIn("#12293B", shrine_world)
        self.assertIn("#0D1726", shrine_world)
        self.assertNotIn("#301930", shrine_world)


if __name__ == "__main__":
    unittest.main()
