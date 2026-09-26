#!/bin/bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
benchmark_dir="$(mktemp -d "${TMPDIR:-/tmp}/foodblob-performance.XXXXXX")"
trap 'rm -rf "$benchmark_dir"' EXIT

xcrun --sdk macosx swiftc \
  -O \
  -whole-module-optimization \
  -module-cache-path "$benchmark_dir/module-cache" \
  "$repo_root/Shared/FoodDateKey.swift" \
  "$repo_root/Shared/WidgetLayoutID.swift" \
  "$repo_root/Shared/FoodWidgetLedger.swift" \
  "$repo_root/Shared/FoodModels.swift" \
  "$repo_root/Shared/FoodBlobPersistence.swift" \
  "$repo_root/Shared/FoodBlobSnapshot.swift" \
  "$repo_root/Shared/FoodWatchTransferContract.swift" \
  "$repo_root/Shared/BlobColor.swift" \
  "$repo_root/Shared/LivingBlob.swift" \
  "$repo_root/FoodBlob/Connectivity/FoodWatchReceiptStore.swift" \
  "$repo_root/Benchmarks/PerformanceBenchmarks.swift" \
  -o "$benchmark_dir/foodblob-performance-benchmarks"

"$benchmark_dir/foodblob-performance-benchmarks" "$@"
