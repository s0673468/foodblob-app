#!/usr/bin/env ruby
# frozen_string_literal: true

require "fileutils"
require "digest"
require "xcodeproj"

ROOT = File.expand_path("..", __dir__)
PROJECT_PATH = File.join(ROOT, "FoodBlob.xcodeproj")
TEAM_ID = ENV.fetch("FOODBLOB_DEVELOPMENT_TEAM", "")

class DeterministicProject < Xcodeproj::Project
  def initialize(*arguments, &block)
    @food_blob_uuid_index = 0
    super
  end

  def generate_available_uuid_list(count = 100)
    new_uuids = (0..count).map do
      @food_blob_uuid_index += 1
      Digest::SHA256
        .hexdigest("food-blob-xcodeproj-#{@food_blob_uuid_index}")
        .slice(0, 24)
        .upcase
    end
    uniques = new_uuids - (@generated_uuids + uuids)
    @generated_uuids += uniques
    @available_uuids += uniques
  end
end

FileUtils.rm_rf(PROJECT_PATH)
project = DeterministicProject.new(PROJECT_PATH)
project.root_object.attributes["LastSwiftUpdateCheck"] = "2660"
project.root_object.attributes["LastUpgradeCheck"] = "2660"

app = project.new_target(:application, "FoodBlob", :ios, "17.0")
widgets = project.new_target(:app_extension, "FoodBlobWidgets", :ios, "17.0")
app_tests = project.new_target(:unit_test_bundle, "FoodBlobTests", :ios, "17.0")
widget_tests = project.new_target(
  :unit_test_bundle,
  "FoodBlobWidgetTests",
  :ios,
  "17.0"
)
watch_app = project.new_target(:application, "FoodBlobWatch", :watchos, "10.0")
watch_widgets = project.new_target(
  :app_extension,
  "FoodBlobWatchWidgets",
  :watchos,
  "10.0"
)

app.product_reference.path = "Food Blob.app"
watch_app.product_reference.path = "FoodBlobWatch.app"

app.add_dependency(widgets)
app_tests.add_dependency(app)
widget_tests.add_dependency(widgets)
widget_tests.add_dependency(app)
app.add_dependency(watch_app)
watch_dependency = app.dependency_for_target(watch_app)
watch_dependency.platform_filters = ["watchos"]
watch_app.add_dependency(watch_widgets)

embed_extensions = app.new_copy_files_build_phase("Embed App Extensions")
embed_extensions.dst_subfolder_spec = "13"
embedded_widgets = embed_extensions.add_file_reference(
  widgets.product_reference,
  true
)
embedded_widgets.settings = {
  "ATTRIBUTES" => ["CodeSignOnCopy", "RemoveHeadersOnCopy"],
}

embed_watch_content = app.new_copy_files_build_phase("Embed Watch Content")
embed_watch_content.dst_path = "$(CONTENTS_FOLDER_PATH)/Watch"
embed_watch_content.dst_subfolder_spec = "16"
embed_watch_content.run_only_for_deployment_postprocessing = "1"
embedded_watch_app = embed_watch_content.add_file_reference(
  watch_app.product_reference,
  true
)
embedded_watch_app.settings = {
  "ATTRIBUTES" => ["CodeSignOnCopy", "RemoveHeadersOnCopy"],
}

embed_watch_extensions = watch_app.new_copy_files_build_phase(
  "Embed Watch Extensions"
)
embed_watch_extensions.dst_subfolder_spec = "13"
[watch_widgets].each do |target|
  embedded = embed_watch_extensions.add_file_reference(
    target.product_reference,
    true
  )
  embedded.settings = {
    "ATTRIBUTES" => ["CodeSignOnCopy", "RemoveHeadersOnCopy"],
  }
end

