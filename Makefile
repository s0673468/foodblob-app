SHELL := /bin/bash

PROJECT := FoodBlob.xcodeproj
SCHEME := FoodBlob
DESTINATION ?= platform=iOS Simulator,name=iPhone 17 Pro,OS=latest
XCODEBUILD ?= xcodebuild
CHECK_BASE ?= origin/main

.PHONY: project project-check release-tests workflow-lint ios-test watch-build apple-check android-unit android-build-check android-check ios-acceptance android-device-test check-affected check-affected-run perf-benchmark check check-ci

project:
	ruby tools/generate_project.rb

project-check: project
	git diff --exit-code -- $(PROJECT)

release-tests:
	PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
		-s scripts/tests -p 'test_*.py' -v

workflow-lint:
	ruby -e 'require "yaml"; ARGV.each { |path| YAML.parse_file(path) }' \
		.github/workflows/*.yml
	@! rg -n 'actions/checkout@(v[0-5]|v7|main|master)' \
		.github/workflows

ios-test:
	$(XCODEBUILD) \
		-project $(PROJECT) \
		-scheme $(SCHEME) \
		-configuration Debug \
		-destination '$(DESTINATION)' \
		CODE_SIGNING_ALLOWED=NO \
		test

watch-build:
	$(XCODEBUILD) \
		-project $(PROJECT) \
		-scheme FoodBlobWatch \
		-configuration Debug \
		-sdk watchsimulator \
		-destination 'generic/platform=watchOS Simulator' \
		CODE_SIGNING_ALLOWED=NO \
		build

# Iteration entry points. android-check below keeps one Gradle invocation for the full gate.
apple-check: ios-test watch-build

android-unit:
	scripts/android_gradle.sh :app:testDebugUnitTest :app:lintDebug

android-build-check:
	scripts/android_gradle.sh \
		:app:assembleDebug \
		:app:assembleRelease \
		:app:assembleBenchmark \
		:app:assembleDebugAndroidTest \
		:benchmark:assemble

# Builds device/benchmark tests; it does not execute them.
android-check:
	scripts/android_gradle.sh \
		:app:testDebugUnitTest \
		:app:lintDebug \
		:app:assembleDebug \
		:app:assembleRelease \
		:app:assembleBenchmark \
		:app:assembleDebugAndroidTest \
		:benchmark:assemble

# Requires a caller-owned synthetic simulator with onboarding already completed.
ios-acceptance:
	@test -n "$(ACCEPTANCE_SIMULATOR_ID)" || { echo 'Set ACCEPTANCE_SIMULATOR_ID to an owned synthetic simulator.' >&2; exit 2; }
	$(XCODEBUILD) -project $(PROJECT) -scheme FoodBlobAcceptance \
		-destination 'platform=iOS Simulator,id=$(ACCEPTANCE_SIMULATOR_ID)' \
		CODE_SIGN_IDENTITY=- test

# See docs/android.md for personal-device preservation and isolated-emulator flags.
android-device-test:
	@test -n "$(DEVICE_SERIAL)" || { echo 'Set DEVICE_SERIAL explicitly.' >&2; exit 2; }
	scripts/android_device_test.sh '$(DEVICE_SERIAL)' '$(TEST_SELECTOR)'

# Planning is read-only; execution requires one explicitly chosen, admitted host lane.
check-affected:
	PYTHONDONTWRITEBYTECODE=1 python3 scripts/affected_checks.py --base '$(CHECK_BASE)' --plan

check-affected-run:
	PYTHONDONTWRITEBYTECODE=1 python3 scripts/affected_checks.py --base '$(CHECK_BASE)' --execute --platform '$(PLATFORM)'

perf-benchmark:
	scripts/run_performance_benchmarks.sh $(PERF_ARGS)

check: release-tests workflow-lint project-check ios-test watch-build android-check

# CI validates the committed project without requiring the local generator.
check-ci: release-tests workflow-lint ios-test watch-build android-check
