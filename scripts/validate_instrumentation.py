#!/usr/bin/env python3
"""Validate AndroidJUnitRunner raw output; adb exit zero alone is not a pass."""
import argparse
from pathlib import Path
import re
import sys


def validate_report(report):
    # AndroidX InstrumentationResultPrinter emits START=1, PASS=0, ERROR=-1,
    # FAILURE=-2, IGNORED=-3 and ASSUMPTION_FAILURE=-4 for individual tests.
    # The separate final INSTRUMENTATION_CODE uses Activity.RESULT_OK (-1).
    bundle = {}
    active = set()
    passed = ignored = assumptions = 0
    final_codes = []
    summary_lines = []
    in_summary = False
    for line in report.splitlines():
        if line.startswith("INSTRUMENTATION_FAILED:"):
            raise ValueError("instrumentation did not start or finish successfully")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if separator:
                bundle[key] = value
            in_summary = False
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            if final_codes:
                raise ValueError("test status arrived after the final result")
            code = int(line.partition(":")[2].strip())
            if code not in (1, 0, -3, -4):
                raise ValueError(f"test failure or unsupported status code {code}")
            # Other AndroidX listeners may send an informational stream-only 0.
            if code == 0 and not ("class" in bundle or "test" in bundle):
                bundle = {}
                continue
            identity = tuple(bundle.get(key, "") for key in ("class", "test", "current"))
            if not all(identity):
                raise ValueError("test status lacks its class, method or sequence number")
            if code == 1:
                if identity in active:
                    raise ValueError("duplicate test start")
                active.add(identity)
            else:
                if identity not in active:
                    raise ValueError("test completion has no matching start")
                active.remove(identity)
                passed += code == 0
                ignored += code == -3
                assumptions += code == -4
            bundle = {}
            in_summary = False
        elif line.startswith("INSTRUMENTATION_RESULT:"):
            if final_codes:
                raise ValueError("result arrived after the final code")
            key, separator, value = line.partition(":")[2].lstrip().partition("=")
            if key == "shortMsg":
                raise ValueError(f"instrumentation error: {value}")
            in_summary = separator == "=" and key == "stream"
            if in_summary:
                summary_lines.append(value)
        elif line.startswith("INSTRUMENTATION_CODE:"):
            final_codes.append(int(line.partition(":")[2].strip()))
            in_summary = False
        elif in_summary:
            summary_lines.append(line)

    if final_codes != [-1]:
        raise ValueError("missing, duplicate or unsuccessful final instrumentation code")
    if active or bundle:
        raise ValueError("instrumentation ended with an incomplete test")
    summaries = [re.fullmatch(r"OK \((\d+) tests?\)", line.strip()) for line in summary_lines]
    counts = [int(match.group(1)) for match in summaries if match]
    if len(counts) != 1 or counts[0] != passed + assumptions:
        raise ValueError("missing or inconsistent final JUnit success summary")
    if any("FAILURES!!!" in line for line in summary_lines):
        raise ValueError("JUnit reported failures")
    if passed == 0:
        raise ValueError("no tests passed: zero tests or every selected test was skipped")
    return passed, ignored + assumptions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    try:
        passed, skipped = validate_report(args.report.read_text(errors="replace"))
    except (OSError, ValueError) as exc:
        print(f"Instrumentation validation failed: {exc}", file=sys.stderr)
        return 1
    print(f"Instrumentation result: {passed} passed, {skipped} skipped.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