def add_swift_sources(
  project,
  group_name,
  relative_path,
  targets,
  excluded_target_sources: {},
  only: nil
)
  root = project.main_group.new_group(group_name, relative_path)
  Dir.glob(File.join(ROOT, relative_path, "**", "*.swift")).sort.each do |path|
    relative = path.delete_prefix("#{File.join(ROOT, relative_path)}/")
    next if only && !only.include?(relative)
    group = relative.split("/")[0...-1].reduce(root) do |parent, component|
      parent.groups.find { |candidate| candidate.display_name == component } ||
        parent.new_group(component, component)
    end
    file = group.new_file(File.basename(relative))
    targets.each do |target|
      excluded = excluded_target_sources.fetch(target.name, [])
      target.add_file_references([file]) unless excluded.include?(relative)
    end
  end
  root
end

app_group = add_swift_sources(project, "FoodBlob", "FoodBlob", [app])
add_swift_sources(
  project,
  "Shared",
  "Shared",
  [app, widgets],
  excluded_target_sources: {
    "FoodBlobWidgets" => [
      "FoodStore.swift",
      "FoodWatchAcknowledgementDeliveryTracker.swift",
    ],
  }
)
watch_shared_sources = [
  "BlobColor.swift",
  "BlobMaterial.swift",
  "BlobColor+SwiftUI.swift",
  "FoodDateKey.swift",
  "FoodModels.swift",
  "WidgetLayoutID.swift",
  "FoodWidgetLedger.swift",
  "FoodWatchTransferContract.swift",
  "LivingBlob.swift",
]
add_swift_sources(
  project,
  "Shared Watch Sources",
  "Shared",
  [watch_app, watch_widgets],
  only: watch_shared_sources
)
add_swift_sources(
  project,
  "WatchShared",
  "WatchShared",
  [watch_app, watch_widgets, widget_tests],
  excluded_target_sources: {
    "FoodBlobWatchWidgets" => [
      "FoodWatchAcknowledgementApplier.swift",
      "FoodWatchIncomingDeliveryTracker.swift",
    ],
  }
)
widgets_group = add_swift_sources(
  project,
  "FoodBlobWidgets",
  "FoodBlobWidgets",
  [widgets]
)
add_swift_sources(project, "FoodBlobTests", "FoodBlobTests", [app_tests])
add_swift_sources(
  project,
  "FoodBlobWidgetTests",
  "FoodBlobWidgetTests",
  [widget_tests]
)
add_swift_sources(
  project,
  "FoodBlobWatchExtension",
  "FoodBlobWatchExtension",
  [watch_app]
)
add_swift_sources(
  project,
  "FoodBlobWatchWidgets",
  "FoodBlobWatchWidgets",
  [watch_widgets]
)
watch_group = project.main_group.new_group("FoodBlobWatch", "FoodBlobWatch")
watch_assets = watch_group.new_file("Assets.xcassets")
watch_app.resources_build_phase.add_file_reference(watch_assets, true)

assets = app_group.new_file("Assets.xcassets")
privacy = app_group.new_file("PrivacyInfo.xcprivacy")
app.resources_build_phase.add_file_reference(assets, true)
app.resources_build_phase.add_file_reference(privacy, true)
widgets.resources_build_phase.add_file_reference(privacy, true)

def common_settings(target, bundle_id)
  target.build_configurations.each do |configuration|
    configuration.build_settings.merge!(
      "CODE_SIGN_STYLE" => "Automatic",
      "CURRENT_PROJECT_VERSION" => "1",
      "DEVELOPMENT_TEAM" => TEAM_ID,
      "IPHONEOS_DEPLOYMENT_TARGET" => "17.0",
      "MARKETING_VERSION" => "1.5.1",
      "PRODUCT_BUNDLE_IDENTIFIER" => bundle_id,
      "SWIFT_VERSION" => "5.0",
      "TARGETED_DEVICE_FAMILY" => "1",
      "VERSIONING_SYSTEM" => "apple-generic"
    )
  end
end

def common_watch_settings(target, bundle_id)
  target.build_configurations.each do |configuration|
    configuration.build_settings.merge!(
      "CODE_SIGN_STYLE" => "Automatic",
      "CURRENT_PROJECT_VERSION" => "1",
      "DEVELOPMENT_TEAM" => TEAM_ID,
      "MARKETING_VERSION" => "1.5.1",
      "PRODUCT_BUNDLE_IDENTIFIER" => bundle_id,
      "SDKROOT" => "watchos",
      "SUPPORTED_PLATFORMS" => "watchos watchsimulator",
      "SWIFT_VERSION" => "5.0",
      "TARGETED_DEVICE_FAMILY" => "4",
      "VERSIONING_SYSTEM" => "apple-generic",
      "WATCHOS_DEPLOYMENT_TARGET" => "10.0"
    )
  end
