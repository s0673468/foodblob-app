SHELL := /bin/bash

PROJECT := FoodBlob.xcodeproj
SCHEME := FoodBlob
DESTINATION ?= platform=iOS Simulator,name=iPhone 17 Pro,OS=latest
XCODEBUILD ?= xcodebuild

.PHONY: project project-check release-tests workflow-lint ios-test watch-build android-check perf-benchmark check check-ci

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

android-check:
	scripts/android_gradle.sh \
		:app:testDebugUnitTest \
		:app:lintDebug \
		:app:assembleDebug \
		:app:assembleRelease \
		:app:assembleBenchmark \
		:app:assembleDebugAndroidTest \
		:benchmark:assemble

perf-benchmark:
	scripts/run_performance_benchmarks.sh $(PERF_ARGS)

check: release-tests workflow-lint project-check ios-test watch-build android-check

# CI validates the committed project without requiring the local generator.
check-ci: release-tests workflow-lint ios-test watch-build android-check
