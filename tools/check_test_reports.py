#!/usr/bin/env python3
"""Reject missing or failing Runtime unit-test reports."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
def report_counts(path: Path) -> tuple[int, int, int, int]:
    root = ElementTree.parse(path).getroot()
    return tuple(int(root.attrib.get(name, "0"))
                 for name in ("tests", "failures", "errors", "skipped"))


def main() -> int:
    failures: list[str] = []
    unit_reports = sorted((ROOT / "target" / "surefire-reports").glob("TEST-*.xml"))
    if not unit_reports:
        failures.append("no Runtime unit-test reports were produced")
    else:
        counts = [report_counts(path) for path in unit_reports]
        if sum(item[0] for item in counts) < 1:
            failures.append("Runtime unit-test reports contain no tests")
        if any(item[1] or item[2] or item[3] for item in counts):
            failures.append("Runtime unit-test reports contain failures, errors, or skips")

    if failures:
        for failure in failures:
            print(f"test-report error: {failure}", file=sys.stderr)
        return 1
    print("Verified Runtime unit reports")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
