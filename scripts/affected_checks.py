#!/usr/bin/env python3
"""Plan conservative local iteration checks; the final gate remains make check."""

import argparse
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys


APPLE_ROOTS = (
    "FoodBlob/", "FoodBlobTests/", "FoodBlobWidgetTests/", "FoodBlobWidgets/",
    "FoodBlobWatch/", "FoodBlobWatchExtension/", "FoodBlobWatchWidgets/",
    "FoodBlobUITests/", "Shared/", "WatchShared/",
)


def changed_paths(root, base):
    """Include branch changes and the entire dirty tree, including rename sources."""
    try:
        if base.startswith("-"):
            raise ValueError("base must be a Git revision")
        merge_base = subprocess.check_output(
            ["git", "merge-base", base, "HEAD"], cwd=root, stderr=subprocess.PIPE,
        ).decode().strip()
        tracked = subprocess.check_output(
            ["git", "diff", "--no-renames", "--name-only", "-z", merge_base, "--"],
            cwd=root, stderr=subprocess.PIPE,
        )
        untracked = subprocess.check_output(
            ["git", "ls-files", "--others", "--exclude-standard", "-z"],
            cwd=root, stderr=subprocess.PIPE,
        )
        paths = {p.decode("utf-8", errors="surrogateescape") for p in (tracked + untracked).split(b"\0") if p}
        return sorted(paths), None
    except (OSError, subprocess.CalledProcessError, ValueError) as exc:
        # No quiet empty selection when history is shallow or the base is absent.
        return [], f"Cannot establish changed paths against {base!r}: {type(exc).__name__}; select the full gate."


def plan_checks(paths, discovery_error=None):
    paths = sorted(set(paths))
    reasons = []
    apple = android = android_unit = False
    full = bool(discovery_error)
    acceptance = set()
    if discovery_error:
        reasons.append(discovery_error)
    for path in paths:
        safe = bool(path) and not PurePosixPath(path).is_absolute() and ".." not in PurePosixPath(path).parts
        if safe and path.startswith("android/app/src/test/"):
            android_unit = True
            reasons.append(f"{path}: Android JVM tests and lint.")
        elif safe and path.startswith("android/"):
            android = True
            reasons.append(f"{path}: Android tests, lint and build variants.")
            if path.startswith(("android/app/src/main/", "android/app/src/androidTest/", "android/app/schemas/")):
                acceptance.add("android-device-test")
        elif safe and path.startswith(APPLE_ROOTS):
            apple = True
            reasons.append(f"{path}: Apple tests and Watch build.")
            if path.startswith(("FoodBlob/Features/", "FoodBlob/Design/", "FoodBlobUITests/", "FoodBlobWidgets/")):
                acceptance.add("ios-acceptance")
        elif safe and (path in ("README.md", "LICENSE") or path.startswith("docs/")):
            reasons.append(f"{path}: lightweight contracts, including documentation assertions.")
        else:
            full = True
            reasons.append(f"{path}: global configuration or unclassified path; select the full gate.")
    portable = ["release-tests", "workflow-lint"]
    if full or android:
        portable.append("android-check")
    elif android_unit:
        portable.append("android-unit")
    apple_targets = ["project-check", "apple-check"] if full else (["apple-check"] if apple else [])
    return {
        "purpose": "local iteration; never a final full-gate receipt",
        "changed_paths": paths,
        "full_fallback": full,
        "lanes": {"portable": portable, "apple": apple_targets},
        "acceptance": sorted(acceptance),
        "reasons": reasons or ["No changed paths; run lightweight contracts."],
        "final_gate": "make check on the exact final head",
    }


def execute_plan(plan, root, platform):
    targets = plan["lanes"][platform]
    if not targets:
        return 0
    return subprocess.run(["make", *targets], cwd=root).returncode


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--base", default="origin/main", help="merge-base revision; default origin/main")
    parser.add_argument("--path", action="append", default=[], help="additional affected path; never excludes discovered changes")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--plan", action="store_true", help="print JSON only (the default)")
    mode.add_argument("--execute", action="store_true", help="run only the explicitly selected host lane")
    parser.add_argument("--platform", choices=("portable", "apple"), help="portable on admitted ger-z; Apple on admitted M1")
    args = parser.parse_args(argv)
    if args.execute and not args.platform:
        parser.error("--execute requires --platform; arrange host admission before execution")
    root = args.root.resolve()
    paths, error = changed_paths(root, args.base)
    plan = plan_checks(paths + args.path, discovery_error=error)
    plan["base"] = args.base
    print(json.dumps(plan, indent=2, ensure_ascii=True), flush=True)
    if not args.execute:
        return 0
    print(f"Running only the {args.platform} iteration lane. Other lanes and prepared-device acceptance remain separate.", file=sys.stderr)
    return execute_plan(plan, root, args.platform)


if __name__ == "__main__":
    raise SystemExit(main())