end

common_settings(app, "org.example.foodblob")
common_settings(widgets, "org.example.foodblob.widgets")
common_settings(app_tests, "org.example.foodblob.tests")
common_settings(widget_tests, "org.example.foodblob.widgettests")
common_watch_settings(watch_app, "org.example.foodblob.watchkitapp")
common_watch_settings(
  watch_widgets,
  "org.example.foodblob.watchkitapp.widgets"
)

app.build_configurations.each do |configuration|
  configuration.build_settings.merge!(
    "ASSETCATALOG_COMPILER_APPICON_NAME" => "AppIcon",
    "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME" => "AccentColor",
    "CODE_SIGN_ENTITLEMENTS" => "FoodBlob/FoodBlob.entitlements",
    "GENERATE_INFOPLIST_FILE" => "NO",
    "INFOPLIST_FILE" => "FoodBlob/Info.plist",
    "PRODUCT_MODULE_NAME" => "FoodBlob",
    "PRODUCT_NAME" => "Food Blob",
    "SUPPORTED_PLATFORMS" => "iphoneos iphonesimulator"
  )
end

widgets.build_configurations.each do |configuration|
  configuration.build_settings.merge!(
    "APPLICATION_EXTENSION_API_ONLY" => "YES",
    "CODE_SIGN_ENTITLEMENTS" =>
      "FoodBlobWidgets/FoodBlobWidgets.entitlements",
    "GENERATE_INFOPLIST_FILE" => "NO",
    "INFOPLIST_FILE" => "FoodBlobWidgets/Info.plist",
    "PRODUCT_NAME" => "FoodBlobWidgets",
    "SKIP_INSTALL" => "YES",
    "SUPPORTED_PLATFORMS" => "iphoneos iphonesimulator"
  )
end

watch_app.build_configurations.each do |configuration|
  configuration.build_settings.merge!(
    "ASSETCATALOG_COMPILER_APPICON_NAME" => "AppIcon",
    "CODE_SIGN_ENTITLEMENTS" => "FoodBlobWatch/FoodBlobWatch.entitlements",
    "GENERATE_INFOPLIST_FILE" => "NO",
    "INFOPLIST_FILE" => "FoodBlobWatch/Info.plist",
    "PRODUCT_NAME" => "FoodBlobWatch",
    "SKIP_INSTALL" => "YES",
    "SUPPORTED_PLATFORMS" => "watchos watchsimulator"
  )
end

watch_widgets.build_configurations.each do |configuration|
  configuration.build_settings.merge!(
    "APPLICATION_EXTENSION_API_ONLY" => "YES",
    "CODE_SIGN_ENTITLEMENTS" =>
      "FoodBlobWatchWidgets/FoodBlobWatchWidgets.entitlements",
    "GENERATE_INFOPLIST_FILE" => "NO",
    "INFOPLIST_FILE" => "FoodBlobWatchWidgets/Info.plist",
    "PRODUCT_NAME" => "FoodBlobWatchWidgets",
    "SKIP_INSTALL" => "YES",
    "SUPPORTED_PLATFORMS" => "watchos watchsimulator"
  )
end

[app_tests, widget_tests].each do |target|
  target.build_configurations.each do |configuration|
    configuration.build_settings.merge!(
      "BUNDLE_LOADER" => "$(TEST_HOST)",
      "GENERATE_INFOPLIST_FILE" => "YES",
      "PRODUCT_NAME" => "$(TARGET_NAME)",
      "TEST_HOST" =>
        "$(BUILT_PRODUCTS_DIR)/Food Blob.app/Food Blob",
      "SUPPORTED_PLATFORMS" => "iphonesimulator"
    )
  end
end

widget_tests.build_configurations.each do |configuration|
  configuration.build_settings[
    "SWIFT_ACTIVE_COMPILATION_CONDITIONS"
  ] = "$(inherited) FOOD_BLOB_TESTS"
