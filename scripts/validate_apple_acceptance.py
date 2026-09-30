#!/usr/bin/env python3
"""Require the complete FoodBlob UI inventory in xcresulttool's test tree.

Input: `xcresulttool get test-results {summary,tests} --path ... --compact`.
Counts alone cannot distinguish omitted cases from duplicated or retried cases.
"""
EXPECTED_IDENTIFIERS = {
    "JellyInteractionUITests/" + name + "()" for name in (
        "testPaintJellySliderPersistsWithoutChangingFoodAndBothWorldsStayPlayable",
        "testDragOfferingCommitsOnceAndCancelledDragDoesNotLog",
        "testGrowthPortraitsAndBothWorlds",
        "testHistoryPortraitExpandsAndSettingsWelcomeAreReachable",
        "testMenusRemainNavigableAndPreserveCounts",
        "testAllFoodControlsFitWithoutScrollingAtStandardTextSize",
        "testCancelledLensPressAndSettingsLinksPreserveCounts",
        "testJellyFeedingStretchingAndUndoPreserveCounts",
    )
}


def _walk(nodes):
    if not isinstance(nodes, list):
        raise ValueError("Expected an xcresult test-node list")
    for node in nodes:
        if not isinstance(node, dict) or not isinstance(node.get("nodeType"), str):
            raise ValueError("Malformed xcresult test node")
        yield node
        yield from _walk(node.get("children", []))


def validate(summary, tests, expected_identifiers=None):
    """Return exact successful counts/identifiers, or reject incomplete evidence."""
    if expected_identifiers is None:
        expected_identifiers = EXPECTED_IDENTIFIERS
    if not isinstance(expected_identifiers, (set, frozenset, list, tuple)):
        raise ValueError("Expected identifiers must be an explicit inventory subset")
    if any(not isinstance(identifier, str) for identifier in expected_identifiers):
        raise ValueError("Expected identifiers must be strings")
    expected = set(expected_identifiers)
    if not expected or not expected <= EXPECTED_IDENTIFIERS or len(expected) != len(expected_identifiers):
        raise ValueError("Expected identifiers must be a nonempty unique subset of the UI inventory")
    count = len(expected)
    expected_counts = {"totalTestCount": count, "passedTests": count, "skippedTests": 0,
                       "failedTests": 0, "expectedFailures": 0}
    if not isinstance(summary, dict) or summary.get("result") != "Passed":
        raise ValueError("Apple acceptance summary must be Passed")
    for key, expected_count in expected_counts.items():
        if type(summary.get(key)) is not int or summary[key] != expected_count:
            raise ValueError(f"Expected {key}={expected_count}; got {summary.get(key)!r}")
    if summary.get("testFailures") != []:
        raise ValueError("Apple acceptance has missing or nonempty testFailures")
    if not isinstance(tests, dict):
        raise ValueError("Expected xcresult tests object")
    nodes = list(_walk(tests.get("testNodes")))
    for node in nodes:
        kind = node["nodeType"]
        if kind in ("Repetition", "Failure Message", "Skip Message", "Expected Failure"):
            raise ValueError(f"Unclean or repeated acceptance evidence: {kind}")
        if "result" in node and node["result"] != "Passed":
            raise ValueError(f"Non-passing test node: {node.get('nodeIdentifier', kind)}")
        if kind in ("Test Case", "Test Case Run") and node.get("result") != "Passed":
            raise ValueError("Test case/run has no passing result")
    cases = [node for node in nodes if node["nodeType"] == "Test Case"]
    identifiers = [node.get("nodeIdentifier") for node in cases]
    if any(not isinstance(identifier, str) for identifier in identifiers):
        raise ValueError("A test case is missing its identifier")
    if len(identifiers) != len(set(identifiers)):
        raise ValueError("Duplicate acceptance case identifiers")
    if set(identifiers) != expected:
        raise ValueError(f"Incomplete acceptance inventory: missing={sorted(expected-set(identifiers))}, "
                         f"extra={sorted(set(identifiers)-expected)}")
    for case in cases:
        runs = [node for node in _walk(case.get("children", [])) if node["nodeType"] == "Test Case Run"]
        if len(runs) > 1:
            raise ValueError(f"Repeated acceptance case runs: {case['nodeIdentifier']}")
    return {"passedTests": count, "skippedTests": 0, "failedTests": 0,
            "identifiers": sorted(identifiers)}
