#!/usr/bin/env python3
"""Reject partial or skipped device acceptance, even when transport succeeds."""
from validate_instrumentation import validate_report


def android(report, expected):
    passed, skipped = validate_report(report)
    if passed != expected or skipped:
        raise ValueError(f"Expected {expected} genuine passes and no skips; got {passed} passed, {skipped} skipped")
    return {"passed": passed, "skipped": skipped, "failed": 0}