end

project.build_configurations.each do |configuration|
  configuration.build_settings.merge!(
    "CLANG_ANALYZER_LOCALIZABILITY_NONLOCALIZED" => "YES",
    "ENABLE_USER_SCRIPT_SANDBOXING" => "YES",
    "IPHONEOS_DEPLOYMENT_TARGET" => "17.0",
    "WATCHOS_DEPLOYMENT_TARGET" => "10.0",
    "SWIFT_TREAT_WARNINGS_AS_ERRORS" => "YES"
  )
end

# Keep the allocated dependency identifiers stable for the generated project,
# but let the archive scheme own the cross-platform Watch build ordering.
watch_dependency_proxy = watch_dependency.target_proxy
app.dependencies.delete(watch_dependency)
project.objects_by_uuid.delete(watch_dependency.uuid)
project.objects_by_uuid.delete(watch_dependency_proxy.uuid)

# Compile app-only jelly materials into default.metallib for SwiftUI ShaderLibrary.
# Widgets retain their CPU snapshot renderer and do not need a live GPU surface.
Dir.glob(File.join(ROOT, "FoodBlob", "Design", "*.metal")).sort.each do |path|
  file = project.main_group.new_file(path.delete_prefix("#{ROOT}/"))
  app.add_file_references([file])
end

# Explicit native gesture acceptance lives in its own scheme, so the normal
# unit-test gate never starts a session that taps a user's simulator data.
acceptance_tests = project.new_target(:ui_test_bundle, "FoodBlobUITests", :ios, "17.0")
acceptance_tests.add_dependency(app)
add_swift_sources(project, "FoodBlobUITests", "FoodBlobUITests", [acceptance_tests])
common_settings(acceptance_tests, "org.example.foodblob.uitests")
acceptance_tests.build_configurations.each do |configuration|
  configuration.build_settings.merge!(
    "GENERATE_INFOPLIST_FILE" => "YES",
    "TEST_TARGET_NAME" => "FoodBlob",
    "SUPPORTED_PLATFORMS" => "iphoneos iphonesimulator"
  )
end
project.root_object.attributes["TargetAttributes"] ||= {}
project.root_object.attributes["TargetAttributes"][acceptance_tests.uuid] = { "TestTargetID" => app.uuid }

project.save

scheme = Xcodeproj::XCScheme.new
scheme.build_action.parallelize_buildables = false
watch_archive_entry = Xcodeproj::XCScheme::BuildAction::Entry.new(watch_app)
watch_archive_entry.build_for_testing = false
watch_archive_entry.build_for_running = false
watch_archive_entry.build_for_profiling = false
watch_archive_entry.build_for_archiving = true
watch_archive_entry.build_for_analyzing = false
scheme.build_action.add_entry(watch_archive_entry)
scheme.add_build_target(app)
scheme.add_build_target(widgets)
scheme.add_test_target(app_tests)
scheme.add_test_target(widget_tests)
scheme.set_launch_target(app)
scheme.save_as(PROJECT_PATH, "FoodBlob", true)

watch_scheme = Xcodeproj::XCScheme.new
watch_scheme.add_build_target(watch_app)
watch_scheme.add_build_target(watch_widgets)
watch_scheme.set_launch_target(watch_app)
watch_scheme.save_as(PROJECT_PATH, "FoodBlobWatch", true)

acceptance_scheme = Xcodeproj::XCScheme.new
acceptance_scheme.build_action.parallelize_buildables = false
acceptance_scheme.add_build_target(app)
acceptance_scheme.add_build_target(widgets)
acceptance_scheme.add_test_target(acceptance_tests)
acceptance_scheme.set_launch_target(app)
acceptance_scheme.test_action.should_use_launch_scheme_args_env = false
acceptance_scheme.test_action.environment_variables = Xcodeproj::XCScheme::EnvironmentVariables.new(
  [{ key: "FOODBLOB_JELLY_ACCEPTANCE", value: "1" }]
)
acceptance_scheme.save_as(PROJECT_PATH, "FoodBlobAcceptance", true)

puts "Generated #{PROJECT_PATH}"
